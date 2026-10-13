// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Memory pool implementation for frequently created structures.

#include "mtpndd_memory_pool.h"

#include <stdbool.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdatomic.h>
#include <pthread.h>

#include "mtpndd_common.h"
#include "mtpndd_nodetable.h"
#include "lace.h"

typedef struct mtpndd_slab_block_s {
    struct mtpndd_slab_block_s *next;
    /* Flexible array member containing objects follows */
    unsigned char data[];
} mtpndd_slab_block_t;

typedef struct mtpndd_slab_local_s {
    void *free_list;
    size_t count;
    /* Per-worker in-use counter. Avoids 6-way cacheline contention on a
     * single global atomic in the acquire/release fast path. Summed at
     * stats-read time to compute the global in-use count. Need not be
     * atomic — only the owning worker writes, and readers tolerate a
     * slightly stale total. */
    size_t in_use;
    /* Pad to cacheline size to isolate per-worker writes. */
    char _pad[64 - 3 * sizeof(size_t)];
} mtpndd_slab_local_t;

typedef struct mtpndd_slab_pool_s {
    size_t object_size;
    size_t objects_per_slab;
    void *free_list;
    mtpndd_slab_block_t *blocks;
    size_t slab_count;
    /* Fallback in-use counter for non-Lace-worker callers (e.g. initial
     * setup on the main thread before lace_start). Still atomic because
     * that path uses pool->lock and is rare. */
    _Atomic size_t in_use_global;
    size_t refill_batch;
    size_t local_max;
    pthread_mutex_t lock;
    bool lock_initialized;

    // Per-worker cache (fast path) to reduce global lock contention.
    // - locals[i] is only accessed by Lace worker i (no locking required).
    // - global lock is used only for refill/spill and slab growth.
    mtpndd_slab_local_t *locals;
    size_t locals_count;
} mtpndd_slab_pool_t;

static mtpndd_slab_pool_t g_node_pool = {0};
static mtpndd_slab_pool_t g_edge_entry_pool = {0};
static mtpndd_slab_pool_t g_nodetable_entry_pool = {0};
static mtpndd_slab_pool_t g_edge_map_pool = {0};
static size_t g_edge_bucket_count = 0;
static size_t g_edge_bucket_array_offset = 0;

// Batch sizes are tuned for high-frequency alloc/free paths:
// refill/spill in larger chunks amortizes the global mutex.
#define MTPNDD_POOL_REFILL_BATCH_DEFAULT 32
#define MTPNDD_POOL_LOCAL_MAX_DEFAULT 256

static inline int mtpndd_worker_id(void) {
    WorkerP *w = lace_get_worker();
    if (!w) return -1;
    return (int)w->worker;
}

static size_t mtpndd_align_size(size_t size, size_t alignment) {
    if (alignment <= 1) {
        return size;
    }
    return (size + alignment - 1) & ~(alignment - 1);
}

static void mtpndd_slab_pool_reset(mtpndd_slab_pool_t *pool) {
    pool->object_size = 0;
    pool->objects_per_slab = 0;
    pool->free_list = NULL;
    pool->blocks = NULL;
    pool->slab_count = 0;
    atomic_store_explicit(&pool->in_use_global, 0, memory_order_relaxed);
    pool->refill_batch = MTPNDD_POOL_REFILL_BATCH_DEFAULT;
    pool->local_max = MTPNDD_POOL_LOCAL_MAX_DEFAULT;
    pool->lock_initialized = false;
    pool->locals = NULL;
    pool->locals_count = 0;
}

static void mtpndd_slab_pool_destroy(mtpndd_slab_pool_t *pool) {
    if (pool->lock_initialized) {
        pthread_mutex_lock(&pool->lock);
    }
    mtpndd_slab_block_t *block = pool->blocks;
    while (block) {
        mtpndd_slab_block_t *next = block->next;
        free(block);
        block = next;
    }
    pool->blocks = NULL;
    pool->free_list = NULL;
    if (pool->locals) {
        free(pool->locals);
        pool->locals = NULL;
        pool->locals_count = 0;
    }
    if (pool->lock_initialized) {
        pthread_mutex_unlock(&pool->lock);
        pthread_mutex_destroy(&pool->lock);
        pool->lock_initialized = false;
    }
}

