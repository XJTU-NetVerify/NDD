// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include "mtpndd_nodetable.h"
#include "mtpndd_node.h"
#include "mtpndd_memory_pool.h"
#include "mtpndd_operation_cache.h"
#include "mtpndd_leaf_table.h"
#include "sylvan.h"
#include "sylvan_common.h"
#include "lace.h"
#include <stdio.h>
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
#include <time.h>
#endif

static void gc_internal(void);
static void grow_internal(void);
static bool mtpndd_nodetable_rehash(mtpndd_nodetable_t *table, size_t new_bucket_count);
static void mtpndd_nodetable_maybe_rehash(mtpndd_nodetable_t *table);

void mtpndd_gc_run_prehooks(void);
void mtpndd_gc_run_posthooks(void);

static void gcOrGrow(void);
static size_t mtpndd_gc_sweep(void);
static void mtpndd_release_node(mtpndd_nodetable_t *table, size_t bucket_idx, mtpndd_nodetable_bucket_entry_t *entry, mtpndd_node_t *node);
static void mtpndd_gc_lock_all_tables(void);
static void mtpndd_gc_unlock_all_tables(void);

static _Atomic bool g_mtpndd_gc_running = false;

static inline size_t mtpndd_nodetable_lock_index(const mtpndd_nodetable_t *table, uint64_t cached_hash) {
    if (!table || !table->bucket_locks || table->bucket_lock_count == 0) return 0;
    return (size_t)(cached_hash & (table->bucket_lock_count - 1));
}

static inline void mtpndd_nodetable_lock_hash(mtpndd_nodetable_t *table, uint64_t cached_hash) {
    if (!table || !table->bucket_locks || table->bucket_lock_count == 0) return;
    pthread_spin_lock(&table->bucket_locks[mtpndd_nodetable_lock_index(table, cached_hash)]);
}

static inline void mtpndd_nodetable_unlock_hash(mtpndd_nodetable_t *table, uint64_t cached_hash) {
    if (!table || !table->bucket_locks || table->bucket_lock_count == 0) return;
    pthread_spin_unlock(&table->bucket_locks[mtpndd_nodetable_lock_index(table, cached_hash)]);
}

static void mtpndd_nodetable_lock_all(mtpndd_nodetable_t *table) {
    if (!table || !table->bucket_locks || table->bucket_lock_count == 0) return;
    for (size_t i = 0; i < table->bucket_lock_count; ++i) {
        pthread_spin_lock(&table->bucket_locks[i]);
    }
}

static void mtpndd_nodetable_unlock_all(mtpndd_nodetable_t *table) {
    if (!table || !table->bucket_locks || table->bucket_lock_count == 0) return;
    for (size_t i = 0; i < table->bucket_lock_count; ++i) {
        pthread_spin_unlock(&table->bucket_locks[i]);
    }
}

void mtpndd_gc_before_sylvan(void) {
    bool expected = false;
    if (!atomic_compare_exchange_strong(&g_mtpndd_gc_running, &expected, true)) {
        // avoid gc loop
        return;
    }

    gc_internal();

    mtpndd_op_cache_clear(g_mtpndd_config.and_cache);
    mtpndd_op_cache_clear(g_mtpndd_config.or_cache);
    mtpndd_op_cache_clear(g_mtpndd_config.not_cache);

    atomic_store(&g_mtpndd_gc_running, false);
}

/**
 * Sylvan GC mark callback: walk all live MTPNDD nodes and mark their
 * BDD edge labels so that Sylvan's clear-and-mark phase preserves them.
 *
 * Called from sylvan_clear_and_mark via the mark_list (registered with
 * sylvan_gc_add_mark).  Runs inside NEWFRAME on a Lace worker, so
 * CALL/SPAWN/SYNC are available through __lace_worker/__lace_dq_head.
 */
