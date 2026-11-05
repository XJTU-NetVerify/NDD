#include "pndd_mc.h"
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Always active in Release; oracle is a flat bitset of explicit tuples. */
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #x); exit(1); } } while (0)
typedef struct { pndd_mc_set *set; unsigned mask; uint64_t tuples; } Model;
static unsigned random_state = 0x249746u;
static unsigned rnd(void) { random_state ^= random_state << 13; random_state ^= random_state >> 17; random_state ^= random_state << 5; return random_state; }
static unsigned arity(unsigned mask) { return (unsigned)__builtin_popcount(mask); }
static unsigned universe(unsigned mask) { return 1u << (2 * arity(mask)); }
static unsigned restrict_tuple(unsigned code, unsigned from, unsigned to) {
    unsigned out = 0, shift = 0;
    for (unsigned slot = 0; slot < 3; ++slot) if (from & (1u << slot)) {
        if (to & (1u << slot)) { out |= (code & 3) << shift; shift += 2; }
        code >>= 2;
    }
    return out;
}
static void decode(unsigned code, unsigned mask, int tuple[3]) {
    for (unsigned i = 0; i < arity(mask); ++i) { tuple[i] = (int)(code & 3); code >>= 2; }
}
static Model create(pndd_mc_domain *d, unsigned mask) {
    uint32_t proj[3]; int k = 0;
    for (unsigned i = 0; i < 3; ++i) if (mask & (1u << i)) proj[k++] = i;
    Model m = {pndd_mc_set_create(d, k, proj), mask, 0}; CHECK(m.set); return m;
}
static void add(Model *m, unsigned code) {
    int tuple[3]; decode(code, m->mask, tuple);
    CHECK(pndd_mc_set_add(m->set, arity(m->mask) ? tuple : NULL));
    m->tuples |= UINT64_C(1) << code;
}
typedef struct { unsigned mask; uint64_t seen; } Enumeration;
static int enumerate(void *context, const int *tuple, size_t n) {
    Enumeration *e = context; CHECK(n == arity(e->mask)); unsigned code = 0;
    if (!n) CHECK(!tuple);
    for (size_t i = 0; i < n; ++i) { CHECK(tuple[i] >= 0 && tuple[i] < 4); code |= (unsigned)tuple[i] << (2 * i); }
    uint64_t bit = UINT64_C(1) << code; CHECK(!(e->seen & bit)); e->seen |= bit; return 1;
}
static void verify(Model m) {
    CHECK(pndd_mc_set_arity(m.set) == arity(m.mask));
    CHECK(pndd_mc_set_count(m.set) == __builtin_popcountll(m.tuples));
    uint64_t nodes = UINT64_MAX; CHECK(pndd_mc_set_node_count(m.set, &nodes));
    CHECK(nodes <= (uint64_t)__builtin_popcountll(m.tuples) * arity(m.mask));
    CHECK(pndd_mc_set_empty(m.set) == !m.tuples);
    for (unsigned v = 0; v < universe(m.mask); ++v) {
        int tuple[3]; decode(v, m.mask, tuple);
        CHECK(pndd_mc_set_member(m.set, arity(m.mask) ? tuple : NULL) == (int)((m.tuples >> v) & 1));
    }
    Enumeration e = {m.mask, 0}; CHECK(pndd_mc_set_enum(m.set, enumerate, &e) == 1); CHECK(e.seen == m.tuples);
    int tuple[3] = {-1, -1, -1}; CHECK(pndd_mc_set_example(m.set, arity(m.mask) ? tuple : NULL) == !!m.tuples);
    if (m.tuples) CHECK(pndd_mc_set_member(m.set, arity(m.mask) ? tuple : NULL) == 1);
}
static uint64_t project(Model a, unsigned to) {
    uint64_t expected = 0;
    for (unsigned v = 0; v < universe(a.mask); ++v)
        if ((a.tuples >> v) & 1) expected |= UINT64_C(1) << restrict_tuple(v, a.mask, to);
    return expected;
}
static uint64_t join(Model a, Model b) {
    unsigned mask = a.mask | b.mask; uint64_t expected = 0;
    for (unsigned v = 0; v < universe(mask); ++v)
        if (((a.tuples >> restrict_tuple(v, mask, a.mask)) & 1) &&
            ((b.tuples >> restrict_tuple(v, mask, b.mask)) & 1)) expected |= UINT64_C(1) << v;
    return expected;
}
static int stop(void *ctx, const int *tuple, size_t n) { (void)tuple; (void)n; ++*(int *)ctx; return 0; }
static int abort_visit(void *ctx, const int *tuple, size_t n) { (void)ctx; (void)tuple; (void)n; return -1; }
typedef struct { pndd_mc_domain *d; Model *source, *copy; unsigned count; } Reentry;
static int reenter(void *context, const int *tuple, size_t n) {
    Reentry *r = context; CHECK(n == 3);
    if (!r->count) { CHECK(pndd_mc_set_clear(r->source->set)); r->source->tuples = 0; }
    CHECK(!pndd_mc_close(r->d));
    CHECK(pndd_mc_set_add(r->copy->set, tuple));
    r->copy->tuples |= UINT64_C(1) << ((unsigned)tuple[0] | (unsigned)tuple[1] << 2 | (unsigned)tuple[2] << 4);
    CHECK(pndd_mc_collect(r->d)); ++r->count; return 1;
}
static int update_copy(pndd_mc_set *dst, void *ctx, const int *tuple, size_t n) {
    (void)n; ++*(unsigned *)ctx; return pndd_mc_set_add(dst, tuple) ? 1 : -1;
}
static int update_shift(pndd_mc_set *dst, void *ctx, const int *tuple, size_t n) {
    CHECK(n == 3); ++*(unsigned *)ctx;
    int shifted[] = {(tuple[0] + 1) % 4, tuple[1], tuple[2]};
    return pndd_mc_set_add(dst, shifted) ? 1 : -1;
}
static int expand_group(pndd_mc_set *dst, void *ctx, const int *tuple, size_t n) {
    CHECK(n == 1); ++*(unsigned *)ctx;
    int state[] = {tuple[0], 0, 0};
    return pndd_mc_set_add(dst, state) ? 1 : -1;
}
static int destroy_source(void *ctx, const int *tuple, size_t n) {
    (void)tuple; (void)n; Reentry *r = ctx;
    if (!r->count) { pndd_mc_set_destroy(r->source->set); r->source->set = NULL; }
    CHECK(pndd_mc_collect(r->d)); ++r->count; return 1;
}