static mtpndd_error_t mtpndd_slab_pool_grow(mtpndd_slab_pool_t *pool) {
    size_t object_size = pool->object_size;
    size_t capacity = pool->objects_per_slab;
    if (capacity == 0) {
        capacity = 1;
    }
    size_t block_size = sizeof(mtpndd_slab_block_t) + object_size * capacity;
    mtpndd_slab_block_t *block = (mtpndd_slab_block_t *)malloc(block_size);
    if (!block) {
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    block->next = pool->blocks;
    pool->blocks = block;
    pool->slab_count++;

    unsigned char *cursor = block->data;
    for (size_t i = 0; i < capacity; ++i) {
        void *object = cursor + (i * object_size);
        *((void **)object) = pool->free_list;
        pool->free_list = object;
    }
    return MTPNDD_SUCCESS;
}

static void mtpndd_slab_pool_refill_local(mtpndd_slab_pool_t *pool, mtpndd_slab_local_t *local, bool *new_slab) {
    if (!pool || !local || !pool->lock_initialized) return;
    pthread_mutex_lock(&pool->lock);

    if (!pool->free_list) {
        if (mtpndd_slab_pool_grow(pool) == MTPNDD_SUCCESS) {
            if (new_slab) *new_slab = true;
        } else {
            pthread_mutex_unlock(&pool->lock);
            return;
        }
    }

    size_t moved = 0;
    while (pool->free_list && moved < pool->refill_batch) {
        void *obj = pool->free_list;
        pool->free_list = *((void **)obj);
        *((void **)obj) = local->free_list;
        local->free_list = obj;
        local->count++;
        moved++;
    }

    pthread_mutex_unlock(&pool->lock);
}

static void mtpndd_slab_pool_spill_local(mtpndd_slab_pool_t *pool, mtpndd_slab_local_t *local) {
    if (!pool || !local || !pool->lock_initialized) return;
    pthread_mutex_lock(&pool->lock);

    size_t moved = 0;
    while (local->free_list && local->count > pool->local_max / 2 && moved < pool->refill_batch) {
        void *obj = local->free_list;
        local->free_list = *((void **)obj);
        local->count--;
        *((void **)obj) = pool->free_list;
        pool->free_list = obj;
        moved++;
    }

    pthread_mutex_unlock(&pool->lock);
}

static void *mtpndd_slab_pool_acquire(mtpndd_slab_pool_t *pool, bool *new_slab) {
    if (!pool) return NULL;

    int wid = mtpndd_worker_id();
    if (wid >= 0 && pool->locals && (size_t)wid < pool->locals_count) {
        mtpndd_slab_local_t *local = &pool->locals[wid];
        if (!local->free_list) {
            mtpndd_slab_pool_refill_local(pool, local, new_slab);
        }
        if (local->free_list) {
            void *result = local->free_list;
            local->free_list = *((void **)result);
            if (local->count > 0) local->count--;
            local->in_use++;
            return result;
        }
        return NULL;
    }

    // Fallback path (non-Lace thread or locals not initialized): global lock per op.
    void *result = NULL;
    pthread_mutex_lock(&pool->lock);
    if (!pool->free_list) {
        if (mtpndd_slab_pool_grow(pool) != MTPNDD_SUCCESS) {
            pthread_mutex_unlock(&pool->lock);
            return NULL;
        }
        if (new_slab) {
            *new_slab = true;
        }
    }
    result = pool->free_list;
    pool->free_list = *((void **)result);
    atomic_fetch_add_explicit(&pool->in_use_global, 1, memory_order_relaxed);
    pthread_mutex_unlock(&pool->lock);
    return result;
}

static void mtpndd_slab_pool_release(mtpndd_slab_pool_t *pool, void *object) {
    if (!object) {
        return;
    }

    int wid = mtpndd_worker_id();
    if (wid >= 0 && pool->locals && (size_t)wid < pool->locals_count) {
        mtpndd_slab_local_t *local = &pool->locals[wid];
        *((void **)object) = local->free_list;
        local->free_list = object;
        local->count++;
        if (local->in_use > 0) local->in_use--;
        if (local->count > pool->local_max) {
            mtpndd_slab_pool_spill_local(pool, local);
        }
        return;
    }

    pthread_mutex_lock(&pool->lock);
    *((void **)object) = pool->free_list;
    pool->free_list = object;
    size_t prev = atomic_load_explicit(&pool->in_use_global, memory_order_relaxed);
    if (prev > 0) atomic_fetch_sub_explicit(&pool->in_use_global, 1, memory_order_relaxed);
    pthread_mutex_unlock(&pool->lock);
}

static size_t mtpndd_scaled_param(size_t base, unsigned int workers, size_t max_cap) {
    if (workers == 0) workers = 1;
    size_t scaled = base * (size_t)workers;
    if (max_cap && scaled > max_cap) return max_cap;
    return scaled;
}

static void mtpndd_slab_pool_setup(mtpndd_slab_pool_t *pool, size_t object_size, size_t alignment,
                                   size_t objects_per_slab, size_t refill_batch, size_t local_max) {
    mtpndd_slab_pool_reset(pool);
    size_t align = alignment < sizeof(void *) ? sizeof(void *) : alignment;
    size_t aligned_size = mtpndd_align_size(object_size, align);
    pool->object_size = aligned_size;
    pool->objects_per_slab = objects_per_slab ? objects_per_slab : 1;
    pool->refill_batch = refill_batch ? refill_batch : MTPNDD_POOL_REFILL_BATCH_DEFAULT;
    pool->local_max = local_max ? local_max : MTPNDD_POOL_LOCAL_MAX_DEFAULT;
    pthread_mutex_init(&pool->lock, NULL);
    pool->lock_initialized = true;
}

static bool mtpndd_slab_pool_init_locals(mtpndd_slab_pool_t *pool) {
    if (!pool || !pool->lock_initialized) return false;
    if (pool->locals) return true;
    unsigned int workers = lace_workers();
    if (workers == 0) workers = 1;
    pool->locals = (mtpndd_slab_local_t *)calloc(workers, sizeof(*pool->locals));
    if (!pool->locals) return false;
    pool->locals_count = (size_t)workers;
    return true;
}

void mtpndd_memory_pools_init(void) {
    size_t node_capacity = g_mtpndd_pal_config.node_slab_capacity;
    size_t edge_capacity = g_mtpndd_pal_config.edge_entry_slab_capacity;
    size_t nodetable_capacity = g_mtpndd_pal_config.nodetable_entry_slab_capacity;
    size_t edge_map_capacity = g_mtpndd_pal_config.edge_map_slab_capacity;

    unsigned int workers = lace_workers();
    size_t edge_refill = mtpndd_scaled_param(64, workers, 256);
    size_t edge_local = mtpndd_scaled_param(512, workers, 2048);

    mtpndd_slab_pool_setup(&g_node_pool, sizeof(mtpndd_node_t), _Alignof(mtpndd_node_t), node_capacity,
                           MTPNDD_POOL_REFILL_BATCH_DEFAULT, MTPNDD_POOL_LOCAL_MAX_DEFAULT);

    g_edge_bucket_count = g_mtpndd_pal_config.edge_bucket_count;
    if (g_edge_bucket_count == 0) {
        g_edge_bucket_count = MTPNDD_DEFAULT_EDGE_BUCKET_COUNT; // default 8
    }
    if (g_edge_bucket_count < MTPNDD_DEFAULT_EDGE_BUCKET_COUNT) {
        g_edge_bucket_count = MTPNDD_DEFAULT_EDGE_BUCKET_COUNT;
    }
    // round up to power of two (keep default at 8)
    g_edge_bucket_count = mtpndd_round_up_pow2(g_edge_bucket_count);
    g_mtpndd_pal_config.edge_bucket_count = g_edge_bucket_count;

    size_t offset = sizeof(mtpndd_edge_t);
    offset = mtpndd_align_size(offset, _Alignof(edge_bucket_entry_t *));
    g_edge_bucket_array_offset = offset;
    offset += sizeof(edge_bucket_entry_t *) * g_edge_bucket_count;
    mtpndd_slab_pool_setup(&g_edge_map_pool, offset, _Alignof(mtpndd_edge_t), edge_map_capacity,
                           edge_refill, edge_local);
    mtpndd_slab_pool_setup(&g_edge_entry_pool, sizeof(edge_bucket_entry_t), _Alignof(edge_bucket_entry_t), edge_capacity,
                           edge_refill, edge_local);
    mtpndd_slab_pool_setup(&g_nodetable_entry_pool, sizeof(mtpndd_nodetable_bucket_entry_t), _Alignof(mtpndd_nodetable_bucket_entry_t), nodetable_capacity,
                           MTPNDD_POOL_REFILL_BATCH_DEFAULT, MTPNDD_POOL_LOCAL_MAX_DEFAULT);

    // Must be called after lace_start() so lace_workers() is valid.
    (void)mtpndd_slab_pool_init_locals(&g_node_pool);
    (void)mtpndd_slab_pool_init_locals(&g_edge_entry_pool);
    (void)mtpndd_slab_pool_init_locals(&g_nodetable_entry_pool);
    (void)mtpndd_slab_pool_init_locals(&g_edge_map_pool);
}

void mtpndd_memory_pools_shutdown(void) {
    mtpndd_slab_pool_destroy(&g_edge_map_pool);
    mtpndd_slab_pool_destroy(&g_nodetable_entry_pool);
    mtpndd_slab_pool_destroy(&g_edge_entry_pool);
    mtpndd_slab_pool_destroy(&g_node_pool);
}

static size_t mtpndd_slab_pool_in_use_sum(const mtpndd_slab_pool_t *pool) {
    size_t total = atomic_load_explicit(&pool->in_use_global, memory_order_relaxed);
    if (pool->locals) {
        for (size_t i = 0; i < pool->locals_count; i++) {
            total += pool->locals[i].in_use;
        }
    }
    return total;
}

void mtpndd_memory_pools_snapshot(mtpndd_memory_pool_stats_t *stats) {
    if (!stats) return;
    stats->node_slabs = g_node_pool.slab_count;
    stats->node_in_use = mtpndd_slab_pool_in_use_sum(&g_node_pool);
    stats->node_capacity_per_slab = g_node_pool.objects_per_slab;

    stats->edge_entry_slabs = g_edge_entry_pool.slab_count;
    stats->edge_entry_in_use = mtpndd_slab_pool_in_use_sum(&g_edge_entry_pool);
    stats->edge_entry_capacity_per_slab = g_edge_entry_pool.objects_per_slab;

    stats->nodetable_entry_slabs = g_nodetable_entry_pool.slab_count;
    stats->nodetable_entry_in_use = mtpndd_slab_pool_in_use_sum(&g_nodetable_entry_pool);
    stats->nodetable_entry_capacity_per_slab = g_nodetable_entry_pool.objects_per_slab;

    stats->edge_map_slabs = g_edge_map_pool.slab_count;
    stats->edge_map_in_use = mtpndd_slab_pool_in_use_sum(&g_edge_map_pool);
    stats->edge_map_capacity_per_slab = g_edge_map_pool.objects_per_slab;
}

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
void mtpndd_log_memory_pools(const char *phase) {
    mtpndd_memory_pool_stats_t stats = {0};
    mtpndd_memory_pools_snapshot(&stats);
    fprintf(stdout,
            "[MTPNDD MEM] %s node slabs=%zu in_use=%zu slabCap=%zu | edge_entry slabs=%zu in_use=%zu | nodetable_entry slabs=%zu in_use=%zu | edge_map slabs=%zu in_use=%zu\n",
            phase ? phase : "unknown",
            stats.node_slabs, stats.node_in_use, stats.node_capacity_per_slab,
            stats.edge_entry_slabs, stats.edge_entry_in_use,
            stats.nodetable_entry_slabs, stats.nodetable_entry_in_use,
            stats.edge_map_slabs, stats.edge_map_in_use);
    fflush(stdout);
}
#endif

mtpndd_node_t *mtpndd_memory_acquire_node(void) {
    bool grew = false;
    mtpndd_node_t *node = (mtpndd_node_t *)mtpndd_slab_pool_acquire(&g_node_pool, &grew);
    if (!node) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        return NULL;
    }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(node_pool_acquire_total, 1);
    if (grew) {
        MTPNDD_STAT_ADD(node_pool_slab_total, 1);
    }
#endif
    return node;
}

