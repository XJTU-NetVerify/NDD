/* Private MC implementation: shares admitted operation, node, label and GC
 * machinery with pndd.c. All stored roots own one reference. Projection/count
 * memo is operation-local; image/RelNext use dedicated shared cache tags. */
#include "pndd_mc.h"

struct pndd_mc_domain {
    pndd_engine *engine;
    size_t slots, sets, relations, enumerations;
    uint32_t bits;
    const char *error;
};
struct pndd_mc_set {
    pndd_mc_domain *domain;
    pndd_handle root;
    size_t arity;
    uint32_t proj[MAX_FIELDS];
};
static const char *mc_open_error = "No MC domain opened";

static int mc_enter(pndd_mc_domain *d) {
    pthread_mutex_lock(&admission);
    if (!d || !begin(d->engine)) {
        if (d) d->error = pndd_error(d->engine);
        pthread_mutex_unlock(&admission); return 0;
    }
    d->error = "Success"; return 1;
}
static int mc_invalid(pndd_mc_domain *d, const char *message) { d->error = message; return 0; }
static int mc_leave(pndd_mc_domain *d, int ok) {
    if (atomic_load(&G->failure)) { d->error = pndd_error(d->engine); ok = 0; }
    pthread_mutex_unlock(&admission); return ok;
}
static int mc_layout(const pndd_mc_set *a, const pndd_mc_set *b) {
    return a && b && a->domain == b->domain && a->arity == b->arity &&
           !memcmp(a->proj, b->proj, a->arity * sizeof(uint32_t));
}
static int mc_subset(const pndd_mc_set *a, const pndd_mc_set *b) {
    if (!a || !b || a->domain != b->domain) return 0;
    size_t j = 0;
    for (size_t i = 0; i < a->arity; ++i) {
        while (j < b->arity && b->proj[j] < a->proj[i]) ++j;
        if (j == b->arity || b->proj[j] != a->proj[i]) return 0;
    }
    return 1;
}
static int mc_tuple_valid(const pndd_mc_set *s, const int *tuple) {
    if (s->arity && !tuple) return mc_invalid(s->domain, "Missing tuple");
    for (size_t i = 0; i < s->arity; ++i)
        if (tuple[i] < 0 || (uint32_t)tuple[i] >= (1u << s->domain->bits))
            return mc_invalid(s->domain, "Tuple value outside logical domain");
    return 1;
}
/* No allocation or GC between taking ownership and publishing it. */
static int mc_replace(pndd_mc_set *s, uint32_t id) {
    pndd_handle next = own(id);
    if (next == PNDD_INVALID) return 0;
    uint32_t old = resolve(s->root);
    if (old == INVALID_ID) { --node((uint32_t)next)->refs; return 0; }
    s->root = next; --node(old)->refs; return 1;
}
pndd_mc_config pndd_mc_default_config(void) {
    pndd_mc_config c = {pndd_default_config(), 16, PNDD_BCDD};
    /* MC admissions include nonallocating clear/copy/query calls. Capacity
     * pressure still collects; a call-count interval is an explicit override. */
    c.engine.gc_interval = 0;
    return c;
}
pndd_mc_domain *pndd_mc_open(const pndd_mc_config *requested, size_t slots) {
    pndd_mc_config c = requested ? *requested : pndd_mc_default_config();
    if (slots > MAX_FIELDS || !c.value_bits || c.value_bits > 16 ||
        c.backend != PNDD_BCDD || c.engine.reverse_labels) {
        mc_open_error = "MC requires BCDD, 1..16 value bits, <=256 slots and forward labels"; return NULL;
    }
    pndd_mc_domain *d = calloc(1, sizeof(*d));
    if (!d) { mc_open_error = "MC domain allocation failed"; return NULL; }
    pndd_field fields[MAX_FIELDS]; size_t physical = slots ? slots : 1;
    for (size_t i = 0; i < physical; ++i) fields[i] = (pndd_field){2 * c.value_bits + 1, PNDD_BCDD};
    d->engine = pndd_open(&c.engine, fields, physical);
    if (!d->engine) { mc_open_error = pndd_error(NULL); free(d); return NULL; }
    d->slots = slots; d->bits = c.value_bits; d->error = "Success"; return d;
}
int pndd_mc_close(pndd_mc_domain *d) {
    if (!d) return 0;
    pthread_mutex_lock(&admission);
    if (d->sets || d->relations || d->enumerations) {
        d->error = "MC domain still has live sets, relations or enumeration snapshots";
        pthread_mutex_unlock(&admission); return 0;
    }
    if (d->engine == G) destroy_engine();
    free(d); pthread_mutex_unlock(&admission); return 1;
}
const char *pndd_mc_error(const pndd_mc_domain *d) { return d ? d->error : mc_open_error; }
int pndd_mc_collect(pndd_mc_domain *d) {
    if (!mc_enter(d)) return 0;
    return mc_leave(d, collect_internal());
}
pndd_stats pndd_mc_stats(pndd_mc_domain *d) {
    return pndd_get_stats(d ? d->engine : NULL);
}
pndd_mc_set *pndd_mc_set_create(pndd_mc_domain *d, int k, const uint32_t *proj) {
    if (!mc_enter(d)) return NULL;
    int valid = k >= -1 && (k == -1 ? !proj : (size_t)k <= d->slots && (!k || proj));
    if (valid && k > 0) for (int i = 0; i < k; ++i)
        if (proj[i] >= d->slots || (i && proj[i - 1] >= proj[i])) valid = 0;
    pndd_mc_set *s = NULL;
    if (!valid) mc_invalid(d, "Invalid projection");
    else {
        s = calloc(1, sizeof(*s));
        if (!s) mc_invalid(d, "MC set allocation failed");
        else {
            s->domain = d; s->arity = k == -1 ? d->slots : (size_t)k; s->root = own(0);
            for (size_t i = 0; i < s->arity; ++i) s->proj[i] = k == -1 ? (uint32_t)i : proj[i];
            if (s->root == PNDD_INVALID) { free(s); s = NULL; } else ++d->sets;
        }
    }
    mc_leave(d, s != NULL); return s;
}
void pndd_mc_set_destroy(pndd_mc_set *s) {
    if (!s) return;
    pthread_mutex_lock(&admission);
    pndd_mc_domain *d = s->domain;
    if (d->engine == G) { reset_failure(); uint32_t id = resolve(s->root); if (id != INVALID_ID) --node(id)->refs; }
    --d->sets; free(s); pthread_mutex_unlock(&admission);
}
size_t pndd_mc_set_arity(const pndd_mc_set *s) { return s ? s->arity : 0; }
static int mc_constant(pndd_mc_set *s, uint32_t value) {
    if (!s || !mc_enter(s->domain)) return 0;
    return mc_leave(s->domain, mc_replace(s, value));
}
int pndd_mc_set_clear(pndd_mc_set *s) { return mc_constant(s, 0); }
int pndd_mc_set_fill(pndd_mc_set *s) { return mc_constant(s, 1); }
int pndd_mc_set_empty(const pndd_mc_set *s) {
    if (!s || !mc_enter(s->domain)) return -1;
    uint32_t id = resolve(s->root);
    return mc_leave(s->domain, id != INVALID_ID) ? id == 0 : -1;
}
int pndd_mc_set_equal(const pndd_mc_set *a, const pndd_mc_set *b) {
    if (!a || !b || !mc_enter(a->domain)) return -1;
    int equal = mc_layout(a, b) && a->root == b->root;
    return mc_leave(a->domain, 1) ? equal : -1;
}
int pndd_mc_set_copy(pndd_mc_set *dst, const pndd_mc_set *src) {
    if (!dst || !mc_enter(dst->domain)) return 0;
    int ok = mc_layout(dst, src) ? mc_replace(dst, resolve(src->root)) :
        mc_invalid(dst->domain, "Copy requires equal projections");
    return mc_leave(dst->domain, ok);
}

