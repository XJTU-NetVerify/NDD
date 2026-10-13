// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab
//
// Arithmetic operations on fraction-leaf MTPNDDs.  See mtpndd_arith.h.

#include "mtpndd_arith.h"
#include "mtpndd_common.h"
#include "mtpndd_node.h"
#include "mtpndd_nodetable.h"
#include "mtpndd_leaf.h"
#include "mtpndd_leaf_table.h"
#include "mtpndd_memory_pool.h"
#include "mtpndd_operation_cache.h"
#include "sylvan.h"
#include "lace.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <inttypes.h>
#include <math.h>

// Re-declared here so we don't need the BDD label accessor from mtpndd_node.c.
static inline mtpndd_bdd_t edge_label_load(edge_bucket_entry_t *entry) {
    return atomic_load_explicit(&entry->label, memory_order_acquire);
}

// Granularity heuristic for arith SPAWN decisions.  Tighter than the AND
// path because arith sub-tasks are dominated by a single sylvan_and plus a
// shallow recursive call — task overhead dominates unless the recursive
// child operation is itself substantial (~256+ pair iterations).
//
// MTPNDD_ARITH_SPAWN_MIN_PROD: minimum (children's edge_count product)
// required at the recursive level to justify SPAWNing the pair.
#ifndef MTPNDD_ARITH_SPAWN_MIN_PROD
#define MTPNDD_ARITH_SPAWN_MIN_PROD 256
#endif

static inline bool arith_should_spawn(mtpndd_t *a, mtpndd_t *b) {
#ifdef MTPNDD_ARITH_DISABLE_SPAWN
    (void)a; (void)b;
    return false;
#else
    if (lace_workers() <= 1) return false;
    if (mtpndd_is_terminal(a) || mtpndd_is_terminal(b)) return false;
    size_t edges_a = a->edges ? a->edges->edge_count : 0;
    size_t edges_b = b->edges ? b->edges->edge_count : 0;
    return edges_a * edges_b >= MTPNDD_ARITH_SPAWN_MIN_PROD;
#endif
}

// Same-field pair flush threshold (mirrors MTPNDD_AND_PENDING_FLUSH_THRESHOLD).
#ifndef MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD
#define MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD 256
#endif

/********************************
 * Parallel plus sub-task infrastructure
 ********************************/

// Result of one arithmetic sub-task.  `emit` means the pair produced an
// edge; in that case `child` has a +1 MTPNDD ref and `label` has a +1
// Sylvan ref that the caller takes ownership of when merging.
typedef struct {
    mtpndd_error_t status;
    uint32_t emit;
    mtpndd_t *child;
    mtpndd_bdd_t label;
} mtpndd_arith_item_t;

TASK_DECL_2(mtpndd_arith_item_t, mtpndd_plus_diff_pair, edge_bucket_entry_t*, mtpndd_t*);
TASK_DECL_2(mtpndd_arith_item_t, mtpndd_times_diff_pair, edge_bucket_entry_t*, mtpndd_t*);

// Row-level chunks: each sub-task processes one A-entry against all of B
// (or vice versa) and returns an array of surviving (child, label) items.
// Reduces task count from O(|A|*|B|) to O(|A|) and amortizes Lace overhead
// across the inner loop's work.
typedef struct {
    mtpndd_error_t status;
    uint32_t count;
    mtpndd_arith_item_t *items;   // malloc'd; caller frees after merge
} mtpndd_arith_chunk_t;

TASK_DECL_2(mtpndd_arith_chunk_t, mtpndd_plus_same_row, edge_bucket_entry_t*, mtpndd_t*);
TASK_DECL_2(mtpndd_arith_chunk_t, mtpndd_times_same_row, edge_bucket_entry_t*, mtpndd_t*);

/********************************
 * int64 fraction helpers
 ********************************/
static uint64_t gcd_u64(uint64_t a, uint64_t b) {
    while (b != 0) {
        uint64_t t = a % b;
        a = b;
        b = t;
    }
    return a;
}

// Normalize an int64 fraction and emit the canonical leaf, or NULL on
// overflow / invalid input (denom == 0).  Computes gcd on int64 first so
// that values that only temporarily overshoot int32 still fit.
static mtpndd_t *make_fraction_i64(int64_t n, int64_t d) {
    if (d == 0) {
        mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
        return NULL;
    }
    if (n == 0) {
        return &MTPNDD_FALSE;
    }
    // Move sign to the numerator.
    if (d < 0) {
        n = -n;
        d = -d;
    }
    uint64_t abs_n = (n < 0) ? (uint64_t)(-n) : (uint64_t)n;
    uint64_t ud = (uint64_t)d;
    uint64_t g = gcd_u64(abs_n, ud);
    abs_n /= g;
    ud /= g;
    // Check int32 fit (numerator is signed; -INT32_MIN doesn't fit either).
    if (abs_n > (uint64_t)INT32_MAX || ud > (uint64_t)INT32_MAX) {
        mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
        return NULL;
    }
    int32_t numer = (n < 0) ? -(int32_t)abs_n : (int32_t)abs_n;
    int32_t denom = (int32_t)ud;
    return mtpndd_make_fraction(numer, denom);
}

/********************************
 * Leaf-level arithmetic
 *
 * Dispatches on leaf type: both operands must share the same leaf type
 * (fraction or double).  Mixing types is an error — the caller should
 * convert explicitly.  MTPNDD_TRUE / MTPNDD_FALSE are canonical fraction
 * leaves (1/1 and 0/1) and are handled by the op fast-paths before leaf
 * arithmetic runs.
 ********************************/
static mtpndd_t *leaf_type_mismatch(const char *func, int line) {
    mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, func, line);
    return NULL;
}

static mtpndd_t *leaf_add(mtpndd_t *a, mtpndd_t *b) {
    if (mtpndd_is_fraction_leaf(a) && mtpndd_is_fraction_leaf(b)) {
        int64_t na = mtpndd_get_numer(a), da = mtpndd_get_denom(a);
        int64_t nb = mtpndd_get_numer(b), db = mtpndd_get_denom(b);
        return make_fraction_i64(na * db + nb * da, da * db);
    }
    if (mtpndd_is_double_leaf(a) && mtpndd_is_double_leaf(b)) {
        return mtpndd_make_double(mtpndd_get_double(a) + mtpndd_get_double(b));
    }
    return leaf_type_mismatch(__func__, __LINE__);
}

static mtpndd_t *leaf_sub(mtpndd_t *a, mtpndd_t *b) {
    if (mtpndd_is_fraction_leaf(a) && mtpndd_is_fraction_leaf(b)) {
        int64_t na = mtpndd_get_numer(a), da = mtpndd_get_denom(a);
        int64_t nb = mtpndd_get_numer(b), db = mtpndd_get_denom(b);
        return make_fraction_i64(na * db - nb * da, da * db);
    }
    if (mtpndd_is_double_leaf(a) && mtpndd_is_double_leaf(b)) {
        return mtpndd_make_double(mtpndd_get_double(a) - mtpndd_get_double(b));
    }
    return leaf_type_mismatch(__func__, __LINE__);
}

static mtpndd_t *leaf_mul(mtpndd_t *a, mtpndd_t *b) {
    if (mtpndd_is_fraction_leaf(a) && mtpndd_is_fraction_leaf(b)) {
        int64_t na = mtpndd_get_numer(a), da = mtpndd_get_denom(a);
        int64_t nb = mtpndd_get_numer(b), db = mtpndd_get_denom(b);
        return make_fraction_i64(na * nb, da * db);
    }
    if (mtpndd_is_double_leaf(a) && mtpndd_is_double_leaf(b)) {
        return mtpndd_make_double(mtpndd_get_double(a) * mtpndd_get_double(b));
    }
    return leaf_type_mismatch(__func__, __LINE__);
}

static mtpndd_t *leaf_div(mtpndd_t *a, mtpndd_t *b) {
    if (mtpndd_is_fraction_leaf(a) && mtpndd_is_fraction_leaf(b)) {
        int64_t na = mtpndd_get_numer(a), da = mtpndd_get_denom(a);
        int64_t nb = mtpndd_get_numer(b), db = mtpndd_get_denom(b);
        if (nb == 0) {
            mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
            return NULL;
        }
        return make_fraction_i64(na * db, da * nb);
    }
    if (mtpndd_is_double_leaf(a) && mtpndd_is_double_leaf(b)) {
        double vb = mtpndd_get_double(b);
        if (vb == 0.0) {
            mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
            return NULL;
        }
        return mtpndd_make_double(mtpndd_get_double(a) / vb);
    }
    return leaf_type_mismatch(__func__, __LINE__);
}

/********************************
 * Manual temp-ref helper
 ********************************/
typedef struct {
    mtpndd_t **items;
    size_t count;
    size_t capacity;
    mtpndd_t *inline_items[16];
} pin_list_t;

static void pin_list_init(pin_list_t *pl) {
    pl->items = pl->inline_items;
    pl->count = 0;
    pl->capacity = 16;
}

