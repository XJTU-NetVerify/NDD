// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#ifndef MTPNDD_LEAF_TABLE_H
#define MTPNDD_LEAF_TABLE_H

#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stddef.h>
#include "mtpndd_common.h"

// Canonical table for leaf (terminal) nodes keyed on a packed 64-bit value.
// Phase 1 stores only fraction leaves (int32 numerator : int32 denominator).
// Leaves are long-lived and protected; the table does not currently support
// rehashing or GC — the bucket count is fixed at creation time.

typedef struct mtpndd_leaf_bucket_entry_s {
    struct mtpndd_leaf_bucket_entry_s *next;
    uint64_t leaf_value;       // packed (numer << 32) | (uint32_t)denom
    mtpndd_node_t *node;
    uint8_t marked;            // GC mark bit (cleared at start of sweep)
} mtpndd_leaf_bucket_entry_t;

typedef struct mtpndd_leaf_table_s {
    size_t bucket_count;                  // power of two
    mtpndd_leaf_bucket_entry_t **buckets;
    _Atomic size_t entry_count;

    size_t bucket_lock_count;             // power of two
    pthread_spinlock_t *bucket_locks;
} mtpndd_leaf_table_t;

mtpndd_leaf_table_t *mtpndd_leaf_table_create(size_t bucket_count);
void mtpndd_leaf_table_free(mtpndd_leaf_table_t *table);

// Look up (or create) the canonical leaf node with the given packed value.
// `leaf_field_id` is written into the new node (MTPNDD_FRACTION_LEAF_FIELD_ID
// or MTPNDD_DOUBLE_LEAF_FIELD_ID).
// Returns NULL on allocation failure (last error is set).
mtpndd_node_t *mtpndd_leaf_table_lookup_or_insert(
        mtpndd_leaf_table_t *table, uint32_t leaf_field_id, uint64_t leaf_value);

// Pre-seed the table with an externally allocated leaf node (used for
// MTPNDD_TRUE / MTPNDD_FALSE singletons during init). The caller owns `node`
// and is responsible for keeping it alive; the table stores the pointer.
mtpndd_error_t mtpndd_leaf_table_insert_sentinel(
        mtpndd_leaf_table_t *table, uint64_t leaf_value, mtpndd_node_t *node);

size_t mtpndd_leaf_table_size(const mtpndd_leaf_table_t *table);

// Mark-and-sweep GC primitives.  `mtpndd_leaf_table_mark` finds the entry
// whose node pointer matches `leaf` and sets its marked flag; `sweep`
// releases every unmarked entry (except the pre-seeded sentinels
// MTPNDD_TRUE / MTPNDD_FALSE which are always kept).
void   mtpndd_leaf_table_clear_marks(mtpndd_leaf_table_t *table);
void   mtpndd_leaf_table_mark(mtpndd_leaf_table_t *table, mtpndd_node_t *leaf);
size_t mtpndd_leaf_table_sweep_unmarked(mtpndd_leaf_table_t *table);

// Full GC entry point: walks all live internal nodes in the field
// nodetables, marks every leaf they reach, and sweeps the rest from both
// the fraction and double leaf tables.  Returns the number of leaves
// reclaimed.
//
// Also wired into mtpndd_gc_before_sylvan / gc_internal automatically:
// whenever the node-table GC runs, leaf GC runs immediately afterwards
// while the nodetables are still locked.  External callers can invoke
// it directly for diagnostics or explicit cleanup; the function takes
// all leaf-table bucket locks for the duration of the sweep.
size_t mtpndd_leaf_gc(void);

#endif // MTPNDD_LEAF_TABLE_H