void mtpndd_gc_mark_bdd_labels(WorkerP *__lace_worker, Task *__lace_dq_head) {
    (void)__lace_dq_head;

    for (uint32_t field = 1; field <= g_mtpndd_config.field_count; ++field) {
        mtpndd_nodetable_t *nodetable = g_mtpndd_config.node_tables_by_field[field];
        if (!nodetable) continue;

        size_t bucket_count = nodetable->nodetable_bucket_count;
        for (size_t i = 0; i < bucket_count; ++i) {
            mtpndd_nodetable_bucket_entry_t *entry = nodetable->buckets[i];
            while (entry) {
                mtpndd_node_t *node = entry->node;
                if (!node) { entry = entry->next; continue; }

                mtpndd_edge_t *edges = node->edges;
                if (edges && edges->buckets) {
                    size_t edge_bucket_count = edges->bucket_count
                        ? edges->bucket_count
                        : g_mtpndd_pal_config.edge_bucket_count;
                    for (size_t eb = 0; eb < edge_bucket_count; ++eb) {
                        edge_bucket_entry_t *edge_entry = edges->buckets[eb];
                        while (edge_entry) {
                            mtpndd_bdd_t label = atomic_load_explicit(
                                &edge_entry->label, memory_order_relaxed);
                            if (label != sylvan_false && label != sylvan_true) {
                                CALL(mtbdd_gc_mark_rec, label);
                            }
                            edge_entry = edge_entry->next;
                        }
                    }
                }
                entry = entry->next;
            }
        }
    }
}


mtpndd_nodetable_t *mtpndd_nodetable_declare_field() {
    mtpndd_nodetable_t *table = (mtpndd_nodetable_t *)malloc(sizeof(mtpndd_nodetable_t));
    if (!table) return NULL;
    memset(table, 0, sizeof(mtpndd_nodetable_t));
    size_t bucket_cnt = g_mtpndd_pal_config.nodetable_bucket_count;
    if (bucket_cnt == 0) {
        bucket_cnt = MTPNDD_DEFAULT_NODETABLE_BUCKET_COUNT;
    }
    // Cap initial bucket count to limit virtual address space allocation.
    // Physical memory is only consumed for non-NULL (written) bucket slots.
    if (bucket_cnt > (1 << 23)) bucket_cnt = (1 << 23);
    if (bucket_cnt < 16) bucket_cnt = 16;
    bucket_cnt = mtpndd_round_up_pow2(bucket_cnt);
    table->nodetable_bucket_count = bucket_cnt;

    // Shard bucket access: cap lock count to keep memory small but contention low.
    size_t lock_cnt = bucket_cnt;
    const size_t lock_cap = (size_t)1 << 12; // 4096
    if (lock_cnt > lock_cap) lock_cnt = lock_cap;
    if (lock_cnt < 16) lock_cnt = 16;
    lock_cnt = mtpndd_round_up_pow2(lock_cnt);
    table->bucket_lock_count = lock_cnt;
    table->bucket_locks = (pthread_spinlock_t *)calloc(lock_cnt, sizeof(*table->bucket_locks));
    if (!table->bucket_locks) {
        free(table);
        return NULL;
    }
    for (size_t i = 0; i < lock_cnt; ++i) {
        pthread_spin_init(&table->bucket_locks[i], PTHREAD_PROCESS_PRIVATE);
    }
    pthread_mutex_init(&table->rehash_mutex, NULL);

    table->buckets =
            (mtpndd_nodetable_bucket_entry_t **)malloc(
                bucket_cnt * sizeof(mtpndd_nodetable_bucket_entry_t *));
    if (!table->buckets) {
        for (size_t i = 0; i < lock_cnt; ++i) pthread_spin_destroy(&table->bucket_locks[i]);
        free((void *)table->bucket_locks);
        pthread_mutex_destroy(&table->rehash_mutex);
        free(table);
        return NULL;
    }
    for (size_t i = 0; i < bucket_cnt; i++) {
        table->buckets[i] = NULL;
    }
    atomic_init(&table->entry_count, 0);
    table->load_threshold = bucket_cnt - (bucket_cnt >> 2);

    return table;
}

void mtpndd_nodetable_free(mtpndd_nodetable_t *table) {
    if (!table) return;
    if (table->buckets) {
        free(table->buckets);
        table->buckets = NULL;
    }
    if (table->bucket_locks) {
        for (size_t i = 0; i < table->bucket_lock_count; ++i) {
            pthread_spin_destroy(&table->bucket_locks[i]);
        }
        free((void *)table->bucket_locks);
        table->bucket_locks = NULL;
        table->bucket_lock_count = 0;
    }
    pthread_mutex_destroy(&table->rehash_mutex);
    free(table);
}