static bool pin_list_push(pin_list_t *pl, mtpndd_t *node) {
    if (!node || mtpndd_is_terminal(node)) return true;
    if (pl->count == pl->capacity) {
        size_t new_cap = pl->capacity * 2;
        mtpndd_t **next = NULL;
        if (pl->items == pl->inline_items) {
            next = (mtpndd_t **)malloc(new_cap * sizeof(mtpndd_t *));
            if (next) memcpy(next, pl->inline_items, pl->count * sizeof(mtpndd_t *));
        } else {
            next = (mtpndd_t **)realloc(pl->items, new_cap * sizeof(mtpndd_t *));
        }
        if (!next) return false;
        pl->items = next;
        pl->capacity = new_cap;
    }
    mtpndd_ref(node);
    pl->items[pl->count++] = node;
    return true;
}

static void pin_list_release(pin_list_t *pl) {
    for (size_t i = 0; i < pl->count; ++i) {
        mtpndd_deref(pl->items[i]);
    }
    pl->count = 0;
    if (pl->items != pl->inline_items) {
        free(pl->items);
        pl->items = pl->inline_items;
        pl->capacity = 16;
    }
}

// Push a node whose +1 ref is already owned by the caller (no extra ref).
// Used by parallel merge paths: the sub-task hands us a ref, we transfer it
// into the pin list so it is released after mtpndd_mk runs.
static bool pin_list_push_owned(pin_list_t *pl, mtpndd_t *node) {
    if (!node || mtpndd_is_terminal(node)) return true;
    if (pl->count == pl->capacity) {
        size_t new_cap = pl->capacity * 2;
        mtpndd_t **next = NULL;
        if (pl->items == pl->inline_items) {
            next = (mtpndd_t **)malloc(new_cap * sizeof(mtpndd_t *));
            if (next) memcpy(next, pl->inline_items, pl->count * sizeof(mtpndd_t *));
        } else {
            next = (mtpndd_t **)realloc(pl->items, new_cap * sizeof(mtpndd_t *));
        }
        if (!next) return false;
        pl->items = next;
        pl->capacity = new_cap;
    }
    pl->items[pl->count++] = node;
    return true;
}

/********************************
 * Helpers shared by plus/minus/times/divide
 ********************************/

// OR-reduce the labels of an edge map. Returns a sylvan_ref'd BDD that the
// caller must sylvan_deref.
static mtpndd_bdd_t edge_map_label_union(mtpndd_edge_t *edges) {
    mtpndd_bdd_t acc = sylvan_ref(sylvan_false);
    if (!edges || !edges->buckets) return acc;
    size_t bc = edges->bucket_count ? edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t i = 0; i < bc; ++i) {
        edge_bucket_entry_t *e = edges->buckets[i];
        while (e) {
            mtpndd_bdd_t lab = edge_label_load(e);
            mtpndd_bdd_t next = sylvan_ref(sylvan_or(acc, lab));
            sylvan_deref(acc);
            acc = next;
            e = e->next;
        }
    }
    return acc;
}

// Forward declarations for TASK implementations so they can recurse.
TASK_DECL_2(mtpndd_t*, mtpndd_plus_rec, mtpndd_t*, mtpndd_t*);
TASK_DECL_2(mtpndd_t*, mtpndd_minus_rec, mtpndd_t*, mtpndd_t*);
TASK_DECL_2(mtpndd_t*, mtpndd_times_rec, mtpndd_t*, mtpndd_t*);
TASK_DECL_2(mtpndd_t*, mtpndd_divide_rec, mtpndd_t*, mtpndd_t*);
TASK_DECL_1(mtpndd_t*, mtpndd_negate_rec, mtpndd_t*);

/********************************
 * negate (used by minus for B-side residuals)
 ********************************/
TASK_IMPL_1(mtpndd_t*, mtpndd_negate_rec, mtpndd_t*, a) {
    if (!a) return NULL;
    if (mtpndd_is_fraction_leaf(a)) {
        int32_t n = mtpndd_get_numer(a);
        int32_t d = mtpndd_get_denom(a);
        if (n == INT32_MIN) {
            mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
            return NULL;
        }
        return mtpndd_make_fraction(-n, d);
    }
    if (mtpndd_is_double_leaf(a)) {
        return mtpndd_make_double(-mtpndd_get_double(a));
    }
    // Internal: rebuild edges with the same labels but negated children.
    mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
    if (!res_edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    mtpndd_edge_map_init(res_edges);
    pin_list_t pins;
    pin_list_init(&pins);

    size_t bc = a->edges->bucket_count ? a->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t i = 0; i < bc; ++i) {
        edge_bucket_entry_t *e = a->edges->buckets[i];
        while (e) {
            mtpndd_t *neg = mtpndd_negate_rec_CALL(__lace_worker, __lace_dq_head, e->child);
            if (!neg) goto fail;
            if (!pin_list_push(&pins, neg)) goto fail;
            mtpndd_bdd_t lab = edge_label_load(e);
            if (mtpndd_add_edge(res_edges, neg, sylvan_ref(lab)) != MTPNDD_SUCCESS) goto fail;
            e = e->next;
        }
    }

    mtpndd_t *res = NULL;
    mtpndd_mk(a->field_id, res_edges, &res);
    if (!res) {
        mtpndd_edge_map_free(res_edges);
        pin_list_release(&pins);
        return NULL;
    }
    pin_list_release(&pins);
    return res;

fail:
    mtpndd_edge_map_free(res_edges);
    pin_list_release(&pins);
    return NULL;
}

/********************************
 * Parallel plus sub-tasks
 ********************************/
TASK_IMPL_2(mtpndd_arith_item_t, mtpndd_plus_diff_pair,
            edge_bucket_entry_t*, ea,
            mtpndd_t*, other)
{
    mtpndd_arith_item_t out = {0};
    out.status = MTPNDD_SUCCESS;
    out.emit = 0;

    mtpndd_bdd_t la = sylvan_ref(edge_label_load(ea));
    mtpndd_t *child = mtpndd_plus_rec_CALL(__lace_worker, __lace_dq_head, ea->child, other);
    if (!child) {
        sylvan_deref(la);
        out.status = mtpndd_get_last_error().code;
        return out;
    }
    mtpndd_ref(child);

    out.emit = 1;
    out.child = child;
    out.label = la;
    return out;
}

// Merge a sub-task item into the result edge map.  Takes ownership of the
// item's label and pins the item's child into `pins` (transferring the
// +1 ref held by the sub-task).
static mtpndd_error_t merge_arith_item(
        mtpndd_edge_t *res_edges, pin_list_t *pins,
        const mtpndd_arith_item_t *item) {
    if (item->status != MTPNDD_SUCCESS) return item->status;
    if (!item->emit) return MTPNDD_SUCCESS;
    if (!pin_list_push_owned(pins, item->child)) {
        sylvan_deref(item->label);
        mtpndd_deref(item->child);
        return MTPNDD_ERROR_OUT_OF_MEMORY;
    }
    return mtpndd_add_edge(res_edges, item->child, item->label);
}

static void cancel_plus_diff_items(WorkerP *__lace_worker, Task **dq_head_ptr, size_t *pending) {
    while (*pending > 0) {
        (*dq_head_ptr)--;
        Task *t = (Task *)(*dq_head_ptr);
        if (TASK_IS_STOLEN(t)) {
            mtpndd_arith_item_t item = mtpndd_plus_diff_pair_SYNC(__lace_worker, *dq_head_ptr);
            if (item.emit) {
                sylvan_deref(item.label);
                mtpndd_deref(item.child);
            }
        } else {
            lace_drop(__lace_worker, *dq_head_ptr);
        }
        (*pending)--;
    }
}

/********************************
 * Row-level chunked sub-tasks
 ********************************/

// Release all owned refs in a chunk's items (used on failure / after merge).
static void chunk_release_items(mtpndd_arith_chunk_t *chunk) {
    if (!chunk || !chunk->items) return;
    for (uint32_t i = 0; i < chunk->count; ++i) {
        sylvan_deref(chunk->items[i].label);
        mtpndd_deref(chunk->items[i].child);
    }
    free(chunk->items);
    chunk->items = NULL;
    chunk->count = 0;
}

// Merge a chunk into the result edge map.  Transfers ownership of each
// item's +1 child ref into `pins` and the +1 label ref into res_edges.
// Always frees chunk->items before returning (on success or failure).
static mtpndd_error_t merge_arith_chunk(
        mtpndd_edge_t *res_edges, pin_list_t *pins,
        mtpndd_arith_chunk_t *chunk) {
    if (chunk->status != MTPNDD_SUCCESS) {
        // The failing task already cleaned up its items before returning.
        return chunk->status;
    }
    for (uint32_t i = 0; i < chunk->count; ++i) {
        mtpndd_arith_item_t *it = &chunk->items[i];
        if (!pin_list_push_owned(pins, it->child)) {
            sylvan_deref(it->label);
            mtpndd_deref(it->child);
            // Release the remaining items we haven't consumed yet.
            for (uint32_t k = i + 1; k < chunk->count; ++k) {
                sylvan_deref(chunk->items[k].label);
                mtpndd_deref(chunk->items[k].child);
            }
            free(chunk->items);
            chunk->items = NULL;
            chunk->count = 0;
            return MTPNDD_ERROR_OUT_OF_MEMORY;
        }
        mtpndd_error_t st = mtpndd_add_edge(res_edges, it->child, it->label);
        if (st != MTPNDD_SUCCESS) {
            // label already consumed by add_edge on failure? Assume not;
            // conservatively skip derefing label and release the rest.
            for (uint32_t k = i + 1; k < chunk->count; ++k) {
                sylvan_deref(chunk->items[k].label);
                mtpndd_deref(chunk->items[k].child);
            }
            free(chunk->items);
            chunk->items = NULL;
            chunk->count = 0;
            return st;
        }
    }
    free(chunk->items);
    chunk->items = NULL;
    chunk->count = 0;
    return MTPNDD_SUCCESS;
}

