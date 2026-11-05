#include "pndd_mc.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #x); exit(1); } } while (0)
static unsigned seed = 0xc2b719;
static unsigned rnd(void) { seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5; return seed; }
static int projection(unsigned mask, uint32_t *p) {
    int k = 0; for (unsigned i = 0; i < 3; ++i) if (mask & (1u << i)) p[k++] = i; return k;
}
static unsigned unpack(unsigned id, unsigned mask, unsigned bits, int *tuple) {
    unsigned k = 0; for (unsigned f = 0; f < 3; ++f) if (mask & (1u << f)) tuple[k++] = (id >> (f * bits)) & ((1u << bits) - 1); return k;
}
static unsigned expand_mask(unsigned mask, unsigned bits) {
    unsigned out = 0; for (unsigned f = 0; f < 3; ++f) if (mask & (1u << f)) out |= ((1u << bits) - 1) << (f * bits); return out;
}
typedef struct { unsigned mask, bits, seen[64]; } Enumeration;
static int visit(void *ctx, const int *tuple, size_t n) {
    Enumeration *e = ctx; unsigned id = 0, k = 0;
    for (unsigned f = 0; f < 3; ++f) if (e->mask & (1u << f)) { CHECK(k < n); id |= (unsigned)tuple[k++] << (f * e->bits); }
    CHECK(k == n && id < 64 && !e->seen[id]); e->seen[id] = 1; return 1;
}
/* Expected Post is computed entirely from integer tuples and a Boolean matrix.
 * Neither rel_member nor a DD operation participates in the oracle. */