mtpndd_node_t *find_node_in_nodetable(mtpndd_nodetable_t *nodetable, mtpndd_edge_t *edges) {
    size_t hash = 0;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    struct timespec phase_start = {0};
    struct timespec phase_end = {0};
    clock_gettime(CLOCK_MONOTONIC, &phase_start);
#endif
    uint64_t cached_hash = edges ? edges->cached_hash : 0;
    mtpndd_nodetable_lock_hash(nodetable, cached_hash);
    hash = NODETABLE_HASH_VAL(edges, nodetable);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    clock_gettime(CLOCK_MONOTONIC, &phase_end);
    MTPNDD_STAT_ADD(nodetable_hash_ns, mtpndd_timespec_diff_ns(&phase_start, &phase_end));
    clock_gettime(CLOCK_MONOTONIC, &phase_start);
#endif
    mtpndd_node_t *found = NULL;

    for (mtpndd_nodetable_bucket_entry_t *entry = nodetable->buckets[hash];
         entry; entry = entry->next) {
        /* Fast reject without dereferencing entry->edges (cold cacheline
         * for unrelated entries). The in-entry cached_hash mirrors
         * entry->edges->cached_hash, set on insert. */
        if (entry->cached_hash != cached_hash) continue;
        mtpndd_edge_t *entry_edges = entry->edges;
        if (entry_edges == edges) {
            found = entry->node;
            break;
        }
        if (!entry_edges) {
            continue;
        }
        if (nodetable_edges_equal(entry_edges, edges)) {
            found = entry->node;
            break;
        }
    }
    mtpndd_nodetable_unlock_hash(nodetable, cached_hash);

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    clock_gettime(CLOCK_MONOTONIC, &phase_end);
    MTPNDD_STAT_ADD(nodetable_bucket_scan_ns, mtpndd_timespec_diff_ns(&phase_start, &phase_end));
    if (found) {
        MTPNDD_STAT_ADD(nodetable_lookup_hits, 1);
    } else {
        MTPNDD_STAT_ADD(nodetable_lookup_misses, 1);
    }
#endif
    return found;
}

/********************************
 * MTPNDD node
 ********************************/
mtpndd_error_t mtpndd_ref(mtpndd_t *node) {
    if (!node) {
        mtpndd_set_error(MTPNDD_ERROR_NULL_POINTER, __func__, __LINE__);
        return MTPNDD_ERROR_NULL_POINTER;
    }
    if (node->ref_count == UINT32_MAX) {
        // Terminal / protected nodes
        return MTPNDD_SUCCESS;
    }
    atomic_fetch_add(&node->ref_count, 1);
    return MTPNDD_SUCCESS;
}

mtpndd_error_t mtpndd_deref(mtpndd_t *node) {
    if (!node) {
        mtpndd_set_error(MTPNDD_ERROR_NULL_POINTER, __func__, __LINE__);
        return MTPNDD_ERROR_NULL_POINTER;
    }
    if (node->ref_count == UINT32_MAX) {
        // Terminal / protected nodes
        return MTPNDD_SUCCESS;
    }
    atomic_fetch_sub(&node->ref_count, 1);
    return MTPNDD_SUCCESS;
}

mtpndd_error_t mtpndd_protect(mtpndd_t *node) {
    if (!node) {
        mtpndd_set_error(MTPNDD_ERROR_NULL_POINTER, __func__, __LINE__);
        return MTPNDD_ERROR_NULL_POINTER;
    }
    atomic_init(&node->ref_count, UINT32_MAX);
    return MTPNDD_SUCCESS;
}

mtpndd_error_t mtpndd_unprotect(mtpndd_t *node) {
    if (!node) {
        mtpndd_set_error(MTPNDD_ERROR_NULL_POINTER, __func__, __LINE__);
        return MTPNDD_ERROR_NULL_POINTER;
    }
    atomic_init(&node->ref_count, 0);
    return MTPNDD_SUCCESS;
}

