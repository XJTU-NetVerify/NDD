#include "mtpndd.h"
#include <pthread.h>
#include <assert.h>
#include <stdint.h>

// Every tuple deliberately collides in a single slot. Results are reused,
// exercising the case that pointer-before/after publication cannot detect.
static mtpndd_node_t nodes[18];
static mtpndd_op_cache_entry_t entry;
static mtpndd_op_cache_t cache = { .entries = &entry, .capacity = 1, .mask = 0, .arity = 2 };
static void *writer(void *arg) {
    uintptr_t id = (uintptr_t)arg;
    mtpndd_node_t *a = &nodes[2 * id], *b = &nodes[2 * id + 1], *r = &nodes[16 + id % 2];
    for (unsigned i = 0; i < 100000; ++i) {
        uint8_t op = i % 2;
        mtpndd_op_cache_store_binary_op(&cache, a, b, r, op);
        mtpndd_node_t *got = mtpndd_op_cache_lookup_binary_op(&cache, a, b, op);
        assert(!got || got == r);
    }
    return NULL;
}
int main(void) {
    pthread_t threads[6];
    for (uintptr_t i = 0; i < 6; ++i) assert(pthread_create(&threads[i], NULL, writer, (void *)i) == 0);
    for (unsigned i = 0; i < 6; ++i) assert(pthread_join(threads[i], NULL) == 0);
    mtpndd_op_cache_store_binary_op(&cache, &nodes[0], &nodes[1], &nodes[16], 1);
    assert(mtpndd_op_cache_lookup_binary_op(&cache, &nodes[0], &nodes[1], 1) == &nodes[16]);
    assert(!mtpndd_op_cache_lookup_binary(&cache, &nodes[0], &nodes[1]));
    mtpndd_op_cache_clear(&cache);
    assert(!mtpndd_op_cache_lookup_binary_op(&cache, &nodes[0], &nodes[1], 1));
    atomic_store(&entry.stamp, ~UINT64_C(511));
    mtpndd_op_cache_clear(&cache);
    mtpndd_op_cache_clear(&cache); // no spin on an exhausted, disabled slot
    mtpndd_op_cache_store_binary(&cache, &nodes[0], &nodes[1], &nodes[16]);
    assert(!mtpndd_op_cache_lookup_binary(&cache, &nodes[0], &nodes[1]));
    return 0;
}
