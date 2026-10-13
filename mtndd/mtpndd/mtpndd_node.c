// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#include "mtpndd_common.h"
#include "mtpndd_node.h"
#include "mtpndd_nodetable.h"
#include "mtpndd_operation_cache.h"
#include "mtpndd_memory_pool.h"
#include "sylvan.h"
#include "sylvan_mtbdd.h"
#include <stdatomic.h>
#include <string.h>
#include <stdint.h>
#include <stdio.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdlib.h>
#include <math.h>
#include <lace.h>
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
#include <time.h>
#endif

// Terminal singletons. Their values are assigned during mtpndd_init once
// the leaf table exists; at that point MTPNDD_TRUE becomes the canonical
// leaf for 1/1 and MTPNDD_FALSE the canonical leaf for 0/1. Their
// addresses remain stable so existing `&MTPNDD_TRUE` / `&MTPNDD_FALSE`
// references keep working.
mtpndd_t MTPNDD_TRUE = {0};
mtpndd_t MTPNDD_FALSE = {0};

static inline mtpndd_bdd_t mtpndd_edge_label_load(edge_bucket_entry_t *entry);
static inline void mtpndd_residual_apply_mask(edge_bucket_entry_t *entry, mtpndd_bdd_t mask);
static mtpndd_error_t mtpndd_edge_map_deep_clone(const mtpndd_edge_t *source, mtpndd_edge_t *dest);
static void mtpndd_edge_map_reset(mtpndd_edge_t *edges);
void mtpndd_edge_map_free(mtpndd_edge_t *edges);
static void mtpndd_edge_map_maybe_rehash(mtpndd_edge_t *edges);
static bool mtpndd_edge_map_rehash(mtpndd_edge_t *edges, size_t new_bucket_count);

// TASK declaration for parallel mtpndd_and_rec
TASK_DECL_2(mtpndd_t*, mtpndd_and_rec, mtpndd_t*, mtpndd_t*);
// TASK declaration for parallel mtpndd_not_rec
TASK_DECL_1(mtpndd_t*, mtpndd_not_rec, mtpndd_t*);
// TASK declaration for parallel mtpndd_or_rec
TASK_DECL_2(mtpndd_t*, mtpndd_or_rec, mtpndd_t*, mtpndd_t*);

// Result item for batched edge construction tasks.
// If `emit` is false, the (child,label) pair should be ignored and owns no resources.
// If `emit` is true, `child` has a +1 MTPNDD ref and `label` has a +1 Sylvan ref.
typedef struct {
    mtpndd_error_t status;
    uint32_t emit;
    mtpndd_t *child;
    mtpndd_bdd_t label;
} mtpndd_and_item_t;

typedef struct {
    mtpndd_error_t status;
    uint32_t emit;
    mtpndd_t *child;
    mtpndd_bdd_t label;
} mtpndd_not_item_t;

typedef struct {
    mtpndd_error_t status;
    uint32_t emit;
    mtpndd_t *child;
    mtpndd_bdd_t label;
} mtpndd_or_item_t;

typedef struct {
    mtpndd_edge_t *residualA;
    mtpndd_edge_t *residualB;
} mtpndd_or_shared_ctx_t;

// Task wrappers used to compute a (child,label) item in parallel without mutating shared state.
TASK_DECL_2(mtpndd_and_item_t, mtpndd_and_same_field_item, edge_bucket_entry_t*, edge_bucket_entry_t*);
TASK_DECL_2(mtpndd_and_item_t, mtpndd_and_diff_field_item, edge_bucket_entry_t*, mtpndd_t*);
TASK_DECL_2(mtpndd_not_item_t, mtpndd_not_expand_item, edge_bucket_entry_t*, _Atomic(mtpndd_bdd_t)*);
TASK_DECL_3(mtpndd_or_item_t, mtpndd_or_same_field_item, edge_bucket_entry_t*, edge_bucket_entry_t*, mtpndd_or_shared_ctx_t*);
TASK_DECL_3(mtpndd_or_item_t, mtpndd_or_diff_field_item, edge_bucket_entry_t*, mtpndd_t*, _Atomic(mtpndd_bdd_t)*);

// Two-phase AND: surviving pair after BDD label filter (Phase 1).
// label is already sylvan_ref'd; child_a/child_b are raw pointers.
typedef struct {
    mtpndd_t *child_a;
    mtpndd_t *child_b;
    mtpndd_bdd_t label;
} mtpndd_and_pair_t;

// Task for Phase 2: recurse on a pre-filtered pair.
// Only does the recursive mtpndd_and_rec; the BDD label is already computed.
TASK_DECL_1(mtpndd_and_item_t, mtpndd_and_recurse_pair, mtpndd_and_pair_t*);

// Minimum E_a * E_b to trigger the two-phase path.
// Below this, the original single-pass loop is used.
// 0 = disabled (always use original single-pass).
#ifndef MTPNDD_AND_TWO_PHASE_THRESHOLD_DEFAULT
#define MTPNDD_AND_TWO_PHASE_THRESHOLD_DEFAULT 4
#endif

static int g_mtpndd_two_phase_threshold = MTPNDD_AND_TWO_PHASE_THRESHOLD_DEFAULT;

void mtpndd_set_two_phase_threshold(int value) {
    g_mtpndd_two_phase_threshold = value;
}

int mtpndd_get_two_phase_threshold(void) {
    return g_mtpndd_two_phase_threshold;
}

// Granularity control: decide whether to SPAWN a sub-problem
static inline bool mtpndd_should_spawn(mtpndd_t *a, mtpndd_t *b) {
    // Never spawn if only 1 worker
    if (lace_workers() <= 1) return false;

    // Don't spawn for terminal nodes
    if (mtpndd_is_terminal(a) || mtpndd_is_terminal(b)) return false;

    // Coarse-grained strategy: avoid spawning too many small tasks (high overhead + contention).
    // Prefer spawning only for the largest sub-problems.
    size_t edges_a = a->edges ? a->edges->edge_count : 0;
    size_t edges_b = b->edges ? b->edges->edge_count : 0;
    size_t prod = edges_a * edges_b;

    // Very top levels: allow some parallelism even if edge maps are not yet large.
    if (a->field_id <= 2 && b->field_id <= 2) return true;

    // Otherwise, only spawn when the pairwise work is substantial.
    return prod >= MTPNDD_SPAWN_THRESHOLD;
}

// Granularity control for unary ops (NOT)
static inline bool mtpndd_should_spawn_unary(mtpndd_t *a) {
    if (lace_workers() <= 1) return false;
    if (mtpndd_is_terminal(a)) return false;
    size_t edges_a = a->edges ? a->edges->edge_count : 0;
    return edges_a >= MTPNDD_SPAWN_THRESHOLD;
}

typedef struct {
    mtpndd_t **items;
    size_t count;
    size_t capacity;
    mtpndd_t *inline_items[32];
} mtpndd_temp_ref_pool_t;

typedef struct {
    mtpndd_temp_ref_pool_t *pool;
    size_t frame_base;
} mtpndd_temp_ref_list_t;

static mtpndd_temp_ref_pool_t *g_mtpndd_temp_ref_pools = NULL;
static size_t g_mtpndd_temp_ref_pool_count = 0;
static __thread mtpndd_temp_ref_pool_t g_mtpndd_temp_ref_tls_pool = {0};
static __thread bool g_mtpndd_temp_ref_tls_pool_initialized = false;

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
static _Atomic uint64_t g_mtpndd_temp_refs_grow_total = 0;
static _Atomic uint64_t g_mtpndd_temp_refs_peak_capacity = 0;

static inline void mtpndd_temp_refs_record_capacity(size_t capacity) {
    uint64_t observed = atomic_load_explicit(&g_mtpndd_temp_refs_peak_capacity, memory_order_relaxed);
    uint64_t candidate = (uint64_t)capacity;
    while (candidate > observed &&
           !atomic_compare_exchange_weak_explicit(&g_mtpndd_temp_refs_peak_capacity, &observed, candidate,
                                                  memory_order_relaxed, memory_order_relaxed)) {
        /* retry with updated observed */
    }
}

static inline void mtpndd_temp_refs_record_growth(size_t new_cap) {
    atomic_fetch_add_explicit(&g_mtpndd_temp_refs_grow_total, 1, memory_order_relaxed);
    mtpndd_temp_refs_record_capacity(new_cap);
}
#endif

static inline unsigned int mtpndd_temp_refs_worker_id(void) {
    WorkerP *worker = lace_get_worker();
    return worker ? worker->worker : UINT32_MAX;
}

static inline void mtpndd_temp_ref_pool_init(mtpndd_temp_ref_pool_t *pool) {
    pool->items = pool->inline_items;
    pool->count = 0;
    pool->capacity = sizeof(pool->inline_items) / sizeof(pool->inline_items[0]);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    mtpndd_temp_refs_record_capacity(pool->capacity);
#endif
}

static inline mtpndd_temp_ref_pool_t *mtpndd_temp_ref_tls_pool_get(void) {
    if (!g_mtpndd_temp_ref_tls_pool_initialized) {
        mtpndd_temp_ref_pool_init(&g_mtpndd_temp_ref_tls_pool);
        g_mtpndd_temp_ref_tls_pool_initialized = true;
    }
    return &g_mtpndd_temp_ref_tls_pool;
}

static inline mtpndd_temp_ref_pool_t *mtpndd_temp_ref_pool_get(void) {
    unsigned int wid = mtpndd_temp_refs_worker_id();
    if (g_mtpndd_temp_ref_pools && wid < g_mtpndd_temp_ref_pool_count) {
        return &g_mtpndd_temp_ref_pools[wid];
    }
    return mtpndd_temp_ref_tls_pool_get();
}

static void mtpndd_temp_refs_init(mtpndd_temp_ref_list_t *list) {
    if (!list) return;
    list->pool = mtpndd_temp_ref_pool_get();
    list->frame_base = list->pool ? list->pool->count : 0;
}

static bool mtpndd_temp_refs_reserve_slot(mtpndd_temp_ref_list_t *list) {
    if (!list || !list->pool) return false;
    mtpndd_temp_ref_pool_t *pool = list->pool;
    if (pool->count == pool->capacity) {
        size_t new_cap = pool->capacity ? pool->capacity * 2 : 64;
        mtpndd_t **next = NULL;
        if (pool->items == pool->inline_items) {
            next = (mtpndd_t **)malloc(new_cap * sizeof(mtpndd_t *));
            if (next) {
                memcpy(next, pool->inline_items, pool->count * sizeof(mtpndd_t *));
            }
        } else {
            next = (mtpndd_t **)realloc(pool->items, new_cap * sizeof(mtpndd_t *));
        }
        if (!next) {
            return false;
        }
        pool->items = next;
        pool->capacity = new_cap;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        mtpndd_temp_refs_record_growth(new_cap);
#endif
    }
    return true;
}

static bool mtpndd_temp_refs_push(mtpndd_temp_ref_list_t *list, mtpndd_t *node) {
    if (!list || !list->pool) return false;
    if (!node) return true;
    if (!mtpndd_temp_refs_reserve_slot(list)) return false;
    mtpndd_temp_ref_pool_t *pool = list->pool;
    mtpndd_ref(node);
    pool->items[pool->count++] = node;
    return true;
}

// Push a node that is already ref'd (+1). Used for parallel tasks that return an owned ref.
static bool mtpndd_temp_refs_push_owned(mtpndd_temp_ref_list_t *list, mtpndd_t *node) {
    if (!list || !list->pool) return false;
    if (!node) return true;
    if (!mtpndd_temp_refs_reserve_slot(list)) return false;
    mtpndd_temp_ref_pool_t *pool = list->pool;
    pool->items[pool->count++] = node;
    return true;
}

static void mtpndd_temp_refs_release(mtpndd_temp_ref_list_t *list) {
    if (!list || !list->pool) return;
    mtpndd_temp_ref_pool_t *pool = list->pool;
    while (pool->count > list->frame_base) {
        mtpndd_deref(pool->items[--pool->count]);
    }
    list->pool = NULL;
    list->frame_base = 0;
}

void mtpndd_edge_map_init(mtpndd_edge_t *edges) {
    size_t bucket_cnt = edges->bucket_count ? edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    if (bucket_cnt < MTPNDD_DEFAULT_EDGE_BUCKET_COUNT) bucket_cnt = MTPNDD_DEFAULT_EDGE_BUCKET_COUNT;
    bucket_cnt = mtpndd_round_up_pow2(bucket_cnt);
    edges->bucket_count = bucket_cnt;
    edges->load_threshold = bucket_cnt - (bucket_cnt >> 3); // ~0.875 load factor (default 0.8+)
    edges->edge_count = 0;
    edges->cached_hash = 0;
    edges->buckets_malloced = false;
    // Use memset for faster initialization than loop
    memset(edges->buckets, 0, bucket_cnt * sizeof(edge_bucket_entry_t *));
}

/* mtpndd_hash_node_identity: moved to mtpndd_node.h as static inline. */

static mtpndd_error_t mtpndd_edge_map_deep_clone(const mtpndd_edge_t *source, mtpndd_edge_t *dest) {
    dest->edge_count = source->edge_count;
    dest->cached_hash = source->cached_hash;

    size_t bucket_cnt = source->bucket_count ? source->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    if (bucket_cnt < MTPNDD_DEFAULT_EDGE_BUCKET_COUNT) bucket_cnt = MTPNDD_DEFAULT_EDGE_BUCKET_COUNT;
    bucket_cnt = mtpndd_round_up_pow2(bucket_cnt);
    if (bucket_cnt != dest->bucket_count) {
        if (dest->buckets_malloced && dest->buckets) {
            free(dest->buckets);
        }
        dest->buckets = (edge_bucket_entry_t **)calloc(bucket_cnt, sizeof(edge_bucket_entry_t *));
        if (!dest->buckets) {
            dest->bucket_count = 0;
            dest->load_threshold = 0;
            dest->buckets_malloced = false;
            return MTPNDD_ERROR_OUT_OF_MEMORY;
        }
        dest->buckets_malloced = true;
        dest->bucket_count = bucket_cnt;
        dest->load_threshold = bucket_cnt - (bucket_cnt >> 2);
    } else {
        memset(dest->buckets, 0, bucket_cnt * sizeof(edge_bucket_entry_t *));
    }

    if (!source->buckets) {
        return MTPNDD_SUCCESS;
    }

    for (size_t i = 0; i < bucket_cnt; ++i) {
        edge_bucket_entry_t *src_entry = source->buckets[i];
        if (!src_entry) continue;

        edge_bucket_entry_t *dst_head = NULL;
        edge_bucket_entry_t *dst_tail = NULL;

        while (src_entry) {
            edge_bucket_entry_t *new_entry = mtpndd_memory_acquire_edge_entry();
            if (!new_entry) {
                mtpndd_edge_map_reset(dest);
                return MTPNDD_ERROR_OUT_OF_MEMORY;
            }
            new_entry->child = src_entry->child;
            if (new_entry->child && !mtpndd_is_terminal(new_entry->child)) {
                mtpndd_ref(new_entry->child);
            }
            mtpndd_bdd_t label = atomic_load_explicit(&src_entry->label, memory_order_relaxed);
            atomic_store_explicit(&new_entry->label, sylvan_ref(label), memory_order_relaxed);

            new_entry->next = NULL;
            if (dst_tail) {
                dst_tail->next = new_entry;
            } else {
                dst_head = new_entry;
            }
            dst_tail = new_entry;

            src_entry = src_entry->next;
        }

        dest->buckets[i] = dst_head;
    }

    return MTPNDD_SUCCESS;
}

