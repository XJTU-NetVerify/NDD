// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#include "mtpndd_leaf_table.h"
#include "mtpndd_node.h"
#include "mtpndd_nodetable.h"
#include "mtpndd_memory_pool.h"

#include <stdlib.h>
#include <stdatomic.h>
#include <string.h>

#define MTPNDD_LEAF_TABLE_DEFAULT_BUCKETS 1024u
#define MTPNDD_LEAF_TABLE_LOCK_CAP 64u

static inline size_t leaf_bucket_index(const mtpndd_leaf_table_t *t, uint64_t v) {
    uint64_t h = mtpndd_hash_u64(v);
    return (size_t)(h & (t->bucket_count - 1));
}

static inline size_t leaf_lock_index(const mtpndd_leaf_table_t *t, uint64_t v) {
    uint64_t h = mtpndd_hash_u64(v);
    return (size_t)(h & (t->bucket_lock_count - 1));
}

mtpndd_leaf_table_t *mtpndd_leaf_table_create(size_t bucket_count) {
    if (bucket_count == 0) bucket_count = MTPNDD_LEAF_TABLE_DEFAULT_BUCKETS;
    bucket_count = mtpndd_round_up_pow2(bucket_count);
    if (bucket_count < 16) bucket_count = 16;

    mtpndd_leaf_table_t *t = (mtpndd_leaf_table_t *)calloc(1, sizeof(*t));
    if (!t) return NULL;

    t->bucket_count = bucket_count;
    t->buckets = (mtpndd_leaf_bucket_entry_t **)calloc(
            bucket_count, sizeof(mtpndd_leaf_bucket_entry_t *));
    if (!t->buckets) {
        free(t);
        return NULL;
    }

    size_t locks = bucket_count;
    if (locks > MTPNDD_LEAF_TABLE_LOCK_CAP) locks = MTPNDD_LEAF_TABLE_LOCK_CAP;
    if (locks < 4) locks = 4;
    locks = mtpndd_round_up_pow2(locks);
    t->bucket_lock_count = locks;
    t->bucket_locks = (pthread_spinlock_t *)calloc(locks, sizeof(*t->bucket_locks));
    if (!t->bucket_locks) {
        free(t->buckets);
        free(t);
        return NULL;
    }
    for (size_t i = 0; i < locks; ++i) {
        pthread_spin_init(&t->bucket_locks[i], PTHREAD_PROCESS_PRIVATE);
    }
    atomic_init(&t->entry_count, 0);
    return t;
}

void mtpndd_leaf_table_free(mtpndd_leaf_table_t *t) {
    if (!t) return;
    if (t->buckets) {
        for (size_t i = 0; i < t->bucket_count; ++i) {
            mtpndd_leaf_bucket_entry_t *e = t->buckets[i];
            while (e) {
                mtpndd_leaf_bucket_entry_t *next = e->next;
                // The node pointer is either a pre-seeded sentinel (owned by
                // the caller) or a pooled node (owned by the memory pool).
                // In either case we do not free `e->node` here; we only free
                // pool-allocated nodes that are not the TRUE/FALSE sentinels.
                if (e->node != &MTPNDD_TRUE && e->node != &MTPNDD_FALSE) {
                    mtpndd_memory_release_node(e->node);
                }
                free(e);
                e = next;
            }
        }
        free(t->buckets);
    }
    if (t->bucket_locks) {
        for (size_t i = 0; i < t->bucket_lock_count; ++i) {
            pthread_spin_destroy(&t->bucket_locks[i]);
        }
        free((void *)t->bucket_locks);
    }
    free(t);
}

mtpndd_error_t mtpndd_leaf_table_insert_sentinel(
        mtpndd_leaf_table_t *t, uint64_t leaf_value, mtpndd_node_t *node) {
    if (!t || !node) {
        return MTPNDD_ERROR_NULL_POINTER;
    }
    mtpndd_leaf_bucket_entry_t *entry = (mtpndd_leaf_bucket_entry_t *)malloc(sizeof(*entry));
    if (!entry) return MTPNDD_ERROR_OUT_OF_MEMORY;
    entry->leaf_value = leaf_value;
    entry->node = node;
    entry->marked = 0;

    size_t lock_idx = leaf_lock_index(t, leaf_value);
    size_t bkt = leaf_bucket_index(t, leaf_value);
    pthread_spin_lock(&t->bucket_locks[lock_idx]);
    entry->next = t->buckets[bkt];
    t->buckets[bkt] = entry;
    pthread_spin_unlock(&t->bucket_locks[lock_idx]);
    atomic_fetch_add_explicit(&t->entry_count, 1, memory_order_relaxed);
    return MTPNDD_SUCCESS;
}