TASK_DECL_2(uint32_t, mc_add_task, const pndd_mc_set *, const int *);
TASK_IMPL_2(uint32_t, mc_add_task, const pndd_mc_set *, s, const int *, tuple) {
    uint32_t id = 1;
    for (size_t i = s->arity; i > 0 && id != INVALID_ID; --i) {
        uint32_t field = s->proj[i - 1]; uint64_t value = truth(field);
        RootFrame r; root_push(&r, field, &value, sizeof(value), 1);
        for (uint32_t bit = s->domain->bits; bit > 0 && value != PNDD_INVALID; --bit) {
            unsigned positive = ((unsigned)tuple[i - 1] >> (bit - 1)) & 1;
            value = make_label(field, 2 * (bit - 1), positive ? 0 : value, positive ? value : 0);
        }
        Edge edge = {value, id}; id = make_node(field, &edge, 1); root_pop(&r);
    }
    return CALL(apply_rec, PNDD_OR, resolve(s->root), id, 0);
}
int pndd_mc_set_add(pndd_mc_set *s, const int *tuple) {
    if (!s || !mc_enter(s->domain)) return 0;
    int ok = mc_tuple_valid(s, tuple);
    if (ok) { atomic_fetch_add(&G->runs, 1); ok = mc_replace(s, RUN(mc_add_task, s, tuple)); }
    return mc_leave(s->domain, ok);
}
static int mc_label_member(uint32_t field, uint64_t label_value, unsigned value) {
    while (label_value && label_value != truth(field)) {
        uint32_t var = label_var(field, label_value);
        label_value = branch(field, label_value, var, (value >> (var / 2)) & 1);
    }
    return label_value != 0;
}
int pndd_mc_set_member(const pndd_mc_set *s, const int *tuple) {
    if (!s || !mc_enter(s->domain)) return -1;
    int ok = mc_tuple_valid(s, tuple); uint32_t id = 0;
    if (ok) {
        id = resolve(s->root); size_t pos = 0;
        while (id >= 2 && id != INVALID_ID) {
            Node *n = node(id);
            while (pos < s->arity && s->proj[pos] < n->field) ++pos;
            if (pos == s->arity || s->proj[pos] != n->field) { fail(3); break; }
            id = 0;
            for (size_t i = 0; i < n->count; ++i)
                if (mc_label_member(n->field, n->edges[i].label, (unsigned)tuple[pos])) { id = n->edges[i].target; break; }
        }
    }
    return mc_leave(s->domain, ok) ? (int)id : -1;
}
static int mc_binary(pndd_mc_set *dst, const pndd_mc_set *src, uint32_t op) {
    if (!dst || !mc_enter(dst->domain)) return 0;
    int ok = mc_layout(dst, src);
    if (!ok) mc_invalid(dst->domain, "Set operation requires equal projections");
    else {
        uint32_t a = resolve(dst->root), b = resolve(src->root);
        atomic_fetch_add(&G->runs, 1); ok = mc_replace(dst, RUN(apply_rec, op, a, b, 0));
    }
    return mc_leave(dst->domain, ok);
}
int pndd_mc_set_union(pndd_mc_set *d, const pndd_mc_set *s) { return mc_binary(d, s, PNDD_OR); }
int pndd_mc_set_intersect(pndd_mc_set *d, const pndd_mc_set *s) { return mc_binary(d, s, PNDD_AND); }
int pndd_mc_set_minus(pndd_mc_set *d, const pndd_mc_set *s) { return mc_binary(d, s, PNDD_DIFF); }
typedef struct { uint32_t a, b, joined, remaining; } McZip;
VOID_TASK_DECL_1(mc_zip_task, McZip *);
VOID_TASK_IMPL_1(mc_zip_task, McZip *, z) {
    z->joined = CALL(apply_rec, PNDD_OR, z->a, z->b, 0);
    z->remaining = CALL(apply_rec, PNDD_DIFF, z->b, z->a, 0);
}
int pndd_mc_set_zip(pndd_mc_set *dst, pndd_mc_set *src) {
    if (!dst || !mc_enter(dst->domain)) return 0;
    int ok = src != dst && mc_layout(dst, src);
    if (!ok) mc_invalid(dst->domain, "Zip requires distinct sets with equal projections");
    else {
        McZip z = {resolve(dst->root), resolve(src->root), INVALID_ID, INVALID_ID};
        atomic_fetch_add(&G->runs, 1); RUN(mc_zip_task, &z);
        pndd_handle joined = own(z.joined), remaining = own(z.remaining);
        ok = joined != PNDD_INVALID && remaining != PNDD_INVALID;
        if (ok) { dst->root = joined; src->root = remaining; --node(z.a)->refs; --node(z.b)->refs; }
        else { if (joined != PNDD_INVALID) --node((uint32_t)joined)->refs; if (remaining != PNDD_INVALID) --node((uint32_t)remaining)->refs; }
    }
    return mc_leave(dst->domain, ok);
}

