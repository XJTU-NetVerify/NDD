#include "pndd_mc.h"
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #x); exit(1); } } while (0)
/* Independent oracle: enumerate every full source/destination tuple and apply
 * integer read/write/copy rules. No NDD representation participates. */
typedef struct {
    pndd_mc_rel *rel;
    unsigned slots, bits, reads, writes;
    unsigned char pairs[4096];
} Oracle;
static unsigned seed = 0x77311;
static unsigned random_u(void) { seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5; return seed; }
static int projection(unsigned mask, uint32_t *out) {
    int k = 0; for (unsigned i = 0; i < 3; ++i) if (mask & (1u << i)) out[k++] = i; return k;
}
static Oracle relation(pndd_mc_domain *d, unsigned slots, unsigned bits, unsigned reads, unsigned writes) {
    uint32_t rp[3], wp[3]; int rk = projection(reads, rp), wk = projection(writes, wp);
    Oracle o = {.rel = pndd_mc_rel_create_rw(d, rk, rp, wk, wp), .slots = slots, .bits = bits, .reads = reads, .writes = writes};
    CHECK(o.rel); CHECK(pndd_mc_rel_read_arity(o.rel) == (size_t)rk); CHECK(pndd_mc_rel_write_arity(o.rel) == (size_t)wk);
    return o;
}
static void decode(unsigned value, unsigned slots, unsigned bits, int *tuple) {
    for (unsigned i = 0; i < slots; ++i) { tuple[i] = (int)(value & ((1u << bits) - 1)); value >>= bits; }
}
static void oracle_insert(Oracle *o, const int *read, const int *write, const int *copy) {
    unsigned states = 1u << (o->slots * o->bits);
    for (unsigned a = 0; a < states; ++a) for (unsigned b = 0; b < states; ++b) {
        int src[3], dst[3]; decode(a, o->slots, o->bits, src); decode(b, o->slots, o->bits, dst);
        unsigned ri = 0, wi = 0; int match = 1;
        for (unsigned f = 0; f < o->slots; ++f) {
            int r = !!(o->reads & (1u << f)), w = !!(o->writes & (1u << f));
            if (r) match &= src[f] == read[ri++];
            if (w) { match &= dst[f] == ((!r && copy && copy[wi]) ? src[f] : write[wi]); ++wi; }
            else match &= src[f] == dst[f];
        }
        if (match) o->pairs[a * states + b] = 1;
    }
}
static void insert(Oracle *o, const int *r, const int *w, const int *copy) {
    CHECK(pndd_mc_rel_add_cpy(o->rel, r, w, copy)); oracle_insert(o, r, w, copy);
}
static void verify(Oracle *o) {
    unsigned states = 1u << (o->slots * o->bits), total = 0;
    for (unsigned a = 0; a < states; ++a) for (unsigned b = 0; b < states; ++b) {
        int src[3], dst[3]; decode(a, o->slots, o->bits, src); decode(b, o->slots, o->bits, dst);
        int expected = o->pairs[a * states + b]; total += expected;
        int actual = pndd_mc_rel_member(o->rel, src, dst);
        if (actual != expected) fprintf(stderr, "pair mismatch: reads=%u writes=%u src=%u dst=%u actual=%d expected=%d\n", o->reads, o->writes, a, b, actual, expected);
        CHECK(actual == expected);
    }
    unsigned fields = (unsigned)__builtin_popcount(o->reads | o->writes);
    unsigned identity_factor = 1u << ((o->slots - fields) * o->bits);
    CHECK(total % identity_factor == 0);
    CHECK(pndd_mc_rel_count(o->rel) == total / identity_factor);
    CHECK(pndd_mc_rel_empty(o->rel) == !total);
    uint64_t nodes = UINT64_MAX; CHECK(pndd_mc_rel_node_count(o->rel, &nodes));
    CHECK(nodes <= (uint64_t)fields * (total / identity_factor));
}
static void lifecycle_and_modes(pndd_mc_domain *d) {
    for (unsigned reads = 0; reads < 4; ++reads) for (unsigned writes = 0; writes < 4; ++writes) {
        Oracle o = relation(d, 2, 2, reads, writes); verify(&o);
        for (unsigned step = 0; step < 24; ++step) {
            int r[2], w[2], c[2];
            for (unsigned i = 0; i < 2; ++i) { r[i] = (int)(random_u() % 4); w[i] = (int)(random_u() % 4); c[i] = (int)(random_u() % 2); }
            insert(&o, reads ? r : NULL, writes ? w : NULL, step % 3 ? c : NULL);
            /* Repeated insertion is idempotent even with overlapping copy. */
            CHECK(pndd_mc_rel_add_cpy(o.rel, reads ? r : NULL, writes ? w : NULL, step % 3 ? c : NULL));
            if (step == 11) verify(&o);
        }
        verify(&o); CHECK(!pndd_mc_close(d));
        Oracle copied = relation(d, 2, 2, reads, writes);
        CHECK(pndd_mc_rel_copy(copied.rel, o.rel)); memcpy(copied.pairs, o.pairs, sizeof(o.pairs));
        CHECK(pndd_mc_rel_equal(copied.rel, o.rel) == 1); CHECK(pndd_mc_rel_copy(copied.rel, copied.rel));
        CHECK(pndd_mc_rel_clear(o.rel)); memset(o.pairs, 0, sizeof(o.pairs));
        CHECK(pndd_mc_collect(d)); verify(&copied); verify(&o);
        pndd_mc_rel_destroy(o.rel); pndd_mc_rel_destroy(copied.rel);
    }
}
static void dense_and_scope(pndd_mc_domain *d) {
    Oracle dense = relation(d, 2, 2, 3, 3);
    for (unsigned a = 0; a < 16; ++a) for (unsigned b = 0; b < 16; ++b) {
        int src[2], dst[2]; decode(a, 2, 2, src); decode(b, 2, 2, dst); insert(&dense, src, dst, NULL);
    }
    verify(&dense); CHECK(pndd_mc_rel_count(dense.rel) == 256);
    uint64_t nodes; CHECK(pndd_mc_rel_node_count(dense.rel, &nodes)); CHECK(nodes == 2);
    Oracle write_all = relation(d, 2, 2, 0, 3);
    for (unsigned b = 0; b < 16; ++b) { int dst[2]; decode(b, 2, 2, dst); insert(&write_all, NULL, dst, NULL); }
    verify(&write_all); CHECK(!memcmp(write_all.pairs, dense.pairs, sizeof(dense.pairs)));
    CHECK(pndd_mc_rel_count(write_all.rel) == 256); CHECK(pndd_mc_rel_node_count(write_all.rel, &nodes)); CHECK(nodes == 2);
    CHECK(!pndd_mc_rel_copy(write_all.rel, dense.rel)); CHECK(pndd_mc_rel_equal(write_all.rel, dense.rel) == 0); verify(&write_all);
    Oracle copy = relation(d, 2, 2, 0, 3), epsilon = relation(d, 2, 2, 0, 0);
    int ignored[] = {0, 0}, cpy[] = {1, 1}; insert(&copy, NULL, ignored, cpy); insert(&epsilon, NULL, NULL, NULL);
    verify(&copy); verify(&epsilon); CHECK(!memcmp(copy.pairs, epsilon.pairs, sizeof(copy.pairs)));
    CHECK(pndd_mc_rel_count(copy.rel) == 16); CHECK(pndd_mc_rel_count(epsilon.rel) == 1);
    CHECK(pndd_mc_rel_node_count(copy.rel, &nodes)); CHECK(nodes == 2);
    CHECK(pndd_mc_rel_node_count(epsilon.rel, &nodes)); CHECK(nodes == 0);
    int from[] = {0, 0}, to[] = {1, 2};
    CHECK(pndd_mc_rel_member(dense.rel, from, to) == 1); CHECK(pndd_mc_rel_member(epsilon.rel, from, to) == 0);
    CHECK(pndd_mc_collect(d)); verify(&dense); verify(&copy);
    pndd_mc_rel_destroy(dense.rel); pndd_mc_rel_destroy(write_all.rel); pndd_mc_rel_destroy(copy.rel); pndd_mc_rel_destroy(epsilon.rel);

    /* Read-only slot 0 and write-only slot 1: fixed-next union copy overlaps in
     * exactly one pair. This exercises merging different relation predicates. */
    Oracle overlap = relation(d, 2, 2, 1, 2); int read[] = {1}, write[] = {2}, flag[] = {1};
    insert(&overlap, read, write, NULL); insert(&overlap, read, write, flag);
    verify(&overlap); CHECK(pndd_mc_rel_count(overlap.rel) == 7); pndd_mc_rel_destroy(overlap.rel);
    Oracle rw = relation(d, 2, 2, 1, 1); insert(&rw, read, write, flag); verify(&rw);
    int src[] = {1, 0}, dst[] = {2, 0}; CHECK(pndd_mc_rel_member(rw.rel, src, dst) == 1);
    CHECK(pndd_mc_rel_member(rw.rel, src, src) == 0); pndd_mc_rel_destroy(rw.rel);
}
typedef struct { pndd_mc_domain *d; pndd_mc_set *source; unsigned calls; int first; int abort; } Update;
static int learn(pndd_mc_rel *r, void *context, const int *tuple, size_t n) {
    Update *u = context; CHECK(n == 1);
    int dst[] = {(tuple[0] + 1) % 4};
    if (!u->calls) { u->first = tuple[0]; CHECK(pndd_mc_set_clear(u->source)); }
    CHECK(pndd_mc_rel_add(r, tuple, dst)); CHECK(!pndd_mc_close(u->d)); CHECK(pndd_mc_collect(u->d));
    ++u->calls; return u->abort ? -1 : 1;
}
static void updates_and_invalid(pndd_mc_domain *d) {
    Oracle o = relation(d, 2, 2, 1, 2); uint32_t proj[] = {0};
    pndd_mc_set *source = pndd_mc_set_create(d, 1, proj); CHECK(source); CHECK(pndd_mc_set_fill(source));
    Update u = {.d = d, .source = source};
    CHECK(pndd_mc_rel_update(o.rel, source, learn, &u) == 1); CHECK(u.calls == 4);
    for (int i = 0; i < 4; ++i) { int dst = (i + 1) % 4; oracle_insert(&o, &i, &dst, NULL); }
    verify(&o); CHECK(pndd_mc_set_empty(source) == 1);
    CHECK(pndd_mc_rel_update(o.rel, source, learn, &u) == 1); CHECK(u.calls == 4);
    CHECK(pndd_mc_rel_update(o.rel, source, NULL, &u) == -1);
    pndd_mc_set *full = pndd_mc_set_create(d, -1, NULL); CHECK(full);
    CHECK(pndd_mc_rel_update(o.rel, full, learn, &u) == -1); verify(&o);
    CHECK(pndd_mc_rel_clear(o.rel)); memset(o.pairs, 0, sizeof(o.pairs));
    CHECK(pndd_mc_set_fill(source)); u.calls = 0; u.abort = 1;
    CHECK(pndd_mc_rel_update(o.rel, source, learn, &u) == -1); CHECK(u.calls == 1);
    int dst = (u.first + 1) % 4; oracle_insert(&o, &u.first, &dst, NULL); verify(&o);
    int bad[] = {-1}, large[] = {4}, valid[] = {0}, flag[] = {1};
    CHECK(!pndd_mc_rel_add(o.rel, NULL, valid)); CHECK(!pndd_mc_rel_add(o.rel, valid, NULL));
    CHECK(!pndd_mc_rel_add(o.rel, bad, valid)); CHECK(!pndd_mc_rel_add(o.rel, valid, large));
    CHECK(!pndd_mc_rel_add_cpy(o.rel, valid, large, flag)); verify(&o);
    int invalid_pair[] = {0, 4}; CHECK(pndd_mc_rel_member(o.rel, invalid_pair, invalid_pair) == -1);
    CHECK(pndd_mc_rel_member(o.rel, NULL, invalid_pair) == -1);
    uint32_t duplicate[] = {0, 0}, descending[] = {1, 0}, outside[] = {2};
    CHECK(!pndd_mc_rel_create_rw(d, 2, duplicate, 0, NULL)); CHECK(!pndd_mc_rel_create(d, 2, descending));
    CHECK(!pndd_mc_rel_create(d, 1, outside)); CHECK(!pndd_mc_rel_create(d, -2, NULL));
    CHECK(!pndd_mc_rel_create(d, 1, NULL)); CHECK(!pndd_mc_rel_create(d, -1, proj));
    pndd_mc_rel *all = pndd_mc_rel_create(d, -1, NULL); CHECK(all);
    CHECK(pndd_mc_rel_read_arity(all) == 2 && pndd_mc_rel_write_arity(all) == 2);
    pndd_mc_rel_destroy(all); pndd_mc_rel_destroy(o.rel); pndd_mc_set_destroy(full); pndd_mc_set_destroy(source);
}
static pndd_mc_config config(unsigned workers, unsigned bits) {
    pndd_mc_config c = pndd_mc_default_config(); c.value_bits = bits; c.engine.workers = workers;
    c.engine.memory_bytes = 16u * 1024 * 1024; c.engine.cache_entries = 64; c.engine.gc_interval = 29;
    c.engine.label_parallel = 1; c.engine.task_threshold = 2; c.engine.chunk_size = 1; return c;
}
static void small(unsigned workers) {
    pndd_mc_config c = config(workers, 2); pndd_mc_domain *d = pndd_mc_open(&c, 2); CHECK(d);
    CHECK(pndd_mc_collect(d)); uint64_t baseline = pndd_mc_stats(d).nodes_live;
    lifecycle_and_modes(d); dense_and_scope(d); updates_and_invalid(d);
    CHECK(pndd_mc_collect(d)); pndd_stats stats = pndd_mc_stats(d);
    CHECK(stats.nodes_live == baseline); CHECK(stats.collections && stats.label_collections); CHECK(pndd_mc_close(d));
    c.value_bits = 1; d = pndd_mc_open(&c, 3); CHECK(d);
    /* Dependency across two outer fields; middle slot is omitted identity. */
    Oracle cross = relation(d, 3, 1, 1, 4);
    int zero = 0, one = 1; insert(&cross, &zero, &one, NULL); insert(&cross, &one, &zero, NULL);
    verify(&cross); CHECK(pndd_mc_rel_count(cross.rel) == 4); pndd_mc_rel_destroy(cross.rel); CHECK(pndd_mc_close(d));
    printf("C2A explicit RW/copy/scope/dedup/update/GC PASS workers=%u\n", workers);
}
static void boundary(unsigned workers) {
    pndd_mc_config c = config(workers, 16); pndd_mc_domain *d = pndd_mc_open(&c, 2); CHECK(d);
    pndd_mc_rel *r = pndd_mc_rel_create_rw(d, 0, NULL, -1, NULL); CHECK(r);
    int dst[] = {65535, 32768}, copy[] = {1, 1};
    CHECK(pndd_mc_rel_add_cpy(r, NULL, dst, copy)); CHECK(pndd_mc_rel_count(r) == ldexp(1.0, 32));
    int src[] = {0, 65535}; CHECK(pndd_mc_rel_member(r, src, src) == 1); CHECK(pndd_mc_rel_member(r, src, dst) == 0);
    int overflow[] = {0, 65536}; CHECK(!pndd_mc_rel_add_cpy(r, NULL, overflow, copy)); CHECK(pndd_mc_rel_count(r) == ldexp(1.0, 32));
    pndd_mc_rel_destroy(r); r = pndd_mc_rel_create(d, -1, NULL); CHECK(r);
    CHECK(pndd_mc_rel_add(r, src, dst)); CHECK(pndd_mc_rel_member(r, src, dst) == 1); CHECK(pndd_mc_rel_count(r) == 1);
    CHECK(pndd_mc_collect(d)); CHECK(pndd_mc_rel_count(r) == 1); pndd_mc_rel_destroy(r); CHECK(pndd_mc_close(d));
    d = pndd_mc_open(&c, 0); CHECK(d); r = pndd_mc_rel_create(d, -1, NULL); CHECK(r);
    CHECK(pndd_mc_rel_count(r) == 0); CHECK(pndd_mc_rel_member(r, NULL, NULL) == 0);
    CHECK(pndd_mc_rel_add(r, NULL, NULL)); CHECK(pndd_mc_rel_count(r) == 1); CHECK(pndd_mc_rel_member(r, NULL, NULL) == 1);
    CHECK(!pndd_mc_close(d)); CHECK(pndd_mc_collect(d)); CHECK(pndd_mc_rel_count(r) == 1);
    pndd_mc_rel_destroy(r); CHECK(pndd_mc_close(d)); puts("C2A 16-bit and zero-domain boundaries PASS");
}
int main(int argc, char **argv) {
    unsigned workers = argc > 1 ? (unsigned)atoi(argv[1]) : 1; small(workers); boundary(workers); return 0;
}