TASK_IMPL_2(mtpndd_arith_chunk_t, mtpndd_plus_same_row,
            edge_bucket_entry_t*, ea,
            mtpndd_t*, b)
{
    mtpndd_arith_chunk_t out = { MTPNDD_SUCCESS, 0, NULL };
    size_t b_edge_count = b->edges ? b->edges->edge_count : 0;
    if (b_edge_count == 0) return out;

    out.items = (mtpndd_arith_item_t *)malloc(sizeof(mtpndd_arith_item_t) * b_edge_count);
    if (!out.items) {
        out.status = MTPNDD_ERROR_OUT_OF_MEMORY;
        return out;
    }

    mtpndd_bdd_t la = edge_label_load(ea);
    size_t bc = b->edges->bucket_count ? b->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t j = 0; j < bc; ++j) {
        for (edge_bucket_entry_t *eb = b->edges->buckets[j]; eb; eb = eb->next) {
            mtpndd_bdd_t lb = edge_label_load(eb);
            mtpndd_bdd_t inter = sylvan_ref(sylvan_and(la, lb));
            if (inter == sylvan_false) { sylvan_deref(inter); continue; }

            mtpndd_t *child = mtpndd_plus_rec_CALL(__lace_worker, __lace_dq_head, ea->child, eb->child);
            if (!child) {
                sylvan_deref(inter);
                out.status = mtpndd_get_last_error().code;
                chunk_release_items(&out);
                return out;
            }
            mtpndd_ref(child);

            out.items[out.count].status = MTPNDD_SUCCESS;
            out.items[out.count].emit = 1;
            out.items[out.count].child = child;
            out.items[out.count].label = inter;
            out.count++;
        }
    }
    return out;
}

TASK_IMPL_2(mtpndd_arith_chunk_t, mtpndd_times_same_row,
            edge_bucket_entry_t*, ea,
            mtpndd_t*, b)
{
    mtpndd_arith_chunk_t out = { MTPNDD_SUCCESS, 0, NULL };
    size_t b_edge_count = b->edges ? b->edges->edge_count : 0;
    if (b_edge_count == 0) return out;

    out.items = (mtpndd_arith_item_t *)malloc(sizeof(mtpndd_arith_item_t) * b_edge_count);
    if (!out.items) {
        out.status = MTPNDD_ERROR_OUT_OF_MEMORY;
        return out;
    }

    mtpndd_bdd_t la = edge_label_load(ea);
    size_t bc = b->edges->bucket_count ? b->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t j = 0; j < bc; ++j) {
        for (edge_bucket_entry_t *eb = b->edges->buckets[j]; eb; eb = eb->next) {
            mtpndd_bdd_t lb = edge_label_load(eb);
            mtpndd_bdd_t inter = sylvan_ref(sylvan_and(la, lb));
            if (inter == sylvan_false) { sylvan_deref(inter); continue; }

            mtpndd_t *child = mtpndd_times_rec_CALL(__lace_worker, __lace_dq_head, ea->child, eb->child);
            if (!child) {
                sylvan_deref(inter);
                out.status = mtpndd_get_last_error().code;
                chunk_release_items(&out);
                return out;
            }
            mtpndd_ref(child);

            out.items[out.count].status = MTPNDD_SUCCESS;
            out.items[out.count].emit = 1;
            out.items[out.count].child = child;
            out.items[out.count].label = inter;
            out.count++;
        }
    }
    return out;
}

// Row-level spawn decision: SPAWN a full row only when the inner loop has
// enough work to amortize task overhead.  Also guarded against terminal
// children (trivial recursion).
static inline bool arith_should_spawn_row(mtpndd_t *a_child, mtpndd_t *b) {
    if (lace_workers() <= 1) return false;
    if (mtpndd_is_terminal(a_child)) return false;
    size_t b_edges = b->edges ? b->edges->edge_count : 0;
    return b_edges >= 8;
}

// Cancel pending row SPAWNs on failure: drain and release the items
// any already-completed tasks emitted.
static void cancel_row_tasks_plus(WorkerP *__lace_worker, Task **dq_head_ptr, size_t *pending) {
    while (*pending > 0) {
        (*dq_head_ptr)--;
        Task *t = (Task *)(*dq_head_ptr);
        if (TASK_IS_STOLEN(t)) {
            mtpndd_arith_chunk_t chunk = mtpndd_plus_same_row_SYNC(__lace_worker, *dq_head_ptr);
            chunk_release_items(&chunk);
        } else {
            lace_drop(__lace_worker, *dq_head_ptr);
        }
        (*pending)--;
    }
}

static void cancel_row_tasks_times(WorkerP *__lace_worker, Task **dq_head_ptr, size_t *pending) {
    while (*pending > 0) {
        (*dq_head_ptr)--;
        Task *t = (Task *)(*dq_head_ptr);
        if (TASK_IS_STOLEN(t)) {
            mtpndd_arith_chunk_t chunk = mtpndd_times_same_row_SYNC(__lace_worker, *dq_head_ptr);
            chunk_release_items(&chunk);
        } else {
            lace_drop(__lace_worker, *dq_head_ptr);
        }
        (*pending)--;
    }
}

/********************************
 * Parallel times sub-tasks
 ********************************/
TASK_IMPL_2(mtpndd_arith_item_t, mtpndd_times_diff_pair,
            edge_bucket_entry_t*, ea,
            mtpndd_t*, other)
{
    mtpndd_arith_item_t out = {0};
    out.status = MTPNDD_SUCCESS;
    out.emit = 0;

    mtpndd_bdd_t la = sylvan_ref(edge_label_load(ea));
    mtpndd_t *child = mtpndd_times_rec_CALL(__lace_worker, __lace_dq_head, ea->child, other);
    if (!child) {
        sylvan_deref(la);
        out.status = mtpndd_get_last_error().code;
        return out;
    }
    mtpndd_ref(child);

    out.emit = 1;
    out.child = child;
    out.label = la;
    return out;
}

static void cancel_times_diff_items(WorkerP *__lace_worker, Task **dq_head_ptr, size_t *pending) {
    while (*pending > 0) {
        (*dq_head_ptr)--;
        Task *t = (Task *)(*dq_head_ptr);
        if (TASK_IS_STOLEN(t)) {
            mtpndd_arith_item_t item = mtpndd_times_diff_pair_SYNC(__lace_worker, *dq_head_ptr);
            if (item.emit) {
                sylvan_deref(item.label);
                mtpndd_deref(item.child);
            }
        } else {
            lace_drop(__lace_worker, *dq_head_ptr);
        }
        (*pending)--;
    }
}

/********************************
 * Additive ops (plus / minus): share the residual-handling structure
 ********************************/

typedef enum { OP_PLUS, OP_MINUS } additive_op_t;

// Recursive dispatch helper: invoke plus or minus depending on op.
static mtpndd_t *additive_recurse(
        WorkerP *__lace_worker, Task *__lace_dq_head,
        additive_op_t op, mtpndd_t *x, mtpndd_t *y) {
    if (op == OP_PLUS) {
        return mtpndd_plus_rec_CALL(__lace_worker, __lace_dq_head, x, y);
    } else {
        return mtpndd_minus_rec_CALL(__lace_worker, __lace_dq_head, x, y);
    }
}

// For minus, residual-B adds the negated child. For plus, residual-B adds the
// child as-is.  This helper produces the child to place in the residual-B
// region for each edge of b.
static mtpndd_t *additive_b_residual_child(
        WorkerP *__lace_worker, Task *__lace_dq_head,
        additive_op_t op, mtpndd_t *child_b) {
    if (op == OP_PLUS) return child_b;
    return mtpndd_negate_rec_CALL(__lace_worker, __lace_dq_head, child_b);
}

