#include "pndd.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
static unsigned rng = 7;
static size_t exercise_cache_entries = 4096;
static unsigned random_u(void) { rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return rng; }
static int bool_op(int op, int a, int b) {
    switch (op) { case PNDD_AND: return a && b; case PNDD_OR: return a || b; case PNDD_DIFF: return a && !b; case PNDD_XOR: return a != b; case PNDD_IMP: return !a || b; case PNDD_EQUIV: return a == b; case PNDD_NAND: return !(a && b); default: return !(a || b); }
}
static void exercise(unsigned workers, int mixed, pndd_backend backend) {
    pndd_field fields[] = {{2, backend}, {1, backend}, {2, backend}, {1, backend}};
    if (mixed) for (int i = 0; i < 4; ++i) fields[i].backend = (pndd_backend)i;
    pndd_config config = pndd_default_config(); config.workers = workers; config.cache_entries = exercise_cache_entries; config.gc_interval = 7; config.label_parallel = 1;
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
        assert(pndd_satcount(e, roots[size]) == count);
        for (size_t j = 0; j < size; ++j) {
            int same = 1;
            for (unsigned v = 0; v < 64; ++v) if (truth[size][v] != truth[j][v]) { same = 0; break; }
            if (same) assert(roots[size] == roots[j]);
        }
        ++size;
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
    uint32_t group_ends[] = {2, 4, 6};
    unsigned indices[] = {0, 1, 2, 4, 5, 2};
    pndd_handle arrivals[6], selected[5];
    for (unsigned i = 0; i < 6; ++i) arrivals[i] = roots[indices[i]];
    for (int flags = 0; flags < 4; ++flags) {
        int ecmp = flags & PNDD_PRIORITY_MULTIPATH;
        assert(pndd_priority_select(e, roots[3], arrivals, 6, group_ends, 3, 1, flags, selected));
        assert(pndd_collect(e));
        for (unsigned i = 1; i < 6; ++i) {
            unsigned count = 0, group_start = i < 2 ? 0 : i < 4 ? 2 : 4;
            for (unsigned v = 0; v < 64; ++v) {
                uint64_t values[] = {v & 3, (v >> 2) & 1, (v >> 3) & 3, (v >> 5) & 1};
                int expected = truth[3][v] && truth[indices[i]][v];
                for (unsigned j = 0; j < (ecmp ? group_start : i); ++j) expected &= !truth[indices[j]][v];
                assert(pndd_evaluate(e, selected[i - 1], values, 4) == expected); count += expected;
            }
            assert(pndd_satcount(e, selected[i - 1]) == count); pndd_release(e, selected[i - 1]);
        }
    }
    assert(pndd_priority_select(e, roots[3], arrivals, 6, group_ends, 3, 6, 1, NULL));
    assert(pndd_priority_select(e, roots[3], NULL, 0, NULL, 0, 0, 1, NULL));
    pndd_priority_request requests[10]; pndd_handle many[8][6];
    for (unsigned i = 0; i < 8; ++i)
        requests[i] = (pndd_priority_request){roots[i % 2 ? 4 : 3], arrivals, 6, group_ends, 3, i % 3, (int)(i % 4), many[i]};
    requests[8] = (pndd_priority_request){roots[3], NULL, 0, NULL, 0, 0, 0, NULL};
    requests[9] = (pndd_priority_request){roots[3], arrivals, 6, group_ends, 3, 6, 0, NULL};
    pndd_stats before_many = pndd_get_stats(e);
    assert(pndd_priority_batch(e, requests, 10));
    assert(pndd_get_stats(e).external_runs == before_many.external_runs + 1);
    assert(pndd_collect(e));
    for (unsigned r = 0; r < 8; ++r) for (unsigned i = r % 3; i < 6; ++i) {
        unsigned expected_count = 0, group_start = i < 2 ? 0 : i < 4 ? 2 : 4;
        for (unsigned v = 0; v < 64; ++v) {
            uint64_t values[] = {v & 3, (v >> 2) & 1, (v >> 3) & 3, (v >> 5) & 1};
            int expected = truth[r % 2 ? 4 : 3][v] && truth[indices[i]][v];
            for (unsigned j = 0; j < (requests[r].flags & PNDD_PRIORITY_MULTIPATH ? group_start : i); ++j)
                expected &= !truth[indices[j]][v];
            assert(pndd_evaluate(e, many[r][i - r % 3], values, 4) == expected); expected_count += expected;
        }
        assert(pndd_satcount(e, many[r][i - r % 3]) == expected_count);
        pndd_release(e, many[r][i - r % 3]);
    }
    many[0][0] = PNDD_INVALID;
    requests[9].budget = PNDD_INVALID;
    assert(!pndd_priority_batch(e, requests, 10));
    assert(many[0][0] == PNDD_INVALID); /* later invalid request publishes nothing */
    assert(pndd_priority_batch(e, NULL, 0));
    assert(!pndd_priority_batch(e, NULL, 1));
    assert(!pndd_priority_select(e, roots[3], NULL, 0, NULL, 1, 0, 1, NULL));
    uint32_t invalid_ends[] = {2, 2, 6};
    assert(!pndd_priority_select(e, roots[3], arrivals, 6, invalid_ends, 3, 1, 1, selected));
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
    c.workers = workers; c.memory_bytes = 64u * 1024 * 1024; c.cache_entries = 4096;
    c.gc_interval = 0; c.label_parallel = 1; c.task_threshold = 2; c.chunk_size = 1;
    pndd_engine *e = pndd_open(&c, &field, 1); assert(e);
    enum { PREFIXES = 256, SUFFIXES = 128, CASES = PREFIXES * SUFFIXES };
    pndd_handle prefix[PREFIXES], suffix[SUFFIXES];
    pndd_handle a[CASES], b[CASES], out[CASES];
    /* Worker-local allocation regions can grow the table during construction,
     * leaving enough space for the old adjacent-point OR batch without a GC.
     * Instead build small sets of half-word cubes, then materialize their full
     * Cartesian product inside one batch. Its fresh labels outgrow the initial
     * Sylvan table while remaining within the configured maximum capacity. */
    for (unsigned half = 0; half < 2; ++half) {
        unsigned count = half ? SUFFIXES : PREFIXES;
        pndd_handle *parts = half ? suffix : prefix;
        for (unsigned value = 0; value < count; ++value) {
            pndd_handle root = pndd_constant(e, 1);
            for (unsigned bit = 0; bit < 8; ++bit) {
                pndd_handle literal = pndd_literal(e, 0, half * 8 + bit, (value >> (7 - bit)) & 1);
                pndd_handle next = pndd_apply(e, PNDD_AND, root, literal);
                assert(next != PNDD_INVALID);
                pndd_release(e, root); pndd_release(e, literal); root = next;
            }
            parts[value] = root;
        }
    }
    for (unsigned i = 0; i < CASES; ++i) { a[i] = prefix[i / SUFFIXES]; b[i] = suffix[i % SUFFIXES]; }
    for (unsigned round = 0; round < 4; ++round) {
        uint64_t before = pndd_get_stats(e).label_collections;
        assert(pndd_apply_batch(e, PNDD_AND, a, b, CASES, out));
        if (round == 0) assert(pndd_get_stats(e).label_collections > before);
        for (unsigned i = 0; i < CASES; ++i) {
            assert(pndd_satcount(e, out[i]) == 1);
            if (round == 0) {
                uint64_t value = ((uint64_t)(i / SUFFIXES) << 8) | (i % SUFFIXES);
                assert(pndd_evaluate(e, out[i], &value, 1) == 1);
            }
            assert(pndd_release(e, out[i]));
        }
        assert(pndd_collect(e));
    }
    for (unsigned i = 0; i < PREFIXES; ++i) pndd_release(e, prefix[i]);
    for (unsigned i = 0; i < SUFFIXES; ++i) pndd_release(e, suffix[i]);
    assert(pndd_collect(e)); assert(pndd_get_stats(e).label_collections > 0); pndd_close(e);
}
static void memory_failure(unsigned workers) {
    pndd_field fields[10]; for (int i = 0; i < 10; ++i) fields[i] = (pndd_field){10, PNDD_BDD};
    pndd_config c = pndd_default_config(); c.workers = workers; c.memory_bytes = 4u * 1024 * 1024; c.cache_entries = 1024;
    pndd_engine *e = pndd_open(&c, fields, 10); assert(e);
    enum { TOO_MANY = 262144 };
    pndd_handle literal = pndd_literal(e, 0, 0, 1);
    pndd_handle *inputs = malloc(TOO_MANY * sizeof(pndd_handle));
    pndd_handle *outputs = malloc(TOO_MANY * sizeof(pndd_handle)); assert(inputs && outputs);
    for (unsigned i = 0; i < TOO_MANY; ++i) { inputs[i] = literal; outputs[i] = PNDD_INVALID; }
    uint32_t end = TOO_MANY;
    pndd_priority_request oversized = {literal, inputs, TOO_MANY, &end, 1, 0, 1, outputs};
    assert(!pndd_priority_batch(e, &oversized, 1));
    for (unsigned i = 0; i < TOO_MANY; ++i) assert(outputs[i] == PNDD_INVALID);
    assert(pndd_collect(e)); assert(pndd_satcount(e, literal) == ldexp(1, 99));
    pndd_release(e, literal); free(outputs); free(inputs);
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
static void wide_fields_and_inspection(unsigned workers) {
    for (int reverse = 0; reverse <= 1; ++reverse) for (int backend = PNDD_BDD; backend <= PNDD_ZDD; ++backend) {
        pndd_field fields[] = {{108, backend}, {2, backend}};
        pndd_config c = pndd_default_config(); c.workers = workers; c.reverse_labels = reverse;
        pndd_engine *e = pndd_open(&c, fields, 2); assert(e);
        pndd_handle a = pndd_literal(e, 0, 0, 1), b = pndd_literal(e, 0, 107, 0);
        pndd_handle root = pndd_apply(e, PNDD_AND, a, b), dead = pndd_not(e, root);
        assert(pndd_satcount(e, root) == ldexp(1, 108));
        pndd_handle candidates[] = {a, b, root, dead}; unsigned char live[4];
        assert(pndd_reachable(e, &root, 1, candidates, 4, live));
        assert(live[2] && !live[0] && !live[1] && !live[3]);
        uint32_t field; size_t count; pndd_handle *pairs;
        assert(pndd_snapshot(e, root, &field, &count, &pairs)); assert(field == 0 && count == 1);
        assert(pndd_min_label_cost(e, pairs[1], 0, backend == PNDD_ZDD) == 1);
        if (backend != PNDD_ZDD) {
            size_t cube_count; signed char *cubes;
            assert(pndd_label_cubes(e, pairs[1], 0, 16, &cube_count, &cubes));
            assert(cube_count == 1 && cubes[0] == 1 && cubes[107] == 0);
            for (unsigned bit = 1; bit < 107; ++bit) assert(cubes[bit] == -1);
            free(cubes);
        }
        pndd_release(e, pairs[0]); pndd_release(e, pairs[1]); free(pairs);
        pndd_handle value = pndd_value(e, 0, UINT64_MAX);
        assert(pndd_satcount(e, value) == 4);
        uint64_t assignment[] = {UINT64_MAX, 0}; assert(pndd_evaluate(e, value, assignment, 2) == 1);
        pndd_release(e, value); pndd_release(e, a); pndd_release(e, b);
        pndd_release(e, root); pndd_release(e, dead); assert(pndd_collect(e)); pndd_close(e);
    }
}
int main(int argc, char **argv) {
    unsigned workers = argc > 1 ? (unsigned)atoi(argv[1]) : 1;
    if (argc > 2) {
        exercise_cache_entries = (size_t)strtoull(argv[2], NULL, 10);
        assert(exercise_cache_entries && !(exercise_cache_entries & (exercise_cache_entries - 1)));
    }
    for (int backend = 0; backend < 4; ++backend) exercise(workers, 0, (pndd_backend)backend);
    exercise(workers, 1, PNDD_BDD);
    pndd_field invalid = {8, PNDD_BITSET}; assert(!pndd_open(NULL, &invalid, 1));
    const unsigned expected[] = {0, 1, 0, 0, 2, 10, 4, 40, 92};
    for (int compact = 0; compact <= 1; ++compact) for (int backend = 0; backend < 4; ++backend) {
        unsigned n = 6, width = compact ? 3 : n; pndd_field fields[6]; for (unsigned f = 0; f < n; ++f) fields[f] = (pndd_field){width, backend};
        pndd_config config = pndd_default_config(); config.workers = workers; config.task_threshold = 2; config.chunk_size = 1;
        pndd_engine *e = pndd_open(&config, fields, n); assert(e);
        assert(pndd_nqueens(e, n, compact, 0) == expected[n]); assert(pndd_collect(e));
        assert(pndd_nqueens(e, n, compact, 1) == expected[n]); pndd_close(e);
    }
    pressure(workers, PNDD_BCDD); pressure(workers, PNDD_ZDD); memory_failure(workers);
    label_exhaustion(workers, PNDD_BCDD); label_exhaustion(workers, PNDD_ZDD); single_entry_gc(workers); enormous_domain(workers);
    wide_fields_and_inspection(workers);
    printf("all four backends, mixed fields, GC, batch, N-Queens: %u workers OK\n", workers);
    return 0;
}