void mtpndd_memory_release_node(mtpndd_node_t *node) {
    if (!node) {
        return;
    }
    mtpndd_slab_pool_release(&g_node_pool, node);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(node_pool_release_total, 1);
#endif
}

edge_bucket_entry_t *mtpndd_memory_acquire_edge_entry(void) {
    bool grew = false;
    edge_bucket_entry_t *entry = (edge_bucket_entry_t *)mtpndd_slab_pool_acquire(&g_edge_entry_pool, &grew);
    if (!entry) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        return NULL;
    }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(edge_entry_pool_acquire_total, 1);
    if (grew) {
        MTPNDD_STAT_ADD(edge_entry_pool_slab_total, 1);
    }
#endif
    return entry;
}

void mtpndd_memory_release_edge_entry(edge_bucket_entry_t *entry) {
    if (!entry) {
        return;
    }
    mtpndd_slab_pool_release(&g_edge_entry_pool, entry);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(edge_entry_pool_release_total, 1);
#endif
}

mtpndd_nodetable_bucket_entry_t *mtpndd_memory_acquire_nodetable_entry(void) {
    bool grew = false;
    mtpndd_nodetable_bucket_entry_t *entry = (mtpndd_nodetable_bucket_entry_t *)mtpndd_slab_pool_acquire(&g_nodetable_entry_pool, &grew);
    if (!entry) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        return NULL;
    }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(nodetable_entry_pool_acquire_total, 1);
    if (grew) {
        MTPNDD_STAT_ADD(nodetable_entry_pool_slab_total, 1);
    }