void mtpndd_mk(uint32_t field, mtpndd_edge_t *edges, mtpndd_node_t **result) {
    if (!result) {
        mtpndd_set_error(MTPNDD_ERROR_NULL_POINTER, __func__, __LINE__);
        return;
    }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    typedef enum {
        MTPNDD_MK_OTHER = 0,
        MTPNDD_MK_HASH,
        MTPNDD_MK_LOOKUP,
        MTPNDD_MK_FAST_RETURN,
        MTPNDD_MK_REUSE_CLEANUP,
        MTPNDD_MK_REF_CHILDREN,
        MTPNDD_MK_GC_OR_GROW,
        MTPNDD_MK_ALLOC_NODE,
        MTPNDD_MK_ALLOC_ENTRY,
        MTPNDD_MK_BUCKET_SCAN,
        MTPNDD_MK_LINK,
        MTPNDD_MK_COLLISION_CLEANUP,
        MTPNDD_MK_COUNT
    } mtpndd_mk_bucket_t;

    struct timespec mk_phase_start = {0};
    struct timespec mk_phase_end = {0};
    mtpndd_mk_bucket_t mk_bucket = MTPNDD_MK_OTHER;
    uint64_t mk_total_accum = 0;

    clock_gettime(CLOCK_MONOTONIC, &mk_phase_start);

    #define MTPNDD_MK_SWITCH(bucket) do { \
        clock_gettime(CLOCK_MONOTONIC, &mk_phase_end); \
        uint64_t delta = mtpndd_timespec_diff_ns(&mk_phase_start, &mk_phase_end); \
        mk_total_accum += delta; \
        switch (mk_bucket) { \
            case MTPNDD_MK_HASH: MTPNDD_STAT_ADD(mk_hash_ns, delta); break; \
            case MTPNDD_MK_LOOKUP: MTPNDD_STAT_ADD(mk_lookup_ns, delta); break; \
            case MTPNDD_MK_FAST_RETURN: MTPNDD_STAT_ADD(mk_fast_return_ns, delta); break; \
            case MTPNDD_MK_REUSE_CLEANUP: MTPNDD_STAT_ADD(mk_reuse_cleanup_ns, delta); break; \
            case MTPNDD_MK_REF_CHILDREN: MTPNDD_STAT_ADD(mk_ref_children_ns, delta); break; \
            case MTPNDD_MK_GC_OR_GROW: MTPNDD_STAT_ADD(mk_gc_or_grow_ns, delta); break; \
            case MTPNDD_MK_ALLOC_NODE: MTPNDD_STAT_ADD(mk_alloc_node_ns, delta); break; \
            case MTPNDD_MK_ALLOC_ENTRY: MTPNDD_STAT_ADD(mk_alloc_entry_ns, delta); break; \
            case MTPNDD_MK_BUCKET_SCAN: MTPNDD_STAT_ADD(mk_bucket_scan_ns, delta); break; \
            case MTPNDD_MK_LINK: MTPNDD_STAT_ADD(mk_link_ns, delta); break; \
            case MTPNDD_MK_COLLISION_CLEANUP: MTPNDD_STAT_ADD(mk_collision_cleanup_ns, delta); break; \
            default: MTPNDD_STAT_ADD(mk_other_ns, delta); break; \
        } \
        mk_bucket = (bucket); \
        mk_phase_start = mk_phase_end; \
    } while (0)

    #define MTPNDD_MK_FINISH() do { \
        MTPNDD_MK_SWITCH(MTPNDD_MK_OTHER); \
        MTPNDD_STAT_ADD(mk_total_ns, mk_total_accum); \
    } while (0)
