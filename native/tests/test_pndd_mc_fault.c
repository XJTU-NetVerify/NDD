/* Test-only allocation injection, following the legacy clone_failure harness.
 * Compile the core once in this executable with the same feature defines as
 * pndd. No hooks or test API are added to the production library. */
#include "pndd_mc.h"
#include <sylvan.h>
#include <sylvan_int.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <stdio.h>
static _Atomic long fault_at = -1, fault_calls, fault_fired;
static int fault_kind;
static int inject(int kind) {
    if (fault_kind != kind) return 0;
    long i = atomic_fetch_add(&fault_calls, 1);
    if (i != atomic_load(&fault_at)) return 0;
    atomic_fetch_add(&fault_fired, 1); return 1;
}
static void *fault_calloc(size_t n, size_t s) { return inject(1) ? NULL : calloc(n, s); }
static uint64_t fault_makenode(uint32_t var, uint64_t low, uint64_t high) {
    return inject(2) ? PNDD_INVALID : mtbdd_makenode(var, low, high);
}
#define calloc fault_calloc
#undef sylvan_makenode
#define sylvan_makenode fault_makenode
#include "../src/pndd.c"
#undef calloc
#undef sylvan_makenode
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%s:%d: %s (kind=%d at=%ld calls=%ld)\n", __FILE__, __LINE__, #x, fault_kind, atomic_load(&fault_at), atomic_load(&fault_calls)); exit(1); } } while (0)
static void arm(int kind, long at) { fault_kind = kind; atomic_store(&fault_calls, 0); atomic_store(&fault_fired, 0); atomic_store(&fault_at, at); }
static void disarm(void) { fault_kind = 0; atomic_store(&fault_at, -1); }
static uint64_t references(void) {
    uint64_t total = 0; for (uint32_t i = 0; i < G->node_high; ++i) if (node(i)) total += node(i)->refs; return total;
}
static pndd_mc_domain *open_domain(unsigned workers, unsigned bits, unsigned mib) {
    pndd_mc_config c = pndd_mc_default_config(); c.value_bits = bits; c.engine.workers = workers;
    c.engine.memory_bytes = (size_t)mib << 20; c.engine.cache_entries = 64; c.engine.gc_interval = 0;
    c.engine.label_parallel = 1; c.engine.task_threshold = 2; c.engine.chunk_size = 1;
    pndd_mc_domain *d = pndd_mc_open(&c, 2); CHECK(d); return d;
}
/* The same immutable inputs are rebuilt for each injection so caches and
 * slabs cannot turn a late allocation-failure point into a cache hit. */
static long one_failure(unsigned workers, int operation, int kind, long at) {
    pndd_mc_domain *d = open_domain(workers, 6, 16); CHECK(pndd_mc_collect(d));
    uint64_t baseline = pndd_mc_stats(d).nodes_live;
    pndd_mc_set *s = pndd_mc_set_create(d, -1, NULL), *dst = pndd_mc_set_create(d, -1, NULL);
    uint32_t first = 0; pndd_mc_rel *r = pndd_mc_rel_create_rw(d, 1, &first, -1, NULL); CHECK(s && dst && r);
    CHECK(pndd_mc_set_fill(s)); int sentinel[] = {63, 63}; CHECK(pndd_mc_set_add(dst, sentinel));
    for (int i = 0; i < 40; ++i) { int y[] = {0, i}; CHECK(pndd_mc_rel_add(r, &i, y)); }
    CHECK(pndd_mc_collect(d));
    pndd_handle old_dst = dst->root, old_src = s->root, old_rel = r->path.root;
    uint64_t refs = references(); int x = 42, y[] = {3, 47};
    size_t saved_limit = G->allocation_limit;
    if (kind == 3) {
        /* Exhaust actual native budget inside Post, after inputs are built.
         * Flush unused worker credit before lowering the allocation ceiling.
         * last_gc_bytes avoids scheduling an unrelated boundary collection. */
        for (unsigned i = 0; i < G->config.workers; ++i) {
            atomic_fetch_sub(&G->bytes, G->worker_stats[i].credit);
            G->worker_stats[i].credit = 0;
        }
        G->last_gc_bytes = atomic_load(&G->bytes);
        G->allocation_limit = G->last_gc_bytes;
    }
    arm(kind, at);
    int ok = operation == 0 ? pndd_mc_set_next(dst, s, r) :
                             pndd_mc_rel_add(r, &x, y);
    long calls = atomic_load(&fault_calls), fired = atomic_load(&fault_fired); disarm();
    G->allocation_limit = saved_limit;
    int failed = kind == 3 || fired;
    if (failed) {
        CHECK(!ok); CHECK(strstr(pndd_mc_error(d), "exhausted"));
        CHECK(dst->root == old_dst && s->root == old_src && r->path.root == old_rel);
        CHECK(references() == refs);
    } else {
        /* Worker slab/cache ownership can change the allocation count. A
         * scheduled index beyond this run is a verified success, not evidence
         * of fault cleanup, and must not be counted as an injected failure. */
        CHECK(ok); CHECK(at < 0 || calls <= at);
    }
    /* The engine resets its operation error on admission. Explicit collection
     * must clean partial construction; it must not be necessary to publish it. */
    CHECK(pndd_mc_collect(d));
    if (failed) {
        CHECK(pndd_mc_set_count(dst) == 1); CHECK(pndd_mc_set_member(dst, sentinel) == 1);
        CHECK(pndd_mc_set_count(s) == 4096); CHECK(pndd_mc_rel_count(r) == 40 * 64);
        CHECK(pndd_mc_set_next(dst, s, r)); CHECK(pndd_mc_set_count(dst) == 40);
    } else {
        CHECK(pndd_mc_set_count(dst) == (operation == 0 ? 40 : 1));
        CHECK(pndd_mc_rel_count(r) == (operation == 0 ? 40 : 41) * 64);
    }
    pndd_mc_set_destroy(s); pndd_mc_set_destroy(dst); pndd_mc_rel_destroy(r);
    CHECK(pndd_mc_collect(d)); CHECK(pndd_mc_stats(d).nodes_live == baseline);
    CHECK(pndd_mc_close(d)); return at >= 0 && !failed ? -1 : calls;
}
static void failure_matrix(unsigned workers) {
    one_failure(workers, 0, 3, 0);
    printf("C3 Post native-budget exhaustion PASS workers=%u\n", workers);
    for (int op = 0; op < 2; ++op) for (int kind = 1; kind <= 2; ++kind) {
        long calls = one_failure(workers, op, kind, -1), tested = 0, unreached = 0; CHECK(calls > 0);
        for (long at = 0; at < calls; ++at) {
            /* Exhaust small call sequences; sample deep image recursion and
             * OR reduction at the start, powers of two, quartiles and tail. */
            if (calls > 48 && at >= 16 && at < calls - 4 && (at & (at - 1)) &&
                at != calls / 4 && at != calls / 2 && at != 3 * calls / 4) continue;
            if (one_failure(workers, op, kind, at) < 0) ++unreached; else ++tested;
        }
        CHECK(tested > 0);
        printf("C3 faults PASS workers=%u op=%s kind=%s baseline_calls=%ld checked_failures=%ld unreached=%ld\n",
               workers, op ? "relation-add" : "RelNext", kind == 1 ? "calloc" : "label-makenode", calls, tested, unreached);
    }
}
/* Migration of the old residual lifecycle counterexample: repeated uncached
 * OR of shared nonterminal children must not acquire persistent child refs. */
