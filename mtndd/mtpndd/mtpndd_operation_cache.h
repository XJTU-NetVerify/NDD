// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#ifndef MTPNDD_OPERATION_CACHE_H
#define MTPNDD_OPERATION_CACHE_H

#include <stddef.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdatomic.h>

struct mtpndd_node_s;
typedef struct mtpndd_node_s mtpndd_node_t;

// Versioned atomic publication; cache misses never wait for a writer.
typedef struct mtpndd_op_cache_entry_s {
    _Atomic uint64_t stamp; // low 8 bits: operation; bit 8: BUSY; upper 55: version
    _Atomic(mtpndd_node_t *) operands[2];
    _Atomic(mtpndd_node_t *) result;
} mtpndd_op_cache_entry_t;

typedef struct mtpndd_op_cache_s {
    mtpndd_op_cache_entry_t *entries;
    size_t capacity;
    size_t mask;
    uint8_t arity;
} mtpndd_op_cache_t;

bool mtpndd_op_cache_initialize(
        size_t size,
        mtpndd_op_cache_t **and_cache,
        mtpndd_op_cache_t **or_cache,
        mtpndd_op_cache_t **not_cache);
void mtpndd_op_cache_destroy();
void mtpndd_op_cache_clear(mtpndd_op_cache_t *cache);
mtpndd_node_t *mtpndd_op_cache_lookup_binary(mtpndd_op_cache_t *cache, mtpndd_node_t *lhs, mtpndd_node_t *rhs);
void mtpndd_op_cache_store_binary(mtpndd_op_cache_t *cache, mtpndd_node_t *lhs, mtpndd_node_t *rhs, mtpndd_node_t *result);
mtpndd_node_t *mtpndd_op_cache_lookup_unary(mtpndd_op_cache_t *cache, mtpndd_node_t *operand);
void mtpndd_op_cache_store_unary(mtpndd_op_cache_t *cache, mtpndd_node_t *operand, mtpndd_node_t *result);

/* 0 = Boolean within its AND/OR cache; 1 = corresponding TIMES/PLUS operation. */
mtpndd_node_t *mtpndd_op_cache_lookup_binary_op(mtpndd_op_cache_t *, mtpndd_node_t *, mtpndd_node_t *, uint8_t);
void mtpndd_op_cache_store_binary_op(mtpndd_op_cache_t *, mtpndd_node_t *, mtpndd_node_t *, mtpndd_node_t *, uint8_t);
#endif // MTPNDD_OPERATION_CACHE_H
