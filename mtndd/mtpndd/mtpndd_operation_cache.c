// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#include "mtpndd_operation_cache.h"
#include "mtpndd_common.h"
#include "mtpndd_node.h"  /* for inlined mtpndd_hash_node_identity */

#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <stdint.h>

static const size_t kMtpnddOpCacheMinSize = 1024;
static size_t mtpndd_op_cache_adjust_size(size_t requested) {
    size_t capacity = 1;
    while (capacity < requested) {
        if (capacity > (SIZE_MAX >> 1)) {
            capacity = SIZE_MAX & ~(SIZE_MAX >> 1);
            break;
        }
        capacity <<= 1;
    }
    return capacity ? capacity : kMtpnddOpCacheMinSize;
}

static mtpndd_op_cache_t *mtpndd_op_cache_create(size_t requested_size, uint8_t arity) {
    size_t capacity = mtpndd_op_cache_adjust_size(requested_size);
    mtpndd_op_cache_t *cache = (mtpndd_op_cache_t *)malloc(sizeof(mtpndd_op_cache_t));
    if (!cache) {
        return NULL;
    }

    cache->entries = (mtpndd_op_cache_entry_t *)calloc(capacity, sizeof(mtpndd_op_cache_entry_t));
    if (!cache->entries) {
        free(cache);
        return NULL;
    }

    for (size_t i = 0; i < capacity; ++i) {
        atomic_init(&cache->entries[i].stamp, 0);
        atomic_init(&cache->entries[i].operands[0], NULL);
        atomic_init(&cache->entries[i].operands[1], NULL);
        atomic_init(&cache->entries[i].result, NULL);
    }
    cache->capacity = capacity;
    cache->mask = capacity - 1;
    cache->arity = arity;
    return cache;
}

static void mtpndd_op_cache_release(mtpndd_op_cache_t *cache) {
    if (!cache) {
        return;
    }
    free(cache->entries);
    free(cache);
}

static inline size_t mtpndd_op_cache_hash_binary_index(const mtpndd_op_cache_t *cache, const mtpndd_node_t *lhs, const mtpndd_node_t *rhs) {
    uint64_t h1 = mtpndd_hash_node_identity(lhs);
    uint64_t h2 = mtpndd_hash_node_identity(rhs);
    uint64_t hash = h1 ^ (h2 + 0x9e3779b97f4a7c15ULL + (h1 << 6) + (h1 >> 2));
    return (size_t)(hash & cache->mask);
}

static inline size_t mtpndd_op_cache_hash_unary_index(const mtpndd_op_cache_t *cache, const mtpndd_node_t *operand) {
    uint64_t hash = mtpndd_hash_node_identity(operand);
    hash ^= hash >> 33;
    hash *= 0xff51afd7ed558ccdULL;
    hash ^= hash >> 33;
    hash *= 0xc4ceb9fe1a85ec53ULL;
    hash ^= hash >> 33;
    return (size_t)(hash & cache->mask);
}

bool mtpndd_op_cache_initialize(
        size_t size,
        mtpndd_op_cache_t **and_cache,
        mtpndd_op_cache_t **or_cache,
        mtpndd_op_cache_t **not_cache)
{
    mtpndd_op_cache_t *andc = mtpndd_op_cache_create(size, 2);
    if (!andc) {
        return false;
    }

    mtpndd_op_cache_t *orc = mtpndd_op_cache_create(size, 2);
    if (!orc) {
        mtpndd_op_cache_release(andc);
        return false;
    }

    mtpndd_op_cache_t *notc = mtpndd_op_cache_create(size, 1);
    if (!notc) {
        mtpndd_op_cache_release(andc);
        mtpndd_op_cache_release(orc);
        return false;
    }

    *and_cache = andc;
    *or_cache = orc;
    *not_cache = notc;
    return true;
}

void mtpndd_op_cache_destroy() {
    mtpndd_op_cache_release(g_mtpndd_config.and_cache);
    mtpndd_op_cache_release(g_mtpndd_config.or_cache);
    mtpndd_op_cache_release(g_mtpndd_config.not_cache);
    g_mtpndd_config.and_cache = NULL;
    g_mtpndd_config.or_cache = NULL;
    g_mtpndd_config.not_cache = NULL;
}


#define CACHE_BUSY UINT64_C(256)
#define CACHE_VERSION_MASK (~UINT64_C(511))