static mtpndd_t *additive_build(
        WorkerP *__lace_worker, Task *__lace_dq_head,
        additive_op_t op, mtpndd_t *a, mtpndd_t *b) {
    // Same-field or different-field (including leaf vs internal).
    // For leaf-vs-internal, pretend the leaf occupies a virtual field that is
    // deeper than any internal field, so the internal node becomes the "top".
    bool a_leaf = mtpndd_is_leaf(a);
    bool b_leaf = mtpndd_is_leaf(b);

    uint32_t fa = a_leaf ? MTPNDD_LEAF_FIELD_ID : a->field_id;
    uint32_t fb = b_leaf ? MTPNDD_LEAF_FIELD_ID : b->field_id;

    bool same_field = (!a_leaf && !b_leaf && fa == fb);

    mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
    if (!res_edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    mtpndd_edge_map_init(res_edges);
    pin_list_t pins;
    pin_list_init(&pins);

    mtpndd_bdd_t or_a = sylvan_false;
    mtpndd_bdd_t or_b = sylvan_false;
    bool have_or_a = false, have_or_b = false;

    if (same_field) {
        or_a = edge_map_label_union(a->edges); have_or_a = true;
        or_b = edge_map_label_union(b->edges); have_or_b = true;

        // Intersection pairs.
        size_t ba = a->edges->bucket_count ? a->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        size_t bb = b->edges->bucket_count ? b->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t i = 0; i < ba; ++i) {
            for (edge_bucket_entry_t *ea = a->edges->buckets[i]; ea; ea = ea->next) {
                mtpndd_bdd_t la = edge_label_load(ea);
                for (size_t j = 0; j < bb; ++j) {
                    for (edge_bucket_entry_t *eb = b->edges->buckets[j]; eb; eb = eb->next) {
                        mtpndd_bdd_t lb = edge_label_load(eb);
                        mtpndd_bdd_t inter = sylvan_ref(sylvan_and(la, lb));
                        if (inter == sylvan_false) { sylvan_deref(inter); continue; }
                        mtpndd_t *child = additive_recurse(__lace_worker, __lace_dq_head, op, ea->child, eb->child);
                        if (!child) { sylvan_deref(inter); goto fail; }
                        if (!pin_list_push(&pins, child)) { sylvan_deref(inter); goto fail; }
                        if (mtpndd_add_edge(res_edges, child, inter) != MTPNDD_SUCCESS) goto fail;
                    }
                }
            }
        }

        // A-only residuals: label_a AND NOT or_b  →  child_a (a + 0 or a - 0)
        mtpndd_bdd_t not_or_b = sylvan_ref(sylvan_not(or_b));
        for (size_t i = 0; i < ba; ++i) {
            for (edge_bucket_entry_t *ea = a->edges->buckets[i]; ea; ea = ea->next) {
                mtpndd_bdd_t la = edge_label_load(ea);
                mtpndd_bdd_t resA = sylvan_ref(sylvan_and(la, not_or_b));
                if (resA == sylvan_false) { sylvan_deref(resA); continue; }
                if (!pin_list_push(&pins, ea->child)) { sylvan_deref(resA); sylvan_deref(not_or_b); goto fail; }
                if (mtpndd_add_edge(res_edges, ea->child, resA) != MTPNDD_SUCCESS) { sylvan_deref(not_or_b); goto fail; }
            }
        }
        sylvan_deref(not_or_b);

        // B-only residuals: label_b AND NOT or_a
        //  plus:  add (child_b, residual)
        //  minus: add (negate(child_b), residual)
        mtpndd_bdd_t not_or_a = sylvan_ref(sylvan_not(or_a));
        for (size_t j = 0; j < bb; ++j) {
            for (edge_bucket_entry_t *eb = b->edges->buckets[j]; eb; eb = eb->next) {
                mtpndd_bdd_t lb = edge_label_load(eb);
                mtpndd_bdd_t resB = sylvan_ref(sylvan_and(lb, not_or_a));
                if (resB == sylvan_false) { sylvan_deref(resB); continue; }
                mtpndd_t *child = additive_b_residual_child(__lace_worker, __lace_dq_head, op, eb->child);
                if (!child) { sylvan_deref(resB); sylvan_deref(not_or_a); goto fail; }
                if (!pin_list_push(&pins, child)) { sylvan_deref(resB); sylvan_deref(not_or_a); goto fail; }
                if (mtpndd_add_edge(res_edges, child, resB) != MTPNDD_SUCCESS) { sylvan_deref(not_or_a); goto fail; }
            }
        }
        sylvan_deref(not_or_a);
    } else {
        // Different fields (or one is a leaf). Whichever operand has the
        // smaller field_id becomes the "top"; the other is passed through
        // unchanged at each recursive step.  If both sides are leaves, this
        // branch is not reached (handled by caller fast-path).
        bool a_is_top = (fa < fb);
        mtpndd_t *top = a_is_top ? a : b;
        mtpndd_t *other = a_is_top ? b : a;
        // Ordering matters for minus: a - b.
        //   If a is top: child' = op(top_child, other)
        //   If b is top: child' = op(other, top_child)
        // For additive_b_residual_child, "b residual" corresponds to the b
        // side of the original operands, i.e. `b` — which may be `top` or
        // `other`. Handle carefully.

        or_a = edge_map_label_union(top->edges); have_or_a = true;

        size_t bt = top->edges->bucket_count ? top->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t i = 0; i < bt; ++i) {
            for (edge_bucket_entry_t *e = top->edges->buckets[i]; e; e = e->next) {
                mtpndd_t *x = a_is_top ? e->child : other;
                mtpndd_t *y = a_is_top ? other : e->child;
                mtpndd_t *child = additive_recurse(__lace_worker, __lace_dq_head, op, x, y);
                if (!child) goto fail;
                if (!pin_list_push(&pins, child)) goto fail;
                mtpndd_bdd_t lab = edge_label_load(e);
                if (mtpndd_add_edge(res_edges, child, sylvan_ref(lab)) != MTPNDD_SUCCESS) goto fail;
            }
        }

        // Residual region of `top`: NOT or_a → the other operand contributes.
        // For plus: op(0, other) = other  OR  op(other, 0) = other → add `other`.
        // For minus with a on top: op(0, other) = -other on residual region
        //                          (because 0 - other = -other)
        //              with b on top: op(other, 0) = other
        mtpndd_bdd_t not_or_top = sylvan_ref(sylvan_not(or_a));
        if (not_or_top != sylvan_false) {
            mtpndd_t *residual_child = NULL;
            if (op == OP_PLUS) {
                residual_child = other;
            } else {
                // OP_MINUS
                if (a_is_top) {
                    // a is top; residual region: a = 0, b = other → 0 - other = -other
                    residual_child = mtpndd_negate_rec_CALL(__lace_worker, __lace_dq_head, other);
                    if (!residual_child) { sylvan_deref(not_or_top); goto fail; }
                    if (!pin_list_push(&pins, residual_child)) { sylvan_deref(not_or_top); goto fail; }
                } else {
                    // b is top; residual region: a = other, b = 0 → other - 0 = other
                    residual_child = other;
                }
            }
            if (!pin_list_push(&pins, residual_child)) { sylvan_deref(not_or_top); goto fail; }
            if (mtpndd_add_edge(res_edges, residual_child, not_or_top) != MTPNDD_SUCCESS) goto fail;
        } else {
            sylvan_deref(not_or_top);
        }
    }

    if (have_or_a) sylvan_deref(or_a);
    if (have_or_b) sylvan_deref(or_b);

    uint32_t target_field;
    if (a_leaf) target_field = b->field_id;
    else if (b_leaf) target_field = a->field_id;
    else target_field = (fa < fb) ? fa : fb;

    mtpndd_t *res = NULL;
    mtpndd_mk(target_field, res_edges, &res);
    if (!res) {
        mtpndd_edge_map_free(res_edges);
        pin_list_release(&pins);
        return NULL;
    }
    pin_list_release(&pins);
    return res;

fail:
    if (have_or_a) sylvan_deref(or_a);
    if (have_or_b) sylvan_deref(or_b);
    mtpndd_edge_map_free(res_edges);
    pin_list_release(&pins);
    return NULL;
}

/********************************
 * plus (parallel)
 ********************************/
