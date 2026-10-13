// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#include "mtpndd_leaf.h"
#include "mtpndd_leaf_table.h"
#include "mtpndd_node.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// g_mtpndd_config.leaf_table lives in mtpndd_common.
extern mtpndd_leaf_table_t *mtpndd_get_leaf_table(void);
extern mtpndd_leaf_table_t *mtpndd_get_double_leaf_table(void);

static uint32_t gcd_u32(uint32_t a, uint32_t b) {
    while (b != 0) {
        uint32_t t = a % b;
        a = b;
        b = t;
    }
    return a;
}

// Normalize (n, d) into canonical form with d > 0, gcd(|n|, d) = 1.
// Returns false if d == 0.  On success writes canonical values to *out_n/*out_d.
static bool normalize_fraction(int32_t n, int32_t d, int32_t *out_n, uint32_t *out_d) {
    if (d == 0) return false;

    // Move sign to the numerator.
    if (d < 0) {
        // Guard against d == INT32_MIN (cannot negate).
        if (d == INT32_MIN) {
            // d == -2^31 — scale down by 2 so we can negate safely.
            // n/(-2^31) == (-n)/2^31, but -n may also overflow if n == INT32_MIN.
            // In that degenerate case the value is 1 (INT32_MIN / INT32_MIN),
            // but we cannot represent the negation. Report invalid.
            if (n == INT32_MIN) return false;
            n = -n;
            // Hoist out the factor of two.
            // d was -2^31; after sign flip that's 2^31 which does not fit in int32_t.
            // Store as uint32_t directly.
            if (n == 0) {
                *out_n = 0;
                *out_d = 1;
                return true;
            }
            uint32_t abs_n = (n < 0) ? (uint32_t)(-(int64_t)n) : (uint32_t)n;
            uint32_t ud = (uint32_t)INT32_MAX + 1u; // 2^31
            uint32_t g = gcd_u32(abs_n, ud);
            abs_n /= g;
            ud /= g;
            if (abs_n > (uint32_t)INT32_MAX) return false;
            *out_n = (n < 0) ? -(int32_t)abs_n : (int32_t)abs_n;
            *out_d = ud;
            return true;
        }
        if (n == INT32_MIN) return false;
        n = -n;
        d = -d;
    }

    if (n == 0) {
        *out_n = 0;
        *out_d = 1;
        return true;
    }

    uint32_t abs_n = (n < 0) ? (uint32_t)(-(int64_t)n) : (uint32_t)n;
    uint32_t ud = (uint32_t)d;
    uint32_t g = gcd_u32(abs_n, ud);
    abs_n /= g;
    ud /= g;
    if (abs_n > (uint32_t)INT32_MAX) return false;
    *out_n = (n < 0) ? -(int32_t)abs_n : (int32_t)abs_n;
    *out_d = ud;
    return true;
}

mtpndd_t *mtpndd_make_fraction(int32_t numer, int32_t denom) {
    int32_t n;
    uint32_t d;
    if (!normalize_fraction(numer, denom, &n, &d)) {
        mtpndd_set_error(MTPNDD_ERROR_INVALID_PARAM, __func__, __LINE__);
        return NULL;
    }
    uint64_t packed = mtpndd_pack_fraction(n, d);
    mtpndd_leaf_table_t *table = mtpndd_get_leaf_table();
    if (!table) {
        mtpndd_set_error(MTPNDD_ERROR_NOT_INITIALIZED, __func__, __LINE__);
        return NULL;
    }
    return mtpndd_leaf_table_lookup_or_insert(
            table, MTPNDD_FRACTION_LEAF_FIELD_ID, packed);
}

mtpndd_t *mtpndd_make_double(double value) {
    mtpndd_leaf_table_t *table = mtpndd_get_double_leaf_table();
    if (!table) {
        mtpndd_set_error(MTPNDD_ERROR_NOT_INITIALIZED, __func__, __LINE__);
        return NULL;
    }
    uint64_t packed;
    memcpy(&packed, &value, sizeof(packed));
    return mtpndd_leaf_table_lookup_or_insert(
            table, MTPNDD_DOUBLE_LEAF_FIELD_ID, packed);
}

bool mtpndd_is_leaf(const mtpndd_t *node) {
    return node && node->field_id >= MTPNDD_LEAF_FIELD_ID_MIN;
}

bool mtpndd_is_fraction_leaf(const mtpndd_t *node) {
    return node && node->field_id == MTPNDD_FRACTION_LEAF_FIELD_ID;
}

bool mtpndd_is_double_leaf(const mtpndd_t *node) {
    return node && node->field_id == MTPNDD_DOUBLE_LEAF_FIELD_ID;
}

int32_t mtpndd_get_numer(const mtpndd_t *node) {
    if (!mtpndd_is_fraction_leaf(node)) return 0;
    return mtpndd_unpack_numer(node->leaf_value);
}

int32_t mtpndd_get_denom(const mtpndd_t *node) {
    if (!mtpndd_is_fraction_leaf(node)) return 0;
    return (int32_t)mtpndd_unpack_denom(node->leaf_value);
}

double mtpndd_get_double(const mtpndd_t *node) {
    if (!mtpndd_is_double_leaf(node)) return 0.0;
    double v;
    memcpy(&v, &node->leaf_value, sizeof(v));
    return v;
}

uint64_t mtpndd_get_leaf_value(const mtpndd_t *node) {
    if (!mtpndd_is_leaf(node)) return 0;
    return node->leaf_value;
}
