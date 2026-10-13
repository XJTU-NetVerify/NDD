// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#ifndef MTPNDD_NODE_H
#define MTPNDD_NODE_H

#include <stdatomic.h>
#include <stdio.h>
#include <stdbool.h>
#include "mtpndd_common.h"

/********************************
 * MTPNDD node definition
 ********************************/
// Terminal (leaf) nodes use the top of the field_id space.  Any field_id
// >= MTPNDD_LEAF_FIELD_ID_MIN is a leaf; the specific value encodes the
// leaf type so that the correct canonical table is consulted.
//   MTPNDD_FRACTION_LEAF_FIELD_ID : int32 numer / int32 denom in leaf_value
//   MTPNDD_DOUBLE_LEAF_FIELD_ID   : IEEE double bit-pattern in leaf_value
#define MTPNDD_FRACTION_LEAF_FIELD_ID ((uint32_t)0xFFFFFFFFu)
#define MTPNDD_DOUBLE_LEAF_FIELD_ID   ((uint32_t)0xFFFFFFFEu)
#define MTPNDD_LEAF_FIELD_ID_MIN      MTPNDD_DOUBLE_LEAF_FIELD_ID

// Backwards-compatibility alias.
#define MTPNDD_LEAF_FIELD_ID MTPNDD_FRACTION_LEAF_FIELD_ID

// directly use field_id instead of mtpndd_field_info_t * for better cache performance
// TODO: cache line friendly
// TODO: like JDD, use a t_list data structure for both node memory pool and node table
struct mtpndd_node_s {
    /* _Atomic uint32_t (not uint_fast32_t which is 8 bytes on x86_64) —
     * saves 4 bytes and eliminates a 4-byte hole before field_id,
     * shrinking the node from 24 to 16 bytes. UINT32_MAX is the
     * "protected" sentinel; plain uint32 can hold it. */
    _Atomic uint32_t ref_count;
    uint32_t field_id;
    union {
        struct mtpndd_edge_s *edges;   // internal nodes
        uint64_t leaf_value;            // leaves (field_id == MTPNDD_LEAF_FIELD_ID)
    };
};

/* Inlined identity hash of a canonicalized node — content-addressed by
 * (field_id, edges pointer). Must be inline because it sits on the
 * mtpndd_op_cache_lookup_binary hot path (12% self in profiling);
 * keeping it in a separate TU costs a call + register spills per lookup. */
static inline size_t mtpndd_hash_node_identity(const mtpndd_node_t *node)
{
    if (!node) return 0;
    uint64_t hash = 1469598103934665603ULL; /* FNV-1a offset basis */
    hash ^= (uint64_t)node->field_id;
    hash *= 1099511628211ULL;               /* FNV prime */
    hash ^= (uintptr_t)node->edges;
    hash *= 1099511628211ULL;
    return (size_t)hash;
}
/********************************
 * MTPNDD edge definition
 ********************************/
typedef struct edge_bucket_entry_s {
    struct edge_bucket_entry_s *next;
    mtpndd_node_t *child;
    _Atomic(mtpndd_bdd_t) label;
} edge_bucket_entry_t;

// TODO: try not to malloc buckets every time, use pooled array instead. Edge map memory pool should alloc every edge map by _edge_bucket_cnt when mtpndd_init
// mtpndd_t* child -> mtpndd_bdd_t label
struct mtpndd_edge_s {
    size_t edge_count;
    uint64_t cached_hash;  // Cached hash value for fast lookup (like Java HashMap)
    edge_bucket_entry_t **buckets;
    size_t bucket_count;
    size_t load_threshold;
    bool buckets_malloced;
};

// Compute hash value for edge map content (matches Java Map.hashCode() semantics)
static inline uint64_t mtpndd_edge_entry_hash(const mtpndd_node_t *child, mtpndd_bdd_t label) {
    uint64_t entry_hash = mtpndd_hash_u64((uintptr_t)child);
    entry_hash ^= mtpndd_hash_u64((uint64_t)label);
    return entry_hash;
}

static inline uint64_t mtpndd_edge_map_compute_hash(const mtpndd_edge_t *edges) {
    if (!edges || !edges->buckets) return 0;

    // Use XOR-based accumulation like Java's HashMap.hashCode()
    // This is order-independent, matching Java's Map.hashCode() behavior
    uint64_t hash = 0;
    size_t edge_bucket_cnt = edges->bucket_count ? edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t i = 0; i < edge_bucket_cnt; i++) {
        edge_bucket_entry_t *entry = edges->buckets[i];
        while (entry) {
            // Hash each (child, label) pair and XOR into result
            mtpndd_bdd_t label = atomic_load_explicit(&entry->label, memory_order_relaxed);
            uint64_t entry_hash = mtpndd_edge_entry_hash(entry->child, label);
            hash ^= entry_hash;
            entry = entry->next;
        }
    }
    return hash;
}