static void mtpndd_edge_map_reset(mtpndd_edge_t *edges) {
    if (!edges) {
        return;
    }
    if (edges->buckets) {
        size_t bucket_cnt = edges->bucket_count ? edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        // Release all entries first
        for (size_t i = 0; i < bucket_cnt; ++i) {
            edge_bucket_entry_t *entry = edges->buckets[i];
            while (entry) {
                edge_bucket_entry_t *next = entry->next;
                mtpndd_bdd_t label = atomic_load_explicit(&entry->label, memory_order_relaxed);
                sylvan_deref(label);
                mtpndd_memory_release_edge_entry(entry);
                entry = next;
            }
        }
        // Use memset to clear all buckets at once
        memset(edges->buckets, 0, bucket_cnt * sizeof(edge_bucket_entry_t *));
    }
    edges->edge_count = 0;
    edges->cached_hash = 0;
}

void mtpndd_edge_map_free(mtpndd_edge_t *edges) {
    if (!edges) {
        return;
    }
    mtpndd_edge_map_reset(edges);
    mtpndd_memory_release_edge_map(edges);
}

static bool mtpndd_edge_map_rehash(mtpndd_edge_t *edges, size_t new_bucket_count) {
    if (!edges) return false;
    if (new_bucket_count < MTPNDD_DEFAULT_EDGE_BUCKET_COUNT) new_bucket_count = MTPNDD_DEFAULT_EDGE_BUCKET_COUNT;
    new_bucket_count = mtpndd_round_up_pow2(new_bucket_count);

    edge_bucket_entry_t **new_buckets = (edge_bucket_entry_t **)calloc(new_bucket_count, sizeof(edge_bucket_entry_t *));
    if (!new_buckets) {
        return false;
    }

    size_t old_bucket_cnt = edges->bucket_count ? edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t i = 0; i < old_bucket_cnt; ++i) {
        edge_bucket_entry_t *entry = edges->buckets[i];
        while (entry) {
            edge_bucket_entry_t *next = entry->next;
            size_t hash = mtpndd_hash_node_identity(entry->child) % new_bucket_count;
            entry->next = new_buckets[hash];
            new_buckets[hash] = entry;
            entry = next;
        }
    }

    if (edges->buckets_malloced && edges->buckets) {
        free(edges->buckets);
    }
    edges->buckets = new_buckets;
    edges->buckets_malloced = true;
    edges->bucket_count = new_bucket_count;
    edges->load_threshold = new_bucket_count - (new_bucket_count >> 3);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(edge_map_rehash_total, 1);
    MTPNDD_STAT_MAX(edge_map_max_buckets, new_bucket_count);
#endif
    return true;
}

static void mtpndd_edge_map_maybe_rehash(mtpndd_edge_t *edges) {
    if (!edges) return;
    if (edges->edge_count >= edges->load_threshold) {
        size_t target = edges->bucket_count ? edges->bucket_count * 2 : (g_mtpndd_pal_config.edge_bucket_count ? g_mtpndd_pal_config.edge_bucket_count * 2 : 16);
        mtpndd_edge_map_rehash(edges, target);
    }
}

/**
 * Find edge entry in hash table
 * Returns NULL if not found
 */
edge_bucket_entry_t *find_edge_entry(mtpndd_edge_t *edge, mtpndd_node_t *key) {
    if (edge == NULL || key == NULL || edge->buckets == NULL) {
        return NULL;
    }

    size_t hash = EDGE_MAP_HASH_VAL(edge, key);

    edge_bucket_entry_t *entry = NULL;
    FOR_EACH_ENTRY_IN_BUCKET(edge, hash, entry) {
        if (EDGE_BUCKET_ENTRY_EQUAL(entry, key)) {
            return entry;
        }
    }

    return NULL;
}

mtpndd_error_t mtpndd_add_edge(mtpndd_edge_t *edges, mtpndd_t *descendant, mtpndd_bdd_t label_bdd) {
    if (mtpndd_is_false(descendant)) {
        sylvan_deref(label_bdd);
        return MTPNDD_SUCCESS;
    }

    mtpndd_bdd_t old_label = sylvan_false;
    size_t hash = EDGE_MAP_HASH_VAL(edges, descendant);

    mtpndd_error_t status = MTPNDD_SUCCESS;
    edge_bucket_entry_t *entry = NULL;
    edge_bucket_entry_t *bucket_head = edges->buckets[hash];
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    bool created_entry = false;
    bool collision_on_insert = false;
#endif

    edge_bucket_entry_t *prev = NULL;
    edge_bucket_entry_t *cursor = bucket_head;
    while (cursor) {
        if (EDGE_BUCKET_ENTRY_EQUAL(cursor, descendant)) {
            old_label = atomic_load_explicit(&cursor->label, memory_order_acquire);
            if (prev) {
                prev->next = cursor->next;
            } else {
                bucket_head = cursor->next;
            }
            entry = cursor;
            edges->edge_count--;
            break;
        }
        prev = cursor;
        cursor = cursor->next;
    }

    if (!entry) {
        entry = mtpndd_memory_acquire_edge_entry();
        if (!entry) {
            mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
            status = MTPNDD_ERROR_OUT_OF_MEMORY;
            goto unlock_and_return;
        }
        entry->child = descendant;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        created_entry = true;
        if (bucket_head != NULL) {
            collision_on_insert = true;
        }
#endif
    }

    // Update label
    mtpndd_bdd_t new_label = sylvan_ref(sylvan_or(old_label, label_bdd));
    atomic_store_explicit(&entry->label, new_label, memory_order_release);
    sylvan_deref(old_label);
    sylvan_deref(label_bdd);

    // Insert into bucket at the front
    entry->next = bucket_head;
    bucket_head = entry;

    edges->buckets[hash] = bucket_head;
    edges->edge_count++;
    mtpndd_edge_map_maybe_rehash(edges);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    if (created_entry) {
        MTPNDD_STAT_ADD(edge_insert_total, 1);
        MTPNDD_STAT_ADD(edge_entry_total, 1);
        if (collision_on_insert) {
            MTPNDD_STAT_ADD(edge_collision_total, 1);
        }
        MTPNDD_STAT_MAX(max_edges_per_node, edges->edge_count);
    }
#endif
unlock_and_return:
    return status;
}

/* mtpndd_is_true / is_false / is_terminal are static inline in mtpndd_node.h. */

static mtpndd_error_t mtpndd_exist_rec(mtpndd_t *a, uint32_t field, mtpndd_t **result);

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
typedef enum {
    MTPNDD_AND_PROF_OTHER = 0,
    MTPNDD_AND_PROF_FASTPATH,
    MTPNDD_AND_PROF_CACHE_HIT,
    MTPNDD_AND_PROF_BUILD_EDGES,
    MTPNDD_AND_PROF_SAME_OUTER_LOOP,
    MTPNDD_AND_PROF_SAME_INNER_LOOP,
    MTPNDD_AND_PROF_SAME_LABEL_LOAD,
    MTPNDD_AND_PROF_SAME_BDD_OP,
    MTPNDD_AND_PROF_SAME_ADD_EDGE,
    MTPNDD_AND_PROF_DIFF_FIELD,
    MTPNDD_AND_PROF_MK,
    MTPNDD_AND_PROF_COUNT
} mtpndd_and_prof_bucket_t;

typedef struct {
    bool recording;
    mtpndd_and_prof_bucket_t active;
    struct timespec last_switch;
    uint64_t bucket_ns[MTPNDD_AND_PROF_COUNT];
} mtpndd_and_prof_ctx_t;

static _Thread_local uint32_t mtpndd_and_depth = 0;
static _Thread_local mtpndd_and_prof_ctx_t mtpndd_and_prof_ctx = {0};

static inline void mtpndd_and_prof_start(void) {
    if (mtpndd_and_depth == 0) {
        mtpndd_and_prof_ctx.recording = true;
        mtpndd_and_prof_ctx.active = MTPNDD_AND_PROF_OTHER;
        for (size_t i = 0; i < MTPNDD_AND_PROF_COUNT; i++) {
            mtpndd_and_prof_ctx.bucket_ns[i] = 0;
        }
        clock_gettime(CLOCK_MONOTONIC, &mtpndd_and_prof_ctx.last_switch);
    }
    mtpndd_and_depth++;
}

static inline void mtpndd_and_prof_switch(mtpndd_and_prof_bucket_t bucket) {
    if (!mtpndd_and_prof_ctx.recording) return;
    struct timespec now = {0};
    clock_gettime(CLOCK_MONOTONIC, &now);
    uint64_t delta = mtpndd_timespec_diff_ns(&mtpndd_and_prof_ctx.last_switch, &now);
    mtpndd_and_prof_ctx.bucket_ns[mtpndd_and_prof_ctx.active] += delta;
    mtpndd_and_prof_ctx.active = bucket;
    mtpndd_and_prof_ctx.last_switch = now;
}

static inline void mtpndd_and_prof_finish(void) {
    if (mtpndd_and_prof_ctx.recording && mtpndd_and_depth == 1) {
        mtpndd_and_prof_switch(MTPNDD_AND_PROF_OTHER);
        mtpndd_and_prof_ctx.recording = false;
        uint64_t total_ns = 0;
        for (size_t i = 0; i < MTPNDD_AND_PROF_COUNT; i++) {
            total_ns += mtpndd_and_prof_ctx.bucket_ns[i];
        }
        uint64_t same_total =
                mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_OUTER_LOOP] +
                mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_INNER_LOOP] +
                mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_LABEL_LOAD] +
                mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_BDD_OP] +
                mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_ADD_EDGE];
        MTPNDD_STAT_ADD(and_fastpath_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_FASTPATH]);
        MTPNDD_STAT_ADD(and_cache_hit_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_CACHE_HIT]);
        MTPNDD_STAT_ADD(and_build_edges_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_BUILD_EDGES]);
        MTPNDD_STAT_ADD(and_same_field_ns, same_total);
        MTPNDD_STAT_ADD(and_same_outer_loop_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_OUTER_LOOP]);
        MTPNDD_STAT_ADD(and_same_inner_loop_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_INNER_LOOP]);
        MTPNDD_STAT_ADD(and_same_label_load_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_LABEL_LOAD]);
        MTPNDD_STAT_ADD(and_same_bdd_op_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_BDD_OP]);
        MTPNDD_STAT_ADD(and_same_add_edge_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_SAME_ADD_EDGE]);
        MTPNDD_STAT_ADD(and_diff_field_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_DIFF_FIELD]);
        MTPNDD_STAT_ADD(and_mk_ns, mtpndd_and_prof_ctx.bucket_ns[MTPNDD_AND_PROF_MK]);
        MTPNDD_STAT_ADD(and_time_ns, total_ns);
    }
    if (mtpndd_and_depth > 0) mtpndd_and_depth--;
}

#define MTPNDD_AND_RETURN(val) do { mtpndd_and_prof_finish(); return (val); } while(0)
#else
#define mtpndd_and_prof_start() ((void)0)
#define mtpndd_and_prof_switch(bucket) ((void)0)
#define mtpndd_and_prof_finish() ((void)0)
#define MTPNDD_AND_RETURN(val) return (val)
#endif

/********************************
 * MTPNDD operations (sub task)
 ********************************/
TASK_IMPL_2(mtpndd_and_item_t, mtpndd_and_same_field_item,
            edge_bucket_entry_t*, entry_a,
            edge_bucket_entry_t*, entry_b)
{
    mtpndd_and_item_t out = {0};
    out.status = MTPNDD_SUCCESS;
    out.emit = 0;
    out.child = NULL;
    out.label = 0;

    mtpndd_bdd_t label_a = mtpndd_edge_label_load(entry_a);
    mtpndd_bdd_t label_b = mtpndd_edge_label_load(entry_b);
    mtpndd_bdd_t combined_label = sylvan_ref(sylvan_and(label_a, label_b));
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(and_same_bdd_op_count, 1);
#endif
    if (combined_label == sylvan_false) {
        sylvan_deref(combined_label);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        MTPNDD_STAT_ADD(and_same_bdd_op_false_count, 1);
#endif
        return out;
    }

    mtpndd_t *sub_result = mtpndd_and_rec_CALL(__lace_worker, __lace_dq_head, entry_a->child, entry_b->child);
    if (!sub_result) {
        sylvan_deref(combined_label);
        out.status = mtpndd_get_last_error().code;
        return out;
    }

    // Hold a ref across batched SYNC so GC can't reclaim the node before merge.
    mtpndd_ref(sub_result);

    out.emit = 1;
    out.child = sub_result;
    out.label = combined_label;
    return out;
}

TASK_IMPL_2(mtpndd_and_item_t, mtpndd_and_diff_field_item,
            edge_bucket_entry_t*, entry_a,
            mtpndd_t*, b)
{
    mtpndd_and_item_t out = {0};
    out.status = MTPNDD_SUCCESS;
    out.emit = 0;
    out.child = NULL;
    out.label = 0;

    mtpndd_bdd_t label_a = sylvan_ref(mtpndd_edge_label_load(entry_a));
    mtpndd_t *sub_result = mtpndd_and_rec_CALL(__lace_worker, __lace_dq_head, entry_a->child, b);
    if (!sub_result) {
        sylvan_deref(label_a);
        out.status = mtpndd_get_last_error().code;
        return out;
    }

    mtpndd_ref(sub_result);

    out.emit = 1;
    out.child = sub_result;
    out.label = label_a;
    return out;
}