#endif
    *result = NULL;
    if (edges->edge_count == 0) {
        *result = &MTPNDD_FALSE;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        MTPNDD_MK_SWITCH(MTPNDD_MK_FAST_RETURN);
        MTPNDD_MK_FINISH();
#endif
        return;
    } else if (edges->edge_count == 1) {
        edge_bucket_entry_t *only_entry = NULL;
    size_t bucket_cnt = (edges && edges->buckets) ? (edges->bucket_count ? edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count) : 0;
        for (size_t i = 0; i < bucket_cnt && !only_entry; ++i) {
            edge_bucket_entry_t *head = edges->buckets[i];
            if (head) {
                only_entry = head;
            }
        }
        if (only_entry && atomic_load_explicit(&only_entry->label, memory_order_acquire) == sylvan_true) {
            *result = only_entry->child;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
            MTPNDD_MK_SWITCH(MTPNDD_MK_FAST_RETURN);
            MTPNDD_MK_FINISH();
#endif
            return;
        }
    }

    // Compute and cache hash value for edges (like Java HashMap)
    // This must be done after all edges are added and before lookup
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_MK_SWITCH(MTPNDD_MK_HASH);
#endif
    edges->cached_hash = mtpndd_edge_map_compute_hash(edges);

    mtpndd_nodetable_t *nodetable = g_mtpndd_config.node_tables_by_field[field];
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_MK_SWITCH(MTPNDD_MK_LOOKUP);
#endif
    mtpndd_node_t *node = find_node_in_nodetable(nodetable, edges);
    edge_bucket_entry_t *entry = NULL;
    if (node) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        MTPNDD_MK_SWITCH(MTPNDD_MK_REUSE_CLEANUP);
#endif
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(edges, entry) {
            mtpndd_bdd_t label = atomic_load_explicit(&entry->label, memory_order_relaxed);
            sylvan_deref(label);
        }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        MTPNDD_STAT_ADD(nodes_reused_total, 1);
        MTPNDD_MK_FINISH();
#endif
        *result = node;
        return;
    }
    // Create new node
    // 1. add ref count of all children
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_MK_SWITCH(MTPNDD_MK_REF_CHILDREN);
#endif
    FOR_EACH_ENTRY_IN_ALL_BUCKETS(edges, entry) {
        if (!mtpndd_is_terminal(entry->child)) {
            mtpndd_ref(entry->child);
        }
    }
    // 2. check if there should be a gc or grow
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_MK_SWITCH(MTPNDD_MK_GC_OR_GROW);
#endif
    if (g_mtpndd_node_count >= g_mtpndd_pal_config.mtpndd_nodetable_size) {
        gcOrGrow();
    }
    // 3. create new node
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_MK_SWITCH(MTPNDD_MK_ALLOC_NODE);
#endif
    node = mtpndd_memory_acquire_node();
    if (!node) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        MTPNDD_MK_FINISH();
#endif
        return;
    }
    node->field_id = field;
    node->edges = edges;
    atomic_init(&node->ref_count, 0);
    // 4. insert into nodetable
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_MK_SWITCH(MTPNDD_MK_ALLOC_ENTRY);
#endif
    mtpndd_nodetable_bucket_entry_t *new_entry = mtpndd_memory_acquire_nodetable_entry();
    if (!new_entry) {
        mtpndd_memory_release_node(node);
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        MTPNDD_MK_FINISH();
#endif
        return;
    }
    new_entry->edges = edges;
    new_entry->node = node;
    new_entry->cached_hash = edges->cached_hash;
    mtpndd_nodetable_lock_hash(nodetable, edges->cached_hash);
    size_t hash = NODETABLE_HASH_VAL(edges, nodetable);
    mtpndd_nodetable_bucket_entry_t *existing_entry = nodetable->buckets[hash];
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    bool bucket_had_entries = existing_entry != NULL;
#endif
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_MK_SWITCH(MTPNDD_MK_BUCKET_SCAN);
#endif
    while (existing_entry) {
        if (existing_entry->cached_hash == edges->cached_hash
            && NODETABLE_BUCKET_ENTRY_EQUAL(existing_entry, edges)) {
            break;
        }
        existing_entry = existing_entry->next;
    }

    if (existing_entry) {
        mtpndd_node_t *existing_node = existing_entry->node;
        mtpndd_nodetable_unlock_hash(nodetable, edges->cached_hash);
        mtpndd_memory_release_nodetable_entry(new_entry);
        mtpndd_memory_release_node(node);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        MTPNDD_MK_SWITCH(MTPNDD_MK_COLLISION_CLEANUP);
#endif
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(edges, entry) {
            if (!mtpndd_is_terminal(entry->child)) {
                mtpndd_deref(entry->child);
            }
            mtpndd_bdd_t label = atomic_load_explicit(&entry->label, memory_order_relaxed);
            sylvan_deref(label);
        }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        if (bucket_had_entries) {
            MTPNDD_STAT_ADD(nodetable_collision_total, 1);
        }
        MTPNDD_MK_FINISH();
#endif
        *result = existing_node;
        return;
    }

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_MK_SWITCH(MTPNDD_MK_LINK);
#endif
    new_entry->next = nodetable->buckets[hash];
    new_entry->prev = NULL;
    if (nodetable->buckets[hash]) {
        nodetable->buckets[hash]->prev = new_entry;
    }
    nodetable->buckets[hash] = new_entry;
    atomic_fetch_add_explicit(&nodetable->entry_count, 1, memory_order_relaxed);
    mtpndd_nodetable_unlock_hash(nodetable, edges->cached_hash);
    mtpndd_nodetable_maybe_rehash(nodetable);
    __atomic_add_fetch(&g_mtpndd_node_count, 1, __ATOMIC_RELAXED);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(nodes_created_total, 1);
    if (bucket_had_entries) {
        MTPNDD_STAT_ADD(nodetable_collision_total, 1);
    }
    MTPNDD_MK_FINISH();