TASK_IMPL_2(mtpndd_t*, mtpndd_plus_rec, mtpndd_t*, a, mtpndd_t*, b) {
    if (!a || !b) return NULL;
    if (a == &MTPNDD_FALSE) return b;                           // 0 + x = x
    if (b == &MTPNDD_FALSE) return a;
    if (mtpndd_is_leaf(a) && mtpndd_is_leaf(b)) {
        return leaf_add(a, b);
    }

    // Cache lookup: plus reuses or_cache.
    mtpndd_node_t *ka = a, *kb = b;
    if ((uintptr_t)ka > (uintptr_t)kb) { mtpndd_node_t *t = ka; ka = kb; kb = t; }
    mtpndd_op_cache_t *cache = g_mtpndd_config.or_cache;
    mtpndd_node_t *cached = mtpndd_op_cache_lookup_binary_op(cache, ka, kb, 1);
    if (cached) return cached;

    bool a_leaf = mtpndd_is_leaf(a);
    bool b_leaf = mtpndd_is_leaf(b);
    bool same_field = (!a_leaf && !b_leaf && a->field_id == b->field_id);

    mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
    if (!res_edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    mtpndd_edge_map_init(res_edges);
    pin_list_t pins;
    pin_list_init(&pins);
    mtpndd_error_t status = MTPNDD_SUCCESS;
    size_t pending = 0;
    mtpndd_bdd_t or_a = sylvan_false, or_b = sylvan_false;
    bool have_or_a = false, have_or_b = false;

    if (same_field) {
        // Row-level parallelism: SPAWN one task per A-entry, each handles
        // the full B inner loop inline.  Reduces task count from |A|*|B|
        // to |A| and amortizes Lace overhead across the inner work.
        size_t ba = a->edges->bucket_count ? a->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t i = 0; i < ba; ++i) {
            for (edge_bucket_entry_t *ea = a->edges->buckets[i]; ea; ea = ea->next) {
                if (arith_should_spawn_row(ea->child, b)) {
                    mtpndd_plus_same_row_SPAWN(__lace_worker, __lace_dq_head, ea, b);
                    __lace_dq_head++;
                    pending++;
                } else {
                    mtpndd_arith_chunk_t chunk = mtpndd_plus_same_row_CALL(__lace_worker, __lace_dq_head, ea, b);
                    status = merge_arith_chunk(res_edges, &pins, &chunk);
                    if (status != MTPNDD_SUCCESS) goto fail_same;
                }
                if (pending >= MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD) {
                    size_t keep = MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD / 2;
                    size_t to_drain = pending - keep;
                    while (to_drain-- > 0) {
                        __lace_dq_head--;
                        mtpndd_arith_chunk_t chunk = mtpndd_plus_same_row_SYNC(__lace_worker, __lace_dq_head);
                        pending--;
                        status = merge_arith_chunk(res_edges, &pins, &chunk);
                        if (status != MTPNDD_SUCCESS) goto fail_same;
                    }
                }
            }
        }
        // Drain remaining.
        while (pending > 0) {
            __lace_dq_head--;
            mtpndd_arith_chunk_t chunk = mtpndd_plus_same_row_SYNC(__lace_worker, __lace_dq_head);
            pending--;
            status = merge_arith_chunk(res_edges, &pins, &chunk);
            if (status != MTPNDD_SUCCESS) goto fail_same;
        }

        // A-only and B-only residuals: pure BDD work, sequential.
        or_a = edge_map_label_union(a->edges); have_or_a = true;
        or_b = edge_map_label_union(b->edges); have_or_b = true;

        mtpndd_bdd_t not_or_b = sylvan_ref(sylvan_not(or_b));
        for (size_t i = 0; i < ba; ++i) {
            for (edge_bucket_entry_t *ea = a->edges->buckets[i]; ea; ea = ea->next) {
                mtpndd_bdd_t la = edge_label_load(ea);
                mtpndd_bdd_t resA = sylvan_ref(sylvan_and(la, not_or_b));
                if (resA == sylvan_false) { sylvan_deref(resA); continue; }
                if (!pin_list_push(&pins, ea->child)) {
                    sylvan_deref(resA); sylvan_deref(not_or_b); goto fail_build;
                }
                if (mtpndd_add_edge(res_edges, ea->child, resA) != MTPNDD_SUCCESS) {
                    sylvan_deref(not_or_b); goto fail_build;
                }
            }
        }
        sylvan_deref(not_or_b);

        mtpndd_bdd_t not_or_a = sylvan_ref(sylvan_not(or_a));
        size_t bb_resid = b->edges->bucket_count ? b->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t j = 0; j < bb_resid; ++j) {
            for (edge_bucket_entry_t *eb = b->edges->buckets[j]; eb; eb = eb->next) {
                mtpndd_bdd_t lb = edge_label_load(eb);
                mtpndd_bdd_t resB = sylvan_ref(sylvan_and(lb, not_or_a));
                if (resB == sylvan_false) { sylvan_deref(resB); continue; }
                if (!pin_list_push(&pins, eb->child)) {
                    sylvan_deref(resB); sylvan_deref(not_or_a); goto fail_build;
                }
                if (mtpndd_add_edge(res_edges, eb->child, resB) != MTPNDD_SUCCESS) {
                    sylvan_deref(not_or_a); goto fail_build;
                }
            }
        }
        sylvan_deref(not_or_a);
        goto build_ok;

    fail_same:
        cancel_row_tasks_plus(__lace_worker, &__lace_dq_head, &pending);
        goto fail_build;
    } else {
        // Different fields (or one operand is a leaf).
        uint32_t fa = a_leaf ? MTPNDD_LEAF_FIELD_ID : a->field_id;
        uint32_t fb = b_leaf ? MTPNDD_LEAF_FIELD_ID : b->field_id;
        bool a_is_top = (fa < fb);
        mtpndd_t *top = a_is_top ? a : b;
        mtpndd_t *other = a_is_top ? b : a;

        or_a = edge_map_label_union(top->edges); have_or_a = true;

        size_t bt = top->edges->bucket_count ? top->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t i = 0; i < bt; ++i) {
            for (edge_bucket_entry_t *e = top->edges->buckets[i]; e; e = e->next) {
                if (arith_should_spawn(e->child, other)) {
                    mtpndd_plus_diff_pair_SPAWN(__lace_worker, __lace_dq_head, e, other);
                    __lace_dq_head++;
                    pending++;
                } else {
                    mtpndd_arith_item_t item = mtpndd_plus_diff_pair_CALL(__lace_worker, __lace_dq_head, e, other);
                    status = merge_arith_item(res_edges, &pins, &item);
                    if (status != MTPNDD_SUCCESS) goto fail_diff;
                }
                if (pending >= MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD) {
                    size_t keep = MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD / 2;
                    size_t to_drain = pending - keep;
                    while (to_drain-- > 0) {
                        __lace_dq_head--;
                        mtpndd_arith_item_t item = mtpndd_plus_diff_pair_SYNC(__lace_worker, __lace_dq_head);
                        pending--;
                        status = merge_arith_item(res_edges, &pins, &item);
                        if (status != MTPNDD_SUCCESS) goto fail_diff;
                    }
                }
            }
        }
        while (pending > 0) {
            __lace_dq_head--;
            mtpndd_arith_item_t item = mtpndd_plus_diff_pair_SYNC(__lace_worker, __lace_dq_head);
            pending--;
            status = merge_arith_item(res_edges, &pins, &item);
            if (status != MTPNDD_SUCCESS) goto fail_diff;
        }

        // Residual of top's support: NOT or_a → plus(0, other) = other.
        mtpndd_bdd_t not_or_top = sylvan_ref(sylvan_not(or_a));
        if (not_or_top != sylvan_false) {
            if (!pin_list_push(&pins, other)) {
                sylvan_deref(not_or_top); goto fail_build;
            }
            if (mtpndd_add_edge(res_edges, other, not_or_top) != MTPNDD_SUCCESS) {
                goto fail_build;
            }
        } else {
            sylvan_deref(not_or_top);
        }
        goto build_ok;

    fail_diff:
        cancel_plus_diff_items(__lace_worker, &__lace_dq_head, &pending);
        goto fail_build;
    }

build_ok:
    if (have_or_a) sylvan_deref(or_a);
    if (have_or_b) sylvan_deref(or_b);

    {
        uint32_t target_field = a_leaf ? b->field_id
                               : b_leaf ? a->field_id
                               : (a->field_id < b->field_id ? a->field_id : b->field_id);
        mtpndd_t *res = NULL;
        mtpndd_mk(target_field, res_edges, &res);
        if (!res) {
            mtpndd_edge_map_free(res_edges);
            pin_list_release(&pins);
            return NULL;
        }
        pin_list_release(&pins);
        mtpndd_op_cache_store_binary_op(cache, ka, kb, res, 1);
        return res;
    }

fail_build:
    if (have_or_a) sylvan_deref(or_a);
    if (have_or_b) sylvan_deref(or_b);
    mtpndd_edge_map_free(res_edges);
    pin_list_release(&pins);
    return NULL;
}

/********************************
 * minus
 ********************************/
TASK_IMPL_2(mtpndd_t*, mtpndd_minus_rec, mtpndd_t*, a, mtpndd_t*, b) {
    if (!a || !b) return NULL;
    if (b == &MTPNDD_FALSE) return a;          // a - 0 = a
    if (a == &MTPNDD_FALSE) {                  // 0 - b = -b
        return mtpndd_negate_rec_CALL(__lace_worker, __lace_dq_head, b);
    }
    if (a == b) return &MTPNDD_FALSE;          // a - a = 0
    if (mtpndd_is_leaf(a) && mtpndd_is_leaf(b)) {
        return leaf_sub(a, b);
    }
    // minus is not cached in phase 1 (non-commutative, no Boolean analog).
    return additive_build(__lace_worker, __lace_dq_head, OP_MINUS, a, b);
}

/********************************
 * times (parallel)
 ********************************/