// Phase 2 task: recurse on a surviving pair whose label was already computed in Phase 1.
TASK_IMPL_1(mtpndd_and_item_t, mtpndd_and_recurse_pair, mtpndd_and_pair_t*, pair) {
    mtpndd_and_item_t out = {0};
    out.status = MTPNDD_SUCCESS;
    out.emit = 0;
    out.child = NULL;
    out.label = 0;

    mtpndd_t *sub_result = mtpndd_and_rec_CALL(__lace_worker, __lace_dq_head, pair->child_a, pair->child_b);
    if (!sub_result) {
        sylvan_deref(pair->label);
        out.status = mtpndd_get_last_error().code;
        return out;
    }

    mtpndd_ref(sub_result);

    out.emit = 1;
    out.child = sub_result;
    out.label = pair->label;   // transfer ownership of the ref
    return out;
}

static inline void mtpndd_and_cancel_same_field_items(WorkerP *__lace_worker, Task **dq_head, size_t *pending) {
    if (!pending) return;
    while (*pending > 0) {
        (*dq_head)--;
        Task *t = (Task *)*dq_head;
        if (TASK_IS_STOLEN(t)) {
            mtpndd_and_item_t item = mtpndd_and_same_field_item_SYNC(__lace_worker, *dq_head);
            if (item.emit) {
                sylvan_deref(item.label);
                mtpndd_deref(item.child);
            }
        } else {
            // Not stolen: dropping avoids executing the task and allocates no new resources.
            lace_drop(__lace_worker, *dq_head);
        }
        (*pending)--;
    }
}

static inline void mtpndd_and_cancel_diff_field_items(WorkerP *__lace_worker, Task **dq_head, size_t *pending) {
    if (!pending) return;
    while (*pending > 0) {
        (*dq_head)--;
        Task *t = (Task *)*dq_head;
        if (TASK_IS_STOLEN(t)) {
            mtpndd_and_item_t item = mtpndd_and_diff_field_item_SYNC(__lace_worker, *dq_head);
            if (item.emit) {
                sylvan_deref(item.label);
                mtpndd_deref(item.child);
            }
        } else {
            lace_drop(__lace_worker, *dq_head);
        }
        (*pending)--;
    }
}

static mtpndd_error_t mtpndd_and_merge_item(
        mtpndd_edge_t *res_edges,
        mtpndd_temp_ref_list_t *temp_refs,
        const mtpndd_and_item_t *item,
        bool same_field)
{
    if (!item) return MTPNDD_ERROR_NULL_POINTER;
    if (item->emit == 0) {
        return item->status;
    }

    if (item->status != MTPNDD_SUCCESS || item->child == NULL) {
        if (item->label) sylvan_deref(item->label);
        if (item->child) mtpndd_deref(item->child);
        return item->status != MTPNDD_SUCCESS ? item->status : MTPNDD_ERROR_UNKNOWN;
    }

    if (same_field) mtpndd_and_prof_switch(MTPNDD_AND_PROF_SAME_ADD_EDGE);
    if (!mtpndd_temp_refs_push_owned(temp_refs, item->child)) {
        if (same_field) mtpndd_and_prof_switch(MTPNDD_AND_PROF_SAME_INNER_LOOP);
        sylvan_deref(item->label);
        mtpndd_deref(item->child);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    mtpndd_error_t status = mtpndd_add_edge(res_edges, item->child, item->label);
    if (status != MTPNDD_SUCCESS) {
        // mtpndd_add_edge only derefs label_bdd on success
        sylvan_deref(item->label);
        if (same_field) mtpndd_and_prof_switch(MTPNDD_AND_PROF_SAME_INNER_LOOP);
        return status;
    }
    if (same_field) mtpndd_and_prof_switch(MTPNDD_AND_PROF_SAME_INNER_LOOP);
    return MTPNDD_SUCCESS;
}

static mtpndd_error_t mtpndd_and_drain_same_field_items(
        WorkerP *__lace_worker, Task **dq_head,
        size_t *pending, size_t max_drain,
        mtpndd_edge_t *res_edges,
        mtpndd_temp_ref_list_t *temp_refs)
{
    size_t n = (pending && *pending < max_drain) ? *pending : max_drain;
    for (size_t i = 0; i < n; ++i) {
        (*dq_head)--;
        mtpndd_and_item_t item = mtpndd_and_same_field_item_SYNC(__lace_worker, *dq_head);
        (*pending)--;
        mtpndd_error_t status = mtpndd_and_merge_item(res_edges, temp_refs, &item, true);
        if (status != MTPNDD_SUCCESS) return status;
    }
    return MTPNDD_SUCCESS;
}

static mtpndd_error_t mtpndd_and_drain_diff_field_items(
        WorkerP *__lace_worker, Task **dq_head,
        size_t *pending, size_t max_drain,
        mtpndd_edge_t *res_edges,
        mtpndd_temp_ref_list_t *temp_refs)
{
    size_t n = (pending && *pending < max_drain) ? *pending : max_drain;
    for (size_t i = 0; i < n; ++i) {
        (*dq_head)--;
        mtpndd_and_item_t item = mtpndd_and_diff_field_item_SYNC(__lace_worker, *dq_head);
        (*pending)--;
        mtpndd_error_t status = mtpndd_and_merge_item(res_edges, temp_refs, &item, false);
        if (status != MTPNDD_SUCCESS) return status;
    }
    return MTPNDD_SUCCESS;
}

/**
 * Two-phase AND for same-field case: separate BDD filtering from recursive descent.
 *
 * Phase 1 (serial): iterate E_a × E_b, compute sylvan_and on labels, collect
 *   surviving pairs where label != false.
 * Phase 2 (parallel): SPAWN independent recursive mtpndd_and_rec for each
 *   surviving pair, then SYNC + merge into res_edges.
 *
 * This decouples the cheap O(E_a*E_b) BDD filter from the expensive recursive
 * descent, letting the scheduler distribute recursive work across all workers.
 */
static mtpndd_error_t mtpndd_and_two_phase_same_field(
        WorkerP *__lace_worker, Task *__lace_dq_head,
        mtpndd_t *a, mtpndd_t *b,
        mtpndd_edge_t *res_edges,
        mtpndd_temp_ref_list_t *temp_refs)
{
    mtpndd_error_t status = MTPNDD_SUCCESS;
    size_t ea = a->edges ? a->edges->edge_count : 0;
    size_t eb = b->edges ? b->edges->edge_count : 0;
    size_t max_pairs = ea * eb;

    // Allocate pair buffer (stack for small, heap for large).
    mtpndd_and_pair_t stack_buf[64];
    mtpndd_and_pair_t *pairs = (max_pairs <= 64) ? stack_buf : NULL;
    if (!pairs) {
        pairs = (mtpndd_and_pair_t *)malloc(max_pairs * sizeof(mtpndd_and_pair_t));
        if (!pairs) return MTPNDD_ERROR_OUT_OF_MEMORY;
    }

    // --- Phase 1: filter ---
    size_t npairs = 0;
    size_t total_checked = 0;
    edge_bucket_entry_t *entry_a;
    edge_bucket_entry_t *entry_b;
    FOR_EACH_ENTRY_IN_ALL_BUCKETS(a->edges, entry_a) {
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(b->edges, entry_b) {
            total_checked++;
            mtpndd_bdd_t label_a = mtpndd_edge_label_load(entry_a);
            mtpndd_bdd_t label_b = mtpndd_edge_label_load(entry_b);
            mtpndd_bdd_t combined = sylvan_and(label_a, label_b);
            if (combined != sylvan_false) {
                sylvan_ref(combined);
                pairs[npairs++] = (mtpndd_and_pair_t){
                    .child_a = entry_a->child,
                    .child_b = entry_b->child,
                    .label = combined
                };
            }
        }
    }

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(and_two_phase_trigger_count, 1);
    MTPNDD_STAT_ADD(and_two_phase_total_pairs, total_checked);
    MTPNDD_STAT_ADD(and_two_phase_surviving, npairs);
    MTPNDD_STAT_ADD(and_same_bdd_op_count, total_checked);
    MTPNDD_STAT_ADD(and_same_bdd_op_false_count, total_checked - npairs);
#endif

    if (npairs == 0) {
        goto done;
    }

    // --- Phase 2: parallel recurse ---
    {
        size_t pending = 0;

        for (size_t i = 0; i < npairs; i++) {
            if (mtpndd_should_spawn(pairs[i].child_a, pairs[i].child_b)) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
                MTPNDD_STAT_ADD(and_spawn_total, 1);
                MTPNDD_STAT_ADD(and_spawn_same_total, 1);
#endif
                mtpndd_and_recurse_pair_SPAWN(__lace_worker, __lace_dq_head, &pairs[i]);
                __lace_dq_head++;
                pending++;
            } else {
                mtpndd_and_item_t item = mtpndd_and_recurse_pair_CALL(__lace_worker, __lace_dq_head, &pairs[i]);
                status = mtpndd_and_merge_item(res_edges, temp_refs, &item, true);
                if (status != MTPNDD_SUCCESS) goto fail_phase2;
            }

            if (pending >= MTPNDD_AND_PENDING_FLUSH_THRESHOLD) {
                size_t keep = MTPNDD_AND_PENDING_FLUSH_THRESHOLD / 2;
                if (keep == 0) keep = 1;
                size_t to_drain = pending > keep ? (pending - keep) : pending;
                // Drain recurse-pair tasks (same item type as same_field_item)
                for (size_t j = 0; j < to_drain; j++) {
                    __lace_dq_head--;
                    mtpndd_and_item_t item = mtpndd_and_recurse_pair_SYNC(__lace_worker, __lace_dq_head);
                    pending--;
                    status = mtpndd_and_merge_item(res_edges, temp_refs, &item, true);
                    if (status != MTPNDD_SUCCESS) goto fail_phase2;
                }
            }
        }

        // Drain remaining
        while (pending > 0) {
            __lace_dq_head--;
            mtpndd_and_item_t item = mtpndd_and_recurse_pair_SYNC(__lace_worker, __lace_dq_head);
            pending--;
            status = mtpndd_and_merge_item(res_edges, temp_refs, &item, true);
            if (status != MTPNDD_SUCCESS) goto fail_phase2;
        }

        goto done;

    fail_phase2:
        // Cancel remaining spawned tasks
        while (pending > 0) {
            __lace_dq_head--;
            Task *t = (Task *)__lace_dq_head;
            if (TASK_IS_STOLEN(t)) {
                mtpndd_and_item_t item = mtpndd_and_recurse_pair_SYNC(__lace_worker, __lace_dq_head);
                if (item.emit) {
                    sylvan_deref(item.label);
                    mtpndd_deref(item.child);
                }
            } else {
                lace_drop(__lace_worker, __lace_dq_head);
            }
            pending--;
        }
        // Release labels for un-processed pairs (they were not handed to a task)
        // All pairs before the current index already had their labels consumed (by CALL or SPAWN).
        // No cleanup needed — tasks own the labels.
    }

done:
    if (pairs != stack_buf) free(pairs);
    return status;
}

TASK_IMPL_2(mtpndd_t*, mtpndd_and_rec, mtpndd_t*, a, mtpndd_t*, b) {
    mtpndd_and_prof_start();
    mtpndd_error_t status = MTPNDD_SUCCESS;
    if (mtpndd_is_false(a) || mtpndd_is_true(b)) {
        mtpndd_and_prof_switch(MTPNDD_AND_PROF_FASTPATH);
        mtpndd_and_prof_finish();
        return a;
    } else if (mtpndd_is_false(b) || mtpndd_is_true(a) || a == b) {
        mtpndd_and_prof_switch(MTPNDD_AND_PROF_FASTPATH);
        mtpndd_and_prof_finish();
        return b;
    }

    mtpndd_node_t *cache_a = a;
    mtpndd_node_t *cache_b = b;
    if ((uintptr_t)cache_a > (uintptr_t)cache_b) {
        mtpndd_node_t *tmp = cache_a;
        cache_a = cache_b;
        cache_b = tmp;
    }
    mtpndd_op_cache_t *and_cache = g_mtpndd_config.and_cache;
    mtpndd_and_prof_switch(MTPNDD_AND_PROF_CACHE_HIT);
    mtpndd_node_t *cached = mtpndd_op_cache_lookup_binary(and_cache, cache_a, cache_b);
    if (cached) {
        mtpndd_and_prof_finish();
        return cached;
    }

    mtpndd_t *res_node = NULL;
    mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
    if (!res_edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        mtpndd_and_prof_finish();
        return NULL;
    }
    mtpndd_edge_map_init(res_edges);
    mtpndd_and_prof_switch(MTPNDD_AND_PROF_BUILD_EDGES);
    mtpndd_temp_ref_list_t temp_refs;
    mtpndd_temp_refs_init(&temp_refs);
    const bool same_field_case = (a->field_id == b->field_id);
    size_t pending = 0;

    if (same_field_case) {
        // Two-phase path: when the Cartesian product is large enough and we have
        // multiple workers, separate BDD label filtering from recursive descent.
        size_t ea = a->edges ? a->edges->edge_count : 0;
        size_t eb = b->edges ? b->edges->edge_count : 0;
        if (lace_workers() > 1 && g_mtpndd_two_phase_threshold > 0
                && (int)(ea * eb) >= g_mtpndd_two_phase_threshold) {
            status = mtpndd_and_two_phase_same_field(
                __lace_worker, __lace_dq_head,
                a, b, res_edges, &temp_refs);
            if (status != MTPNDD_SUCCESS) goto fail_build_edges;
            goto build_edges_ok;
        }

        // Original single-pass path (small edge maps or single worker).
        edge_bucket_entry_t *entry_a;
        edge_bucket_entry_t *entry_b;
        mtpndd_and_prof_switch(MTPNDD_AND_PROF_SAME_OUTER_LOOP);
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(a->edges, entry_a) {
            mtpndd_and_prof_switch(MTPNDD_AND_PROF_SAME_INNER_LOOP);
            FOR_EACH_ENTRY_IN_ALL_BUCKETS(b->edges, entry_b) {
                if (mtpndd_should_spawn(entry_a->child, entry_b->child)) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
                    MTPNDD_STAT_ADD(and_spawn_total, 1);
                    MTPNDD_STAT_ADD(and_spawn_same_total, 1);
#endif
                    mtpndd_and_same_field_item_SPAWN(__lace_worker, __lace_dq_head, entry_a, entry_b);
                    __lace_dq_head++;
                    pending++;
                } else {
                    mtpndd_and_item_t item = mtpndd_and_same_field_item_CALL(__lace_worker, __lace_dq_head, entry_a, entry_b);
                    status = mtpndd_and_merge_item(res_edges, &temp_refs, &item, true);
                    if (status != MTPNDD_SUCCESS) goto fail_build_edges;
                }

                if (pending >= MTPNDD_AND_PENDING_FLUSH_THRESHOLD) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
                    MTPNDD_STAT_ADD(and_pending_flush_total, 1);
#endif
                    size_t keep = MTPNDD_AND_PENDING_FLUSH_THRESHOLD / 2;
                    if (keep == 0) keep = 1;
                    size_t to_drain = pending > keep ? (pending - keep) : pending;
                    status = mtpndd_and_drain_same_field_items(__lace_worker, &__lace_dq_head, &pending, to_drain, res_edges, &temp_refs);
                    if (status != MTPNDD_SUCCESS) goto fail_build_edges;
                }
            }
            mtpndd_and_prof_switch(MTPNDD_AND_PROF_SAME_OUTER_LOOP);
        }

        if (pending > 0) {
            status = mtpndd_and_drain_same_field_items(__lace_worker, &__lace_dq_head, &pending, pending, res_edges, &temp_refs);
            if (status != MTPNDD_SUCCESS) goto fail_build_edges;
        }

        mtpndd_and_prof_switch(MTPNDD_AND_PROF_BUILD_EDGES);
    } else {
        // Different fields
        if (a->field_id > b->field_id) {
            mtpndd_t *temp = a;
            a = b;
            b = temp;
        }
        mtpndd_and_prof_switch(MTPNDD_AND_PROF_DIFF_FIELD);
        edge_bucket_entry_t *entry_a;
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(a->edges, entry_a) {
            if (mtpndd_should_spawn(entry_a->child, b)) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
                MTPNDD_STAT_ADD(and_spawn_total, 1);
                MTPNDD_STAT_ADD(and_spawn_diff_total, 1);
#endif
                mtpndd_and_diff_field_item_SPAWN(__lace_worker, __lace_dq_head, entry_a, b);
                __lace_dq_head++;
                pending++;
            } else {
                mtpndd_and_item_t item = mtpndd_and_diff_field_item_CALL(__lace_worker, __lace_dq_head, entry_a, b);
                status = mtpndd_and_merge_item(res_edges, &temp_refs, &item, false);
                if (status != MTPNDD_SUCCESS) goto fail_build_edges;
            }

            if (pending >= MTPNDD_AND_PENDING_FLUSH_THRESHOLD) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
                MTPNDD_STAT_ADD(and_pending_flush_total, 1);
#endif
                size_t keep = MTPNDD_AND_PENDING_FLUSH_THRESHOLD / 2;
                if (keep == 0) keep = 1;
                size_t to_drain = pending > keep ? (pending - keep) : pending;
                status = mtpndd_and_drain_diff_field_items(__lace_worker, &__lace_dq_head, &pending, to_drain, res_edges, &temp_refs);
                if (status != MTPNDD_SUCCESS) goto fail_build_edges;
            }
        }

        if (pending > 0) {
            status = mtpndd_and_drain_diff_field_items(__lace_worker, &__lace_dq_head, &pending, pending, res_edges, &temp_refs);
            if (status != MTPNDD_SUCCESS) goto fail_build_edges;
        }
    }

    goto build_edges_ok;

