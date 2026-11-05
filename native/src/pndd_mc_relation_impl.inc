/* C2A. Same per-field RW/copy and !scope representation as the legacy MTPNDD
 * relation path, built with checked C-NDD storage primitives. The embedded
 * path shares only the root/projection lifecycle helpers, never state count
 * or state enumeration (relation labels also depend on next/scope). */
enum { MC_READ = 1, MC_WRITE = 2 };
struct pndd_mc_rel {
    pndd_mc_set path;
    size_t read_arity, write_arity;
    uint32_t read_proj[MAX_FIELDS], write_proj[MAX_FIELDS];
    unsigned char modes[MAX_FIELDS];
};
static int mc_projection_copy(pndd_mc_domain *d, int k, const uint32_t *src,
                               uint32_t *dst, size_t *count) {
    if (k < -1 || (k == -1 && src) || (k >= 0 && ((size_t)k > d->slots || (k && !src))))
        return mc_invalid(d, "Invalid relation projection");
    *count = k == -1 ? d->slots : (size_t)k;
    for (size_t i = 0; i < *count; ++i) {
        dst[i] = k == -1 ? (uint32_t)i : src[i];
        if (dst[i] >= d->slots || (i && dst[i - 1] >= dst[i]))
            return mc_invalid(d, "Relation projection must be sorted, unique and in range");
    }
    return 1;
}
pndd_mc_rel *pndd_mc_rel_create_rw(pndd_mc_domain *d, int rk, const uint32_t *rp,
                                  int wk, const uint32_t *wp) {
    if (!mc_enter(d)) return NULL;
    pndd_mc_rel *r = calloc(1, sizeof(*r));
    if (!r) mc_invalid(d, "MC relation allocation failed");
    else if (!mc_projection_copy(d, rk, rp, r->read_proj, &r->read_arity) ||
             !mc_projection_copy(d, wk, wp, r->write_proj, &r->write_arity)) {
        free(r); r = NULL;
    } else {
        r->path.domain = d; size_t i = 0, j = 0;
        while (i < r->read_arity || j < r->write_arity) {
            uint32_t a = i < r->read_arity ? r->read_proj[i] : MAX_FIELDS;
            uint32_t b = j < r->write_arity ? r->write_proj[j] : MAX_FIELDS;
            uint32_t f = a < b ? a : b; size_t pos = r->path.arity++;
            r->path.proj[pos] = f;
            if (a == f) { ++i; r->modes[pos] |= MC_READ; }
            if (b == f) { ++j; r->modes[pos] |= MC_WRITE; }
        }
        r->path.root = own(0);
        if (r->path.root == PNDD_INVALID) { free(r); r = NULL; } else ++d->relations;
    }
    mc_leave(d, r != NULL); return r;
}
pndd_mc_rel *pndd_mc_rel_create(pndd_mc_domain *d, int k, const uint32_t *proj) {
    return pndd_mc_rel_create_rw(d, k, proj, k, proj);
}
void pndd_mc_rel_destroy(pndd_mc_rel *r) {
    if (!r) return;
    pthread_mutex_lock(&admission); pndd_mc_domain *d = r->path.domain;
    if (d->engine == G) { reset_failure(); uint32_t id = resolve(r->path.root); if (id != INVALID_ID) --node(id)->refs; }
    --d->relations; free(r); pthread_mutex_unlock(&admission);
}
int pndd_mc_rel_clear(pndd_mc_rel *r) { return r ? mc_constant(&r->path, 0) : 0; }
int pndd_mc_rel_empty(const pndd_mc_rel *r) { return r ? pndd_mc_set_empty(&r->path) : -1; }
size_t pndd_mc_rel_read_arity(const pndd_mc_rel *r) { return r ? r->read_arity : 0; }
size_t pndd_mc_rel_write_arity(const pndd_mc_rel *r) { return r ? r->write_arity : 0; }
static int mc_relation_layout(const pndd_mc_rel *a, const pndd_mc_rel *b) {
    return a && b && a->path.domain == b->path.domain &&
        a->read_arity == b->read_arity && a->write_arity == b->write_arity &&
        !memcmp(a->read_proj, b->read_proj, a->read_arity * sizeof(uint32_t)) &&
        !memcmp(a->write_proj, b->write_proj, a->write_arity * sizeof(uint32_t));
}
int pndd_mc_rel_copy(pndd_mc_rel *dst, const pndd_mc_rel *src) {
    if (!dst || !mc_enter(dst->path.domain)) return 0;
    int ok = mc_relation_layout(dst, src) ? mc_replace(&dst->path, resolve(src->path.root)) :
        mc_invalid(dst->path.domain, "Relation copy requires equal read/write projections");
    return mc_leave(dst->path.domain, ok);
}
int pndd_mc_rel_equal(const pndd_mc_rel *a, const pndd_mc_rel *b) {
    if (!a || !b || !mc_enter(a->path.domain)) return -1;
    int equal = mc_relation_layout(a, b) && a->path.root == b->path.root;
    return mc_leave(a->path.domain, 1) ? equal : -1;
}
static int mc_relation_values(pndd_mc_domain *d, const int *values, size_t count) {
    if (count && !values) return mc_invalid(d, "Missing relation vector");
    for (size_t i = 0; i < count; ++i)
        if (values[i] < 0 || (uint32_t)values[i] >= (1u << d->bits))
            return mc_invalid(d, "Relation value outside logical domain");
    return 1;
}
typedef struct {
    const pndd_mc_rel *relation;
    int current[MAX_FIELDS], next[MAX_FIELDS];
    unsigned char copy[MAX_FIELDS];
} McRelationAdd;
TASK_DECL_1(uint32_t, mc_relation_add_task, const McRelationAdd *);
TASK_IMPL_1(uint32_t, mc_relation_add_task, const McRelationAdd *, a) {
    const pndd_mc_rel *r = a->relation; uint32_t id = 1, bits = r->path.domain->bits;
    for (size_t pos = r->path.arity; pos > 0 && id != INVALID_ID; --pos) {
        size_t i = pos - 1; uint32_t f = r->path.proj[i]; unsigned mode = r->modes[i];
        /* Explicit fields always constrain scope=0, including all-pairs. Thus
         * canonicalization cannot erase them into an omitted identity field. */
        uint64_t values[3] = {0, 0, 0}; RootFrame roots;
        root_push(&roots, f, values, sizeof(uint64_t), 3);
        values[0] = make_label(f, 2 * bits, truth(f), 0);
        for (uint32_t bit = bits; bit > 0 && values[0] != PNDD_INVALID; --bit) {
            uint32_t var = 2 * (bit - 1);
            if (a->copy[i]) {
                /* Build x==x' directly in interleaved order. Both new branches
                 * are rooted across allocation of the next branch/parent. */
                values[1] = make_label(f, var + 1, values[0], 0);
                values[2] = make_label(f, var + 1, 0, values[0]);
                values[0] = make_label(f, var, values[1], values[2]);
            } else {
                unsigned next = (unsigned)((mode & MC_WRITE) ? a->next[i] : a->current[i]);
                unsigned positive = (next >> (bit - 1)) & 1;
                values[0] = make_label(f, var + 1, positive ? 0 : values[0], positive ? values[0] : 0);
                if (mode & MC_READ) {
                    positive = ((unsigned)a->current[i] >> (bit - 1)) & 1;
                    values[0] = make_label(f, var, positive ? 0 : values[0], positive ? values[0] : 0);
                }
            }
        }
        Edge edge = {values[0], id}; id = make_node(f, &edge, 1); root_pop(&roots);
    }
    return CALL(apply_rec, PNDD_OR, resolve(r->path.root), id, 0);
}
int pndd_mc_rel_add_cpy(pndd_mc_rel *r, const int *src, const int *dst, const int *copy) {
    if (!r || !mc_enter(r->path.domain)) return 0;
    pndd_mc_domain *d = r->path.domain;
    int ok = mc_relation_values(d, src, r->read_arity) && mc_relation_values(d, dst, r->write_arity);
    if (ok) {
        McRelationAdd a = {.relation = r}; size_t ri = 0, wi = 0;
        for (size_t i = 0; i < r->path.arity; ++i) {
            if (r->modes[i] & MC_READ) a.current[i] = src[ri++];
            if (r->modes[i] & MC_WRITE) {
                a.next[i] = dst[wi];
                a.copy[i] = !(r->modes[i] & MC_READ) && copy && copy[wi]; ++wi;
            }
        }
        atomic_fetch_add(&G->runs, 1); ok = mc_replace(&r->path, RUN(mc_relation_add_task, &a));
    }
    return mc_leave(d, ok);
}
int pndd_mc_rel_add(pndd_mc_rel *r, const int *src, const int *dst) {
    return pndd_mc_rel_add_cpy(r, src, dst, NULL);
}
static int mc_relation_label_member(uint32_t f, uint64_t value, uint32_t bits, unsigned src, unsigned dst) {
    while (value && value != truth(f)) {
        uint32_t var = label_var(f, value);
        unsigned bit = var == 2 * bits ? 0 : ((var & 1 ? dst : src) >> (var / 2)) & 1;
        value = branch(f, value, var, (int)bit);
    }
    return value != 0;
}
int pndd_mc_rel_member(const pndd_mc_rel *r, const int *src, const int *dst) {
    if (!r || !mc_enter(r->path.domain)) return -1;
    pndd_mc_domain *d = r->path.domain;
    int ok = mc_relation_values(d, src, d->slots) && mc_relation_values(d, dst, d->slots);
    uint32_t id = 0;
    if (ok) {
        id = resolve(r->path.root); size_t pos = 0;
        for (size_t f = 0; f < d->slots; ++f) {
            if (pos < r->path.arity && r->path.proj[pos] == f) ++pos;
            else if (src[f] != dst[f]) { id = 0; break; }
        }
        while (id >= 2 && id != INVALID_ID) {
            Node *n = node(id); id = 0;
            for (size_t i = 0; i < n->count; ++i)
                if (mc_relation_label_member(n->field, n->edges[i].label, d->bits,
                                             (unsigned)src[n->field], (unsigned)dst[n->field])) {
                    id = n->edges[i].target; break;
                }
        }
    }
    return mc_leave(d, ok) ? (int)id : -1;
}
static double mc_relation_count_node(uint32_t id, double *memo, unsigned char *seen) {
    if (id < 2) return (double)id;
    if (seen[id]) return memo[id];
    Node *n = node(id); double count = 0;
    /* Every explicit field constrains scope=0, so physical satcount already
     * counts each logical pair once. Omitted fields are outside this count. */
    for (size_t i = 0; i < n->count; ++i)
        count += label_count(n->field, n->edges[i].label, 0) *
                 mc_relation_count_node(n->edges[i].target, memo, seen);
    seen[id] = 1; memo[id] = count; return count;
}
double pndd_mc_rel_count(const pndd_mc_rel *r) {
    if (!r || !mc_enter(r->path.domain)) return NAN;
    uint32_t id = resolve(r->path.root); double result = NAN;
    if (id != INVALID_ID) {
        double *memo = allocate((size_t)G->node_high * sizeof(double));
        unsigned char *seen = allocate(G->node_high);
        if (memo && seen) result = mc_relation_count_node(id, memo, seen);
        deallocate(memo); deallocate(seen);
    }
    return mc_leave(r->path.domain, !isnan(result)) ? result : NAN;
}
int pndd_mc_rel_node_count(const pndd_mc_rel *r, uint64_t *out) {
    return r ? pndd_mc_set_node_count(&r->path, out) : 0;
}
typedef struct { pndd_mc_rel *relation; pndd_mc_rel_update_cb callback; void *context; } McRelUpdate;
static int mc_relation_update_visit(void *ctx, const int *tuple, size_t count) {
    McRelUpdate *u = ctx; return u->callback(u->relation, u->context, tuple, count);
}
int pndd_mc_rel_update(pndd_mc_rel *r, const pndd_mc_set *src, pndd_mc_rel_update_cb cb, void *context) {
    if (!r || !mc_enter(r->path.domain)) return -1;
    int ok = src && cb && src->domain == r->path.domain && src->arity == r->read_arity &&
             !memcmp(src->proj, r->read_proj, src->arity * sizeof(uint32_t));
    if (!ok) mc_invalid(r->path.domain, "Relation update source must match read projection and supply a callback");
    if (!mc_leave(r->path.domain, ok)) return -1;
    McRelUpdate u = {r, cb, context}; return pndd_mc_set_enum(src, mc_relation_update_visit, &u);
}
