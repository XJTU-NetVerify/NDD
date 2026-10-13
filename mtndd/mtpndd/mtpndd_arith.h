// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab
//
// Arithmetic operations over MT (fraction-leaf) MTPNDDs.  Phase 1 is
// sequential — the implementation uses TASK_IMPL only so that it can call
// Sylvan BDD primitives, but does not SPAWN sub-tasks.

#ifndef MTPNDD_ARITH_H
#define MTPNDD_ARITH_H

#include <stddef.h>
#include "mtpndd_common.h"

// All four operations return NULL on error (overflow, divide-by-zero, OOM).
// The last-error state is updated via MTPNDD_SET_ERROR.
mtpndd_t *mtpndd_plus(mtpndd_t *a, mtpndd_t *b);
mtpndd_t *mtpndd_minus(mtpndd_t *a, mtpndd_t *b);
mtpndd_t *mtpndd_times(mtpndd_t *a, mtpndd_t *b);
mtpndd_t *mtpndd_divide(mtpndd_t *a, mtpndd_t *b);

// Count of distinct terminal (leaf) nodes reachable from `root`, including
// MTPNDD_TRUE / MTPNDD_FALSE if they appear in the support.
size_t mtpndd_leafcount(mtpndd_t *root);

// Sum-out (abstract) one field from `root`.  Semantics:
//   abstract_plus(f, k)(σ_rest) = sum over σ_k of f(σ_rest, σ_k)
// Assumes labels at any node's field only depend on that field's BDD vars
// (the natural construction from build_indicator / mtbdd_to_mtpndd).
// Returns NULL on overflow, OOM, or invalid field_id.
mtpndd_t *mtpndd_abstract_plus(mtpndd_t *root, uint32_t field_id);

// Validate the implicit invariant that every internal node's edge labels
// only depend on its own field's BDD variables.  abstract_plus relies on
// this; violators give silently wrong results.  Returns true iff the
// invariant holds.  On violation, sets last-error to MTPNDD_ERROR_INVALID_PARAM
// (with context pointing at the offending field).  Complexity is
// O(|DAG nodes|) plus one sylvan_support per distinct edge.
bool mtpndd_abstract_plus_validate(mtpndd_t *root);

#endif // MTPNDD_ARITH_H