#endif
    *result = node;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    #undef MTPNDD_MK_SWITCH
    #undef MTPNDD_MK_FINISH
#endif
}

static void gcOrGrow(void) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    struct timespec gc_timer_start = {0};
    clock_gettime(CLOCK_MONOTONIC, &gc_timer_start);
    MTPNDD_LOG_DEBUG(
            "[MTPNDD DEBUG] gcOrGrow start node_count=%zu capacity=%zu threshold=%.2f\n",
            (size_t)g_mtpndd_node_count,
            (size_t)g_mtpndd_pal_config.mtpndd_nodetable_size,
            g_mtpndd_pal_config.quick_growth_threshold);
    mtpndd_log_memory_pools("pre-gc");
#endif

    // Ensure only one thread executes MTPNDD GC/grow at a time.
    bool expected = false;
    if (!atomic_compare_exchange_strong(&g_mtpndd_gc_running, &expected, true)) {
        return;
    }

    gc_internal();

    if (g_mtpndd_pal_config.mtpndd_nodetable_size - g_mtpndd_node_count
            < g_mtpndd_pal_config.quick_growth_threshold * g_mtpndd_pal_config.mtpndd_nodetable_size) {
        MTPNDD_LOG_DEBUG("[MTPNDD DEBUG] triggering grow (node_count=%zu capacity=%zu)\n",
                (size_t)g_mtpndd_node_count,
                (size_t)g_mtpndd_pal_config.mtpndd_nodetable_size);
        grow_internal();
    }

    // clear operation caches after mutating nodetables, before resuming user code
    mtpndd_op_cache_clear(g_mtpndd_config.and_cache);
    mtpndd_op_cache_clear(g_mtpndd_config.or_cache);
    mtpndd_op_cache_clear(g_mtpndd_config.not_cache);

    sylvan_gc();

    atomic_store(&g_mtpndd_gc_running, false);

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    mtpndd_log_memory_pools("post-gc");
    MTPNDD_LOG_DEBUG("[MTPNDD DEBUG] gcOrGrow end node_count=%zu capacity=%zu\n",
            (size_t)g_mtpndd_node_count,
            (size_t)g_mtpndd_pal_config.mtpndd_nodetable_size);

    struct timespec gc_timer_end = {0};
    clock_gettime(CLOCK_MONOTONIC, &gc_timer_end);
    MTPNDD_STAT_ADD(gc_pause_time_ns, mtpndd_timespec_diff_ns(&gc_timer_start, &gc_timer_end));
#endif
}