TASK_IMPL_2(mtpndd_t*, mtpndd_times_rec, mtpndd_t*, a, mtpndd_t*, b) {
    if (!a || !b) return NULL;
    if (a == &MTPNDD_FALSE || b == &MTPNDD_FALSE) return &MTPNDD_FALSE;
    if (a == &MTPNDD_TRUE) return b;
    if (b == &MTPNDD_TRUE) return a;
    if (mtpndd_is_leaf(a) && mtpndd_is_leaf(b)) {
        return leaf_mul(a, b);
    }
    // Cache (reuses and_cache).
    mtpndd_node_t *ka = a, *kb = b;
    if ((uintptr_t)ka > (uintptr_t)kb) { mtpndd_node_t *t = ka; ka = kb; kb = t; }
    mtpndd_op_cache_t *cache = g_mtpndd_config.and_cache;
    mtpndd_node_t *cached = mtpndd_op_cache_lookup_binary_op(cache, ka, kb, 1);
    if (cached) return cached;

    bool a_leaf = mtpndd_is_leaf(a);
    bool b_leaf = mtpndd_is_leaf(b);
    bool same_field = (!a_leaf && !b_leaf && a->field_id == b->field_id);

    mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
    if (!res_edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    mtpndd_edge_map_init(res_edges);
    pin_list_t pins;
    pin_list_init(&pins);
    mtpndd_error_t status = MTPNDD_SUCCESS;
    size_t pending = 0;

    if (same_field) {
        // Row-level chunked SPAWN (see mtpndd_plus_rec for rationale).
        size_t ba = a->edges->bucket_count ? a->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t i = 0; i < ba; ++i) {
            for (edge_bucket_entry_t *ea = a->edges->buckets[i]; ea; ea = ea->next) {
                if (arith_should_spawn_row(ea->child, b)) {
                    mtpndd_times_same_row_SPAWN(__lace_worker, __lace_dq_head, ea, b);
                    __lace_dq_head++;
                    pending++;
                } else {
                    mtpndd_arith_chunk_t chunk = mtpndd_times_same_row_CALL(__lace_worker, __lace_dq_head, ea, b);
                    status = merge_arith_chunk(res_edges, &pins, &chunk);
                    if (status != MTPNDD_SUCCESS) goto times_fail_same;
                }
                if (pending >= MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD) {
                    size_t keep = MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD / 2;
                    size_t to_drain = pending - keep;
                    while (to_drain-- > 0) {
                        __lace_dq_head--;
                        mtpndd_arith_chunk_t chunk = mtpndd_times_same_row_SYNC(__lace_worker, __lace_dq_head);
                        pending--;
                        status = merge_arith_chunk(res_edges, &pins, &chunk);
                        if (status != MTPNDD_SUCCESS) goto times_fail_same;
                    }
                }
            }
        }
        while (pending > 0) {
            __lace_dq_head--;
            mtpndd_arith_chunk_t chunk = mtpndd_times_same_row_SYNC(__lace_worker, __lace_dq_head);
            pending--;
            status = merge_arith_chunk(res_edges, &pins, &chunk);
            if (status != MTPNDD_SUCCESS) goto times_fail_same;
        }
        goto times_build_ok;

    times_fail_same:
        cancel_row_tasks_times(__lace_worker, &__lace_dq_head, &pending);
        goto times_fail;
    } else {
        uint32_t fa = a_leaf ? MTPNDD_LEAF_FIELD_ID : a->field_id;
        uint32_t fb = b_leaf ? MTPNDD_LEAF_FIELD_ID : b->field_id;
        bool a_is_top = (fa < fb);
        mtpndd_t *top = a_is_top ? a : b;
        mtpndd_t *other = a_is_top ? b : a;

        size_t bt = top->edges->bucket_count ? top->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t i = 0; i < bt; ++i) {
            for (edge_bucket_entry_t *e = top->edges->buckets[i]; e; e = e->next) {
                if (arith_should_spawn(e->child, other)) {
                    mtpndd_times_diff_pair_SPAWN(__lace_worker, __lace_dq_head, e, other);
                    __lace_dq_head++;
                    pending++;
                } else {
                    mtpndd_arith_item_t item = mtpndd_times_diff_pair_CALL(__lace_worker, __lace_dq_head, e, other);
                    status = merge_arith_item(res_edges, &pins, &item);
                    if (status != MTPNDD_SUCCESS) goto times_fail_diff;
                }
                if (pending >= MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD) {
                    size_t keep = MTPNDD_ARITH_PENDING_FLUSH_THRESHOLD / 2;
                    size_t to_drain = pending - keep;
                    while (to_drain-- > 0) {
                        __lace_dq_head--;
                        mtpndd_arith_item_t item = mtpndd_times_diff_pair_SYNC(__lace_worker, __lace_dq_head);
                        pending--;
                        status = merge_arith_item(res_edges, &pins, &item);
                        if (status != MTPNDD_SUCCESS) goto times_fail_diff;
                    }
                }
            }
        }
        while (pending > 0) {
            __lace_dq_head--;
            mtpndd_arith_item_t item = mtpndd_times_diff_pair_SYNC(__lace_worker, __lace_dq_head);
            pending--;
            status = merge_arith_item(res_edges, &pins, &item);
            if (status != MTPNDD_SUCCESS) goto times_fail_diff;
        }
        goto times_build_ok;

    times_fail_diff:
        cancel_times_diff_items(__lace_worker, &__lace_dq_head, &pending);
        goto times_fail;
    }

times_build_ok:
    {
        uint32_t target_field = a_leaf ? b->field_id
                               : b_leaf ? a->field_id
                               : (a->field_id < b->field_id ? a->field_id : b->field_id);
        mtpndd_t *res = NULL;
        mtpndd_mk(target_field, res_edges, &res);
        if (!res) {
            mtpndd_edge_map_free(res_edges);
            pin_list_release(&pins);
            return NULL;
        }
        pin_list_release(&pins);
        mtpndd_op_cache_store_binary_op(cache, ka, kb, res, 1);
        return res;
    }

times_fail:
    mtpndd_edge_map_free(res_edges);
    pin_list_release(&pins);
    return NULL;
}

/********************************
 * divide
 ********************************/
TASK_IMPL_2(mtpndd_t*, mtpndd_divide_rec, mtpndd_t*, a, mtpndd_t*, b) {
    if (!a || !b) return NULL;
    // Division by zero: b can be zero either as MTPNDD_FALSE or as a zero
    // fraction leaf.  Either case at the top level is an error.
    if (b == &MTPNDD_FALSE) {
        mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
        return NULL;
    }
    if (a == &MTPNDD_FALSE) return &MTPNDD_FALSE;     // 0 / b (b != 0) = 0
    if (b == &MTPNDD_TRUE) return a;                  // a / 1 = a
    if (a == b) return &MTPNDD_TRUE;                  // a / a = 1 (assumes a != 0 in support)
    if (mtpndd_is_leaf(a) && mtpndd_is_leaf(b)) {
        return leaf_div(a, b);
    }

    // divide is not cached in phase 1 (non-commutative, no Boolean analog,
    // and error-prone to cache incorrectly).
    bool a_leaf = mtpndd_is_leaf(a);
    bool b_leaf = mtpndd_is_leaf(b);

    mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
    if (!res_edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    mtpndd_edge_map_init(res_edges);
    pin_list_t pins;
    pin_list_init(&pins);
    mtpndd_t *res = NULL;

    if (!a_leaf && !b_leaf && a->field_id == b->field_id) {
        // Same field.  For intersection pairs recurse; outside b's support we
        // would be dividing by zero (unless a is also zero there).  Require
        // that b's labels cover the full universe at this level — otherwise
        // report error.
        mtpndd_bdd_t or_b = edge_map_label_union(b->edges);
        mtpndd_bdd_t not_or_b = sylvan_ref(sylvan_not(or_b));
        sylvan_deref(or_b);
        // Check that (a is also zero wherever b is zero). With the current
        // representation the "a zero" region is NOT or_a, so we need
        // not_or_b AND or_a == false, i.e. or_a covers not_or_b?  Actually
        // we want: wherever b = 0, a is also 0. That means (a's covered
        // region AND not_or_b) must be empty.
        mtpndd_bdd_t or_a = edge_map_label_union(a->edges);
        mtpndd_bdd_t conflict = sylvan_ref(sylvan_and(or_a, not_or_b));
        sylvan_deref(or_a);
        sylvan_deref(not_or_b);
        if (conflict != sylvan_false) {
            sylvan_deref(conflict);
            mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
            goto divide_fail;
        }
        sylvan_deref(conflict);

        size_t ba = a->edges->bucket_count ? a->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        size_t bb = b->edges->bucket_count ? b->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t i = 0; i < ba; ++i) {
            for (edge_bucket_entry_t *ea = a->edges->buckets[i]; ea; ea = ea->next) {
                mtpndd_bdd_t la = edge_label_load(ea);
                for (size_t j = 0; j < bb; ++j) {
                    for (edge_bucket_entry_t *eb = b->edges->buckets[j]; eb; eb = eb->next) {
                        mtpndd_bdd_t lb = edge_label_load(eb);
                        mtpndd_bdd_t inter = sylvan_ref(sylvan_and(la, lb));
                        if (inter == sylvan_false) { sylvan_deref(inter); continue; }
                        mtpndd_t *child = mtpndd_divide_rec_CALL(__lace_worker, __lace_dq_head, ea->child, eb->child);
                        if (!child) { sylvan_deref(inter); goto divide_fail; }
                        if (!pin_list_push(&pins, child)) { sylvan_deref(inter); goto divide_fail; }
                        if (mtpndd_add_edge(res_edges, child, inter) != MTPNDD_SUCCESS) goto divide_fail;
                    }
                }
            }
        }
    } else {
        // a or b (or both) at different field / leaf. The top walks; the
        // other stays constant at each step.
        uint32_t fa = a_leaf ? MTPNDD_LEAF_FIELD_ID : a->field_id;
        uint32_t fb = b_leaf ? MTPNDD_LEAF_FIELD_ID : b->field_id;
        bool a_is_top = (fa < fb);
        mtpndd_t *top = a_is_top ? a : b;
        mtpndd_t *other = a_is_top ? b : a;

        // If b is the top: its residual (uncovered) region is division by 0.
        // If a is the top: its residual region is 0/b = 0, so we need to add
        // edge (FALSE, residual_label).  But mtpndd_mk would drop 0-edges
        // anyway via the label-covered-by-child collapsing; the simpler fix
        // is to skip adding zero edges (they're implicit).
        if (!a_is_top) {
            // b is top — check residual is empty
            mtpndd_bdd_t or_top = edge_map_label_union(top->edges);
            mtpndd_bdd_t not_or_top = sylvan_ref(sylvan_not(or_top));
            sylvan_deref(or_top);
            if (not_or_top != sylvan_false) {
                sylvan_deref(not_or_top);
                mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
                goto divide_fail;
            }
            sylvan_deref(not_or_top);
        }

        size_t bt = top->edges->bucket_count ? top->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t i = 0; i < bt; ++i) {
            for (edge_bucket_entry_t *e = top->edges->buckets[i]; e; e = e->next) {
                mtpndd_t *x = a_is_top ? e->child : other;
                mtpndd_t *y = a_is_top ? other : e->child;
                mtpndd_t *child = mtpndd_divide_rec_CALL(__lace_worker, __lace_dq_head, x, y);
                if (!child) goto divide_fail;
                if (!pin_list_push(&pins, child)) goto divide_fail;
                mtpndd_bdd_t lab = edge_label_load(e);
                if (mtpndd_add_edge(res_edges, child, sylvan_ref(lab)) != MTPNDD_SUCCESS) goto divide_fail;
            }
        }
    }

    {
        uint32_t target_field = a_leaf ? b->field_id
                               : b_leaf ? a->field_id
                               : (a->field_id < b->field_id ? a->field_id : b->field_id);
        mtpndd_mk(target_field, res_edges, &res);
    }
    if (!res) {
        mtpndd_edge_map_free(res_edges);
        pin_list_release(&pins);
        return NULL;
    }
    pin_list_release(&pins);
    return res;

