// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#ifndef MTPNDD_LEAF_H
#define MTPNDD_LEAF_H

#include <stdbool.h>
#include <stdint.h>
#include "mtpndd_common.h"

// Phase 1 only stores fraction leaves.  The packed value layout is:
//   hi32  : int32_t  numerator  (signed)
//   lo32  : uint32_t denominator (always > 0 after normalization)
// Canonical form: denom > 0, gcd(|numer|, denom) == 1, 0 is stored as 0/1.

static inline uint64_t mtpndd_pack_fraction(int32_t numer, uint32_t denom) {
    return (((uint64_t)(uint32_t)numer) << 32) | (uint64_t)denom;
}

static inline int32_t mtpndd_unpack_numer(uint64_t packed) {
    return (int32_t)(uint32_t)(packed >> 32);
}

static inline uint32_t mtpndd_unpack_denom(uint64_t packed) {
    return (uint32_t)(packed & 0xFFFFFFFFu);
}

// Create (or look up) the canonical fraction leaf for numer/denom.
// Returns NULL on invalid input (denom == 0) or OOM.
// Sets last error accordingly.
mtpndd_t *mtpndd_make_fraction(int32_t numer, int32_t denom);

// Create (or look up) the canonical double leaf.  Distinct IEEE bit
// patterns (including +/-0, NaN variants) produce distinct nodes; the
// caller is responsible for pre-canonicalizing if that matters.
mtpndd_t *mtpndd_make_double(double value);

bool    mtpndd_is_leaf(const mtpndd_t *node);
bool    mtpndd_is_fraction_leaf(const mtpndd_t *node);
bool    mtpndd_is_double_leaf(const mtpndd_t *node);

int32_t mtpndd_get_numer(const mtpndd_t *node);
int32_t mtpndd_get_denom(const mtpndd_t *node);
double  mtpndd_get_double(const mtpndd_t *node);

// Returns the packed leaf_value for a leaf, or 0 for non-leaves.
uint64_t mtpndd_get_leaf_value(const mtpndd_t *node);

#endif // MTPNDD_LEAF_H