static void gc_internal(void) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    __atomic_add_fetch(&g_mtpndd_stats.gc_runs, 1, __ATOMIC_RELAXED);
#endif
    mtpndd_gc_run_prehooks();

    // Quiesce nodetable mutations by taking all table locks.
    // This avoids suspending workers while they may hold spin locks.
    mtpndd_gc_lock_all_tables();
    size_t reclaimed = mtpndd_gc_sweep();

    // Sweep the leaf tables while the nodetables are still locked so that
    // the set of live internal nodes (and thus the set of reachable leaves)
    // cannot change underneath us.  Leaves freed now are those that were
    // only reachable from internal nodes reclaimed above.
    size_t leaves_reclaimed = mtpndd_leaf_gc();

    mtpndd_gc_unlock_all_tables();

    if (reclaimed > 0) {
        __atomic_sub_fetch(&g_mtpndd_node_count, reclaimed, __ATOMIC_RELAXED);

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        MTPNDD_STAT_SET(nodes_collected_last, reclaimed);
#endif
    }
    (void)leaves_reclaimed;

    mtpndd_gc_run_posthooks();
}

static void mtpndd_gc_lock_all_tables(void) {
    for (uint32_t field = 1; field <= g_mtpndd_config.field_count; ++field) {
        mtpndd_nodetable_t *table = g_mtpndd_config.node_tables_by_field[field];
        if (!table) continue;
        // Follow the same lock order used by rehash paths: mutex -> bucket locks.
        pthread_mutex_lock(&table->rehash_mutex);
        mtpndd_nodetable_lock_all(table);
    }
}

static void mtpndd_gc_unlock_all_tables(void) {
    for (uint32_t field = g_mtpndd_config.field_count; field >= 1; --field) {
        mtpndd_nodetable_t *table = g_mtpndd_config.node_tables_by_field[field];
        if (!table) continue;
        mtpndd_nodetable_unlock_all(table);
        pthread_mutex_unlock(&table->rehash_mutex);
    }
}

static void mtpndd_release_node(mtpndd_nodetable_t *table, size_t bucket_idx, mtpndd_nodetable_bucket_entry_t *entry, mtpndd_node_t *node) {
    if (entry->prev) {
        entry->prev->next = entry->next;
    } else {
        table->buckets[bucket_idx] = entry->next;
    }
    if (entry->next) {
        entry->next->prev = entry->prev;
    }

    mtpndd_edge_t *edges = node->edges;
    if (edges && edges->buckets) {
        size_t edge_bucket_count = edges->bucket_count ? edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t eb = 0; eb < edge_bucket_count; ++eb) {
            edge_bucket_entry_t *head = edges->buckets[eb];
            if (!head) {
                continue;
            }
            edge_bucket_entry_t *edge_entry = head;
            while (edge_entry) {
                mtpndd_node_t *child = edge_entry->child;
                if (child && !mtpndd_is_terminal(child)) {
                    mtpndd_deref(child);
                }
                edge_entry = edge_entry->next;
            }
        }
    }
    mtpndd_edge_map_free(edges);

    mtpndd_memory_release_node(node);
    mtpndd_memory_release_nodetable_entry(entry);
    size_t prev = atomic_load_explicit(&table->entry_count, memory_order_relaxed);
    if (prev > 0) {
        atomic_fetch_sub_explicit(&table->entry_count, 1, memory_order_relaxed);
    }
}

static size_t mtpndd_gc_sweep(void) {
    size_t reclaimed = 0;

    for (uint32_t field = 1; field <= g_mtpndd_config.field_count; ++field) {
        mtpndd_nodetable_t *nodetable = g_mtpndd_config.node_tables_by_field[field];
        if (!nodetable) {
            continue;
        }

        size_t bucket_count = nodetable->nodetable_bucket_count;
        for (size_t i = 0; i < bucket_count; ++i) {

            mtpndd_nodetable_bucket_entry_t *entry = nodetable->buckets[i];
            while (entry) {
                mtpndd_nodetable_bucket_entry_t *next_entry = entry->next;
                mtpndd_node_t *node = entry->node;
                uint32_t refc = atomic_load_explicit(&node->ref_count, memory_order_relaxed);
                if (refc == 0) {
                    mtpndd_release_node(nodetable, i, entry, node);
                    reclaimed++;
                }
                entry = next_entry;
            }
        }
    }

    return reclaimed;
}