static void exercise(unsigned workers, uint32_t interval) {
    pndd_mc_config c = pndd_mc_default_config(); c.value_bits = 2;
    c.engine.workers = workers; c.engine.gc_interval = interval; c.engine.cache_entries = 64;
    c.engine.memory_bytes = 16u * 1024 * 1024; c.engine.label_parallel = 1;
    c.engine.task_threshold = 2; c.engine.chunk_size = 1;
    pndd_mc_domain *d = pndd_mc_open(&c, 3); CHECK(d);
    CHECK(!pndd_mc_open(&c, 3)); CHECK(pndd_mc_collect(d));
    uint64_t baseline = pndd_mc_stats(d).nodes_live;
    Model sets[8];
    for (unsigned mask = 0; mask < 8; ++mask) {
        sets[mask] = create(d, mask); verify(sets[mask]);
        CHECK(pndd_mc_set_fill(sets[mask].set));
        sets[mask].tuples = mask == 7 ? UINT64_MAX : (UINT64_C(1) << universe(mask)) - 1;
        verify(sets[mask]);
        CHECK(pndd_mc_set_clear(sets[mask].set)); sets[mask].tuples = 0;
        for (unsigned j = 0; j < 20; ++j) add(&sets[mask], rnd() % universe(mask));
        verify(sets[mask]);
    }
    for (unsigned from = 0; from < 8; ++from) for (unsigned to = 0; to < 8; ++to) {
        if ((from & to) == to) {
            Model out = create(d, to); out.tuples = project(sets[from], to);
            CHECK(pndd_mc_set_project(out.set, sets[from].set)); verify(out);
            CHECK(pndd_mc_set_project(out.set, out.set)); verify(out);
            CHECK(pndd_mc_set_project_minus(out.set, sets[from].set, sets[to].set)); out.tuples &= ~sets[to].tuples; verify(out);
            CHECK(pndd_mc_set_project_minus(out.set, sets[from].set, out.set)); out.tuples = project(sets[from], to) & ~out.tuples; verify(out);
            pndd_mc_set_destroy(out.set);
        }
        Model out = create(d, from | to); out.tuples = join(sets[from], sets[to]);
        CHECK(pndd_mc_set_join(out.set, sets[from].set, sets[to].set)); verify(out);
        if (out.mask == from) { CHECK(pndd_mc_set_copy(out.set, sets[from].set)); CHECK(pndd_mc_set_join(out.set, out.set, sets[to].set)); verify(out); }
        pndd_mc_set_destroy(out.set);
    }
    for (unsigned round = 0; round < 100; ++round) {
        unsigned mask = rnd() % 8; Model a = create(d, mask), b = create(d, mask), copy = create(d, mask);
        for (unsigned i = 0; i < 12; ++i) { add(&a, rnd() % universe(mask)); add(&b, rnd() % universe(mask)); }
        CHECK(pndd_mc_set_copy(copy.set, a.set)); copy.tuples = a.tuples; CHECK(pndd_mc_set_equal(copy.set, a.set) == 1);
        CHECK(pndd_mc_set_union(copy.set, b.set)); copy.tuples |= b.tuples; verify(copy);
        CHECK(pndd_mc_set_intersect(copy.set, a.set)); copy.tuples &= a.tuples; verify(copy);
        CHECK(pndd_mc_set_minus(copy.set, b.set)); copy.tuples &= ~b.tuples; verify(copy);
        CHECK(pndd_mc_set_zip(a.set, b.set)); uint64_t old = a.tuples; a.tuples |= b.tuples; b.tuples &= ~old; verify(a); verify(b);
        CHECK(!pndd_mc_set_zip(a.set, a.set)); verify(a);
        CHECK(pndd_mc_set_copy(a.set, a.set)); CHECK(pndd_mc_set_union(a.set, a.set)); CHECK(pndd_mc_set_intersect(a.set, a.set)); verify(a);
        CHECK(pndd_mc_set_minus(a.set, a.set)); a.tuples = 0; verify(a);
        pndd_mc_set_destroy(a.set); pndd_mc_set_destroy(b.set); pndd_mc_set_destroy(copy.set);
    }
    /* Invalid calls must not partially overwrite destinations. */
    uint32_t bad[] = {1, 0}, duplicate[] = {1, 1}, outside[] = {3};
    CHECK(!pndd_mc_set_create(d, 2, bad)); CHECK(!pndd_mc_set_create(d, 2, duplicate)); CHECK(!pndd_mc_set_create(d, 1, outside));
    CHECK(!pndd_mc_set_create(d, -2, NULL)); CHECK(!pndd_mc_set_create(d, 1, NULL));
    CHECK(!pndd_mc_set_copy(sets[1].set, sets[2].set)); CHECK(!pndd_mc_set_union(sets[1].set, sets[2].set));
    CHECK(!pndd_mc_set_project(sets[7].set, sets[1].set)); CHECK(!pndd_mc_set_join(sets[1].set, sets[1].set, sets[2].set));
    CHECK(pndd_mc_set_equal(sets[1].set, sets[2].set) == 0);
    int negative[] = {-1}, overflow[] = {4};
    CHECK(!pndd_mc_set_add(sets[1].set, negative)); CHECK(!pndd_mc_set_add(sets[1].set, overflow));
    CHECK(pndd_mc_set_member(sets[1].set, negative) == -1); CHECK(!pndd_mc_set_add(sets[1].set, NULL));
    for (unsigned i = 0; i < 8; ++i) verify(sets[i]);
    int calls = 0; CHECK(pndd_mc_set_enum(sets[7].set, stop, &calls) == 0); CHECK(calls == 1);
    CHECK(pndd_mc_set_enum(sets[7].set, abort_visit, NULL) == -1);
    CHECK(pndd_mc_set_enum(sets[7].set, NULL, NULL) == -1);

    Model copy = create(d, 7); uint64_t expected = sets[7].tuples;
    Reentry r = {d, &sets[7], &copy, 0}; CHECK(pndd_mc_set_enum(sets[7].set, reenter, &r) == 1);
    CHECK(copy.tuples == expected); CHECK(r.count == (unsigned)__builtin_popcountll(expected)); verify(copy); verify(sets[7]);
    unsigned updates = 0; CHECK(pndd_mc_set_update(sets[7].set, copy.set, update_copy, &updates) == 1);
    sets[7].tuples = expected; CHECK(updates == r.count); verify(sets[7]);
    /* In-place update enumerates the original snapshot, not a growing set. */
    CHECK(pndd_mc_set_update(copy.set, copy.set, update_copy, &updates) == 1); verify(copy);
    unsigned shifts = 0; uint64_t original = copy.tuples;
    CHECK(pndd_mc_set_update(copy.set, copy.set, update_shift, &shifts) == 1);
    CHECK(shifts == (unsigned)__builtin_popcountll(original));
    for (unsigned v = 0; v < 64; ++v) if ((original >> v) & 1)
        copy.tuples |= UINT64_C(1) << ((v & ~3u) | ((v + 1) & 3));
    verify(copy);
    /* Project only newly encountered read tuples, remember them, then invoke
     * a callback which creates full states. A repeated expansion does no work. */
    Model seen = create(d, 1), todo = create(d, 1), expanded = create(d, 7);
    add(&seen, 0); unsigned expansions = 0;
    todo.tuples = project(copy, 1) & ~seen.tuples;
    CHECK(pndd_mc_set_project_minus(todo.set, copy.set, seen.set)); verify(todo);
    CHECK(pndd_mc_set_union(seen.set, todo.set)); seen.tuples |= todo.tuples;
    CHECK(pndd_mc_set_update(expanded.set, todo.set, expand_group, &expansions) == 1);
    expanded.tuples = todo.tuples; verify(expanded);
    CHECK(expansions == (unsigned)__builtin_popcountll(todo.tuples));
    CHECK(pndd_mc_set_project_minus(todo.set, copy.set, seen.set)); todo.tuples = 0; verify(todo);
    unsigned before = expansions;
    CHECK(pndd_mc_set_update(expanded.set, todo.set, expand_group, &expansions) == 1); CHECK(expansions == before);
    pndd_mc_set_destroy(seen.set); pndd_mc_set_destroy(todo.set); pndd_mc_set_destroy(expanded.set);
    expected = copy.tuples;
    r.source = &copy; r.count = 0; CHECK(pndd_mc_set_enum(copy.set, destroy_source, &r) == 1);
    CHECK(r.count == (unsigned)__builtin_popcountll(expected));
    for (unsigned i = 0; i < 8; ++i) pndd_mc_set_destroy(sets[i].set);
    CHECK(pndd_mc_collect(d)); pndd_stats stats = pndd_mc_stats(d);
    CHECK(stats.nodes_live == baseline); CHECK(stats.collections > 0); CHECK(stats.label_collections > 0);
    CHECK(pndd_mc_close(d));
    printf("MC explicit sets/project/join/GC PASS workers=%u interval=%u\n", workers, interval);
}