divide_fail:
    mtpndd_edge_map_free(res_edges);
    pin_list_release(&pins);
    return NULL;
}

/********************************
 * Public entry points
 ********************************/
mtpndd_t *mtpndd_plus(mtpndd_t *a, mtpndd_t *b) {
    if (!mtpndd_is_initialized()) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED);
        return NULL;
    }
    if (!a || !b) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_NULL_POINTER);
        return NULL;
    }
    return RUN(mtpndd_plus_rec, a, b);
}

mtpndd_t *mtpndd_minus(mtpndd_t *a, mtpndd_t *b) {
    if (!mtpndd_is_initialized()) { MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED); return NULL; }
    if (!a || !b) { MTPNDD_SET_ERROR(MTPNDD_ERROR_NULL_POINTER); return NULL; }
    return RUN(mtpndd_minus_rec, a, b);
}

mtpndd_t *mtpndd_times(mtpndd_t *a, mtpndd_t *b) {
    if (!mtpndd_is_initialized()) { MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED); return NULL; }
    if (!a || !b) { MTPNDD_SET_ERROR(MTPNDD_ERROR_NULL_POINTER); return NULL; }
    return RUN(mtpndd_times_rec, a, b);
}

mtpndd_t *mtpndd_divide(mtpndd_t *a, mtpndd_t *b) {
    if (!mtpndd_is_initialized()) { MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED); return NULL; }
    if (!a || !b) { MTPNDD_SET_ERROR(MTPNDD_ERROR_NULL_POINTER); return NULL; }
    return RUN(mtpndd_divide_rec, a, b);
}

/********************************
 * abstract_plus
 *
 * Sums out `target` field from the structure.  Assumes that any internal
 * node's edge labels only depend on that node's own field's BDD vars
 * (the natural construction from build_indicator / mtbdd_to_mtpndd).
 ********************************/

TASK_DECL_2(mtpndd_t*, mtpndd_abstract_plus_rec, mtpndd_t*, uint32_t);

// Match scalar weights to the subtree's numeric domain. Boolean sentinels
// alone use fraction arithmetic; zeros do not determine a nonzero domain.
static int numeric_domain(mtpndd_t *node) {
    if (node == &MTPNDD_FALSE || node == &MTPNDD_TRUE) return 0;
    if (mtpndd_is_double_leaf(node)) return 2;
    if (mtpndd_is_fraction_leaf(node)) return 1;
    size_t bc = node->edges->bucket_count ? node->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t i = 0; i < bc; ++i)
        for (edge_bucket_entry_t *e = node->edges->buckets[i]; e; e = e->next) {
            int domain = numeric_domain(e->child);
            if (domain) return domain;
        }
    return 0;
}
static mtpndd_t *count_weight(mtpndd_t *node, double count) {
    if (!isfinite(count) || count < 0 || floor(count) != count) {
        mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__); return NULL;
    }
    if (numeric_domain(node) == 2) return mtpndd_make_double(count);
    if (count > INT32_MAX) {
        mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__); return NULL;
    }
    return mtpndd_make_fraction((int32_t)count, 1);
}
static mtpndd_t *abstract_scale_factor(uint32_t target, mtpndd_t *node) {
    if (target == 0 || target > g_mtpndd_config.field_count) {
        mtpndd_set_error(MTPNDD_ERROR_INVALID_FIELD, __func__, __LINE__); return NULL;
    }
    uint32_t bits = g_mtpndd_config.pending_field_bit_widths[target - 1];
    return count_weight(node, ldexp(1.0, bits));
}

TASK_IMPL_2(mtpndd_t*, mtpndd_abstract_plus_rec, mtpndd_t*, node, uint32_t, target) {
    if (!node) return NULL;
    if (node == &MTPNDD_FALSE) return node;

    if (mtpndd_is_terminal(node)) {
        // Leaf reached without target being processed yet → constant value
        // multiplied by the count of σ_target assignments (2^target_bits).
        mtpndd_t *factor = abstract_scale_factor(target, node);
        if (!factor) return NULL;
        return mtpndd_times_rec_CALL(__lace_worker, __lace_dq_head, node, factor);
    }

    if (node->field_id > target) {
        // Target sits between an ancestor and this node — it was implicitly
        // skipped.  Multiply this whole subtree by 2^target_bits.
        mtpndd_t *factor = abstract_scale_factor(target, node);
        if (!factor) return NULL;
        return mtpndd_times_rec_CALL(__lace_worker, __lace_dq_head, node, factor);
    }

    if (node->field_id == target) {
        // Collapse this level.  For each edge (child, label), the contribution
        // is `child * |label|` where |label| is the count of σ_target
        // assignments satisfying label.  Sum all contributions.
        uint32_t target_bits = g_mtpndd_config.pending_field_bit_widths[target - 1];
        mtpndd_t *acc = &MTPNDD_FALSE;
        // No need to ref MTPNDD_FALSE — it's protected.

        size_t bc = node->edges->bucket_count
                ? node->edges->bucket_count
                : g_mtpndd_pal_config.edge_bucket_count;
        for (size_t i = 0; i < bc; ++i) {
            for (edge_bucket_entry_t *e = node->edges->buckets[i]; e; e = e->next) {
                mtpndd_bdd_t lab = edge_label_load(e);
                double count_d = (lab == sylvan_true)
                        ? ldexp(1.0, target_bits)
                        : mtbdd_satcount(lab, target_bits);
                mtpndd_t *weight = count_weight(e->child, count_d);
                if (!weight) {
                    if (acc != &MTPNDD_FALSE) mtpndd_deref(acc);
                    return NULL;
                }
                mtpndd_t *weighted = mtpndd_times_rec_CALL(__lace_worker, __lace_dq_head, e->child, weight);
                if (!weighted) {
                    if (acc != &MTPNDD_FALSE) mtpndd_deref(acc);
                    return NULL;
                }
                mtpndd_ref(weighted);
                mtpndd_t *new_acc = mtpndd_plus_rec_CALL(__lace_worker, __lace_dq_head, acc, weighted);
                mtpndd_deref(weighted);
                if (!new_acc) {
                    if (acc != &MTPNDD_FALSE) mtpndd_deref(acc);
                    return NULL;
                }
                if (acc != &MTPNDD_FALSE) mtpndd_deref(acc);
                mtpndd_ref(new_acc);
                acc = new_acc;
            }
        }
        // Transfer acc's ref to caller (caller will deref).
        // Returning a ref'd node is unusual in this codebase; instead, deref
        // and rely on the leaf table / nodetable keeping it alive.
        // After mtpndd_op_cache stores or upstream consumers add it, the ref
        // count may go to 0 but the next GC will collect it. For safety in
        // the meantime, the node should be either pinned by the caller's
        // pin_list or referenced by a structure.
        // We deref here; nodes returned by abstract_plus must be pinned by
        // the caller (the public entry point handles this).
        if (acc != &MTPNDD_FALSE) mtpndd_deref(acc);
        return acc;
    }

    // node->field_id < target: recurse into children, preserving structure.
    mtpndd_edge_t *res_edges = mtpndd_memory_acquire_edge_map();
    if (!res_edges) {
        mtpndd_set_error(MTPNDD_ERROR_OUT_OF_MEMORY, __func__, __LINE__);
        return NULL;
    }
    mtpndd_edge_map_init(res_edges);
    pin_list_t pins;
    pin_list_init(&pins);

    size_t bc = node->edges->bucket_count
            ? node->edges->bucket_count
            : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t i = 0; i < bc; ++i) {
        for (edge_bucket_entry_t *e = node->edges->buckets[i]; e; e = e->next) {
            mtpndd_t *new_child = mtpndd_abstract_plus_rec_CALL(__lace_worker, __lace_dq_head, e->child, target);
            if (!new_child) goto fail;
            if (!pin_list_push(&pins, new_child)) goto fail;
            mtpndd_bdd_t lab = edge_label_load(e);
            if (mtpndd_add_edge(res_edges, new_child, sylvan_ref(lab)) != MTPNDD_SUCCESS) goto fail;
        }
    }

    {
        mtpndd_t *res = NULL;
        mtpndd_mk(node->field_id, res_edges, &res);
        if (!res) {
            mtpndd_edge_map_free(res_edges);
            pin_list_release(&pins);
            return NULL;
        }
        pin_list_release(&pins);
        return res;
    }