static void insert(pndd_mc_rel *r, unsigned char *matrix, unsigned slots, unsigned bits,
                   unsigned reads, unsigned writes, const int *a, const int *b, const int *cpy) {
    CHECK(pndd_mc_rel_add_cpy(r, reads ? a : NULL, writes ? b : NULL, cpy));
    unsigned n = 1u << (slots * bits);
    for (unsigned x = 0; x < n; ++x) for (unsigned y = 0; y < n; ++y) {
        unsigned ri = 0, wi = 0; int ok = 1;
        for (unsigned f = 0; f < slots; ++f) {
            int cur = (x >> (bits * f)) & ((1u << bits) - 1), next = (y >> (bits * f)) & ((1u << bits) - 1);
            int rd = !!(reads & (1u << f)), wr = !!(writes & (1u << f));
            if (rd) ok &= cur == a[ri++];
            if (wr) { ok &= next == ((!rd && cpy && cpy[wi]) ? cur : b[wi]); ++wi; }
            else ok &= next == cur;
        }
        if (ok) matrix[x * n + y] = 1;
    }
}
static void verify(pndd_mc_domain *d, pndd_mc_set *src, pndd_mc_set *dst, pndd_mc_rel *r,
                   unsigned slots, unsigned bits, unsigned mask, const unsigned char *input, const unsigned char *matrix) {
    unsigned n = 1u << (slots * bits), keep = expand_mask(mask, bits), expected[64] = {0};
    for (unsigned x = 0; x < n; ++x) if (input[x & keep]) for (unsigned y = 0; y < n; ++y)
        if (matrix[x * n + y]) expected[y & keep] = 1;
    uint32_t proj[3]; int k = projection(mask, proj);
    pndd_mc_set *explicit = pndd_mc_set_create(d, k, proj), *saved = pndd_mc_set_create(d, k, proj); CHECK(explicit && saved);
    CHECK(pndd_mc_set_copy(saved, src)); double pairs = pndd_mc_rel_count(r);
    unsigned count = 0;
    for (unsigned y = 0; y < n; ++y) if (expected[y]) { int tuple[3]; unpack(y, mask, bits, tuple); CHECK(pndd_mc_set_add(explicit, k ? tuple : NULL)); ++count; }
    for (unsigned pass = 0; pass < 3; ++pass) {
        if (pass == 2) CHECK(pndd_mc_collect(d));
        pndd_stats before = pndd_mc_stats(d);
        CHECK(pndd_mc_set_next(dst, src, r));
        CHECK(pndd_mc_stats(d).external_runs == before.external_runs + 1);
        CHECK(pndd_mc_set_equal(dst, explicit) == 1); CHECK(pndd_mc_set_count(dst) == count);
        CHECK(pndd_mc_set_equal(src, saved) == 1); CHECK(pndd_mc_rel_count(r) == pairs);
    }
    Enumeration e = {.mask = mask, .bits = bits}; CHECK(pndd_mc_set_enum(dst, visit, &e) == 1);
    for (unsigned y = 0; y < n; ++y) {
        CHECK(e.seen[y] == expected[y]);
        if (!(y & ~keep)) { int tuple[3]; unpack(y, mask, bits, tuple); CHECK(pndd_mc_set_member(dst, k ? tuple : NULL) == (int)expected[y]); }
    }
    int example[3]; CHECK(pndd_mc_set_example(dst, k ? example : NULL) == (count ? 1 : 0));
    CHECK(pndd_mc_set_next(src, src, r)); CHECK(pndd_mc_set_equal(src, explicit) == 1); CHECK(pndd_mc_set_copy(src, saved));
    pndd_mc_set_destroy(saved); pndd_mc_set_destroy(explicit);
}
static pndd_mc_config config(unsigned workers, unsigned bits) {
    pndd_mc_config c = pndd_mc_default_config(); c.value_bits = bits; c.engine.workers = workers;
    c.engine.memory_bytes = 16u * 1024 * 1024; c.engine.cache_entries = 64; c.engine.gc_interval = 13;
    c.engine.label_parallel = 1; c.engine.task_threshold = 2; c.engine.chunk_size = 1; return c;
}
static void matrix_cases(unsigned workers, unsigned slots, unsigned bits) {
    pndd_mc_config c = config(workers, bits); pndd_mc_domain *d = pndd_mc_open(&c, slots); CHECK(d);
    CHECK(pndd_mc_collect(d)); uint64_t baseline = pndd_mc_stats(d).nodes_live; unsigned cases = 0;
    unsigned masks = 1u << slots, n = 1u << (slots * bits);
    for (unsigned mask = 0; mask < masks; ++mask) for (unsigned rd = 0; rd < masks; ++rd) for (unsigned wr = 0; wr < masks; ++wr) {
        if ((rd | wr) & ~mask) continue;
        uint32_t p[3], rp[3], wp[3]; int k = projection(mask, p), rk = projection(rd, rp), wk = projection(wr, wp);
        pndd_mc_set *src = pndd_mc_set_create(d, k, p), *dst = pndd_mc_set_create(d, k, p);
        pndd_mc_rel *r = pndd_mc_rel_create_rw(d, rk, rp, wk, wp); CHECK(src && dst && r);
        unsigned char matrix[4096] = {0}, input[64] = {0}; unsigned keep = expand_mask(mask, bits);
        verify(d, src, dst, r, slots, bits, mask, input, matrix); /* both empty */
        CHECK(pndd_mc_set_fill(src)); for (unsigned x = 0; x < n; ++x) if (!(x & ~keep)) input[x] = 1;
        verify(d, src, dst, r, slots, bits, mask, input, matrix); /* empty relation */
        for (unsigned step = 0; step < 12; ++step) {
            int a[3], b[3], copy[3]; for (unsigned i = 0; i < 3; ++i) { a[i] = rnd() % (1u << bits); b[i] = rnd() % (1u << bits); copy[i] = rnd() & 1; }
            insert(r, matrix, slots, bits, rd, wr, a, b, step % 3 ? copy : NULL);
        }
        verify(d, src, dst, r, slots, bits, mask, input, matrix); /* true root / free source fields */
        for (unsigned iteration = 0; iteration < 3; ++iteration) {
            CHECK(pndd_mc_set_clear(src)); memset(input, 0, sizeof(input));
            for (unsigned x = 0; x < n; ++x) if (!(x & ~keep) && (rnd() % 3 == 0)) {
                int tuple[3]; unpack(x, mask, bits, tuple); input[x] = 1; CHECK(pndd_mc_set_add(src, k ? tuple : NULL));
            }
            verify(d, src, dst, r, slots, bits, mask, input, matrix);
        }
        CHECK(!pndd_mc_set_next(dst, src, NULL));
        pndd_mc_rel_destroy(r); pndd_mc_set_destroy(src); pndd_mc_set_destroy(dst); ++cases;
    }
    CHECK(pndd_mc_collect(d)); CHECK(pndd_mc_stats(d).nodes_live == baseline); CHECK(pndd_mc_close(d));
    printf("C2B matrix oracle PASS slots=%u bits=%u layouts=%u workers=%u\n", slots, bits, cases, workers);
}
static void dense_and_order(unsigned workers) {
    pndd_mc_config c = config(workers, 2); c.engine.gc_interval = 1;
    pndd_mc_domain *d = pndd_mc_open(&c, 2); CHECK(d);
    pndd_mc_set *s = pndd_mc_set_create(d, -1, NULL), *out = pndd_mc_set_create(d, -1, NULL); CHECK(s && out);
    pndd_mc_rel *r = pndd_mc_rel_create(d, -1, NULL); CHECK(r);
    unsigned char matrix[4096] = {0}, input[64] = {0}; int tuple[] = {1, 2};
    CHECK(pndd_mc_set_add(s, tuple)); input[9] = 1;
    for (unsigned x = 0; x < 16; ++x) for (unsigned y = 0; y < 16; ++y) {
        int a[3], b[3]; unpack(x, 3, 2, a); unpack(y, 3, 2, b); insert(r, matrix, 2, 2, 3, 3, a, b, NULL);
    }
    verify(d, s, out, r, 2, 2, 3, input, matrix); CHECK(pndd_mc_set_count(out) == 16);
    pndd_mc_rel_destroy(r);
    /* Force state-first and relation-first with a free intervening field. */
    for (unsigned f = 0; f < 2; ++f) {
        uint32_t field = f, other = 1 - f;
        r = pndd_mc_rel_create(d, 1, &field); CHECK(r); int zero = 0, one = 1; CHECK(pndd_mc_rel_add(r, &zero, &one));
        pndd_mc_set *single = pndd_mc_set_create(d, 1, &other); CHECK(single); CHECK(pndd_mc_set_add(single, &one));
        CHECK(pndd_mc_set_fill(s)); CHECK(pndd_mc_set_join(s, s, single)); CHECK(pndd_mc_set_next(out, s, r));
        CHECK(pndd_mc_set_count(out) == 1); tuple[f] = tuple[other] = 1; CHECK(pndd_mc_set_member(out, tuple) == 1);
        CHECK(!pndd_mc_set_next(single, single, r)); CHECK(pndd_mc_set_count(single) == 1);
        CHECK(!pndd_mc_set_next(single, s, r)); CHECK(pndd_mc_set_count(single) == 1);
        pndd_mc_set_destroy(single); pndd_mc_rel_destroy(r);
    }
    pndd_mc_set_destroy(s); pndd_mc_set_destroy(out); CHECK(pndd_mc_close(d));
}
static void cache_projection_and_overlap(unsigned workers) {
    pndd_mc_config c = config(workers, 2); c.engine.gc_interval = 0;
    pndd_mc_domain *d = pndd_mc_open(&c, 2); CHECK(d);
    uint32_t f = 0;
    pndd_mc_set *small = pndd_mc_set_create(d, 1, &f), *large = pndd_mc_set_create(d, -1, NULL);
    pndd_mc_set *a = pndd_mc_set_create(d, 1, &f), *b = pndd_mc_set_create(d, -1, NULL);
    pndd_mc_rel *r = pndd_mc_rel_create_rw(d, 0, NULL, 1, &f); CHECK(small && large && a && b && r);
    CHECK(pndd_mc_set_fill(small)); CHECK(pndd_mc_set_fill(large)); int one = 1;
    CHECK(pndd_mc_rel_add(r, NULL, &one));
    /* Both source roots are true, and the same result DAG must be interpreted
     * with different projections. Exercise shared memo without boundary GC. */
    for (unsigned pass = 0; pass < 4; ++pass) {
        CHECK(pndd_mc_set_next(a, small, r)); CHECK(pndd_mc_set_next(b, large, r));
        CHECK(pndd_mc_set_count(a) == 1); CHECK(pndd_mc_set_count(b) == 4);
    }
    pndd_mc_rel_destroy(r); r = pndd_mc_rel_create_rw(d, 1, &f, -1, NULL); CHECK(r);
    /* Fixed next=2 and identity on a write-only second field overlap only
     * partly. Distinct first-field reads both map to the same first output. */
    int zero = 0, dst[] = {0, 2}, copy[] = {0, 1};
    CHECK(pndd_mc_rel_add(r, &zero, dst)); CHECK(pndd_mc_rel_add_cpy(r, &one, dst, copy));
    CHECK(pndd_mc_set_clear(large)); int x[] = {0, 0}, y[] = {1, 3};
    CHECK(pndd_mc_set_add(large, x)); CHECK(pndd_mc_set_add(large, y));
    CHECK(pndd_mc_set_next(b, large, r)); CHECK(pndd_mc_set_count(b) == 2);
    CHECK(pndd_mc_set_member(b, dst) == 1); dst[1] = 3; CHECK(pndd_mc_set_member(b, dst) == 1);
    /* Drop old results and relation before GC so recycled IDs cannot leave
     * an old Post memo entry attached to a newly constructed relation. */
    CHECK(pndd_mc_set_clear(b)); CHECK(pndd_mc_rel_clear(r)); CHECK(pndd_mc_collect(d));
    dst[0] = 3; dst[1] = 1; CHECK(pndd_mc_rel_add(r, &zero, dst));
    CHECK(pndd_mc_set_next(b, large, r)); CHECK(pndd_mc_set_count(b) == 1); CHECK(pndd_mc_set_member(b, dst) == 1);
    pndd_mc_rel_destroy(r); pndd_mc_set_destroy(small); pndd_mc_set_destroy(large);
    pndd_mc_set_destroy(a); pndd_mc_set_destroy(b); CHECK(pndd_mc_close(d));
}
static void wide_and_gc(unsigned workers) {
    pndd_mc_config c = config(workers, 6); c.engine.gc_interval = 0;
    pndd_mc_domain *d = pndd_mc_open(&c, 2); CHECK(d);
    pndd_mc_set *s = pndd_mc_set_create(d, -1, NULL), *out = pndd_mc_set_create(d, -1, NULL); CHECK(s && out); CHECK(pndd_mc_set_fill(s));
    uint32_t first = 0; pndd_mc_rel *r = pndd_mc_rel_create_rw(d, 1, &first, -1, NULL); CHECK(r);
    /* 64 distinct first-field relation edges map to an overlapping output
     * label with different children: exercises vector growth and canonical OR. */
    for (int i = 0; i < 64; ++i) { int dst[] = {0, i}; CHECK(pndd_mc_rel_add(r, &i, dst)); }
    CHECK(pndd_mc_collect(d)); pndd_stats before = pndd_mc_stats(d);
    CHECK(pndd_mc_set_next(out, s, r)); pndd_stats after = pndd_mc_stats(d);
    CHECK(after.external_runs == before.external_runs + 1); CHECK(after.collections == before.collections);
#ifdef PNDD_MC_TEST_GC
    CHECK(after.label_collections >= before.label_collections + 2);
#endif
    CHECK(pndd_mc_set_count(out) == 64);
    for (int i = 0; i < 64; ++i) { int dst[] = {0, i}; CHECK(pndd_mc_set_member(out, dst) == 1); }
    CHECK(pndd_mc_set_count(s) == 4096); pndd_mc_set_destroy(s); pndd_mc_set_destroy(out); pndd_mc_rel_destroy(r); CHECK(pndd_mc_close(d));
    c.value_bits = 16; d = pndd_mc_open(&c, 2); CHECK(d);
    s = pndd_mc_set_create(d, -1, NULL); out = pndd_mc_set_create(d, -1, NULL); r = pndd_mc_rel_create_rw(d, 0, NULL, -1, NULL); CHECK(s && out && r);
    int vals[] = {65535, 32768}, copy[] = {1, 1}; CHECK(pndd_mc_set_fill(s)); CHECK(pndd_mc_rel_add_cpy(r, NULL, vals, copy));
    CHECK(pndd_mc_set_next(out, s, r)); CHECK(pndd_mc_set_count(out) == ldexp(1.0, 32)); CHECK(pndd_mc_set_equal(out, s) == 1);
    CHECK(pndd_mc_rel_clear(r)); CHECK(pndd_mc_rel_add(r, NULL, vals)); CHECK(pndd_mc_set_next(out, s, r));
    CHECK(pndd_mc_set_count(out) == 1 && pndd_mc_set_member(out, vals) == 1);
    pndd_mc_set_destroy(s); pndd_mc_set_destroy(out); pndd_mc_rel_destroy(r); CHECK(pndd_mc_close(d));
    d = pndd_mc_open(&c, 0); CHECK(d); s = pndd_mc_set_create(d, -1, NULL); r = pndd_mc_rel_create(d, -1, NULL); CHECK(s && r);
    CHECK(pndd_mc_set_fill(s)); CHECK(pndd_mc_rel_add(r, NULL, NULL)); CHECK(pndd_mc_set_next(s, s, r)); CHECK(pndd_mc_set_count(s) == 1);
    CHECK(pndd_mc_rel_clear(r)); CHECK(pndd_mc_set_next(s, s, r)); CHECK(pndd_mc_set_count(s) == 0);
    pndd_mc_set_destroy(s); pndd_mc_rel_destroy(r); CHECK(pndd_mc_close(d));
    puts("C2B overlapping images / temporary GC roots / 16-bit / epsilon PASS");
}
int main(int argc, char **argv) {
    unsigned workers = argc > 1 ? (unsigned)atoi(argv[1]) : 1;
    matrix_cases(workers, 2, 2); matrix_cases(workers, 3, 1); dense_and_order(workers);
    cache_projection_and_overlap(workers); wide_and_gc(workers); return 0;
}