static void publish(mtpndd_op_cache_entry_t *e, mtpndd_node_t *a,
                    mtpndd_node_t *b, mtpndd_node_t *r, uint8_t op, bool wait) {
    uint64_t old = atomic_load_explicit(&e->stamp, memory_order_acquire);
    for (;;) {
        if ((old & CACHE_VERSION_MASK) == CACHE_VERSION_MASK) {
            // Disable on exhaustion instead of allowing ABA through version wrap.
            if (wait) atomic_fetch_or_explicit(&e->stamp, CACHE_BUSY, memory_order_release);
            return;
        }
        if (old & CACHE_BUSY) {
            if (!wait) return;
            old = atomic_load_explicit(&e->stamp, memory_order_acquire); continue;
        }
        if (atomic_compare_exchange_weak_explicit(&e->stamp, &old, old | CACHE_BUSY,
                memory_order_acq_rel, memory_order_acquire)) break;
        if (!wait) return;
    }
    // If a reader observes any new payload, its acquire fence also observes
    // BUSY before its second stamp read (C11 release/acquire fence rule).
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&e->operands[0], a, memory_order_relaxed);
    atomic_store_explicit(&e->operands[1], b, memory_order_relaxed);
    atomic_store_explicit(&e->result, r, memory_order_relaxed);
    atomic_store_explicit(&e->stamp, ((old & CACHE_VERSION_MASK) + 512) | op,
                          memory_order_release);
}

static mtpndd_node_t *lookup(mtpndd_op_cache_entry_t *e, mtpndd_node_t *a,
                             mtpndd_node_t *b, uint8_t op) {
    uint64_t before = atomic_load_explicit(&e->stamp, memory_order_acquire);
    if ((before & CACHE_BUSY) || (before & 255) != op) return NULL;
    mtpndd_node_t *ea = atomic_load_explicit(&e->operands[0], memory_order_relaxed);
    mtpndd_node_t *eb = atomic_load_explicit(&e->operands[1], memory_order_relaxed);
    mtpndd_node_t *r = atomic_load_explicit(&e->result, memory_order_relaxed);
    atomic_thread_fence(memory_order_acquire);
    uint64_t after = atomic_load_explicit(&e->stamp, memory_order_relaxed);
    bool hit = before == after && ea == a && eb == b && r;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    if (hit) __atomic_add_fetch(&g_mtpndd_stats.cache_lookup_hits, 1, __ATOMIC_RELAXED);
    else __atomic_add_fetch(&g_mtpndd_stats.cache_lookup_misses, 1, __ATOMIC_RELAXED);
#endif
    return hit ? r : NULL;
}

void mtpndd_op_cache_clear(mtpndd_op_cache_t *cache) {
    if (!cache) return;
    // Reclamation itself still requires the engine's GC quiescence protocol.
    for (size_t i = 0; i < cache->capacity; ++i)
        publish(&cache->entries[i], NULL, NULL, NULL, 0, true);
}
mtpndd_node_t *mtpndd_op_cache_lookup_binary_op(mtpndd_op_cache_t *c, mtpndd_node_t *a, mtpndd_node_t *b, uint8_t op) {
    if (!c || c->arity != 2 || !c->capacity) return NULL;
    return lookup(&c->entries[mtpndd_op_cache_hash_binary_index(c, a, b)], a, b, op);
}
void mtpndd_op_cache_store_binary_op(mtpndd_op_cache_t *c, mtpndd_node_t *a, mtpndd_node_t *b, mtpndd_node_t *r, uint8_t op) {
    if (!c || c->arity != 2 || !c->capacity || !r) return;
    publish(&c->entries[mtpndd_op_cache_hash_binary_index(c, a, b)], a, b, r, op, false);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    __atomic_add_fetch(&g_mtpndd_stats.cache_store_total, 1, __ATOMIC_RELAXED);
#endif
}
mtpndd_node_t *mtpndd_op_cache_lookup_binary(mtpndd_op_cache_t *c, mtpndd_node_t *a, mtpndd_node_t *b) {
    return mtpndd_op_cache_lookup_binary_op(c, a, b, 0);
}
void mtpndd_op_cache_store_binary(mtpndd_op_cache_t *c, mtpndd_node_t *a, mtpndd_node_t *b, mtpndd_node_t *r) {
    mtpndd_op_cache_store_binary_op(c, a, b, r, 0);
}
mtpndd_node_t *mtpndd_op_cache_lookup_unary(mtpndd_op_cache_t *c, mtpndd_node_t *a) {
    if (!c || c->arity != 1 || !c->capacity) return NULL;
    return lookup(&c->entries[mtpndd_op_cache_hash_unary_index(c, a)], a, NULL, 0);
}
void mtpndd_op_cache_store_unary(mtpndd_op_cache_t *c, mtpndd_node_t *a, mtpndd_node_t *r) {
    if (!c || c->arity != 1 || !c->capacity || !r) return;
    publish(&c->entries[mtpndd_op_cache_hash_unary_index(c, a)], a, NULL, r, 0, false);
}