fail:
    mtpndd_edge_map_free(res_edges);
    pin_list_release(&pins);
    return NULL;
}

mtpndd_t *mtpndd_abstract_plus(mtpndd_t *root, uint32_t field_id) {
    if (!mtpndd_is_initialized()) { MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED); return NULL; }
    if (!root) { MTPNDD_SET_ERROR(MTPNDD_ERROR_NULL_POINTER); return NULL; }
    if (field_id == 0 || field_id > g_mtpndd_config.field_count) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_FIELD);
        return NULL;
    }
    return RUN(mtpndd_abstract_plus_rec, root, field_id);
}

/********************************
 * abstract_plus validator
 *
 * Walks the DAG rooted at `root` and checks that every internal node's
 * edge labels only use BDD vars belonging to that node's own field.  This
 * is the implicit invariant that mtpndd_abstract_plus relies on — if it
 * does not hold, the collapse step's label-satcount weighting is wrong.
 ********************************/

typedef struct validate_entry_s {
    struct validate_entry_s *next;
    mtpndd_t *node;
} validate_entry_t;

typedef struct {
    size_t bucket_count;
    validate_entry_t **buckets;
} validate_visited_t;

static bool validate_visited_init(validate_visited_t *vs, size_t bucket_count) {
    vs->bucket_count = bucket_count;
    vs->buckets = (validate_entry_t **)calloc(bucket_count, sizeof(validate_entry_t *));
    return vs->buckets != NULL;
}

static void validate_visited_free(validate_visited_t *vs) {
    if (!vs->buckets) return;
    for (size_t i = 0; i < vs->bucket_count; ++i) {
        validate_entry_t *e = vs->buckets[i];
        while (e) {
            validate_entry_t *next = e->next;
            free(e);
            e = next;
        }
    }
    free(vs->buckets);
    vs->buckets = NULL;
}

// Returns true if newly inserted.
static bool validate_visited_mark(validate_visited_t *vs, mtpndd_t *node) {
    size_t h = mtpndd_hash_node_identity(node) & (vs->bucket_count - 1);
    for (validate_entry_t *e = vs->buckets[h]; e; e = e->next) {
        if (e->node == node) return false;
    }
    validate_entry_t *e = (validate_entry_t *)malloc(sizeof(*e));
    if (!e) return false;
    e->node = node;
    e->next = vs->buckets[h];
    vs->buckets[h] = e;
    return true;
}

// Walk a support cube (BDD) and confirm every variable falls in
// [start_var, start_var + bit_width).  A cube is a conjunction of positive
// literals; each level has var, low=false, high=next-or-true.
static bool support_within_range(mtpndd_bdd_t support, uint32_t start_var, uint32_t bit_width) {
    while (support != sylvan_true) {
        if (support == sylvan_false) return true;  // empty support
        uint32_t var = sylvan_var(support);
        if (var < start_var || var >= start_var + bit_width) {
            return false;
        }
        support = sylvan_high(support);
    }
    return true;
}

TASK_DECL_2(int, mtpndd_validate_rec, mtpndd_t*, validate_visited_t*);
TASK_IMPL_2(int, mtpndd_validate_rec, mtpndd_t*, node, validate_visited_t*, vs) {
    if (!node) return 1;
    if (mtpndd_is_terminal(node)) return 1;
    if (!validate_visited_mark(vs, node)) return 1;  // already visited

    mtpndd_field_info_t *info = mtpndd_get_field_info(node->field_id);
    if (!info) {
        mtpndd_set_error(MTPNDD_ERROR_INVALID_FIELD, __func__, __LINE__);
        return 0;
    }
    uint32_t start_var = info->start_var;
    uint32_t bit_width = info->bit_width;

    if (!node->edges || !node->edges->buckets) return 1;
    size_t bc = node->edges->bucket_count
            ? node->edges->bucket_count
            : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t i = 0; i < bc; ++i) {
        for (edge_bucket_entry_t *e = node->edges->buckets[i]; e; e = e->next) {
            mtpndd_bdd_t lab = edge_label_load(e);
            if (lab == sylvan_true || lab == sylvan_false) {
                // Trivially within range.
            } else {
                mtpndd_bdd_t support = CALL(mtbdd_support, lab);
                if (!support_within_range(support, start_var, bit_width)) {
                    mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
                    return 0;
                }
            }
            int child_ok = mtpndd_validate_rec_CALL(__lace_worker, __lace_dq_head, e->child, vs);
            if (!child_ok) return 0;
        }
    }
    return 1;
}

bool mtpndd_abstract_plus_validate(mtpndd_t *root) {
    if (!mtpndd_is_initialized()) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED);
        return false;
    }
    if (!root) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_NULL_POINTER);
        return false;
    }
    validate_visited_t vs;
    if (!validate_visited_init(&vs, 1024)) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        return false;
    }
    int ok = RUN(mtpndd_validate_rec, root, &vs);
    validate_visited_free(&vs);
    return ok != 0;
}

/********************************
 * leafcount
 ********************************/
typedef struct lc_entry_s {
    struct lc_entry_s *next;
    mtpndd_t *node;
} lc_entry_t;

typedef struct {
    size_t bucket_count;
    lc_entry_t **buckets;
    size_t leaf_count;
} lc_set_t;

static bool lc_set_init(lc_set_t *s, size_t bucket_count) {
    s->bucket_count = bucket_count;
    s->buckets = (lc_entry_t **)calloc(bucket_count, sizeof(lc_entry_t *));
    s->leaf_count = 0;
    return s->buckets != NULL;
}

static void lc_set_free(lc_set_t *s) {
    for (size_t i = 0; i < s->bucket_count; ++i) {
        lc_entry_t *e = s->buckets[i];
        while (e) {
            lc_entry_t *next = e->next;
            free(e);
            e = next;
        }
    }
    free(s->buckets);
}

// Returns true if newly inserted (caller should recurse / count).
static bool lc_set_insert(lc_set_t *s, mtpndd_t *node) {
    size_t h = mtpndd_hash_node_identity(node) & (s->bucket_count - 1);
    for (lc_entry_t *e = s->buckets[h]; e; e = e->next) {
        if (e->node == node) return false;
    }
    lc_entry_t *e = (lc_entry_t *)malloc(sizeof(*e));
    if (!e) return false;  // on OOM we silently stop — count will be a lower bound
    e->node = node;
    e->next = s->buckets[h];
    s->buckets[h] = e;
    return true;
}

static void lc_walk(lc_set_t *s, mtpndd_t *node) {
    if (!node) return;
    if (!lc_set_insert(s, node)) return;
    if (mtpndd_is_terminal(node)) {
        s->leaf_count++;
        return;
    }
    if (!node->edges || !node->edges->buckets) return;
    size_t bc = node->edges->bucket_count ? node->edges->bucket_count : g_mtpndd_pal_config.edge_bucket_count;
    for (size_t i = 0; i < bc; ++i) {
        for (edge_bucket_entry_t *e = node->edges->buckets[i]; e; e = e->next) {
            lc_walk(s, e->child);
        }
    }
}

size_t mtpndd_leafcount(mtpndd_t *root) {
    if (!root) return 0;
    lc_set_t set;
    if (!lc_set_init(&set, 1024)) return 0;
    lc_walk(&set, root);
    size_t result = set.leaf_count;
    lc_set_free(&set);
    return result;
}