fail_build_edges:
    if (pending > 0) {
        if (same_field_case) {
            mtpndd_and_cancel_same_field_items(__lace_worker, &__lace_dq_head, &pending);
        } else {
            mtpndd_and_cancel_diff_field_items(__lace_worker, &__lace_dq_head, &pending);
        }
    }
    mtpndd_temp_refs_release(&temp_refs);
    mtpndd_edge_map_free(res_edges);
    mtpndd_and_prof_finish();
    return NULL;

build_edges_ok:
    mtpndd_and_prof_switch(MTPNDD_AND_PROF_MK);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    struct timespec mk_total_start = {0};
    struct timespec mk_total_end = {0};
    struct timespec mk_phase_start = {0};
    uint64_t mk_call_ns = 0;
    uint64_t mk_gc_ns = 0;
    uint64_t mk_cache_ns = 0;
    uint64_t mk_other_ns = 0;
    clock_gettime(CLOCK_MONOTONIC, &mk_total_start);
    clock_gettime(CLOCK_MONOTONIC, &mk_phase_start);
#endif
    mtpndd_mk(a->field_id, res_edges, &res_node);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    struct timespec mk_phase_end = {0};
    clock_gettime(CLOCK_MONOTONIC, &mk_phase_end);
    mk_call_ns = mtpndd_timespec_diff_ns(&mk_phase_start, &mk_phase_end);
    mtpndd_stat_add(&g_mtpndd_stats.and_mk_call_ns, mk_call_ns);
#endif
    if (!res_node) {
        mtpndd_temp_refs_release(&temp_refs);
        mtpndd_edge_map_free(res_edges);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        clock_gettime(CLOCK_MONOTONIC, &mk_total_end);
        uint64_t mk_total_ns = mtpndd_timespec_diff_ns(&mk_total_start, &mk_total_end);
        mk_other_ns = 0;
        if (mk_total_ns > mk_call_ns + mk_gc_ns + mk_cache_ns) {
            mk_other_ns = mk_total_ns - mk_call_ns - mk_gc_ns - mk_cache_ns;
        }
        mtpndd_stat_add(&g_mtpndd_stats.and_mk_other_ns, mk_other_ns);
#endif
        mtpndd_and_prof_finish();
        return NULL;
    }

    mtpndd_temp_refs_release(&temp_refs);

    mtpndd_op_cache_store_binary(and_cache, cache_a, cache_b, res_node);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    clock_gettime(CLOCK_MONOTONIC, &mk_phase_end);
    mk_cache_ns = mtpndd_timespec_diff_ns(&mk_phase_start, &mk_phase_end);
    mtpndd_stat_add(&g_mtpndd_stats.and_mk_cache_store_ns, mk_cache_ns);
    clock_gettime(CLOCK_MONOTONIC, &mk_total_end);
    uint64_t mk_total_ns = mtpndd_timespec_diff_ns(&mk_total_start, &mk_total_end);
    mk_other_ns = 0;
    if (mk_total_ns > mk_call_ns + mk_gc_ns + mk_cache_ns) {
        mk_other_ns = mk_total_ns - mk_call_ns - mk_gc_ns - mk_cache_ns;
    }
    mtpndd_stat_add(&g_mtpndd_stats.and_mk_other_ns, mk_other_ns);
#endif

    mtpndd_and_prof_finish();
    return res_node;
}

static inline void mtpndd_residual_apply_mask(edge_bucket_entry_t *entry, mtpndd_bdd_t mask) {
    if (!entry) {
        return;
    }

    mtpndd_bdd_t expected = atomic_load_explicit(&entry->label, memory_order_acquire);

    while (expected != sylvan_false) {
        mtpndd_bdd_t updated = sylvan_ref(sylvan_and(expected, mask));
        if (atomic_compare_exchange_weak_explicit(
                &entry->label, &expected, updated,
                memory_order_acq_rel, memory_order_acquire)) {
            sylvan_deref(expected);
            return;
        }
        sylvan_deref(updated);
    }
}

static inline mtpndd_bdd_t mtpndd_edge_label_load(edge_bucket_entry_t *entry) {
    if (!entry) {
        return sylvan_false;
    }
    return atomic_load_explicit(&entry->label, memory_order_acquire);
}

/********************************
 * MTPNDD OR sub-tasks (parallel)
 ********************************/
TASK_IMPL_3(mtpndd_or_item_t, mtpndd_or_same_field_item,
            edge_bucket_entry_t*, entry_a,
            edge_bucket_entry_t*, entry_b,
            mtpndd_or_shared_ctx_t*, ctx)
{
    mtpndd_or_item_t out = {0};
    out.status = MTPNDD_SUCCESS;
    out.emit = 0;
    out.child = NULL;
    out.label = 0;

    mtpndd_bdd_t label_a = mtpndd_edge_label_load(entry_a);
    mtpndd_bdd_t label_b = mtpndd_edge_label_load(entry_b);
    mtpndd_bdd_t intersect = sylvan_ref(sylvan_and(label_a, label_b));
    if (intersect == sylvan_false) {
        sylvan_deref(intersect);
        return out;
    }

    mtpndd_bdd_t notIntersect = sylvan_ref(sylvan_not(intersect));
    edge_bucket_entry_t *residual_entry_a = find_edge_entry(ctx->residualA, entry_a->child);
    edge_bucket_entry_t *residual_entry_b = find_edge_entry(ctx->residualB, entry_b->child);
    mtpndd_residual_apply_mask(residual_entry_a, notIntersect);
    mtpndd_residual_apply_mask(residual_entry_b, notIntersect);
    sylvan_deref(notIntersect);

    mtpndd_t *sub_result = mtpndd_or_rec_CALL(__lace_worker, __lace_dq_head, entry_a->child, entry_b->child);
    if (!sub_result) {
        sylvan_deref(intersect);
        out.status = mtpndd_get_last_error().code;
        return out;
    }

    mtpndd_ref(sub_result);

    out.emit = 1;
    out.child = sub_result;
    out.label = intersect;
    return out;
}

TASK_IMPL_3(mtpndd_or_item_t, mtpndd_or_diff_field_item,
            edge_bucket_entry_t*, entry_a,
            mtpndd_t*, b,
            _Atomic(mtpndd_bdd_t)*, residualB)
{
    mtpndd_or_item_t out = {0};
    out.status = MTPNDD_SUCCESS;
    out.emit = 0;
    out.child = NULL;
    out.label = 0;

    mtpndd_bdd_t label_a = mtpndd_edge_label_load(entry_a);
    mtpndd_bdd_t notIntersect = sylvan_ref(sylvan_not(label_a));
    mtpndd_bdd_t expected = atomic_load_explicit(residualB, memory_order_relaxed);
    for (;;) {
        mtpndd_bdd_t updated = sylvan_ref(sylvan_and(expected, notIntersect));
        if (atomic_compare_exchange_weak_explicit(
                residualB, &expected, updated,
                memory_order_acq_rel, memory_order_acquire)) {
            sylvan_deref(expected);
            break;
        }
        sylvan_deref(updated);
    }
    sylvan_deref(notIntersect);

    mtpndd_t *sub_result = mtpndd_or_rec_CALL(__lace_worker, __lace_dq_head, entry_a->child, b);
    if (!sub_result) {
        out.status = mtpndd_get_last_error().code;
        return out;
    }

    mtpndd_ref(sub_result);

    out.emit = 1;
    out.child = sub_result;
    out.label = sylvan_ref(label_a);
    return out;
}

static inline void mtpndd_or_cancel_same_field_items(WorkerP *__lace_worker, Task **dq_head, size_t *pending) {
    if (!pending) return;
    while (*pending > 0) {
        (*dq_head)--;
        Task *t = (Task *)*dq_head;
        if (TASK_IS_STOLEN(t)) {
            mtpndd_or_item_t item = mtpndd_or_same_field_item_SYNC(__lace_worker, *dq_head);
            if (item.emit) {
                sylvan_deref(item.label);
                mtpndd_deref(item.child);
            }
        } else {
            lace_drop(__lace_worker, *dq_head);
        }
        (*pending)--;
    }
}

static inline void mtpndd_or_cancel_diff_field_items(WorkerP *__lace_worker, Task **dq_head, size_t *pending) {
    if (!pending) return;
    while (*pending > 0) {
        (*dq_head)--;
        Task *t = (Task *)*dq_head;
        if (TASK_IS_STOLEN(t)) {
            mtpndd_or_item_t item = mtpndd_or_diff_field_item_SYNC(__lace_worker, *dq_head);
            if (item.emit) {
                sylvan_deref(item.label);
                mtpndd_deref(item.child);
            }
        } else {
            lace_drop(__lace_worker, *dq_head);
        }
        (*pending)--;
    }
}