#endif
    return entry;
}

void mtpndd_memory_release_nodetable_entry(mtpndd_nodetable_bucket_entry_t *entry) {
    if (!entry) {
        return;
    }
    mtpndd_slab_pool_release(&g_nodetable_entry_pool, entry);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(nodetable_entry_pool_release_total, 1);
#endif
}

mtpndd_edge_t *mtpndd_memory_acquire_edge_map(void) {
    bool grew = false;
    mtpndd_edge_t *edges = (mtpndd_edge_t *)mtpndd_slab_pool_acquire(&g_edge_map_pool, &grew);
    if (!edges) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        return NULL;
    }
    char *base = (char *)edges;
    edges->buckets = (edge_bucket_entry_t **)(base + g_edge_bucket_array_offset);
    edges->bucket_count = g_edge_bucket_count;
    edges->load_threshold = g_edge_bucket_count - (g_edge_bucket_count >> 2);
    edges->buckets_malloced = false;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(edge_map_pool_acquire_total, 1);
    if (grew) {
        MTPNDD_STAT_ADD(edge_map_pool_slab_total, 1);
    }
#endif
    return edges;
}

void mtpndd_memory_release_edge_map(mtpndd_edge_t *edges) {
    if (!edges) {
        return;
    }
    if (edges->buckets_malloced && edges->buckets) {
        free(edges->buckets);
        edges->buckets = NULL;
    }
    mtpndd_slab_pool_release(&g_edge_map_pool, edges);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(edge_map_pool_release_total, 1);
#endif
}