static void residual_lifecycle(unsigned workers) {
    pndd_mc_domain *d = open_domain(workers, 2, 4); CHECK(pndd_mc_collect(d)); uint64_t baseline = pndd_mc_stats(d).nodes_live;
    pndd_mc_set *a = pndd_mc_set_create(d, -1, NULL), *b = pndd_mc_set_create(d, -1, NULL), *out = pndd_mc_set_create(d, -1, NULL); CHECK(a && b && out);
    for (int x = 0; x < 4; ++x) for (int y = 0; y < 4; ++y) {
        int t[] = {x, y}; if (x < 3 && y < 2) CHECK(pndd_mc_set_add(a, t)); if (x > 0 && y > 0) CHECK(pndd_mc_set_add(b, t));
    }
    CHECK(pndd_mc_collect(d)); uint64_t refs = references();
    for (unsigned repeat = 0; repeat < 32; ++repeat) {
        CHECK(pndd_mc_set_copy(out, a)); CHECK(pndd_mc_set_union(out, b)); CHECK(references() == refs);
        for (int x = 0; x < 4; ++x) for (int y = 0; y < 4; ++y) {
            int t[] = {x, y}; CHECK(pndd_mc_set_member(out, t) == ((x < 3 && y < 2) || (x > 0 && y > 0)));
        }
        CHECK(pndd_mc_set_clear(out)); CHECK(pndd_mc_collect(d)); CHECK(references() == refs);
    }
    pndd_mc_set_destroy(a); pndd_mc_set_destroy(b); pndd_mc_set_destroy(out);
    CHECK(pndd_mc_collect(d)); CHECK(pndd_mc_stats(d).nodes_live == baseline); CHECK(pndd_mc_close(d));
    printf("C3 residual child-reference lifecycle PASS workers=%u repeats=32\n", workers);
}
static void real_exhaustion(unsigned workers) {
    pndd_mc_domain *d = open_domain(workers, 16, 4); CHECK(pndd_mc_collect(d)); uint64_t baseline = pndd_mc_stats(d).nodes_live;
    pndd_mc_set *s = pndd_mc_set_create(d, -1, NULL); CHECK(s);
    unsigned i; uint32_t seed = 0x331123; pndd_handle old = s->root;
    for (i = 0; i < 100000; ++i) {
        seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5;
        int tuple[] = {(int)(seed & 65535), (int)(seed >> 16)}; old = s->root;
        if (!pndd_mc_set_add(s, tuple)) break;
    }
    CHECK(i > 0 && i < 100000); CHECK(s->root == old); CHECK(strstr(pndd_mc_error(d), "exhausted"));
    pndd_stats stats = pndd_mc_stats(d);
    pndd_mc_set_destroy(s); CHECK(pndd_mc_collect(d)); CHECK(pndd_mc_stats(d).nodes_live == baseline);
    CHECK(pndd_mc_close(d));
    printf("C3 real 4MiB exhaustion PASS workers=%u inserts=%u native=%llu label_gc=%llu\n", workers, i,
           (unsigned long long)stats.native_bytes, (unsigned long long)stats.label_collections);
}
int main(int argc, char **argv) {
    unsigned workers = argc > 1 ? (unsigned)atoi(argv[1]) : 1;
    failure_matrix(workers); residual_lifecycle(workers); real_exhaustion(workers); return 0;
}