typedef struct { unsigned char keep[MAX_FIELDS]; uint32_t *memo; uint32_t limit; } McProject;
TASK_DECL_2(uint32_t, mc_project_rec, uint32_t, McProject *);
TASK_IMPL_2(uint32_t, mc_project_rec, uint32_t, id, McProject *, p) {
    YIELD_NEWFRAME();
    if (id == INVALID_ID || atomic_load(&G->failure)) return INVALID_ID;
    if (id < 2) return id;
    if (p->memo[id] != INVALID_ID) return p->memo[id];
    Node *n = node(id); uint32_t result = 0;
    if (p->keep[n->field]) {
        Edge *edges = allocate(n->count * sizeof(Edge));
        if (!edges) return INVALID_ID;
        for (size_t i = 0; i < n->count; ++i) {
            edges[i].label = n->edges[i].label;
            edges[i].target = CALL(mc_project_rec, n->edges[i].target, p);
        }
        result = make_node(n->field, edges, n->count); deallocate(edges);
    } else for (size_t i = 0; i < n->count && result != INVALID_ID; ++i) {
        uint32_t child = CALL(mc_project_rec, n->edges[i].target, p);
        result = CALL(apply_rec, PNDD_OR, result, child, 0);
    }
    p->memo[id] = result; return result;
}
TASK_DECL_3(uint32_t, mc_project_task, uint32_t, McProject *, uint32_t);
TASK_IMPL_3(uint32_t, mc_project_task, uint32_t, id, McProject *, p, uint32_t, minus) {
    uint32_t projected = CALL(mc_project_rec, id, p);
    return minus ? CALL(apply_rec, PNDD_DIFF, projected, minus, 0) : projected;
}
static int mc_project(pndd_mc_set *dst, const pndd_mc_set *src, const pndd_mc_set *minus) {
    if (!dst || !mc_enter(dst->domain)) return 0;
    int ok = mc_subset(dst, src) && (!minus || mc_layout(dst, minus));
    if (!ok) mc_invalid(dst->domain, "Projection must be a subset; minus must match destination");
    else {
        McProject p = {{0}, NULL, G->node_high};
        for (size_t i = 0; i < dst->arity; ++i) p.keep[dst->proj[i]] = 1;
        p.memo = allocate((size_t)p.limit * sizeof(uint32_t));
        if (!p.memo) ok = 0;
        else {
            for (uint32_t i = 0; i < p.limit; ++i) p.memo[i] = INVALID_ID;
            uint32_t id = resolve(src->root), sub = minus ? resolve(minus->root) : 0;
            atomic_fetch_add(&G->runs, 1); ok = mc_replace(dst, RUN(mc_project_task, id, &p, sub));
            deallocate(p.memo);
        }
    }
    return mc_leave(dst->domain, ok);
}
int pndd_mc_set_project(pndd_mc_set *d, const pndd_mc_set *s) { return mc_project(d, s, NULL); }
int pndd_mc_set_project_minus(pndd_mc_set *d, const pndd_mc_set *s, const pndd_mc_set *m) {
    if (!m) return 0;
    return mc_project(d, s, m);
}
int pndd_mc_set_join(pndd_mc_set *dst, const pndd_mc_set *a, const pndd_mc_set *b) {
    if (!dst || !mc_enter(dst->domain)) return 0;
    int ok = a && b && a->domain == dst->domain && b->domain == dst->domain;
    size_t i = 0, j = 0, k = 0;
    if (ok) while (i < a->arity || j < b->arity) {
        uint32_t av = i < a->arity ? a->proj[i] : MAX_FIELDS;
        uint32_t bv = j < b->arity ? b->proj[j] : MAX_FIELDS;
        uint32_t f = av < bv ? av : bv;
        if (k == dst->arity || dst->proj[k++] != f) { ok = 0; break; }
        if (av == f) ++i;
        if (bv == f) ++j;
    }
    ok = ok && k == dst->arity;
    if (!ok) mc_invalid(dst->domain, "Join destination must be the union projection");
    else {
        uint32_t x = resolve(a->root), y = resolve(b->root);
        atomic_fetch_add(&G->runs, 1); ok = mc_replace(dst, RUN(apply_rec, PNDD_AND, x, y, 0));
    }
    return mc_leave(dst->domain, ok);
}