static mtpndd_error_t mtpndd_or_merge_item(
        mtpndd_edge_t *res_edges,
        mtpndd_temp_ref_list_t *temp_refs,
        const mtpndd_or_item_t *item)
{
    if (!item) return MTPNDD_ERROR_NULL_POINTER;
    if (item->emit == 0) {
        return item->status;
    }

    if (item->status != MTPNDD_SUCCESS || item->child == NULL) {
        if (item->label) sylvan_deref(item->label);
        if (item->child) mtpndd_deref(item->child);
        return item->status != MTPNDD_SUCCESS ? item->status : MTPNDD_ERROR_UNKNOWN;
    }

    if (!mtpndd_temp_refs_push_owned(temp_refs, item->child)) {
        sylvan_deref(item->label);
        mtpndd_deref(item->child);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    mtpndd_error_t status = mtpndd_add_edge(res_edges, item->child, item->label);
    if (status != MTPNDD_SUCCESS) {
        sylvan_deref(item->label);
        return status;
    }
    return MTPNDD_SUCCESS;
}

static mtpndd_error_t mtpndd_or_drain_same_field_items(
        WorkerP *__lace_worker, Task **dq_head,
        size_t *pending, size_t max_drain,
        mtpndd_edge_t *res_edges,
        mtpndd_temp_ref_list_t *temp_refs)
{
    size_t n = (pending && *pending < max_drain) ? *pending : max_drain;
    for (size_t i = 0; i < n; ++i) {
        (*dq_head)--;
        mtpndd_or_item_t item = mtpndd_or_same_field_item_SYNC(__lace_worker, *dq_head);
        (*pending)--;
        mtpndd_error_t status = mtpndd_or_merge_item(res_edges, temp_refs, &item);
        if (status != MTPNDD_SUCCESS) return status;
    }
    return MTPNDD_SUCCESS;
}

static mtpndd_error_t mtpndd_or_drain_diff_field_items(
        WorkerP *__lace_worker, Task **dq_head,
        size_t *pending, size_t max_drain,
        mtpndd_edge_t *res_edges,
        mtpndd_temp_ref_list_t *temp_refs)
{
    size_t n = (pending && *pending < max_drain) ? *pending : max_drain;
    for (size_t i = 0; i < n; ++i) {
        (*dq_head)--;
        mtpndd_or_item_t item = mtpndd_or_diff_field_item_SYNC(__lace_worker, *dq_head);
        (*pending)--;
        mtpndd_error_t status = mtpndd_or_merge_item(res_edges, temp_refs, &item);
        if (status != MTPNDD_SUCCESS) return status;
    }
    return MTPNDD_SUCCESS;
}

TASK_IMPL_2(mtpndd_t*, mtpndd_or_rec, mtpndd_t*, a, mtpndd_t*, b) {
    mtpndd_error_t status = MTPNDD_SUCCESS;
    if (mtpndd_is_true(a) || mtpndd_is_false(b)) {
        return a;
    } else if (mtpndd_is_true(b) || mtpndd_is_false(a) || a == b) {
        return b;
    }

    mtpndd_node_t *cache_a = a;
    mtpndd_node_t *cache_b = b;
    if ((uintptr_t)cache_a > (uintptr_t)cache_b) {
        mtpndd_node_t *tmp = cache_a;
        cache_a = cache_b;
        cache_b = tmp;
    }
    mtpndd_op_cache_t *or_cache = g_mtpndd_config.or_cache;
    mtpndd_node_t *cached = mtpndd_op_cache_lookup_binary(or_cache, cache_a, cache_b);
    if (cached) {
        return cached;
    }

    mtpndd_t *res_node = NULL;
    mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
    if (!res_edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    mtpndd_edge_map_init(res_edges);
    mtpndd_temp_ref_list_t temp_refs;
    mtpndd_temp_refs_init(&temp_refs);
    const bool same_field_case = (a->field_id == b->field_id);
    size_t pending = 0;

    if (same_field_case) {
        // Same field, combine edges
        mtpndd_edge_t *residualA = mtpndd_memory_acquire_edge_map();
        if (!residualA) {
            mtpndd_edge_map_free(res_edges);
            mtpndd_temp_refs_release(&temp_refs);
            mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
            return NULL;
        }
        if (mtpndd_edge_map_deep_clone(a->edges, residualA) != MTPNDD_SUCCESS) {
            mtpndd_edge_map_free(residualA);
            mtpndd_edge_map_free(res_edges);
            mtpndd_temp_refs_release(&temp_refs);
            mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
            return NULL;
        }
        mtpndd_edge_t *residualB = mtpndd_memory_acquire_edge_map();
        if (!residualB) {
            mtpndd_edge_map_free(residualA);
            mtpndd_edge_map_free(res_edges);
            mtpndd_temp_refs_release(&temp_refs);
            mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
            return NULL;
        }
        if (mtpndd_edge_map_deep_clone(b->edges, residualB) != MTPNDD_SUCCESS) {
            mtpndd_edge_map_free(residualA);
            mtpndd_edge_map_free(residualB);
            mtpndd_edge_map_free(res_edges);
            mtpndd_temp_refs_release(&temp_refs);
            mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
            return NULL;
        }

        mtpndd_or_shared_ctx_t ctx = { .residualA = residualA, .residualB = residualB };

        edge_bucket_entry_t *entry_a;
        edge_bucket_entry_t *entry_b;
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(a->edges, entry_a) {
            FOR_EACH_ENTRY_IN_ALL_BUCKETS(b->edges, entry_b) {
                if (mtpndd_should_spawn(entry_a->child, entry_b->child)) {
                    mtpndd_or_same_field_item_SPAWN(__lace_worker, __lace_dq_head, entry_a, entry_b, &ctx);
                    __lace_dq_head++;
                    pending++;
                } else {
                    mtpndd_or_item_t item = mtpndd_or_same_field_item_CALL(__lace_worker, __lace_dq_head, entry_a, entry_b, &ctx);
                    status = mtpndd_or_merge_item(res_edges, &temp_refs, &item);
                    if (status != MTPNDD_SUCCESS) {
                        mtpndd_or_cancel_same_field_items(__lace_worker, &__lace_dq_head, &pending);
                        mtpndd_edge_map_free(residualA);
                        mtpndd_edge_map_free(residualB);
                        goto fail_or;
                    }
                }

                if (pending >= MTPNDD_AND_PENDING_FLUSH_THRESHOLD) {
                    size_t keep = MTPNDD_AND_PENDING_FLUSH_THRESHOLD / 2;
                    if (keep == 0) keep = 1;
                    size_t to_drain = pending > keep ? (pending - keep) : pending;
                    status = mtpndd_or_drain_same_field_items(__lace_worker, &__lace_dq_head, &pending, to_drain, res_edges, &temp_refs);
                    if (status != MTPNDD_SUCCESS) {
                        mtpndd_or_cancel_same_field_items(__lace_worker, &__lace_dq_head, &pending);
                        mtpndd_edge_map_free(residualA);
                        mtpndd_edge_map_free(residualB);
                        goto fail_or;
                    }
                }
            }
        }

        if (pending > 0) {
            status = mtpndd_or_drain_same_field_items(__lace_worker, &__lace_dq_head, &pending, pending, res_edges, &temp_refs);
            if (status != MTPNDD_SUCCESS) {
                mtpndd_or_cancel_same_field_items(__lace_worker, &__lace_dq_head, &pending);
                mtpndd_edge_map_free(residualA);
                mtpndd_edge_map_free(residualB);
                goto fail_or;
            }
        }

        // Add remaining residual edges
        edge_bucket_entry_t *entry_res;
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(residualA, entry_res) {
            mtpndd_bdd_t res_label = mtpndd_edge_label_load(entry_res);
            if (res_label != sylvan_false) {
                if (!mtpndd_temp_refs_push(&temp_refs, entry_res->child)) {
                    mtpndd_edge_map_free(residualA);
                    mtpndd_edge_map_free(residualB);
                    goto fail_or;
                }
                status = mtpndd_add_edge(res_edges, entry_res->child, sylvan_ref(res_label));
                if (status != MTPNDD_SUCCESS) {
                    mtpndd_edge_map_free(residualA);
                    mtpndd_edge_map_free(residualB);
                    goto fail_or;
                }
            }
        }
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(residualB, entry_res) {
            mtpndd_bdd_t res_label = mtpndd_edge_label_load(entry_res);
            if (res_label != sylvan_false) {
                if (!mtpndd_temp_refs_push(&temp_refs, entry_res->child)) {
                    mtpndd_edge_map_free(residualA);
                    mtpndd_edge_map_free(residualB);
                    goto fail_or;
                }
                status = mtpndd_add_edge(res_edges, entry_res->child, sylvan_ref(res_label));
                if (status != MTPNDD_SUCCESS) {
                    mtpndd_edge_map_free(residualA);
                    mtpndd_edge_map_free(residualB);
                    goto fail_or;
                }
            }
        }
        mtpndd_edge_map_free(residualA);
        mtpndd_edge_map_free(residualB);
    } else {
        // Different fields
        if (a->field_id > b->field_id) {
            mtpndd_t *temp = a;
            a = b;
            b = temp;
        }

        _Atomic(mtpndd_bdd_t) residualB_atom = sylvan_true;
        edge_bucket_entry_t *entry_a;
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(a->edges, entry_a) {
            if (mtpndd_should_spawn(entry_a->child, b)) {
                mtpndd_or_diff_field_item_SPAWN(__lace_worker, __lace_dq_head, entry_a, b, &residualB_atom);
                __lace_dq_head++;
                pending++;
            } else {
                mtpndd_or_item_t item = mtpndd_or_diff_field_item_CALL(__lace_worker, __lace_dq_head, entry_a, b, &residualB_atom);
                status = mtpndd_or_merge_item(res_edges, &temp_refs, &item);
                if (status != MTPNDD_SUCCESS) goto fail_or_diff;
            }

            if (pending >= MTPNDD_AND_PENDING_FLUSH_THRESHOLD) {
                size_t keep = MTPNDD_AND_PENDING_FLUSH_THRESHOLD / 2;
                if (keep == 0) keep = 1;
                size_t to_drain = pending > keep ? (pending - keep) : pending;
                status = mtpndd_or_drain_diff_field_items(__lace_worker, &__lace_dq_head, &pending, to_drain, res_edges, &temp_refs);
                if (status != MTPNDD_SUCCESS) goto fail_or_diff;
            }
        }

        if (pending > 0) {
            status = mtpndd_or_drain_diff_field_items(__lace_worker, &__lace_dq_head, &pending, pending, res_edges, &temp_refs);
            if (status != MTPNDD_SUCCESS) goto fail_or_diff;
        }

        mtpndd_bdd_t residualB_val = atomic_load_explicit(&residualB_atom, memory_order_relaxed);
        if (residualB_val != sylvan_false) {
            if (!mtpndd_temp_refs_push(&temp_refs, b)) {
                sylvan_deref(residualB_val);
                goto fail_or;
            }
            status = mtpndd_add_edge(res_edges, b, residualB_val);
            if (status != MTPNDD_SUCCESS) {
                sylvan_deref(residualB_val);
                goto fail_or;
            }
        }

        goto or_build_ok;

    fail_or_diff:
        if (pending > 0) {
            mtpndd_or_cancel_diff_field_items(__lace_worker, &__lace_dq_head, &pending);
        }
        goto fail_or;
    }

    goto or_build_ok;

fail_or:
    mtpndd_temp_refs_release(&temp_refs);
    mtpndd_edge_map_free(res_edges);
    return NULL;

or_build_ok:
    mtpndd_mk(a->field_id, res_edges, &res_node);
    if (!res_node) {
        mtpndd_edge_map_free(res_edges);
        mtpndd_temp_refs_release(&temp_refs);
        return NULL;
    }
    mtpndd_temp_refs_release(&temp_refs);

    mtpndd_op_cache_store_binary(or_cache, cache_a, cache_b, res_node);

    return res_node;
}

/********************************
 * MTPNDD NOT sub-tasks (parallel)
 ********************************/
TASK_IMPL_2(mtpndd_not_item_t, mtpndd_not_expand_item,
            edge_bucket_entry_t*, entry_a,
            _Atomic(mtpndd_bdd_t)*, residual)
{
    mtpndd_not_item_t out = {0};
    out.status = MTPNDD_SUCCESS;
    out.emit = 0;
    out.child = NULL;
    out.label = 0;

    mtpndd_bdd_t label_a = mtpndd_edge_label_load(entry_a);
    mtpndd_bdd_t notIntersect = sylvan_ref(sylvan_not(label_a));
    mtpndd_bdd_t expected = atomic_load_explicit(residual, memory_order_relaxed);
    for (;;) {
        mtpndd_bdd_t updated = sylvan_ref(sylvan_and(expected, notIntersect));
        if (atomic_compare_exchange_weak_explicit(
                residual, &expected, updated,
                memory_order_acq_rel, memory_order_acquire)) {
            sylvan_deref(expected);
            break;
        }
        sylvan_deref(updated);
    }
    sylvan_deref(notIntersect);

    mtpndd_t *sub_result = mtpndd_not_rec_CALL(__lace_worker, __lace_dq_head, entry_a->child);
    if (!sub_result) {
        out.status = mtpndd_get_last_error().code;
        return out;
    }

    mtpndd_ref(sub_result);

    out.emit = 1;
    out.child = sub_result;
    out.label = sylvan_ref(label_a);
    return out;
}

static inline void mtpndd_not_cancel_items(WorkerP *__lace_worker, Task **dq_head, size_t *pending) {
    if (!pending) return;
    while (*pending > 0) {
        (*dq_head)--;
        Task *t = (Task *)*dq_head;
        if (TASK_IS_STOLEN(t)) {
            mtpndd_not_item_t item = mtpndd_not_expand_item_SYNC(__lace_worker, *dq_head);
            if (item.emit) {
                sylvan_deref(item.label);
                mtpndd_deref(item.child);
            }
        } else {
            lace_drop(__lace_worker, *dq_head);
        }
        (*pending)--;
    }
}

static mtpndd_error_t mtpndd_not_merge_item(
        mtpndd_edge_t *res_edges,
        mtpndd_temp_ref_list_t *temp_refs,
        const mtpndd_not_item_t *item)
{
    if (!item) return MTPNDD_ERROR_NULL_POINTER;
    if (item->emit == 0) {
        return item->status;
    }

    if (item->status != MTPNDD_SUCCESS || item->child == NULL) {
        if (item->label) sylvan_deref(item->label);
        if (item->child) mtpndd_deref(item->child);
        return item->status != MTPNDD_SUCCESS ? item->status : MTPNDD_ERROR_UNKNOWN;
    }

    if (!mtpndd_temp_refs_push_owned(temp_refs, item->child)) {
        sylvan_deref(item->label);
        mtpndd_deref(item->child);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    mtpndd_error_t status = mtpndd_add_edge(res_edges, item->child, item->label);
    if (status != MTPNDD_SUCCESS) {
        sylvan_deref(item->label);
        return status;
    }
    return MTPNDD_SUCCESS;
}

static mtpndd_error_t mtpndd_not_drain_items(
        WorkerP *__lace_worker, Task **dq_head,
        size_t *pending, size_t max_drain,
        mtpndd_edge_t *res_edges,
        mtpndd_temp_ref_list_t *temp_refs)
{
    size_t n = (pending && *pending < max_drain) ? *pending : max_drain;
    for (size_t i = 0; i < n; ++i) {
        (*dq_head)--;
        mtpndd_not_item_t item = mtpndd_not_expand_item_SYNC(__lace_worker, *dq_head);
        (*pending)--;
        mtpndd_error_t status = mtpndd_not_merge_item(res_edges, temp_refs, &item);
        if (status != MTPNDD_SUCCESS) return status;
    }
    return MTPNDD_SUCCESS;
}

TASK_IMPL_1(mtpndd_t*, mtpndd_not_rec, mtpndd_t*, a) {
    if (mtpndd_is_true(a)) {
        return &MTPNDD_FALSE;
    } else if (mtpndd_is_false(a)) {
        return &MTPNDD_TRUE;
    }

    mtpndd_op_cache_t *not_cache = g_mtpndd_config.not_cache;
    mtpndd_node_t *cached = mtpndd_op_cache_lookup_unary(not_cache, a);
    if (cached) {
        return cached;
    }

    mtpndd_error_t status = MTPNDD_SUCCESS;
    mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
    if (!res_edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    mtpndd_edge_map_init(res_edges);
    mtpndd_temp_ref_list_t temp_refs;
    mtpndd_temp_refs_init(&temp_refs);

    _Atomic(mtpndd_bdd_t) residual = sylvan_true;
    mtpndd_t *res_node = NULL;
    size_t pending = 0;
    edge_bucket_entry_t *entry_a;
    FOR_EACH_ENTRY_IN_ALL_BUCKETS(a->edges, entry_a) {
        if (mtpndd_should_spawn_unary(entry_a->child)) {
            mtpndd_not_expand_item_SPAWN(__lace_worker, __lace_dq_head, entry_a, &residual);
            __lace_dq_head++;
            pending++;
        } else {
            mtpndd_not_item_t item = mtpndd_not_expand_item_CALL(__lace_worker, __lace_dq_head, entry_a, &residual);
            status = mtpndd_not_merge_item(res_edges, &temp_refs, &item);
            if (status != MTPNDD_SUCCESS) goto fail_not;
        }

        if (pending >= MTPNDD_AND_PENDING_FLUSH_THRESHOLD) {
            size_t keep = MTPNDD_AND_PENDING_FLUSH_THRESHOLD / 2;
            if (keep == 0) keep = 1;
            size_t to_drain = pending > keep ? (pending - keep) : pending;
            status = mtpndd_not_drain_items(__lace_worker, &__lace_dq_head, &pending, to_drain, res_edges, &temp_refs);
            if (status != MTPNDD_SUCCESS) goto fail_not;
        }
    }

    if (pending > 0) {
        status = mtpndd_not_drain_items(__lace_worker, &__lace_dq_head, &pending, pending, res_edges, &temp_refs);
        if (status != MTPNDD_SUCCESS) goto fail_not;
    }

    goto not_build_ok;

fail_not:
    if (pending > 0) {
        mtpndd_not_cancel_items(__lace_worker, &__lace_dq_head, &pending);
    }
    mtpndd_temp_refs_release(&temp_refs);
    mtpndd_edge_map_free(res_edges);
    return NULL;

not_build_ok:;
    mtpndd_bdd_t residual_val = atomic_load_explicit(&residual, memory_order_relaxed);
    if (residual_val != sylvan_false) {
        if (!mtpndd_temp_refs_push(&temp_refs, &MTPNDD_TRUE)) {
            sylvan_deref(residual_val);
            mtpndd_edge_map_free(res_edges);
            mtpndd_temp_refs_release(&temp_refs);
            return NULL;
        }
        status = mtpndd_add_edge(res_edges, &MTPNDD_TRUE, residual_val);
        if (status != MTPNDD_SUCCESS) {
            sylvan_deref(residual_val);
            mtpndd_edge_map_free(res_edges);
            mtpndd_temp_refs_release(&temp_refs);
            return NULL;
        }
    }
    mtpndd_mk(a->field_id, res_edges, &res_node);
    if (!res_node) {
        mtpndd_edge_map_free(res_edges);
        mtpndd_temp_refs_release(&temp_refs);
        return NULL;
    }
    mtpndd_temp_refs_release(&temp_refs);

    mtpndd_op_cache_store_unary(not_cache, a, res_node);

    return res_node;
}

// diff = a AND (NOT b)
static mtpndd_error_t mtpndd_exist_expand(edge_bucket_entry_t *entry_a,
        mtpndd_edge_t *res_edges, uint32_t field,
        mtpndd_temp_ref_list_t *temp_refs)
{
    mtpndd_t *subResult = NULL;
    mtpndd_error_t status = mtpndd_exist_rec(entry_a->child, field, &subResult);
    if (status != MTPNDD_SUCCESS) {
        return status;
    }
    mtpndd_bdd_t label_a = mtpndd_edge_label_load(entry_a);
    if (!mtpndd_temp_refs_push(temp_refs, subResult)) {
        sylvan_deref(label_a);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    status = mtpndd_add_edge(res_edges, subResult, sylvan_ref(label_a));
    if (status != MTPNDD_SUCCESS) {
        sylvan_deref(label_a);
    }
    return status;
}

static mtpndd_error_t mtpndd_exist_rec(mtpndd_t *a, uint32_t field, mtpndd_t **result) {
    if (mtpndd_is_terminal(a)) {
        *result = a;
        return MTPNDD_SUCCESS;
    }

    mtpndd_t *res_node = &MTPNDD_FALSE;
    if (a->field_id == field) {
        edge_bucket_entry_t *entry_a = NULL;
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(a->edges, entry_a) {
            res_node = mtpndd_or(res_node, entry_a->child);
            if (!res_node) {
                return mtpndd_get_last_error().code;
            }
        }
    } else {
        // Keep this field
        mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
        if (!res_edges) {
            mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
            return MTPNDD_ERROR_OUT_OF_MEMORY;
        }
        mtpndd_edge_map_init(res_edges);

        mtpndd_temp_ref_list_t temp_refs;
        mtpndd_temp_refs_init(&temp_refs);
        edge_bucket_entry_t *entry_a;
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(a->edges, entry_a) {
            mtpndd_error_t status = mtpndd_exist_expand(entry_a, res_edges, field, &temp_refs);
            if (status != MTPNDD_SUCCESS) {
                mtpndd_edge_map_free(res_edges);
                mtpndd_temp_refs_release(&temp_refs);
                return status;
            }
        }
        mtpndd_mk(a->field_id, res_edges, &res_node);
        if (!res_node) {
            mtpndd_edge_map_free(res_edges);
            mtpndd_temp_refs_release(&temp_refs);
            return mtpndd_get_last_error().code;
        }
        mtpndd_temp_refs_release(&temp_refs);
    }
    *result = res_node;
    return MTPNDD_SUCCESS;
}

/********************************
 * MTPNDD operations (wrappers)
 ********************************/
mtpndd_t *mtpndd_and(mtpndd_t *a, mtpndd_t *b) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(and_call_total, 1);
    MTPNDD_RECORD_TIME_START(_t_and_call);
    mtpndd_t *result = RUN(mtpndd_and_rec, a, b);
    MTPNDD_RECORD_TIME_END(and_call_wall_ns, _t_and_call);
    return result;
#else
    return RUN(mtpndd_and_rec, a, b);
#endif
}

mtpndd_t *mtpndd_or(mtpndd_t *a, mtpndd_t *b) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(or_call_total, 1);
    MTPNDD_RECORD_TIME_START(_t_or_call);
    MTPNDD_RECORD_TIME_START(_t_or);
    mtpndd_t *result = RUN(mtpndd_or_rec, a, b);
    MTPNDD_RECORD_TIME_END(or_time_ns, _t_or);
    MTPNDD_RECORD_TIME_END(or_call_wall_ns, _t_or_call);
    return result;
#else
    return RUN(mtpndd_or_rec, a, b);
#endif
}

mtpndd_t *mtpndd_not(mtpndd_t *a) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(not_call_total, 1);
    MTPNDD_RECORD_TIME_START(_t_not_call);
    MTPNDD_RECORD_TIME_START(_t_not);
    mtpndd_t *result = RUN(mtpndd_not_rec, a);
    MTPNDD_RECORD_TIME_END(not_time_ns, _t_not);
    MTPNDD_RECORD_TIME_END(not_call_wall_ns, _t_not_call);
    return result;
#else
    return RUN(mtpndd_not_rec, a);
#endif
}

mtpndd_t *mtpndd_diff(mtpndd_t *a, mtpndd_t *b) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(diff_call_total, 1);
    MTPNDD_RECORD_TIME_START(_t_diff_call);
    mtpndd_t *not_b = mtpndd_not(b);
    if (!not_b) {
        MTPNDD_RECORD_TIME_END(diff_call_wall_ns, _t_diff_call);
        return NULL;
    }
    mtpndd_t *result = mtpndd_and(a, not_b);
    MTPNDD_RECORD_TIME_END(diff_call_wall_ns, _t_diff_call);
    return result;
#else
    mtpndd_t *not_b = mtpndd_not(b);
    if (!not_b) {
        return NULL;
    }
    return mtpndd_and(a, not_b);
#endif
}

mtpndd_t *mtpndd_exist(mtpndd_t *a, uint32_t field) {
    mtpndd_t *result = NULL;
    if (mtpndd_exist_rec(a, field, &result) != MTPNDD_SUCCESS) {
        return NULL;
    }
    return result;
}

mtpndd_t *mtpndd_or_demorgan(mtpndd_t *a, mtpndd_t *b) {
    mtpndd_t *not_a = mtpndd_not(a);
    if (!not_a) return NULL;
    mtpndd_ref(not_a);

    mtpndd_t *not_b = mtpndd_not(b);
    if (!not_b) {
        mtpndd_deref(not_a);
        return NULL;
    }
    mtpndd_ref(not_b);

    mtpndd_t *and_result = mtpndd_and(not_a, not_b);
    mtpndd_deref(not_a);
    mtpndd_deref(not_b);
    if (!and_result) return NULL;
    mtpndd_ref(and_result);

    mtpndd_t *result = mtpndd_not(and_result);
    mtpndd_deref(and_result);
    return result;
}

/********************************
 * MTPNDD batch operations
 ********************************/

// Lace task for tree-reduce OR: recursively split, SPAWN left, CALL right, SYNC+OR
// Intermediate results must be protected by temp_refs to survive GC.
TASK_DECL_2(mtpndd_t*, mtpndd_or_reduce_rec, mtpndd_t**, size_t);
TASK_IMPL_2(mtpndd_t*, mtpndd_or_reduce_rec, mtpndd_t**, values, size_t, count) {
    if (count == 0) return &MTPNDD_FALSE;
    if (count == 1) return values[0];
    if (count == 2) return CALL(mtpndd_or_rec, values[0], values[1]);

    size_t mid = count / 2;
    mtpndd_temp_ref_list_t trl;
    mtpndd_temp_refs_init(&trl);

    SPAWN(mtpndd_or_reduce_rec, values, mid);
    mtpndd_t *right = CALL(mtpndd_or_reduce_rec, values + mid, count - mid);
    mtpndd_temp_refs_push(&trl, right);
    mtpndd_t *left = SYNC(mtpndd_or_reduce_rec);
    mtpndd_temp_refs_push(&trl, left);

    mtpndd_t *result = NULL;
    if (left && right) {
        result = CALL(mtpndd_or_rec, left, right);
    }
    mtpndd_temp_refs_release(&trl);
    return result;
}

// Lace task for tree-reduce AND
// Intermediate results must be protected by temp_refs to survive GC.
TASK_DECL_2(mtpndd_t*, mtpndd_and_reduce_rec, mtpndd_t**, size_t);
TASK_IMPL_2(mtpndd_t*, mtpndd_and_reduce_rec, mtpndd_t**, values, size_t, count) {
    if (count == 0) return &MTPNDD_TRUE;
    if (count == 1) return values[0];
    if (count == 2) return CALL(mtpndd_and_rec, values[0], values[1]);

    size_t mid = count / 2;
    mtpndd_temp_ref_list_t trl;
    mtpndd_temp_refs_init(&trl);

    SPAWN(mtpndd_and_reduce_rec, values, mid);
    mtpndd_t *right = CALL(mtpndd_and_reduce_rec, values + mid, count - mid);
    mtpndd_temp_refs_push(&trl, right);
    mtpndd_t *left = SYNC(mtpndd_and_reduce_rec);
    mtpndd_temp_refs_push(&trl, left);

    mtpndd_t *result = NULL;
    if (left && right) {
        result = CALL(mtpndd_and_rec, left, right);
    }
    mtpndd_temp_refs_release(&trl);
    return result;
}

mtpndd_t **mtpndd_and_batch(mtpndd_t **lefts, mtpndd_t **rights, size_t count) {
    if (count == 0) return NULL;

    mtpndd_t **results = (mtpndd_t **)calloc(count, sizeof(mtpndd_t *));
    if (!results) return NULL;

    // Simple sequential approach inside a single RUN entry point.
    // Each individual AND can still exploit internal Lace parallelism.
    for (size_t i = 0; i < count; i++) {
        results[i] = mtpndd_and(lefts[i], rights[i]);
        if (results[i]) {
            mtpndd_ref(results[i]);
        }
    }

    return results;
}

mtpndd_t *mtpndd_or_reduce(mtpndd_t **values, size_t count) {
    if (count == 0) return &MTPNDD_FALSE;
    if (count == 1) return values[0];
    return RUN(mtpndd_or_reduce_rec, values, count);
}

mtpndd_t *mtpndd_and_reduce(mtpndd_t **values, size_t count) {
    if (count == 0) return &MTPNDD_TRUE;
    if (count == 1) return values[0];
    return RUN(mtpndd_and_reduce_rec, values, count);
}

/********************************
 * MTPNDD <-> MTBDD convertion
 ********************************/
typedef struct {
    mtpndd_node_t **keys;
    mtpndd_bdd_t *values;
    size_t capacity;
    size_t count;
} mtpndd_to_mtbdd_cache_t;

typedef struct {
    mtpndd_bdd_t *keys;
    mtpndd_node_t **values;
    size_t capacity;
    size_t count;
} mtbdd_to_mtpndd_cache_t;

static mtpndd_error_t mtpndd_to_mtbdd_cache_init(mtpndd_to_mtbdd_cache_t *cache, size_t initial_capacity) {
    cache->capacity = 1;
    while (cache->capacity < initial_capacity) {
        cache->capacity <<= 1;
    }
    cache->count = 0;
    cache->keys = (mtpndd_node_t **)calloc(cache->capacity, sizeof(mtpndd_node_t *));
    cache->values = (mtpndd_bdd_t *)calloc(cache->capacity, sizeof(mtpndd_bdd_t));
    if (!cache->keys || !cache->values) {
        free(cache->keys);
        free(cache->values);
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    return MTPNDD_SUCCESS;
}

static void mtpndd_to_mtbdd_cache_destroy(mtpndd_to_mtbdd_cache_t *cache) {
    if (!cache || !cache->keys) {
        return;
    }
    for (size_t i = 0; i < cache->capacity; ++i) {
        if (cache->keys[i]) {
            sylvan_deref(cache->values[i]);
        }
    }
    free(cache->keys);
    free(cache->values);
    cache->keys = NULL;
    cache->values = NULL;
    cache->capacity = 0;
    cache->count = 0;
}

static inline void mtpndd_to_mtbdd_cache_place(
        mtpndd_to_mtbdd_cache_t *cache,
        mtpndd_node_t *key,
        mtpndd_bdd_t value,
        bool take_ref)
{
    size_t mask = cache->capacity - 1;
    size_t idx = mtpndd_hash_u64((uint64_t)(uintptr_t)key) & mask;
    while (cache->keys[idx]) {
        idx = (idx + 1) & mask;
    }
    cache->keys[idx] = key;
    cache->values[idx] = take_ref ? sylvan_ref(value) : value;
    cache->count++;
}

static bool mtpndd_to_mtbdd_cache_lookup(mtpndd_to_mtbdd_cache_t *cache, mtpndd_node_t *key, mtpndd_bdd_t *value) {
    if (!cache->keys || cache->count == 0) {
        return false;
    }
    size_t mask = cache->capacity - 1;
    size_t idx = mtpndd_hash_u64((uint64_t)(uintptr_t)key) & mask;
    while (cache->keys[idx]) {
        if (cache->keys[idx] == key) {
            *value = sylvan_ref(cache->values[idx]);
            return true;
        }
        idx = (idx + 1) & mask;
    }
    return false;
}

static mtpndd_error_t mtpndd_to_mtbdd_cache_insert(mtpndd_to_mtbdd_cache_t *cache, mtpndd_node_t *key, mtpndd_bdd_t value);

static mtpndd_error_t mtpndd_to_mtbdd_cache_rehash(mtpndd_to_mtbdd_cache_t *cache) {
    size_t new_capacity = cache->capacity << 1;
    mtpndd_node_t **new_keys = (mtpndd_node_t **)calloc(new_capacity, sizeof(mtpndd_node_t *));
    mtpndd_bdd_t *new_values = (mtpndd_bdd_t *)calloc(new_capacity, sizeof(mtpndd_bdd_t));
    if (!new_keys || !new_values) {
        free(new_keys);
        free(new_values);
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    size_t old_capacity = cache->capacity;
    mtpndd_node_t **old_keys = cache->keys;
    mtpndd_bdd_t *old_values = cache->values;
    cache->keys = new_keys;
    cache->values = new_values;
    cache->capacity = new_capacity;
    cache->count = 0;
    for (size_t i = 0; i < old_capacity; ++i) {
        if (old_keys[i]) {
            mtpndd_to_mtbdd_cache_place(cache, old_keys[i], old_values[i], false);
        }
    }
    free(old_keys);
    free(old_values);
    return MTPNDD_SUCCESS;
}

static mtpndd_error_t mtpndd_to_mtbdd_cache_insert(mtpndd_to_mtbdd_cache_t *cache, mtpndd_node_t *key, mtpndd_bdd_t value) {
    if ((cache->count + 1) * 4 >= cache->capacity * 3) {
        mtpndd_error_t err = mtpndd_to_mtbdd_cache_rehash(cache);
        if (err != MTPNDD_SUCCESS) {
            return err;
        }
    }
    mtpndd_to_mtbdd_cache_place(cache, key, value, true);
    return MTPNDD_SUCCESS;
}

static mtpndd_error_t mtbdd_to_mtpndd_cache_init(mtbdd_to_mtpndd_cache_t *cache, size_t initial_capacity) {
    cache->capacity = 1;
    while (cache->capacity < initial_capacity) {
        cache->capacity <<= 1;
    }
    cache->count = 0;
    cache->keys = (mtpndd_bdd_t *)calloc(cache->capacity, sizeof(mtpndd_bdd_t));
    cache->values = (mtpndd_t **)calloc(cache->capacity, sizeof(mtpndd_t *));
    if (!cache->keys || !cache->values) {
        free(cache->keys);
        free(cache->values);
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    return MTPNDD_SUCCESS;
}

static void mtbdd_to_mtpndd_cache_destroy(mtbdd_to_mtpndd_cache_t *cache) {
    if (!cache || !cache->keys) {
        return;
    }
    free(cache->keys);
    free(cache->values);
    cache->keys = NULL;
    cache->values = NULL;
    cache->capacity = 0;
    cache->count = 0;
}

static bool mtbdd_to_mtpndd_cache_lookup(mtbdd_to_mtpndd_cache_t *cache, mtpndd_bdd_t key, mtpndd_t **value) {
    if (!cache->keys || cache->count == 0) {
        return false;
    }
    size_t mask = cache->capacity - 1;
    size_t idx = mtpndd_hash_u64(key) & mask;
    while (cache->keys[idx]) {
        if (cache->keys[idx] == key) {
            *value = cache->values[idx];
            return true;
        }
        idx = (idx + 1) & mask;
    }
    return false;
}

static mtpndd_error_t mtbdd_to_mtpndd_cache_insert(mtbdd_to_mtpndd_cache_t *cache, mtpndd_bdd_t key, mtpndd_t *value);

static mtpndd_error_t mtbdd_to_mtpndd_cache_rehash(mtbdd_to_mtpndd_cache_t *cache) {
    size_t new_capacity = cache->capacity << 1;
    mtpndd_bdd_t *new_keys = (mtpndd_bdd_t *)calloc(new_capacity, sizeof(mtpndd_bdd_t));
    mtpndd_t **new_values = (mtpndd_t **)calloc(new_capacity, sizeof(mtpndd_t *));
    if (!new_keys || !new_values) {
        free(new_keys);
        free(new_values);
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    size_t old_capacity = cache->capacity;
    mtpndd_bdd_t *old_keys = cache->keys;
    mtpndd_t **old_values = cache->values;
    cache->keys = new_keys;
    cache->values = new_values;
    cache->capacity = new_capacity;
    cache->count = 0;
    for (size_t i = 0; i < old_capacity; ++i) {
        if (old_keys[i]) {
            mtbdd_to_mtpndd_cache_insert(cache, old_keys[i], old_values[i]);
        }
    }
    free(old_keys);
    free(old_values);
    return MTPNDD_SUCCESS;
}

static mtpndd_error_t mtbdd_to_mtpndd_cache_insert(mtbdd_to_mtpndd_cache_t *cache, mtpndd_bdd_t key, mtpndd_t *value) {
    if ((cache->count + 1) * 4 >= cache->capacity * 3) {
        mtpndd_error_t err = mtbdd_to_mtpndd_cache_rehash(cache);
        if (err != MTPNDD_SUCCESS) {
            return err;
        }
    }
    size_t mask = cache->capacity - 1;
    size_t idx = mtpndd_hash_u64(key) & mask;
    while (cache->keys[idx]) {
        idx = (idx + 1) & mask;
    }
    cache->keys[idx] = key;
    cache->values[idx] = value;
    cache->count++;
    return MTPNDD_SUCCESS;
}

static uint32_t mtpndd_field_expanded_start(uint32_t field_id) {
    uint32_t start = 0;
    for (uint32_t i = 1; i < field_id; ++i) {
        start += g_mtpndd_config.pending_field_bit_widths[i - 1];
    }
    return start;
}

static mtpndd_field_info_t *mtpndd_find_field_by_expanded_var(uint32_t var) {
    uint32_t start = 0;
    for (uint32_t i = 1; i <= g_mtpndd_config.field_count; ++i) {
        uint32_t width = g_mtpndd_config.pending_field_bit_widths[i - 1];
        uint32_t end = start + width;
        if (var >= start && var < end) {
            return g_mtpndd_config.field_info[i];
        }
        start = end;
    }
    return NULL;
}

static mtpndd_bdd_t mtpndd_replace_shared_to_expanded(mtpndd_bdd_t bdd, uint32_t field_id) {
    if (bdd == sylvan_false || bdd == sylvan_true) {
        return sylvan_ref(bdd);
    }

    uint32_t bit_width = g_mtpndd_config.pending_field_bit_widths[field_id - 1];
    uint32_t shared_offset = g_mtpndd_config.max_bit_width - bit_width;
    uint32_t expanded_offset = mtpndd_field_expanded_start(field_id);

    if (expanded_offset < g_mtpndd_config.max_bit_width) {
        return sylvan_ref(bdd);
    }

    BDDMAP map = sylvan_map_empty();
    for (uint32_t i = 0; i < bit_width; ++i) {
        uint32_t key = shared_offset + i;
        uint32_t expanded_var = expanded_offset + i;
        map = sylvan_map_add(map, key, sylvan_ithvar(expanded_var));
    }

    BDDMAP map_ref = sylvan_ref(map);
    mtpndd_bdd_t replaced = sylvan_ref(sylvan_compose(bdd, map_ref));
    sylvan_deref(map_ref);
    return replaced;
}

static mtpndd_error_t mtpndd_to_mtbdd_rec(mtpndd_t *node, mtpndd_to_mtbdd_cache_t *cache, mtpndd_bdd_t *result) {
    if (mtpndd_is_true(node)) {
        *result = sylvan_true;
        return MTPNDD_SUCCESS;
    }
    if (mtpndd_is_false(node)) {
        *result = sylvan_false;
        return MTPNDD_SUCCESS;
    }

    mtpndd_bdd_t cached;
    if (mtpndd_to_mtbdd_cache_lookup(cache, node, &cached)) {
        *result = cached;
        return MTPNDD_SUCCESS;
    }

    mtpndd_bdd_t acc = sylvan_ref(sylvan_false);
    mtpndd_error_t status = MTPNDD_SUCCESS;
    size_t bucket_cnt = (node->edges && node->edges->buckets) ? (node->edges->bucket_count ? node->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count) : 0;
    for (size_t bucket = 0; bucket < bucket_cnt; ++bucket) {
        edge_bucket_entry_t *head = node->edges->buckets[bucket];
        if (!head) {
            continue;
        }
        edge_bucket_entry_t *entry = head;
        while (entry) {
            if (entry->child) {
                mtpndd_bdd_t label = mtpndd_edge_label_load(entry);
                if (label != sylvan_false) {
                    mtpndd_bdd_t child_bdd = sylvan_false;
                    status = mtpndd_to_mtbdd_rec(entry->child, cache, &child_bdd);
                    if (status != MTPNDD_SUCCESS) {
                        break;
                    }
                    mtpndd_bdd_t mapped_label = mtpndd_replace_shared_to_expanded(label, node->field_id);
                    mtpndd_bdd_t conjunct = sylvan_ref(sylvan_and(mapped_label, child_bdd));
                    sylvan_deref(mapped_label);
                    if (conjunct != sylvan_false) {
                        mtpndd_bdd_t combined = sylvan_ref(sylvan_or(acc, conjunct));
                        sylvan_deref(acc);
                        sylvan_deref(conjunct);
                        acc = combined;
                    } else {
                        sylvan_deref(conjunct);
                    }
                }
            }
            entry = entry->next;
        }
        if (status != MTPNDD_SUCCESS) {
            break;
        }
    }

    if (status != MTPNDD_SUCCESS) {
        sylvan_deref(acc);
        return status;
    }

    mtpndd_error_t err = mtpndd_to_mtbdd_cache_insert(cache, node, acc);
    if (err != MTPNDD_SUCCESS) {
        sylvan_deref(acc);
        return err;
    }

    *result = acc;
    return MTPNDD_SUCCESS;
}

mtpndd_error_t mtpndd_to_mtbdd(mtpndd_t *node, mtpndd_bdd_t *result) {
    MTPNDD_CHECK_INIT();
    MTPNDD_CHECK_NULL(node, MTPNDD_ERROR_NULL_POINTER);
    MTPNDD_CHECK_NULL(result, MTPNDD_ERROR_NULL_POINTER);

    mtpndd_to_mtbdd_cache_t cache;
    mtpndd_error_t status = mtpndd_to_mtbdd_cache_init(&cache, 64);
    if (status != MTPNDD_SUCCESS) {
        return status;
    }

    mtpndd_bdd_t tmp = sylvan_false;
    status = mtpndd_to_mtbdd_rec(node, &cache, &tmp);
    if (status != MTPNDD_SUCCESS) {
        mtpndd_to_mtbdd_cache_destroy(&cache);
        return status;
    }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    if (status == MTPNDD_SUCCESS) {
        uint64_t nodecount = sylvan_nodecount(tmp);
        __atomic_store_n(&g_mtpndd_stats.bdd_nodes_converted, nodecount, __ATOMIC_RELAXED);
        MTPNDD_STAT_ADD(bdd_nodes_processed_total, nodecount);
    }
#endif

    mtpndd_bdd_t final = sylvan_ref(tmp);
    mtpndd_to_mtbdd_cache_destroy(&cache);
    sylvan_deref(tmp);
    *result = final;
    return MTPNDD_SUCCESS;
}

static mtpndd_error_t mtbdd_to_mtpndd_rec(mtpndd_bdd_t bdd, mtbdd_to_mtpndd_cache_t *cache, mtpndd_t **result);

static mtpndd_error_t mtbdd_to_mtpndd_collect(
        mtpndd_bdd_t current,
        const mtpndd_field_info_t *field,
        uint32_t field_start,
        uint32_t offset,
        mtpndd_bdd_t cube,
        mtbdd_to_mtpndd_cache_t *cache,
        mtpndd_edge_t *edges)
{
    if (current == sylvan_false) {
        return MTPNDD_SUCCESS;
    }

    uint32_t field_end = field_start + field->bit_width;
    if (offset >= field->bit_width || mtbdd_isleaf(current) ||
        (!mtbdd_isleaf(current) && sylvan_var(current) >= field_end)) {
        mtpndd_t *child = NULL;
        mtpndd_error_t status = mtbdd_to_mtpndd_rec(current, cache, &child);
        if (status != MTPNDD_SUCCESS) {
            return status;
        }
        mtpndd_bdd_t label = sylvan_ref(cube);
        status = mtpndd_add_edge(edges, child, label);
        if (status != MTPNDD_SUCCESS) {
            sylvan_deref(label);
        }
        return status;
    }

    uint32_t var = field_start + offset;
    mtpndd_bdd_t literal_pos = field->bdd_vars[offset];
    mtpndd_bdd_t literal_neg = field->bdd_not_vars[offset];

    mtpndd_bdd_t low_child = current;
    mtpndd_bdd_t high_child = current;
    if (!mtbdd_isleaf(current)) {
        uint32_t top_var = sylvan_var(current);
        if (top_var == var) {
            low_child = sylvan_low(current);
            high_child = sylvan_high(current);
        }
    }

    mtpndd_error_t status;
    mtpndd_bdd_t cube_low = sylvan_ref(sylvan_and(cube, literal_neg));
    status = mtbdd_to_mtpndd_collect(low_child, field, field_start, offset + 1, cube_low, cache, edges);
    sylvan_deref(cube_low);
    if (status != MTPNDD_SUCCESS) {
        return status;
    }

    mtpndd_bdd_t cube_high = sylvan_ref(sylvan_and(cube, literal_pos));
    status = mtbdd_to_mtpndd_collect(high_child, field, field_start, offset + 1, cube_high, cache, edges);
    sylvan_deref(cube_high);
    return status;
}

static mtpndd_error_t mtbdd_to_mtpndd_rec(mtpndd_bdd_t bdd, mtbdd_to_mtpndd_cache_t *cache, mtpndd_t **result) {
    if (bdd == sylvan_false) {
        *result = &MTPNDD_FALSE;
        return MTPNDD_SUCCESS;
    }
    if (bdd == sylvan_true) {
        *result = &MTPNDD_TRUE;
        return MTPNDD_SUCCESS;
    }

    mtpndd_t *cached;
    if (mtbdd_to_mtpndd_cache_lookup(cache, bdd, &cached)) {
        *result = cached;
        return MTPNDD_SUCCESS;
    }

    uint32_t var = sylvan_var(bdd);
    mtpndd_field_info_t *field = mtpndd_find_field_by_expanded_var(var);
    if (!field) {
        mtpndd_set_error(MTPNDD_ERROR_INVALID_FIELD, __func__, __LINE__);
        return MTPNDD_ERROR_INVALID_FIELD;
    }
    uint32_t field_start = mtpndd_field_expanded_start(field->field_id);

    mtpndd_edge_t *edges = mtpndd_memory_acquire_edge_map();
    if (!edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    mtpndd_edge_map_init(edges);

    mtpndd_error_t status = mtbdd_to_mtpndd_collect(bdd, field, field_start, 0, sylvan_true, cache, edges);
    if (status != MTPNDD_SUCCESS) {
        mtpndd_edge_map_free(edges);
        return status;
    }

    mtpndd_t *node = NULL;
    mtpndd_mk(field->field_id, edges, &node);
    if (!node) {
        mtpndd_edge_map_free(edges);
        return mtpndd_get_last_error().code;
    }

    mtbdd_to_mtpndd_cache_insert(cache, bdd, node);
    *result = node;
    return MTPNDD_SUCCESS;
}

mtpndd_error_t mtbdd_to_mtpndd(mtpndd_bdd_t bdd, mtpndd_t **result) {
    MTPNDD_CHECK_INIT();
    MTPNDD_CHECK_NULL(result, MTPNDD_ERROR_NULL_POINTER);

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    uint64_t bdd_nodecount = sylvan_nodecount(bdd);
    __atomic_store_n(&g_mtpndd_stats.bdd_nodes_converted, bdd_nodecount, __ATOMIC_RELAXED);
    MTPNDD_STAT_ADD(bdd_nodes_processed_total, bdd_nodecount);
#endif

    mtbdd_to_mtpndd_cache_t cache;
    mtpndd_error_t status = mtbdd_to_mtpndd_cache_init(&cache, 64);
    if (status != MTPNDD_SUCCESS) {
        return status;
    }

    mtpndd_t *tmp = NULL;
    status = mtbdd_to_mtpndd_rec(bdd, &cache, &tmp);
    mtbdd_to_mtpndd_cache_destroy(&cache);
    if (status != MTPNDD_SUCCESS) {
        return status;
    }

    *result = tmp;
    return MTPNDD_SUCCESS;
}

static size_t mtpndd_total_bdd_vars(void) {
    size_t total = 0;
    for (uint32_t i = 1; i <= g_mtpndd_config.field_count; ++i) {
        total += g_mtpndd_config.pending_field_bit_widths[i - 1];
    }
    return total;
}

static double mtpndd_satcount_rec(mtpndd_t *node, uint32_t field) {
    if (mtpndd_is_false(node)) {
        return 0.0;
    }
    // Any non-zero terminal contributes the full measure of the remaining
    // fields.  Zero leaves (whether fraction 0/1 or double 0.0) contribute
    // nothing.
    if (mtpndd_is_terminal(node)) {
        bool is_zero = false;
        if (node->field_id == MTPNDD_FRACTION_LEAF_FIELD_ID) {
            int32_t numer = (int32_t)(uint32_t)(node->leaf_value >> 32);
            is_zero = (numer == 0);
        } else if (node->field_id == MTPNDD_DOUBLE_LEAF_FIELD_ID) {
            double v;
            memcpy(&v, &node->leaf_value, sizeof(v));
            is_zero = (v == 0.0);
        }
        if (is_zero) {
            return 0.0;
        }
        if (field > g_mtpndd_config.field_count) {
            return 1.0;
        }
        double result = 1.0;
        for (uint32_t f = field; f <= g_mtpndd_config.field_count; ++f) {
            uint32_t bits = g_mtpndd_config.pending_field_bit_widths[f - 1];
            result *= pow(2.0, (double)bits);
        }
        return result;
    }

    if (field == node->field_id) {
        uint32_t field_bits = g_mtpndd_config.pending_field_bit_widths[field - 1];
        double divisor = pow(2.0, (double)(g_mtpndd_config.max_bit_width - field_bits));
        double result = 0.0;
        size_t bucket_cnt = (node->edges && node->edges->buckets)
                ? (node->edges->bucket_count ? node->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count)
                : 0;
        for (size_t bucket = 0; bucket < bucket_cnt; ++bucket) {
            edge_bucket_entry_t *entry = node->edges->buckets[bucket];
            while (entry) {
                if (entry->child) {
                    mtpndd_bdd_t label = mtpndd_edge_label_load(entry);
                    if (label != sylvan_false) {
                        double bdd_sat = (label == sylvan_true)
                                ? pow(2.0, (double)g_mtpndd_config.max_bit_width)
                                : mtbdd_satcount(label, g_mtpndd_config.max_bit_width);
                        double ndd_sat = mtpndd_satcount_rec(entry->child, field + 1);
                        result += (bdd_sat / divisor) * ndd_sat;
                    }
                }
                entry = entry->next;
            }
        }
        return result;
    }

    uint32_t bits = g_mtpndd_config.pending_field_bit_widths[field - 1];
    return pow(2.0, (double)bits) * mtpndd_satcount_rec(node, field + 1);
}

double mtpndd_satcount_ndd(mtpndd_t *node) {
    MTPNDD_CHECK_INIT();
    MTPNDD_CHECK_NULL(node, MTPNDD_ERROR_NULL_POINTER);
    return mtpndd_satcount_rec(node, 1);
}

double mtpndd_satcount_mtbdd(mtpndd_t *node) {
    MTPNDD_CHECK_INIT();
    MTPNDD_CHECK_NULL(node, MTPNDD_ERROR_NULL_POINTER);

    mtpndd_bdd_t bdd = sylvan_false;
    mtpndd_error_t status = mtpndd_to_mtbdd(node, &bdd);
    if (status != MTPNDD_SUCCESS) {
        return status;
    }

    size_t nvars = mtpndd_total_bdd_vars();
    double count = mtbdd_satcount(bdd, nvars);
    sylvan_deref(bdd);

    return count;
}

double mtpndd_satcount(mtpndd_t *node) {
    return mtpndd_satcount_ndd(node);
}

/********************************
 * DOT visualization utilities
 ********************************/
typedef struct mtpndd_dot_visit_s {
    const mtpndd_node_t *node;
    size_t id;
    struct mtpndd_dot_visit_s *next;
} mtpndd_dot_visit_t;

typedef struct {
    mtpndd_dot_visit_t *visited;
    size_t next_id;
    const mtpndd_t *root;
    bool error;
} mtpndd_dot_ctx_t;

static void mtpndd_dot_ctx_cleanup(mtpndd_dot_ctx_t *ctx) {
    if (!ctx) {
        return;
    }
    mtpndd_dot_visit_t *entry = ctx->visited;
    while (entry) {
        mtpndd_dot_visit_t *next = entry->next;
        free(entry);
        entry = next;
    }
    ctx->visited = NULL;
    ctx->next_id = 0;
    ctx->error = false;
}

static size_t mtpndd_dot_ctx_get_id(mtpndd_dot_ctx_t *ctx, const mtpndd_node_t *node, bool *is_new) {
    if (!ctx || !node) {
        if (is_new) {
            *is_new = false;
        }
        return 0;
    }

    for (mtpndd_dot_visit_t *entry = ctx->visited; entry; entry = entry->next) {
        if (entry->node == node) {
            if (is_new) {
                *is_new = false;
            }
            return entry->id;
        }
    }

    mtpndd_dot_visit_t *entry = (mtpndd_dot_visit_t *)malloc(sizeof(mtpndd_dot_visit_t));
    if (!entry) {
        ctx->error = true;
        if (is_new) {
            *is_new = false;
        }
        return 0;
    }

    entry->node = node;
    entry->id = ctx->next_id++;
    entry->next = ctx->visited;
    ctx->visited = entry;

    if (is_new) {
        *is_new = true;
    }
    return entry->id;
}

static void mtpndd_dot_format_edge_label(mtpndd_bdd_t label, char *buffer, size_t buffer_size) {
    if (!buffer || buffer_size == 0) {
        return;
    }
    if (label == sylvan_true) {
        (void)snprintf(buffer, buffer_size, "TRUE");
    } else if (label == sylvan_false) {
        (void)snprintf(buffer, buffer_size, "FALSE");
    } else {
        (void)snprintf(buffer, buffer_size, "0x%" PRIx64, (uint64_t)label);
    }
}

static void mtpndd_dot_emit_node(FILE *out, const mtpndd_t *node, size_t node_id, bool highlight_root) {
    if (!out || !node) {
        return;
    }

    if (mtpndd_is_true((mtpndd_t *)node)) {
        (void)fprintf(out,
                "    n%zu [label=\"TRUE\", shape=box, style=\"filled\", fillcolor=\"#dff0d8\", peripheries=%d];\n",
                node_id,
                highlight_root ? 2 : 1);
        return;
    }

    if (mtpndd_is_false((mtpndd_t *)node)) {
        (void)fprintf(out,
                "    n%zu [label=\"FALSE\", shape=box, style=\"filled\", fillcolor=\"#f2dede\", peripheries=%d];\n",
                node_id,
                highlight_root ? 2 : 1);
        return;
    }

    const mtpndd_field_info_t *field = (node->field_id > 0 && node->field_id <= g_mtpndd_config.field_count)
                                           ? g_mtpndd_config.field_info[node->field_id]
                                           : NULL;
    size_t edge_count = (node->edges != NULL) ? node->edges->edge_count : 0;
    uint32_t field_id = node->field_id;
    uint32_t bit_width = field ? field->bit_width : 0;

    (void)fprintf(out,
            "    n%zu [label=\"Field %u\\nBits %u\\nEdges %zu\", shape=ellipse, peripheries=%d];\n",
            node_id,
            field_id,
            bit_width,
            edge_count,
            highlight_root ? 2 : 1);
}

static void mtpndd_fprint_dot_rec(FILE *out, mtpndd_t *node, mtpndd_dot_ctx_t *ctx) {
    if (!out || !node || !ctx || ctx->error) {
        return;
    }

    bool is_new = false;
    size_t node_id = mtpndd_dot_ctx_get_id(ctx, node, &is_new);
    if (ctx->error || !is_new) {
        return;
    }

    mtpndd_dot_emit_node(out, node, node_id, node == ctx->root);

    if (!node->edges || node->edges->edge_count == 0) {
        return;
    }

    edge_bucket_entry_t *entry = NULL;
    FOR_EACH_ENTRY_IN_ALL_BUCKETS(node->edges, entry) {
        if (!entry || !entry->child) {
            continue;
        }
        mtpndd_t *child = entry->child;
        bool child_is_new = false;
        size_t child_id = mtpndd_dot_ctx_get_id(ctx, child, &child_is_new);
        if (ctx->error) {
            return;
        }

        char label_buffer[32] = {0};
        mtpndd_bdd_t label = atomic_load_explicit(&entry->label, memory_order_relaxed);
        mtpndd_dot_format_edge_label(label, label_buffer, sizeof(label_buffer));

        (void)fprintf(out,
                "    n%zu -> n%zu [label=\"%s\"];\n",
                node_id,
                child_id,
                label_buffer);

        if (child_is_new) {
            mtpndd_fprint_dot_rec(out, child, ctx);
        }
    }
}

void mtpndd_fprint_dot(FILE *out, mtpndd_t *root) {
    if (!root) {
        return;
    }
    if (!out) {
        out = stdout;
    }

    mtpndd_dot_ctx_t ctx = {
        .visited = NULL,
        .next_id = 0,
        .root = root,
        .error = false
    };

    (void)fprintf(out, "digraph MTPNDD {\n");
    (void)fprintf(out, "    rankdir=TB;\n");
    (void)fprintf(out, "    node [fontname=\"Helvetica\"];\n");
    (void)fprintf(out, "    edge [fontname=\"Helvetica\"];\n");

    mtpndd_fprint_dot_rec(out, root, &ctx);

    (void)fprintf(out, "}\n");

    mtpndd_dot_ctx_cleanup(&ctx);
}

void mtpndd_print_dot(mtpndd_t *root, const char *path) {
    FILE *out = stdout;
    FILE *file = NULL;

    if (path && path[0] != '\0') {
        file = fopen(path, "w");
        if (file) {
            out = file;
        } else {
            perror("fopen dot file");
        }
    }

    mtpndd_fprint_dot(out, root);

    if (file) {
        fclose(file);
    }
}

mtpndd_error_t mtpndd_temp_refs_runtime_init(void) {
    if (g_mtpndd_temp_ref_pools) {
        return MTPNDD_SUCCESS;
    }

    size_t worker_count = lace_workers();
    if (worker_count == 0) {
        worker_count = 1;
    }

    mtpndd_temp_ref_pool_t *pools =
            (mtpndd_temp_ref_pool_t *)calloc(worker_count, sizeof(mtpndd_temp_ref_pool_t));
    if (!pools) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }

    for (size_t i = 0; i < worker_count; ++i) {
        mtpndd_temp_ref_pool_init(&pools[i]);
    }

    g_mtpndd_temp_ref_pools = pools;
    g_mtpndd_temp_ref_pool_count = worker_count;
    return MTPNDD_SUCCESS;
}

void mtpndd_temp_refs_runtime_shutdown(void) {
    if (g_mtpndd_temp_ref_pools) {
        for (size_t i = 0; i < g_mtpndd_temp_ref_pool_count; ++i) {
            mtpndd_temp_ref_pool_t *pool = &g_mtpndd_temp_ref_pools[i];
            while (pool->count > 0) {
                mtpndd_deref(pool->items[--pool->count]);
            }
            if (pool->items && pool->items != pool->inline_items) {
                free(pool->items);
            }
            pool->items = NULL;
            pool->capacity = 0;
        }
        free(g_mtpndd_temp_ref_pools);
        g_mtpndd_temp_ref_pools = NULL;
        g_mtpndd_temp_ref_pool_count = 0;
    }

    if (g_mtpndd_temp_ref_tls_pool_initialized) {
        while (g_mtpndd_temp_ref_tls_pool.count > 0) {
            mtpndd_deref(g_mtpndd_temp_ref_tls_pool.items[--g_mtpndd_temp_ref_tls_pool.count]);
        }
        if (g_mtpndd_temp_ref_tls_pool.items &&
                g_mtpndd_temp_ref_tls_pool.items != g_mtpndd_temp_ref_tls_pool.inline_items) {
            free(g_mtpndd_temp_ref_tls_pool.items);
        }
        g_mtpndd_temp_ref_tls_pool.items = NULL;
        g_mtpndd_temp_ref_tls_pool.capacity = 0;
        g_mtpndd_temp_ref_tls_pool_initialized = false;
    }
}

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
uint64_t mtpndd_temp_refs_grow_total(void) {
    return atomic_load_explicit(&g_mtpndd_temp_refs_grow_total, memory_order_relaxed);
}

uint64_t mtpndd_temp_refs_peak_capacity(void) {
    return atomic_load_explicit(&g_mtpndd_temp_refs_peak_capacity, memory_order_relaxed);
}
#endif