static void boundary(unsigned workers) {
    pndd_mc_config c = pndd_mc_default_config(); c.engine.workers = workers;
    c.engine.memory_bytes = 16u * 1024 * 1024; c.engine.cache_entries = 64; c.engine.gc_interval = 3;
    pndd_mc_domain *d = pndd_mc_open(&c, 3); CHECK(d);
    pndd_mc_set *full = pndd_mc_set_create(d, -1, NULL), *epsilon = pndd_mc_set_create(d, 0, NULL);
    uint32_t slot[] = {1}; pndd_mc_set *one = pndd_mc_set_create(d, 1, slot); CHECK(full && epsilon && one);
    CHECK(pndd_mc_set_fill(full)); CHECK(pndd_mc_set_count(full) == ldexp(1.0, 48));
    CHECK(pndd_mc_set_project(one, full)); CHECK(pndd_mc_set_count(one) == 65536);
    CHECK(pndd_mc_set_project(epsilon, full)); CHECK(pndd_mc_set_count(epsilon) == 1);
    CHECK(pndd_mc_set_clear(full)); int values[][3] = {{0, 65535, 32768}, {65535, 32768, 1}, {42, 32768, 1}};
    CHECK(pndd_mc_set_add(full, values[0]));
    uint64_t nodes = 0; CHECK(pndd_mc_set_node_count(full, &nodes)); CHECK(nodes == 3);
    for (unsigned i = 0; i < 3; ++i) { CHECK(pndd_mc_set_add(full, values[i])); CHECK(pndd_mc_set_add(full, values[i])); }
    CHECK(pndd_mc_set_count(full) == 3); CHECK(pndd_mc_set_project(one, full)); CHECK(pndd_mc_set_count(one) == 2);
    for (unsigned i = 0; i < 3; ++i) CHECK(pndd_mc_set_member(full, values[i]) == 1);
    int bad[][3] = {{-1, 0, 0}, {65536, 0, 0}};
    CHECK(!pndd_mc_set_add(full, bad[0])); CHECK(!pndd_mc_set_add(full, bad[1])); CHECK(pndd_mc_set_count(full) == 3);
    CHECK(pndd_mc_collect(d)); CHECK(pndd_mc_set_count(one) == 2);
    pndd_mc_set_destroy(one); pndd_mc_set_destroy(full); pndd_mc_set_destroy(epsilon); CHECK(pndd_mc_close(d));

    d = pndd_mc_open(&c, 0); CHECK(d); full = pndd_mc_set_create(d, -1, NULL); epsilon = pndd_mc_set_create(d, 0, NULL);
    CHECK(full && epsilon); CHECK(pndd_mc_set_count(full) == 0); CHECK(pndd_mc_set_add(full, NULL));
    CHECK(pndd_mc_set_count(full) == 1); CHECK(pndd_mc_set_member(full, NULL) == 1);
    CHECK(pndd_mc_set_project(epsilon, full)); CHECK(pndd_mc_set_equal(full, epsilon) == 1);
    Enumeration e = {0, 0}; CHECK(pndd_mc_set_enum(full, enumerate, &e) == 1); CHECK(e.seen == 1);
    CHECK(pndd_mc_set_join(full, full, epsilon)); CHECK(pndd_mc_collect(d)); CHECK(pndd_mc_set_count(full) == 1);
    pndd_mc_set_destroy(full); pndd_mc_set_destroy(epsilon); CHECK(pndd_mc_close(d));
    c.backend = PNDD_BDD; CHECK(!pndd_mc_open(&c, 1)); c.backend = PNDD_BCDD;
    c.engine.reverse_labels = 1; CHECK(!pndd_mc_open(&c, 1)); c.engine.reverse_labels = 0;
    CHECK(!pndd_mc_open(&c, 257)); c.value_bits = 17; CHECK(!pndd_mc_open(&c, 1));
    printf("MC 16-bit and zero-domain boundaries PASS\n");
}
int main(int argc, char **argv) {
    unsigned workers = argc > 1 ? (unsigned)atoi(argv[1]) : 1;
    exercise(workers, 17); boundary(workers); return 0;
}
