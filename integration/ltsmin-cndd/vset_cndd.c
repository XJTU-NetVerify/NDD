/* LTSmin upstream 07f9bf8 bridge. The C-NDD engine owns Lace; every entry below runs on
 * the ordinary frontend thread, never inside another backend's worker task. */
#include <hre/config.h>
#include <hre/user.h>
#include <vset-lib/vdom_object.h>
#include <pndd_mc.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "cndd_identity.h"

static int workers = 1, memory_mib = 64, cache_log2 = 12, gc_interval = 0, report_stats;
struct poptOption cndd_options[] = {
    {"cndd-workers", 0, POPT_ARG_INT | POPT_ARGFLAG_SHOW_DEFAULT, &workers, 0, "C-NDD worker count", "N"},
    {"cndd-memory-mib", 0, POPT_ARG_INT | POPT_ARGFLAG_SHOW_DEFAULT, &memory_mib, 0, "C-NDD engine memory budget in MiB", "N"},
    {"cndd-cache-log2", 0, POPT_ARG_INT | POPT_ARGFLAG_SHOW_DEFAULT, &cache_log2, 0, "log2 C-NDD shared cache entries", "N"},
    {"cndd-gc-interval", 0, POPT_ARG_INT | POPT_ARGFLAG_SHOW_DEFAULT, &gc_interval, 0, "Collect every N admissions (0 disables interval)", "N"},
    {"cndd-stats", 0, POPT_ARG_NONE, &report_stats, 0, "Report C-NDD lifecycle counters at exit", NULL},
    POPT_TABLEEND
};
struct vector_domain {
    struct vector_domain_shared shared;
    pndd_mc_domain *core;
    uint64_t baseline_nodes;
    vset_t sets;
    vrel_t relations;
};
struct vector_set { vdom_t dom; pndd_mc_set *core; vset_t prev, next; };
/* These first three members are the LTSmin vrel ABI, including expand hooks. */
struct vector_relation { vdom_t dom; expand_cb expand; void *expand_ctx; pndd_mc_rel *core; vrel_t prev, next; };
static vdom_t active;
static int exit_registered;
static void check(vdom_t dom, int ok, const char *operation) {
    if (!ok) Abort("C-NDD %s: %s", operation, pndd_mc_error(dom ? dom->core : NULL));
}
static const uint32_t *projection(int k, const int *src, uint32_t *dst) {
    if (k < -1 || k > 256 || (k == -1 && src) || (k > 0 && !src)) Abort("Invalid C-NDD projection");
    for (int i = 0; i < k; ++i) { if (src[i] < 0) Abort("Negative C-NDD projection entry"); dst[i] = (uint32_t)src[i]; }
    return k <= 0 ? NULL : dst;
}
static vset_t set_create(vdom_t dom, int k, int *proj) {
    uint32_t p[256]; const uint32_t *q = projection(k, proj, p);
    vset_t s = RTmalloc(sizeof(*s)); memset(s, 0, sizeof(*s)); s->dom = dom;
    s->core = pndd_mc_set_create(dom->core, k, q); check(dom, s->core != NULL, "set_create");
    s->next = dom->sets; if (s->next) s->next->prev = s; dom->sets = s; return s;
}
static void set_destroy(vset_t s) {
    if (!s) return;
    if (s->prev) s->prev->next = s->next; else s->dom->sets = s->next;
    if (s->next) s->next->prev = s->prev;
    pndd_mc_set_destroy(s->core); RTfree(s);
}
static vrel_t rel_create_rw(vdom_t dom, int rk, int *rp, int wk, int *wp) {
    uint32_t r[256], w[256]; const uint32_t *read = projection(rk, rp, r), *write = projection(wk, wp, w);
    vrel_t rel = RTmalloc(sizeof(*rel)); memset(rel, 0, sizeof(*rel)); rel->dom = dom;
    rel->core = pndd_mc_rel_create_rw(dom->core, rk, read, wk, write); check(dom, rel->core != NULL, "rel_create_rw");
    rel->next = dom->relations; if (rel->next) rel->next->prev = rel; dom->relations = rel; return rel;
}
static vrel_t rel_create(vdom_t dom, int k, int *p) { return rel_create_rw(dom, k, p, k, p); }
static void rel_destroy(vrel_t r) {
    if (!r) return;
    if (r->prev) r->prev->next = r->next; else r->dom->relations = r->next;
    if (r->next) r->next->prev = r->prev;
    pndd_mc_rel_destroy(r->core); RTfree(r);
}
void cndd_collect(void) { if (active) check(active, pndd_mc_collect(active->core), "collect"); }
void cndd_shutdown(void) {
    if (!active) return;
    vdom_t dom = active;
    while (dom->sets) set_destroy(dom->sets);
    while (dom->relations) rel_destroy(dom->relations);
    cndd_collect();
    pndd_stats s = pndd_mc_stats(dom->core);
    if (s.nodes_live != dom->baseline_nodes) Abort("C-NDD cleanup did not restore initial live-node count");
    if (report_stats) {
        fprintf(stderr, "CNDD_STATS ndd_gc=%llu label_gc=%llu nodes_after_cleanup=%llu baseline_nodes=%llu peak_native=%llu external_runs=%llu\n",
                (unsigned long long)s.collections, (unsigned long long)s.label_collections,
                (unsigned long long)s.nodes_live, (unsigned long long)dom->baseline_nodes,
                (unsigned long long)s.peak_native_bytes, (unsigned long long)s.external_runs);
    }
    check(dom, pndd_mc_close(dom->core), "close");
    RTfree(dom->shared.names); RTfree(dom); active = NULL;
}
static int set_empty(vset_t s) { int v = pndd_mc_set_empty(s->core); check(s->dom, v >= 0, "empty"); return v; }
static int set_equal(vset_t a, vset_t b) { int v = pndd_mc_set_equal(a->core, b->core); check(a->dom, v >= 0, "equal"); return v; }
static void set_clear(vset_t s) { check(s->dom, pndd_mc_set_clear(s->core), "clear"); }
static void set_add(vset_t s, const int *t) { check(s->dom, pndd_mc_set_add(s->core, t), "add"); }
static int set_member(vset_t s, const int *t) { int v = pndd_mc_set_member(s->core, t); check(s->dom, v >= 0, "member"); return v; }
static void set_example(vset_t s, int *t) { check(s->dom, pndd_mc_set_example(s->core, t) == 1, "example"); }
#define SET_BINARY(name, core_name) \
static void name(vset_t dst, vset_t src) { check(dst->dom, core_name(dst->core, src->core), #name); }
SET_BINARY(set_copy, pndd_mc_set_copy)
SET_BINARY(set_union, pndd_mc_set_union)
SET_BINARY(set_intersect, pndd_mc_set_intersect)
SET_BINARY(set_minus, pndd_mc_set_minus)
SET_BINARY(set_zip, pndd_mc_set_zip)
SET_BINARY(set_project, pndd_mc_set_project)
static void set_project_minus(vset_t d, vset_t s, vset_t m) { check(d->dom, pndd_mc_set_project_minus(d->core, s->core, m->core), "project_minus"); }
static void set_join(vset_t d, vset_t a, vset_t b) { check(d->dom, pndd_mc_set_join(d->core, a->core, b->core), "join"); }
static void set_next(vset_t d, vset_t s, vrel_t r) { check(d->dom, pndd_mc_set_next(d->core, s->core, r->core), "next"); }
static void rel_add(vrel_t r, const int *s, const int *d) { check(r->dom, pndd_mc_rel_add(r->core, s, d), "rel_add"); }
static void rel_add_cpy(vrel_t r, const int *s, const int *d, const int *c) { check(r->dom, pndd_mc_rel_add_cpy(r->core, s, d, c), "rel_add_cpy"); }
static int separates_rw(void) { return 1; }
static void count_nodes(vdom_t dom, int ok, uint64_t n, long *nodes) {
    check(dom, ok, "node_count"); if (n > LONG_MAX) Abort("C-NDD node count exceeds LONG_MAX"); *nodes = (long)n;
}
static void set_count(vset_t s, long *nodes, double *elements) {
    if (nodes) { uint64_t n = 0; int ok = pndd_mc_set_node_count(s->core, &n); count_nodes(s->dom, ok, n, nodes); }
    if (elements) { *elements = pndd_mc_set_count(s->core); check(s->dom, !isnan(*elements), "set_count"); }
}
static void rel_count(vrel_t r, long *nodes, double *elements) {
    if (nodes) { uint64_t n = 0; int ok = pndd_mc_rel_node_count(r->core, &n); count_nodes(r->dom, ok, n, nodes); }
    if (elements) { *elements = pndd_mc_rel_count(r->core); check(r->dom, !isnan(*elements), "rel_count"); }
}
struct enumeration { vset_element_cb cb; void *context; };
static int enumerate(void *opaque, const int *tuple, size_t n) {
    struct enumeration *e = opaque; int copy[256]; if (n) memcpy(copy, tuple, n * sizeof(int));
    e->cb(e->context, n ? copy : NULL); return 1;
}
static void set_enum(vset_t s, vset_element_cb cb, void *context) {
    if (!cb) Abort("C-NDD NULL enumeration callback");
    vdom_t dom = s->dom; /* Callback may destroy the enumerated wrapper. */
    struct enumeration e = {cb, context}; check(dom, pndd_mc_set_enum(s->core, enumerate, &e) == 1, "enum");
}
struct set_update_context { vset_t dst; vset_update_cb cb; void *context; };
static int update_set(pndd_mc_set *unused, void *opaque, const int *tuple, size_t n) {
    (void)unused; struct set_update_context *u = opaque; int copy[256]; if (n) memcpy(copy, tuple, n * sizeof(int));
    u->cb(u->dst, u->context, n ? copy : NULL); return 1;
}
static void set_update(vset_t dst, vset_t src, vset_update_cb cb, void *context) {
    if (!cb) Abort("C-NDD NULL set update callback");
    struct set_update_context u = {dst, cb, context};
    check(dst->dom, pndd_mc_set_update(dst->core, src->core, update_set, &u) == 1, "set_update");
}
struct rel_update_context { vrel_t dst; vrel_update_cb cb; void *context; };
static int update_rel(pndd_mc_rel *unused, void *opaque, const int *tuple, size_t n) {
    (void)unused; struct rel_update_context *u = opaque; int copy[256]; if (n) memcpy(copy, tuple, n * sizeof(int));
    u->cb(u->dst, u->context, n ? copy : NULL); return 1;
}
static void rel_update(vrel_t dst, vset_t src, vrel_update_cb cb, void *context) {
    if (!cb) Abort("C-NDD NULL relation update callback");
    struct rel_update_context u = {dst, cb, context};
    check(dst->dom, pndd_mc_rel_update(dst->core, src->core, update_rel, &u) == 1, "rel_update");
}
vdom_t vdom_create_cndd(int n) {
    if (active || n < 0 || n > 256) Abort("C-NDD requires one active domain with 0..256 slots");
    if (workers < 1 || workers > 256 || memory_mib < 4 || memory_mib > 16384 || cache_log2 < 0 || cache_log2 > 26 || gc_interval < 0)
        Abort("Invalid C-NDD runtime option");
    pndd_mc_config c = pndd_mc_default_config(); c.engine.workers = workers;
    c.engine.memory_bytes = (size_t)memory_mib << 20; c.engine.cache_entries = (size_t)1 << cache_log2; c.engine.gc_interval = gc_interval;
    vdom_t d = RTmalloc(sizeof(*d)); memset(d, 0, sizeof(*d)); vdom_init_shared(d, n);
    d->core = pndd_mc_open(&c, n); check(d, d->core != NULL, "open"); active = d;
    cndd_collect(); d->baseline_nodes = pndd_mc_stats(d->core).nodes_live;
    if (!exit_registered) { if (atexit(cndd_shutdown)) Abort("Cannot register C-NDD cleanup"); exit_registered = 1; }
#define INSTALL(member, fn) d->shared.member = fn
    INSTALL(set_create, set_create); INSTALL(set_destroy, set_destroy); INSTALL(set_is_empty, set_empty);
    INSTALL(set_equal, set_equal); INSTALL(set_clear, set_clear); INSTALL(set_add, set_add); INSTALL(set_member, set_member);
    INSTALL(set_example, set_example); INSTALL(set_copy, set_copy); INSTALL(set_enum, set_enum);
    INSTALL(set_union, set_union); INSTALL(set_intersect, set_intersect); INSTALL(set_minus, set_minus); INSTALL(set_zip, set_zip);
    INSTALL(set_project, set_project); INSTALL(set_project_minus, set_project_minus); INSTALL(set_join, set_join);
    INSTALL(set_count, set_count); INSTALL(set_next, set_next); INSTALL(set_update, set_update); INSTALL(set_update_seq, set_update);
    INSTALL(rel_create, rel_create); INSTALL(rel_create_rw, rel_create_rw); INSTALL(rel_destroy, rel_destroy);
    INSTALL(rel_add, rel_add); INSTALL(rel_add_cpy, rel_add_cpy); INSTALL(rel_count, rel_count);
    INSTALL(rel_update, rel_update); INSTALL(rel_update_seq, rel_update); INSTALL(separates_rw, separates_rw);
    Warning(info, "C-NDD %s: %d slots, 16 bits, workers=%d, memory=%d MiB", CNDD_IDENTITY, n, workers, memory_mib);
    return d;
}