typedef struct { double *memo; unsigned char *seen; uint32_t rank[MAX_FIELDS + 1]; uint32_t bits; } McCount;
static double mc_count_node(uint32_t id, McCount *c) {
    if (id < 2) return (double)id;
    if (c->seen[id]) return c->memo[id];
    Node *n = node(id); double count = 0;
    for (size_t i = 0; i < n->count; ++i) {
        uint32_t child = n->edges[i].target;
        /* State labels depend only on current bits. Every physical next/scope
         * valuation is duplicated in label_count, so remove that exact factor. */
        double lc = ldexp(label_count(n->field, n->edges[i].label, 0), -(int)(c->bits + 1));
        uint32_t missing = c->rank[node_field(child)] - c->rank[n->field] - 1;
        count += lc * ldexp(mc_count_node(child, c), (int)(missing * c->bits));
    }
    c->seen[id] = 1; c->memo[id] = count; return count;
}
double pndd_mc_set_count(const pndd_mc_set *s) {
    if (!s || !mc_enter(s->domain)) return NAN;
    uint32_t id = resolve(s->root); double result = NAN;
    McCount c = {NULL, NULL, {0}, s->domain->bits};
    if (id != INVALID_ID) {
        size_t pos = 0;
        for (size_t f = 0; f <= G->field_count; ++f) {
            while (pos < s->arity && s->proj[pos] < f) ++pos;
            c.rank[f] = (uint32_t)pos;
        }
        c.memo = allocate((size_t)G->node_high * sizeof(double)); c.seen = allocate(G->node_high);
        if (c.memo && c.seen) result = ldexp(mc_count_node(id, &c), (int)(c.rank[node_field(id)] * c.bits));
        deallocate(c.memo); deallocate(c.seen);
    }
    return mc_leave(s->domain, !isnan(result)) ? result : NAN;
}