static bool mtpndd_nodetable_rehash(mtpndd_nodetable_t *table, size_t new_bucket_count) {
    if (!table || new_bucket_count == 0) {
        return false;
    }

    if (new_bucket_count < 16) new_bucket_count = 16;
    new_bucket_count = mtpndd_round_up_pow2(new_bucket_count);

    mtpndd_nodetable_bucket_entry_t **new_buckets =
            (mtpndd_nodetable_bucket_entry_t **)calloc(new_bucket_count, sizeof(*new_buckets));
    if (!new_buckets) {
        return false;
    }

    size_t old_count = table->nodetable_bucket_count;
    for (size_t i = 0; i < old_count; ++i) {
        mtpndd_nodetable_bucket_entry_t *entry = table->buckets[i];
        while (entry) {
            mtpndd_nodetable_bucket_entry_t *next_entry = entry->next;
            size_t hash = new_bucket_count ? (size_t)(entry->cached_hash & (new_bucket_count - 1)) : 0;
            entry->prev = NULL;
            entry->next = new_buckets[hash];
            if (entry->next) {
                entry->next->prev = entry;
            }
            new_buckets[hash] = entry;
            entry = next_entry;
        }
        table->buckets[i] = NULL;
    }

    free(table->buckets);
    table->buckets = new_buckets;
    table->nodetable_bucket_count = new_bucket_count;
    table->load_threshold = new_bucket_count - (new_bucket_count >> 2);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    MTPNDD_STAT_ADD(nodetable_rehash_total, 1);
    MTPNDD_STAT_MAX(nodetable_max_buckets, new_bucket_count);
#endif
    return true;
}

static void mtpndd_nodetable_maybe_rehash(mtpndd_nodetable_t *table) {
    if (!table) return;
    size_t count = atomic_load_explicit(&table->entry_count, memory_order_relaxed);
    if (count >= table->load_threshold) {
        pthread_mutex_lock(&table->rehash_mutex);
        count = atomic_load_explicit(&table->entry_count, memory_order_relaxed);
        if (count >= table->load_threshold) {
            // Exclusive: block lookups/inserts while swapping buckets.
            mtpndd_nodetable_lock_all(table);
            size_t target = table->nodetable_bucket_count ? table->nodetable_bucket_count * 2 : 16;
            bool ok = mtpndd_nodetable_rehash(table, target);
            mtpndd_nodetable_unlock_all(table);
            if (ok && target > g_mtpndd_pal_config.nodetable_bucket_count) {
                g_mtpndd_pal_config.nodetable_bucket_count = target;
            }
        }
        pthread_mutex_unlock(&table->rehash_mutex);
    }
}

static void grow_internal(void) {
    size_t new_capacity = g_mtpndd_pal_config.mtpndd_nodetable_size * 2;
    for (uint32_t field = 1; field <= g_mtpndd_config.field_count; ++field) {
        mtpndd_nodetable_t *table = g_mtpndd_config.node_tables_by_field[field];
        if (!table) continue;
        size_t new_bucket_count = table->nodetable_bucket_count ? table->nodetable_bucket_count * 2
                                                                : MTPNDD_DEFAULT_NODETABLE_BUCKET_COUNT;
        pthread_mutex_lock(&table->rehash_mutex);
        mtpndd_nodetable_lock_all(table);
        bool ok = mtpndd_nodetable_rehash(table, new_bucket_count);
        mtpndd_nodetable_unlock_all(table);
        pthread_mutex_unlock(&table->rehash_mutex);
        if (!ok) {
            MTPNDD_LOG_ERROR("[MTPNDD ERROR] nodetable rehash failed for field=%u (bucket target=%zu)\n",
                    field, new_bucket_count);
            return;
        }
        MTPNDD_LOG_DEBUG("[MTPNDD DEBUG] field=%u rehashed to buckets=%zu\n",
                field, new_bucket_count);
    }
    g_mtpndd_pal_config.mtpndd_nodetable_size = new_capacity;
    size_t config_bucket = g_mtpndd_pal_config.nodetable_bucket_count;
    if (config_bucket == 0) {
        config_bucket = MTPNDD_DEFAULT_NODETABLE_BUCKET_COUNT;
    }
    g_mtpndd_pal_config.nodetable_bucket_count = config_bucket * 2;
    MTPNDD_LOG_DEBUG("[MTPNDD DEBUG] nodetable capacity doubled to %zu (config buckets=%zu)\n",
            new_capacity, g_mtpndd_pal_config.nodetable_bucket_count);
}
