#include "pndd.h"
#include <sylvan_int.h>
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
static unsigned rng = 7;
static unsigned random_u(void) { rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return rng; }
static int bool_op(int op, int a, int b) {
    switch (op) { case PNDD_AND: return a && b; case PNDD_OR: return a || b; case PNDD_DIFF: return a && !b; case PNDD_XOR: return a != b; case PNDD_IMP: return !a || b; case PNDD_EQUIV: return a == b; case PNDD_NAND: return !(a && b); default: return !(a || b); }
}
static void exercise(unsigned workers, int mixed, pndd_backend backend) {
    pndd_field fields[] = {{2, backend}, {1, backend}, {2, backend}, {1, backend}};
    if (mixed) for (int i = 0; i < 4; ++i) fields[i].backend = (pndd_backend)i;
    pndd_config config = pndd_default_config(); config.workers = workers; config.cache_entries = 4096; config.gc_interval = 7; config.label_parallel = 1;
    config.task_threshold = 2; config.chunk_size = 1; config.spawn_depth = 6; config.memory_bytes = 16u * 1024 * 1024;
    pndd_engine *e = pndd_open(&config, fields, 4); assert(e);
    assert(!pndd_open(&config, fields, 4));
    pndd_handle roots[128]; unsigned char truth[128][64]; size_t size = 0;
    for (int f = 0; f < 4; ++f) for (unsigned bit = 0; bit < fields[f].width; ++bit) {
        roots[size] = pndd_literal(e, f, bit, 1); assert(roots[size] != PNDD_INVALID);
        for (unsigned v = 0; v < 64; ++v) { uint64_t values[] = {v & 3, (v >> 2) & 1, (v >> 3) & 3, (v >> 5) & 1}; truth[size][v] = (values[f] >> (fields[f].width - 1 - bit)) & 1; }
        ++size;
    }
    for (unsigned i = 0; i < 70; ++i) {
        unsigned a = random_u() % size, b = random_u() % size; int op = (int)(i % 8);
        roots[size] = pndd_apply(e, (pndd_op)op, roots[a], roots[b]); assert(roots[size] != PNDD_INVALID);
        unsigned count = 0;
        for (unsigned v = 0; v < 64; ++v) {
            uint64_t values[] = {v & 3, (v >> 2) & 1, (v >> 3) & 3, (v >> 5) & 1};
            truth[size][v] = bool_op(op, truth[a][v], truth[b][v]); count += truth[size][v];
            assert(pndd_evaluate(e, roots[size], values, 4) == truth[size][v]);
        }
        assert(pndd_satcount(e, roots[size]) == count); ++size;
        if (i % 11 == 0) assert(pndd_collect(e));
    }
    for (unsigned f = 0; f < 4; ++f) {
        pndd_handle x = pndd_exist(e, roots[size - 1], f); assert(x != PNDD_INVALID);
        unsigned count = 0;
        for (unsigned v = 0; v < 64; ++v) {
            uint64_t values[] = {v & 3, (v >> 2) & 1, (v >> 3) & 3, (v >> 5) & 1}; int expected = 0;
            for (unsigned val = 0; val < (1u << fields[f].width); ++val) {
                uint64_t changed[4]; for (int j = 0; j < 4; ++j) changed[j] = values[j]; changed[f] = val;
                unsigned index = (unsigned)(changed[0] | (changed[1] << 2) | (changed[2] << 3) | (changed[3] << 5)); expected |= truth[size - 1][index];
            }
            assert(pndd_evaluate(e, x, values, 4) == expected); count += expected;
        }
        assert(pndd_satcount(e, x) == count); assert(pndd_release(e, x));
    }
    pndd_handle batch[32]; assert(pndd_apply_batch(e, PNDD_DIFF, roots, roots + 16, 32, batch));
    for (unsigned i = 0; i < 32; ++i) {
        pndd_handle direct = pndd_apply(e, PNDD_DIFF, roots[i], roots[i + 16]); assert(batch[i] == direct);
        pndd_release(e, batch[i]); pndd_release(e, direct);
    }
    pndd_handle left = pndd_reduce(e, PNDD_OR, roots, size, 0), tree = pndd_reduce(e, PNDD_OR, roots, size, 1);
    assert(left == tree); pndd_release(e, left); pndd_release(e, tree);
    for (size_t i = 0; i < size; ++i) pndd_release(e, roots[i]);
    pndd_handle value = pndd_value(e, 0, 2), inverse = pndd_not(e, value); assert(pndd_satcount(e, value) == 16); assert(pndd_satcount(e, inverse) == 48);
    assert(pndd_release(e, inverse)); assert(pndd_release(e, value)); assert(pndd_collect(e));
    assert(isnan(pndd_satcount(e, value)));
    assert(pndd_literal(e, 0, 2, 1) == PNDD_INVALID);
    pndd_close(e);
    e = pndd_open(&config, fields, 4); assert(e); assert(isnan(pndd_satcount(e, value))); pndd_close(e);
}
static void pressure(unsigned workers, pndd_backend backend) {
    pndd_field field = {16, backend}; pndd_config c = pndd_default_config();
    c.workers = workers; c.memory_bytes = 16u * 1024 * 1024; c.cache_entries = 4096;
    c.gc_interval = 0; c.label_parallel = 1; c.task_threshold = 2; c.chunk_size = 1;
    pndd_engine *e = pndd_open(&c, &field, 1); assert(e);
    enum { CASES = 2048 };
    pndd_handle a[CASES], b[CASES], out[CASES];
    /* Adjacent points share a long prefix. Their unions allocate fresh label
     * paths while tasks are active, rather than only one new top-level node. */
    for (unsigned i = 0; i < CASES; ++i) { a[i] = pndd_value(e, 0, i * 26); b[i] = pndd_value(e, 0, i * 26 + 1); assert(a[i] != PNDD_INVALID && b[i] != PNDD_INVALID); }
    for (unsigned round = 0; round < 4; ++round) {
        uint64_t before = pndd_get_stats(e).label_collections;
        size_t table_before = llmsset_get_size(nodes); assert(pndd_apply_batch(e, PNDD_OR, a, b, CASES, out));
        fprintf(stderr,"workers=%u backend=%d round=%u GC=%llu->%llu table=%zu->%zu\n",workers,backend,round,(unsigned long long)before,(unsigned long long)pndd_get_stats(e).label_collections,table_before,llmsset_get_size(nodes));
        for (unsigned i = 0; i < CASES; ++i) { assert(pndd_satcount(e, out[i]) == 2); assert(pndd_release(e, out[i])); }
        assert(pndd_collect(e));
    }
    for (unsigned i = 0; i < CASES; ++i) { pndd_release(e, a[i]); pndd_release(e, b[i]); }
    assert(pndd_collect(e)); assert(pndd_get_stats(e).label_collections > 0); pndd_close(e);
}
static void memory_failure(unsigned workers) {
    pndd_field fields[10]; for (int i = 0; i < 10; ++i) fields[i] = (pndd_field){10, PNDD_BDD};
    pndd_config c = pndd_default_config(); c.workers = workers; c.memory_bytes = 4u * 1024 * 1024; c.cache_entries = 1024;
    pndd_engine *e = pndd_open(&c, fields, 10); assert(e);
    assert(isnan(pndd_nqueens(e, 10, 0, 0))); assert(pndd_collect(e));
    pndd_handle a = pndd_value(e, 0, 3); assert(a != PNDD_INVALID); assert(pndd_satcount(e, a) == ldexp(1, 90)); pndd_release(e, a); pndd_close(e);
}
static void label_exhaustion(unsigned workers, pndd_backend backend) {
    pndd_field field = {64, backend}; pndd_config c = pndd_default_config();
    c.workers = workers; c.memory_bytes = 4u << 20; c.cache_entries = 1024; c.gc_interval = 0;
    pndd_engine *e = pndd_open(&c, &field, 1); assert(e);
    pndd_handle *roots = malloc(65536 * sizeof(*roots)); assert(roots);
    unsigned count = 0;
    for (; count < 65536; ++count) {
        roots[count] = pndd_value(e, 0, (uint64_t)count * UINT64_C(0x9e3779b97f4a7c15));
        if (roots[count] == PNDD_INVALID) break;
    }
    assert(count > 0 && count < 65536);
    /* Failure occurs while ample native arena space remains, exercising the
     * checked Sylvan exhaustion path rather than only the C allocator limit. */
    pndd_stats limit_stats = pndd_get_stats(e);
    assert(limit_stats.native_bytes < (c.memory_bytes * 3 / 4) * 4 / 5 - 65536);
    for (unsigned i = 0; i < count; ++i) assert(pndd_release(e, roots[i]));
    assert(pndd_collect(e));
    pndd_handle recovered = pndd_value(e, 0, UINT64_MAX);
    assert(recovered != PNDD_INVALID && pndd_satcount(e, recovered) == 1);
    assert(pndd_release(e, recovered)); free(roots); pndd_close(e);
}
static void single_entry_gc(unsigned workers) {
    pndd_field fields[] = {{2, PNDD_BCDD}, {2, PNDD_ZDD}};
    pndd_config c = pndd_default_config(); c.workers = workers; c.memory_bytes = 64u << 20;
    c.cache_entries = 4096; c.gc_interval = 1;
    pndd_engine *e = pndd_open(&c, fields, 2); assert(e);
    pndd_handle a = pndd_value(e, 0, 1), b = pndd_value(e, 1, 2);
    pndd_handle left[] = {a, b}, right[] = {b, a}, out[2];
    pndd_stats before = pndd_get_stats(e);
    assert(pndd_apply_batch(e, PNDD_AND, left, right, 2, out));
    pndd_stats after = pndd_get_stats(e);
    assert(after.external_runs - before.external_runs == 1); assert(after.collections - before.collections == 1);
    for (unsigned i = 0; i < 2; ++i) { assert(pndd_satcount(e, out[i]) == 1); pndd_release(e, out[i]); }
    for (int tree = 0; tree <= 1; ++tree) {
        before = pndd_get_stats(e); pndd_handle result = pndd_reduce(e, PNDD_AND, left, 2, tree);
        after = pndd_get_stats(e); assert(result != PNDD_INVALID);
        assert(after.external_runs - before.external_runs == 1); assert(after.collections - before.collections == 1);
        assert(pndd_satcount(e, result) == 1); pndd_release(e, result);
    }
    pndd_release(e, a); pndd_release(e, b); pndd_close(e);
}
static void enormous_domain(unsigned workers) {
    pndd_field fields[32]; for (unsigned i = 0; i < 32; ++i) fields[i] = (pndd_field){64, PNDD_BDD};
    pndd_config c = pndd_default_config(); c.workers = workers;
    pndd_engine *e = pndd_open(&c, fields, 32); assert(e);
    pndd_handle empty = pndd_constant(e, 0), all = pndd_constant(e, 1);
    assert(pndd_satcount(e, empty) == 0); assert(isinf(pndd_satcount(e, all)));
    pndd_handle root = pndd_retain(e, all);
    for (unsigned i = 0; i < 32; ++i) {
        pndd_handle value = pndd_value(e, i, UINT64_MAX), next = pndd_apply(e, PNDD_AND, root, value);
        assert(next != PNDD_INVALID); pndd_release(e, value); pndd_release(e, root); root = next;
    }
    assert(pndd_collect(e)); assert(pndd_satcount(e, root) == 1);
    pndd_release(e, root); pndd_release(e, empty); pndd_release(e, all); pndd_close(e);
}
int main(void) { pressure(6, PNDD_BCDD); pressure(28, PNDD_BCDD); pressure(6, PNDD_ZDD); pressure(28, PNDD_ZDD); return 0; }