static uint64_t mc_node_count(uint32_t id, unsigned char *seen) {
    if (id < 2 || seen[id]) return 0;
    seen[id] = 1; Node *n = node(id); uint64_t total = 1;
    for (size_t i = 0; i < n->count; ++i) total += mc_node_count(n->edges[i].target, seen);
    return total;
}
int pndd_mc_set_node_count(const pndd_mc_set *s, uint64_t *out) {
    if (!s || !mc_enter(s->domain)) return 0;
    int ok = out != NULL;
    if (!ok) mc_invalid(s->domain, "Missing node count output");
    else {
        uint32_t id = resolve(s->root); unsigned char *seen = allocate(G->node_high);
        ok = id != INVALID_ID && seen;
        if (ok) *out = mc_node_count(id, seen);
        deallocate(seen);
    }
    return mc_leave(s->domain, ok);
}

typedef struct {
    pndd_mc_domain *domain;
    size_t arity;
    uint32_t proj[MAX_FIELDS];
    int tuple[MAX_FIELDS];
    pndd_mc_visit visit;
    void *context;
} McEnum;
static int mc_enum_node(McEnum *, uint32_t, size_t);
static int mc_enum_label(McEnum *e, uint32_t field, uint64_t value, uint32_t bit,
                         unsigned tuple_value, uint32_t child, size_t pos) {
    if (!value) return 1;
    if (bit == e->domain->bits) {
        e->tuple[pos] = (int)tuple_value; return mc_enum_node(e, child, pos + 1);
    }
    /* No label allocation, and root snapshot keeps every edge label alive.
     * Re-fetch cofactors after callbacks: they may trigger label collection. */
    uint64_t low = branch(field, value, bit * 2, 0);
    int status = mc_enum_label(e, field, low, bit + 1, tuple_value, child, pos);
    if (status != 1) return status;
    uint64_t high = branch(field, value, bit * 2, 1);
    return mc_enum_label(e, field, high, bit + 1, tuple_value | (1u << bit), child, pos);
}
static int mc_enum_node(McEnum *e, uint32_t id, size_t pos) {
    if (!id) return 1;
    if (pos == e->arity) {
        if (id != 1) { mc_invalid(e->domain, "State root exceeds projection"); return -1; }
        pthread_mutex_unlock(&admission);
        int status = e->visit(e->context, e->arity ? e->tuple : NULL, e->arity);
        pthread_mutex_lock(&admission);
        return status > 0 ? 1 : status < 0 ? -1 : 0;
    }
    uint32_t field = e->proj[pos];
    if (node_field(id) > field) return mc_enum_label(e, field, truth(field), 0, 0, id, pos);
    if (node_field(id) != field) { mc_invalid(e->domain, "State root outside projection"); return -1; }
    size_t count = node(id)->count;
    for (size_t i = 0; i < count; ++i) {
        Edge edge = node(id)->edges[i];
        int status = mc_enum_label(e, field, edge.label, 0, 0, edge.target, pos);
        if (status != 1) return status;
    }
    return 1;
}
int pndd_mc_set_enum(const pndd_mc_set *s, pndd_mc_visit visit, void *context) {
    if (!s || !mc_enter(s->domain)) return -1;
    pndd_mc_domain *d = s->domain;
    if (!visit) { mc_invalid(d, "Missing enumeration callback"); mc_leave(d, 0); return -1; }
    uint32_t id = resolve(s->root); pndd_handle snapshot = own(id);
    if (snapshot == PNDD_INVALID) { mc_leave(d, 0); return -1; }
    McEnum e = {.domain = d, .arity = s->arity, .visit = visit, .context = context};
    memcpy(e.proj, s->proj, s->arity * sizeof(uint32_t)); ++d->enumerations;
    int status = mc_enum_node(&e, id, 0);
    --node(id)->refs; --d->enumerations;
    /* Callback operations have their own error returns; successful callbacks
     * may intentionally test invalid requests. Do not inherit their failure. */
    reset_failure();
    if (status < 0) d->error = "Enumeration callback or traversal failed";
    pthread_mutex_unlock(&admission); return status;
}
typedef struct { int *tuple; int found; } McExample;
static int mc_example_visit(void *ctx, const int *tuple, size_t count) {
    McExample *e = ctx;
    if (count) memcpy(e->tuple, tuple, count * sizeof(int));
    e->found = 1; return 0;
}
int pndd_mc_set_example(const pndd_mc_set *s, int *tuple) {
    if (!s || (s->arity && !tuple)) return -1;
    McExample e = {tuple, 0};
    return pndd_mc_set_enum(s, mc_example_visit, &e) < 0 ? -1 : e.found;
}
typedef struct { pndd_mc_set *dst; pndd_mc_update_cb cb; void *context; } McUpdate;
static int mc_update_visit(void *ctx, const int *tuple, size_t count) {
    McUpdate *u = ctx; return u->cb(u->dst, u->context, tuple, count);
}
int pndd_mc_set_update(pndd_mc_set *dst, const pndd_mc_set *src, pndd_mc_update_cb cb, void *context) {
    if (!dst || !src || !cb || dst->domain != src->domain) return -1;
    McUpdate u = {dst, cb, context}; return pndd_mc_set_enum(src, mc_update_visit, &u);
}

#include "pndd_mc_relation_impl.inc"
#include "pndd_mc_relnext_impl.inc"