#define EDGE_MAP_BUCKET_COUNT(map) \
    (((map) && (map)->bucket_count) ? (map)->bucket_count : g_mtpndd_pal_config.edge_bucket_count)
#define EDGE_MAP_BUCKET_INDEX(map, key) \
    (EDGE_MAP_BUCKET_COUNT(map) ? (mtpndd_hash_node_identity((key)) & (EDGE_MAP_BUCKET_COUNT(map) - 1)) : 0)
#define EDGE_MAP_HASH_VAL(map, key) EDGE_MAP_BUCKET_INDEX(map, key)

#define EDGE_BUCKET_ENTRY_EQUAL(entry, key) ((entry->child) == (key))

#define FOR_EACH_ENTRY_IN_BUCKET(emap, bucket_idx, entry) \
    for ((entry) = (emap)->buckets[bucket_idx]; \
        (entry); \
        (entry) = (entry)->next)
#define FOR_EACH_ENTRY_IN_ALL_BUCKETS(emap, entry) \
    for (size_t _bkt = 0, _edge_bucket_cnt = (emap)->bucket_count ? (emap)->bucket_count : g_mtpndd_pal_config.edge_bucket_count; _bkt < _edge_bucket_cnt; _bkt++) \
        for ((entry) = (emap)->buckets[_bkt]; \
            (entry); \
            (entry) = (entry)->next)

edge_bucket_entry_t *find_edge_entry(mtpndd_edge_t *edge, mtpndd_node_t *key);
void mtpndd_edge_map_free(mtpndd_edge_t *edges);
mtpndd_error_t mtpndd_add_edge(mtpndd_edge_t *edges, mtpndd_t *descendant, mtpndd_bdd_t label_bdd);
void mtpndd_edge_map_init(mtpndd_edge_t *edges);

/********************************
 * MTPNDD terminal nodes
 ********************************/
extern mtpndd_t MTPNDD_TRUE;
extern mtpndd_t MTPNDD_FALSE;

/* Single-load predicates — inlined because they sit on the
 * mtpndd_and_rec hot path (mtpndd_is_terminal was 4.6% self as a
 * cross-TU call before inlining). */
static inline bool mtpndd_is_true(mtpndd_t *ndd)
{
    return ndd == &MTPNDD_TRUE;
}

static inline bool mtpndd_is_false(mtpndd_t *ndd)
{
    return ndd == &MTPNDD_FALSE;
}

static inline bool mtpndd_is_terminal(mtpndd_t *ndd)
{
    return ndd->field_id >= MTPNDD_LEAF_FIELD_ID_MIN;
}

/********************************
 * MTPNDD tuning
 ********************************/

/**
 * Two-phase AND threshold: minimum E_a * E_b to use the filter-then-recurse
 * path in same-field AND.  0 = disabled (always single-pass).  Default 16.
 */
void mtpndd_set_two_phase_threshold(int value);
int  mtpndd_get_two_phase_threshold(void);

/********************************
 * MTPNDD operations
 ********************************/
mtpndd_t *mtpndd_and(mtpndd_t *a, mtpndd_t *b);
mtpndd_t *mtpndd_or(mtpndd_t *a, mtpndd_t *b);
mtpndd_t *mtpndd_or_demorgan(mtpndd_t *a, mtpndd_t *b);
mtpndd_t *mtpndd_not(mtpndd_t *a);
mtpndd_t *mtpndd_diff(mtpndd_t *a, mtpndd_t *b);
mtpndd_t *mtpndd_exist(mtpndd_t *a, uint32_t field);

/********************************
 * MTPNDD batch operations
 ********************************/
mtpndd_t **mtpndd_and_batch(mtpndd_t **lefts, mtpndd_t **rights, size_t count);
mtpndd_t *mtpndd_or_reduce(mtpndd_t **values, size_t count);
mtpndd_t *mtpndd_and_reduce(mtpndd_t **values, size_t count);
double mtpndd_satcount(mtpndd_t *node);
double mtpndd_satcount_ndd(mtpndd_t *node);
double mtpndd_satcount_mtbdd(mtpndd_t *node);

/********************************
 * MTPNDD <-> MTBDD convertion
 ********************************/
mtpndd_error_t mtpndd_to_mtbdd(mtpndd_t *node, mtpndd_bdd_t *result);
mtpndd_error_t mtbdd_to_mtpndd(mtpndd_bdd_t bdd, mtpndd_t **result);

void mtpndd_fprint_dot(FILE *out, mtpndd_t *root);
void mtpndd_print_dot(mtpndd_t *root, const char *path);

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
uint64_t mtpndd_temp_refs_grow_total(void);
uint64_t mtpndd_temp_refs_peak_capacity(void);
#endif

#endif // MTPNDD_NODE_H