mtpndd_node_t *mtpndd_leaf_table_lookup_or_insert(
        mtpndd_leaf_table_t *t, uint32_t leaf_field_id, uint64_t leaf_value) {
    if (!t) {
        mtpndd_set_error(MTPNDD_ERROR_NULL_POINTER, __func__, __LINE__);
        return NULL;
    }
    size_t lock_idx = leaf_lock_index(t, leaf_value);
    size_t bkt = leaf_bucket_index(t, leaf_value);

    // Fast path: scan under lock.
    pthread_spin_lock(&t->bucket_locks[lock_idx]);
    for (mtpndd_leaf_bucket_entry_t *e = t->buckets[bkt]; e; e = e->next) {
        if (e->leaf_value == leaf_value) {
            mtpndd_node_t *found = e->node;
            pthread_spin_unlock(&t->bucket_locks[lock_idx]);
            return found;
        }
    }
    pthread_spin_unlock(&t->bucket_locks[lock_idx]);

    // Slow path: allocate a new leaf node, then retry under lock.
    mtpndd_node_t *node = mtpndd_memory_acquire_node();
    if (!node) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    node->field_id = leaf_field_id;
    node->leaf_value = leaf_value;
    // Protected: leaves live for the lifetime of the engine in phase 1.
    atomic_init(&node->ref_count, UINT32_MAX);

    mtpndd_leaf_bucket_entry_t *entry = (mtpndd_leaf_bucket_entry_t *)malloc(sizeof(*entry));
    if (!entry) {
        mtpndd_memory_release_node(node);
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    entry->leaf_value = leaf_value;
    entry->node = node;
    entry->marked = 0;

    pthread_spin_lock(&t->bucket_locks[lock_idx]);
    // Re-check to handle a concurrent insert of the same value.
    for (mtpndd_leaf_bucket_entry_t *e = t->buckets[bkt]; e; e = e->next) {
        if (e->leaf_value == leaf_value) {
            mtpndd_node_t *found = e->node;
            pthread_spin_unlock(&t->bucket_locks[lock_idx]);
            free(entry);
            mtpndd_memory_release_node(node);
            return found;
        }
    }
    entry->next = t->buckets[bkt];
    t->buckets[bkt] = entry;
    pthread_spin_unlock(&t->bucket_locks[lock_idx]);
    atomic_fetch_add_explicit(&t->entry_count, 1, memory_order_relaxed);
    return node;
}

size_t mtpndd_leaf_table_size(const mtpndd_leaf_table_t *t) {
    if (!t) return 0;
    return (size_t)atomic_load_explicit(&t->entry_count, memory_order_relaxed);
}

/********************************
 * Mark-and-sweep GC
 ********************************/

// Lock / unlock every bucket on a leaf table.  Used by mtpndd_leaf_gc to
// quiesce concurrent make_fraction / make_double inserts while sweeping.
static void leaf_table_lock_all(mtpndd_leaf_table_t *t) {
    if (!t || !t->bucket_locks) return;
    for (size_t i = 0; i < t->bucket_lock_count; ++i) {
        pthread_spin_lock(&t->bucket_locks[i]);
    }
}

static void leaf_table_unlock_all(mtpndd_leaf_table_t *t) {
    if (!t || !t->bucket_locks) return;
    for (size_t i = t->bucket_lock_count; i-- > 0; ) {
        pthread_spin_unlock(&t->bucket_locks[i]);
    }
}

void mtpndd_leaf_table_clear_marks(mtpndd_leaf_table_t *t) {
    if (!t || !t->buckets) return;
    for (size_t i = 0; i < t->bucket_count; ++i) {
        for (mtpndd_leaf_bucket_entry_t *e = t->buckets[i]; e; e = e->next) {
            e->marked = 0;
        }
    }
}

void mtpndd_leaf_table_mark(mtpndd_leaf_table_t *t, mtpndd_node_t *leaf) {
    if (!t || !leaf) return;
    size_t bkt = leaf_bucket_index(t, leaf->leaf_value);
    for (mtpndd_leaf_bucket_entry_t *e = t->buckets[bkt]; e; e = e->next) {
        if (e->node == leaf) {
            e->marked = 1;
            return;
        }
    }
}

size_t mtpndd_leaf_table_sweep_unmarked(mtpndd_leaf_table_t *t) {
    if (!t || !t->buckets) return 0;
    size_t reclaimed = 0;
    for (size_t i = 0; i < t->bucket_count; ++i) {
        mtpndd_leaf_bucket_entry_t **link = &t->buckets[i];
        mtpndd_leaf_bucket_entry_t *e = *link;
        while (e) {
            mtpndd_leaf_bucket_entry_t *next = e->next;
            // Never reclaim the pre-seeded TRUE / FALSE sentinels — even
            // if they happen to be unmarked for some reason, they live on
            // the data segment and must not be freed.
            bool is_sentinel = (e->node == &MTPNDD_TRUE) || (e->node == &MTPNDD_FALSE);
            if (!e->marked && !is_sentinel) {
                *link = next;
                mtpndd_memory_release_node(e->node);
                free(e);
                atomic_fetch_sub_explicit(&t->entry_count, 1, memory_order_relaxed);
                reclaimed++;
            } else {
                link = &e->next;
            }
            e = next;
        }
    }
    return reclaimed;
}

// Walk all live internal nodes in the field nodetables and mark every
// leaf they reference.  Internal helper for mtpndd_leaf_gc.
static void mtpndd_leaf_gc_mark_from_roots(void) {
    mtpndd_leaf_table_t *frac = g_mtpndd_config.leaf_table;
    mtpndd_leaf_table_t *dbl  = g_mtpndd_config.double_leaf_table;
    if (frac) mtpndd_leaf_table_mark(frac, &MTPNDD_TRUE);
    if (frac) mtpndd_leaf_table_mark(frac, &MTPNDD_FALSE);

    for (uint32_t field = 1; field <= g_mtpndd_config.field_count; ++field) {
        mtpndd_nodetable_t *nt = g_mtpndd_config.node_tables_by_field[field];
        if (!nt || !nt->buckets) continue;
        for (size_t i = 0; i < nt->nodetable_bucket_count; ++i) {
            for (mtpndd_nodetable_bucket_entry_t *ne = nt->buckets[i]; ne; ne = ne->next) {
                mtpndd_node_t *node = ne->node;
                if (!node || !node->edges || !node->edges->buckets) continue;
                size_t bc = node->edges->bucket_count
                        ? node->edges->bucket_count
                        : g_mtpndd_pal_config.edge_bucket_count;
                for (size_t eb = 0; eb < bc; ++eb) {
                    for (edge_bucket_entry_t *edge = node->edges->buckets[eb]; edge; edge = edge->next) {
                        mtpndd_node_t *child = edge->child;
                        if (!child) continue;
                        if (child->field_id == MTPNDD_FRACTION_LEAF_FIELD_ID && frac) {
                            mtpndd_leaf_table_mark(frac, child);
                        } else if (child->field_id == MTPNDD_DOUBLE_LEAF_FIELD_ID && dbl) {
                            mtpndd_leaf_table_mark(dbl, child);
                        }
                    }
                }
            }
        }
    }
}

size_t mtpndd_leaf_gc(void) {
    mtpndd_leaf_table_t *frac = g_mtpndd_config.leaf_table;
    mtpndd_leaf_table_t *dbl  = g_mtpndd_config.double_leaf_table;
    if (!frac && !dbl) return 0;

    // Quiesce concurrent inserts while we clear/mark/sweep.  The caller
    // from gc_internal already holds all nodetable bucket locks, so any
    // thread inside mtpndd_mk is blocked; but make_fraction / make_double
    // only touch the leaf table and must be blocked here explicitly.
    if (frac) leaf_table_lock_all(frac);
    if (dbl)  leaf_table_lock_all(dbl);

    if (frac) mtpndd_leaf_table_clear_marks(frac);
    if (dbl)  mtpndd_leaf_table_clear_marks(dbl);

    mtpndd_leaf_gc_mark_from_roots();

    size_t reclaimed = 0;
    if (frac) reclaimed += mtpndd_leaf_table_sweep_unmarked(frac);
    if (dbl)  reclaimed += mtpndd_leaf_table_sweep_unmarked(dbl);

    if (dbl)  leaf_table_unlock_all(dbl);
    if (frac) leaf_table_unlock_all(frac);
    return reclaimed;
}
