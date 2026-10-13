// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#ifndef MTPNDD_NODETABLE_H
#define MTPNDD_NODETABLE_H

#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include "mtpndd_common.h"
#include "mtpndd_node.h"
#include "lace.h"
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
#include <time.h>
#endif

/********************************
 * MTPNDD nodetable
 ********************************/
typedef struct mtpndd_nodetable_bucket_entry_s {
    struct mtpndd_nodetable_bucket_entry_s *next;
    struct mtpndd_nodetable_bucket_entry_s *prev;
    mtpndd_edge_t *edges;
    mtpndd_node_t *node;
    /* Mirror of edges->cached_hash so the bucket-walk early-reject path
     * can compare without chasing through the edges pointer. Lookup hit
     * rate on the deep comparison is low (cached_hash of an unrelated
     * entry rarely matches), so moving this one field in-line avoids a
     * cache miss per non-matching entry. 40 bytes total, still one
     * cache line. */
    uint64_t cached_hash;
} mtpndd_nodetable_bucket_entry_t;

// mtpndd_edge_t *edges -> mtpndd_node_t* node
typedef struct mtpndd_nodetable_s {
    size_t nodetable_bucket_count;
    mtpndd_nodetable_bucket_entry_t **buckets;
    _Atomic size_t entry_count; // number of nodes stored in this table
    size_t load_threshold; // trigger rehash when entry_count >= load_threshold

    // Concurrency:
    // - bucket_locks shard access to buckets for lookups/inserts.
    // - rehash_mutex ensures a single thread performs rehash at a time.
    size_t bucket_lock_count; // power of two
    pthread_spinlock_t *bucket_locks;
    pthread_mutex_t rehash_mutex;
} mtpndd_nodetable_t;

#define NODETABLE_HASH_VAL(key, nodetable) \
    (((nodetable) && (nodetable)->nodetable_bucket_count) \
        ? (size_t)(((key) ? (key)->cached_hash : 0) & ((nodetable)->nodetable_bucket_count - 1)) \
        : 0)

// Compare edge map CONTENT, not pointer
// Returns true if both edge maps have identical (child, label) pairs
// Optimized: first compare cached hash values for fast rejection
static inline bool nodetable_edges_equal(const mtpndd_edge_t *a, const mtpndd_edge_t *b) {
    if (a == b) return true;
    if (!a || !b) return false;

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    struct timespec compare_start = {0};
    struct timespec compare_end = {0};
    size_t compare_entries = 0;
    size_t compare_steps_total = 0;
    size_t compare_max_steps = 0;
    clock_gettime(CLOCK_MONOTONIC, &compare_start);
#endif
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    bool result = true;
#define NODETABLE_RETURN(val) do { result = (val); goto record_compare; } while (0)
#else
#define NODETABLE_RETURN(val) return (val)
#endif

    // Fast path: compare cached hash values first (like Java HashMap)
    // Different hash means definitely not equal
    if (a->cached_hash != b->cached_hash) {
        NODETABLE_RETURN(false);
    }

    // Hash match, now check edge count
    if (a->edge_count != b->edge_count) {
        NODETABLE_RETURN(false);
    }
    if (a->edge_count == 0) {
        NODETABLE_RETURN(true);
    }

    // Full comparison only if hash and count match
    // For each edge in 'a', find matching edge in 'b'
    size_t edge_bucket_cnt = a->bucket_count ? a->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t i = 0; i < edge_bucket_cnt; i++) {
        edge_bucket_entry_t *entry_a = a->buckets ? a->buckets[i] : NULL;
        while (entry_a) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
            size_t steps = 0;
            compare_entries++;
#endif
            // Find this (child, label) pair in b
            mtpndd_node_t *child_a = entry_a->child;
            mtpndd_bdd_t label_a = atomic_load_explicit(&entry_a->label, memory_order_relaxed);

            // Lookup in b's edge map
            bool found = false;
            if (b->buckets) {
                size_t b_bucket = EDGE_MAP_BUCKET_INDEX(b, child_a);
                edge_bucket_entry_t *entry_b = b->buckets[b_bucket];
                while (entry_b) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
                    steps++;
#endif
                    if (entry_b->child == child_a) {
                        mtpndd_bdd_t label_b = atomic_load_explicit(&entry_b->label, memory_order_relaxed);
                        if (label_a == label_b) {
                            found = true;
                        }
                        break;
                    }
                    entry_b = entry_b->next;
                }
            }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
            compare_steps_total += steps;
            if (steps > compare_max_steps) {
                compare_max_steps = steps;
            }
#endif
            if (!found) {
                NODETABLE_RETURN(false);
            }

            entry_a = entry_a->next;
        }
    }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
record_compare:
    clock_gettime(CLOCK_MONOTONIC, &compare_end);
    MTPNDD_STAT_ADD(nodetable_edge_compare_ns, mtpndd_timespec_diff_ns(&compare_start, &compare_end));
    MTPNDD_STAT_ADD(nodetable_edge_compare_entries, compare_entries);
    MTPNDD_STAT_ADD(nodetable_edge_compare_steps_total, compare_steps_total);
    MTPNDD_STAT_MAX(nodetable_edge_compare_max_steps, compare_max_steps);
    return result;
#endif
#undef NODETABLE_RETURN
    return true;
}

#define NODETABLE_BUCKET_ENTRY_EQUAL(entry, keyEdges) nodetable_edges_equal((entry)->edges, (keyEdges))

#define FOR_EACH_ENTRY_IN_NODETABLE_BUCKET(emap, bucket_idx, entry) \
    for (entry = emap->buckets[bucket_idx]; \
        entry; \
        entry = entry->next)
#define FOR_EACH_ENTRY_IN_ALL_NODETABLE_BUCKETS(emap, entry) \
    for (size_t _bkt = 0; _bkt < emap->nodetable_bucket_count; _bkt++) \
        FOR_EACH_ENTRY_IN_NODETABLE_BUCKET(emap, _bkt, entry)

mtpndd_node_t *find_node_in_nodetable(mtpndd_nodetable_t *nodetable, mtpndd_edge_t *edges);

mtpndd_nodetable_t *mtpndd_nodetable_declare_field();
void mtpndd_nodetable_free(mtpndd_nodetable_t *table);

/********************************
 * MTPNDD node
 ********************************/
mtpndd_error_t mtpndd_ref(mtpndd_t *node);
mtpndd_error_t mtpndd_deref(mtpndd_t *node);
mtpndd_error_t mtpndd_protect(mtpndd_t *node);
mtpndd_error_t mtpndd_unprotect(mtpndd_t *node);
// create or reuse node
void mtpndd_mk(uint32_t field, mtpndd_edge_t *edges, mtpndd_node_t **result);

// Run MTPNDD GC before Sylvan GC (called from Sylvan GC pre-hook).
void mtpndd_gc_before_sylvan(void);

// Sylvan GC mark callback: mark BDD nodes referenced by MTPNDD edge labels.
// Signature matches gc_hook_cb (WorkerP *, Task *) for sylvan_gc_add_mark.
void mtpndd_gc_mark_bdd_labels(WorkerP *worker, Task *task);

#endif // MTPNDD_NODETABLE_H
