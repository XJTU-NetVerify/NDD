/* Multi-terminal algorithms over the shared compact NDD manager.
 * Backend/universe dispatch and fused contraction follow origin/mtndd
 * 8d343b3; storage, scheduling, ownership and GC are shared with parallel. */
#include "pmtndd.h"
typedef union { struct { int64_t n, d; } rational; double real; } MTValue;
static MTValue mt_payload(Node *n) { MTValue v; memcpy(&v, n->edges, sizeof(v)); return v; }
static unsigned __int128 mt_abs(__int128 x) { return x < 0 ? (unsigned __int128)(-x) : (unsigned __int128)x; }
static unsigned __int128 mt_gcd(unsigned __int128 a, unsigned __int128 b) {
    while (b) { unsigned __int128 r = a % b; a = b; b = r; } return a;
}
static uint32_t mt_leaf(int kind, __int128 numerator, __int128 denominator, double value) {
    if (atomic_load(&G->failure)) return INVALID_ID;
    int64_t n = 0, d = 1; uint64_t bits = 0;
    if (kind == 1) {
        if (!denominator) { fail(4); return INVALID_ID; }
        if (denominator < 0) { numerator = -numerator; denominator = -denominator; }
        unsigned __int128 gcd = mt_gcd(mt_abs(numerator), (unsigned __int128)denominator);
        numerator /= (__int128)gcd; denominator /= (__int128)gcd;
        if (numerator < INT64_MIN || numerator > INT64_MAX || denominator > INT64_MAX) { fail(4); return INVALID_ID; }
        n = (int64_t)numerator; d = (int64_t)denominator;
        if (!n) return 0;
        if (n == d) return 1;
    } else {
        if (!isfinite(value)) { fail(4); return INVALID_ID; }
        if (value == 0) return 0;
        if (value == 1) return 1;
        memcpy(&bits, &value, sizeof(bits));
    }
    uint64_t h = mix((uint64_t)kind ^ mix(kind == 1 ? (uint64_t)n : bits) ^ mix((uint64_t)d));
    Unique *u = &G->node_unique[h % SHARDS]; measured_lock(&u->lock, 2);
    size_t bucket = (h / SHARDS) & (u->size - 1);
    for (uint32_t id = u->buckets[bucket]; id; id = node(id)->next) {
        Node *v = node(id);
        MTValue payload = {0}; if (!v->count && v->numeric_kind) payload = mt_payload(v);
        if (v->hash == h && !v->count && v->numeric_kind == kind &&
                (kind == 1 ? payload.rational.n == n && payload.rational.d == d : payload.real == value)) {
            spin_unlock(&u->lock); return id;
        }
    }
    uint32_t id = INVALID_ID;
    if (grow_unique(u, 0)) {
        bucket = (h / SHARDS) & (u->size - 1);
        Node *v = allocate_node(1);
        if (v) {
            v->hash = h; v->field = G->field_count; v->numeric_kind = kind;
            MTValue payload = {0};
            if (kind == 1) { payload.rational.n = n; payload.rational.d = d; } else payload.real = value;
            memcpy(v->edges, &payload, sizeof(payload)); v->next = u->buckets[bucket];
            id = publish_node(v);
            if (id != INVALID_ID) { u->buckets[bucket] = id; ++u->count; } else deallocate_node(v);
        }
    }
    spin_unlock(&u->lock); return id;
}
static pmt_number mt_number(uint32_t id) {
    pmt_number v = {id == 1, 1, id == 1, G->numeric_double ? PMT_DOUBLE : PMT_RATIONAL};
    if (id >= 2) {
        Node *n = node(id); MTValue payload = mt_payload(n);
        if (n->numeric_kind == 1) { v.numerator = payload.rational.n; v.denominator = payload.rational.d; v.value = (double)v.numerator / v.denominator; }
        else { v.numerator = 0; v.denominator = 1; v.value = payload.real; }
    }
    return v;
}
static uint32_t mt_scalar(uint32_t op, uint32_t a, uint32_t b) {
    if (op < PMT_PLUS) {
        if (a > 1 || b > 1) { fail(5); return INVALID_ID; }
        switch (op) {
        case PMT_AND: return a && b; case PMT_OR: return a || b;
        case PMT_DIFF: return a && !b; case PMT_XOR: return a != b;
        case PMT_IMP: return !a || b; case PMT_EQUIV: return a == b;
        case PMT_NAND: return !(a && b); default: return !(a || b);
        }
    }
    pmt_number x = mt_number(a), y = mt_number(b);
    if (G->numeric_double) {
        if (op == PMT_DIVIDE && y.value == 0) { fail(4); return INVALID_ID; }
        double v = op == PMT_PLUS ? x.value + y.value : op == PMT_MINUS ? x.value - y.value :
                   op == PMT_TIMES ? x.value * y.value : x.value / y.value;
        return mt_leaf(2, 0, 1, v);
    }
    __int128 n, d;
    if (op == PMT_PLUS || op == PMT_MINUS) {
        n = (__int128)x.numerator * y.denominator + (op == PMT_PLUS ? 1 : -1) * (__int128)y.numerator * x.denominator;
        d = (__int128)x.denominator * y.denominator;
    } else if (op == PMT_TIMES) {
        n = (__int128)x.numerator * y.numerator; d = (__int128)x.denominator * y.denominator;
    } else { n = (__int128)x.numerator * y.denominator; d = (__int128)x.denominator * y.numerator; }
    return mt_leaf(1, n, d, 0);
}
/* Rational multiplicities must not round through double above 2^53. */
static uint64_t mt_label_cardinality(uint32_t field, uint64_t value, uint32_t start) {
    if (!value || atomic_load(&G->failure)) return 0;
    pndd_backend mode = G->fields[field].spec.backend;
    if (mode == PNDD_BITSET) {
        uint64_t count = __builtin_popcountll(label(value)->low) + __builtin_popcountll(label(value)->high);
        return count >> (G->widths[mode] - G->fields[field].spec.width);
    }
    uint32_t key = 0x28000 + mode * 8192 + start; uint64_t cached;
    if (pndd_cache_get(key, value, label_end(field), &cached)) return cached;
    uint64_t count;
    if (value == truth(field)) {
        uint32_t bits = mode == PNDD_ZDD ? 0 : label_end(field) - start;
        if (bits >= 63) { fail(4); return 0; }
        count = UINT64_C(1) << bits;
    } else {
        uint32_t var = label_var(field, value);
        uint64_t low = mt_label_cardinality(field, branch(field, value, var, 0), var + 1);
        uint64_t high = mt_label_cardinality(field, branch(field, value, var, 1), var + 1);
        if (low > INT64_MAX - high) { fail(4); return 0; }
        count = low + high;
        uint32_t bits = mode == PNDD_ZDD ? 0 : var - start;
        if (count && (bits >= 63 || count > ((uint64_t)INT64_MAX >> bits))) { fail(4); return 0; }
        if (bits < 63) count <<= bits;
    }
    if (!atomic_load(&G->failure)) pndd_cache_put(key, value, label_end(field), count);
    return count;
}
TASK_DECL_5(uint32_t, mt_apply_rec, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t);
static uint32_t mt_weight(uint32_t id, double weight, uint32_t depth) {
    if (!id || weight == 0) return 0;
    if (weight == 1) return id;
    uint32_t factor;
    if (G->numeric_double) factor = mt_leaf(2, 0, 1, weight);
    else {
        if (!isfinite(weight) || weight < 0 || weight >= 0x1p63 || floor(weight) != weight) { fail(4); return INVALID_ID; }
        factor = mt_leaf(1, (int64_t)weight, 1, 0);
    }
    LACE_VARS; return CALL(mt_apply_rec, PMT_TIMES, id, factor, INVALID_ID, depth);
}
static uint32_t mt_edge_weight(uint32_t id, uint32_t field, uint64_t label_value, uint32_t depth) {
    if (!id) return 0;
    if (G->numeric_double) return mt_weight(id, label_count(field, label_value, G->fields[field].start), depth);
    uint64_t count = mt_label_cardinality(field, label_value, G->fields[field].start);
    if (atomic_load(&G->failure)) return INVALID_ID;
    uint32_t factor = mt_leaf(1, count, 1, 0);
    LACE_VARS; return CALL(mt_apply_rec, PMT_TIMES, id, factor, INVALID_ID, depth);
}
VOID_TASK_DECL_6(mt_jobs, Job *, size_t, uint32_t, uint32_t, uint32_t, uint32_t);
VOID_TASK_IMPL_6(mt_jobs, Job *, jobs, size_t, count, uint32_t, op, uint32_t, eliminate, uint32_t, depth, uint32_t, owner) {
    if (__lace_worker->worker != owner) atomic_fetch_add(&G->stolen, 1);
    if (atomic_load(&G->failure)) return;
    if (G->config.ndd_parallel && count > G->config.chunk_size && count >= G->config.task_threshold && depth < G->config.spawn_depth && inner_parallel_available()) {
        size_t half = count / 2; atomic_fetch_add(&G->tasks, 1);
        SPAWN(mt_jobs, jobs, half, op, eliminate, depth + 1, __lace_worker->worker);
        CALL(mt_jobs, jobs + half, count - half, op, eliminate, depth + 1, __lace_worker->worker);
        SYNC(mt_jobs);
    } else for (size_t i = 0; i < count && !atomic_load(&G->failure); ++i)
        jobs[i].target = CALL(mt_apply_rec, op, jobs[i].a, jobs[i].b, eliminate, depth);
}
TASK_IMPL_5(uint32_t, mt_apply_rec, uint32_t, op, uint32_t, a, uint32_t, b, uint32_t, eliminate, uint32_t, depth) {
    YIELD_NEWFRAME();
    if (atomic_load(&G->failure) || a == INVALID_ID || b == INVALID_ID) return INVALID_ID;
    if ((op == PMT_PLUS || op == PMT_TIMES || op == PMT_AND || op == PMT_OR) && a > b) { uint32_t t = a; a = b; b = t; }
    if (op < PMT_PLUS) return CALL(apply_rec, op, a, b, depth);
#ifdef PNDD_BOOLEAN_TIMES
    /* Boolean multiplication is intersection. Reuse the specialized Boolean
     * recurrence and its cache, but never bypass assignment multiplicities
     * when a field is being summed out. This also applies inside numeric jobs. */
    if (op == PMT_TIMES && eliminate == INVALID_ID &&
            node(a)->boolean_graph && node(b)->boolean_graph)
        return CALL(apply_rec, PMT_AND, a, b, depth);
#endif
    if (op == PMT_TIMES && (!a || !b)) return 0;
    if (eliminate == INVALID_ID) {
        if (op == PMT_TIMES && a == 1) return b;
        if (op == PMT_TIMES && b == 1) return a;
        if (op == PMT_PLUS && !a) return b;
        if ((op == PMT_PLUS || op == PMT_MINUS) && !b) return a;
        if (op == PMT_MINUS && a == b) return 0;
        if (op == PMT_DIVIDE && b == 1) return a;
    }
    uint32_t af = node_field(a), bf = node_field(b), field = af < bf ? af : bf;
    uint32_t key = eliminate == INVALID_ID ? 0x20000 + op : 0x21000 + eliminate * 16 + op;
    uint64_t cached;
    if (pndd_cache_get(key, a, b, &cached)) return (uint32_t)cached;
    if (eliminate != INVALID_ID && field > eliminate) {
        uint32_t result = CALL(mt_apply_rec, op, a, b, INVALID_ID, depth);
        result = mt_weight(result, gap(eliminate, eliminate + 1), depth);
        pndd_cache_put(key, a, b, result); return result;
    }
    if (field == G->field_count) {
        uint32_t result = mt_scalar(op, a, b); pndd_cache_put(key, a, b, result); return result;
    }
#ifdef PNDD_PROFILE
    uint64_t prepare_start = now_ns();
#endif
    Edge av = {G->fields[field].universe, a}, bv = {G->fields[field].universe, b};
    const Edge *ae = af == field ? node(a)->edges : &av, *be = bf == field ? node(b)->edges : &bv;
    size_t ac = af == field ? node(a)->count : 1, bc = bf == field ? node(b)->count : 1;
    /* Add implicit zero cofactors. This handles holes for PLUS/MINUS and
     * detects division by zero over the full universe instead of silently
     * dropping assignments. TIMES uses only nonzero intersections. */
    if (ac > SIZE_MAX / (bc + 1) - 1) { fail(1); return INVALID_ID; }
    size_t cap = (ac + 1) * (bc + 1);
    if (cap > SIZE_MAX / sizeof(Job) || cap > SIZE_MAX / sizeof(Edge)) { fail(1); return INVALID_ID; }
    Job *jobs = allocate(cap * sizeof(Job)); Edge *out = allocate(cap * sizeof(Edge));
    if (!jobs || !out) { deallocate(jobs); deallocate(out); return INVALID_ID; }
    RootFrame jr; root_push(&jr, field, jobs, sizeof(Job), 0);
    uint64_t residual[2] = {0, 0}; RootFrame rr; root_push(&rr, field, residual, sizeof(uint64_t), 2);
    if (op != PMT_TIMES) {
        residual[0] = coverage(field, ae, ac);
        residual[0] = labels(field, PNDD_DIFF, G->fields[field].universe, residual[0]);
        residual[1] = coverage(field, be, bc);
        residual[1] = labels(field, PNDD_DIFF, G->fields[field].universe, residual[1]);
    }
    size_t count = 0;
    for (size_t i = 0; i < ac + (op != PMT_TIMES) && !atomic_load(&G->failure); ++i) {
        Edge x = i < ac ? ae[i] : (Edge){residual[0], 0};
        if (!x.label) continue;
        for (size_t j = 0; j < bc + (op != PMT_TIMES) && !atomic_load(&G->failure); ++j) {
            Edge y = j < bc ? be[j] : (Edge){residual[1], 0};
            if (!y.label) continue;
            uint64_t intersection = labels(field, PNDD_AND, x.label, y.label);
            if (intersection && intersection != PNDD_INVALID) {
                jobs[count] = (Job){intersection, x.target, y.target, INVALID_ID}; jr.count = ++count;
            }
        }
    }
    root_pop(&rr);
#ifdef PNDD_PROFILE
    Profile *profile = &worker_stats()->profile;
    ++profile->numeric_misses; profile->numeric_prepare_ns += now_ns() - prepare_start;
    profile->numeric_jobs += count;
    ++profile->numeric_bins[count <= 1 ? 0 : count <= 3 ? 1 : count <= 7 ? 2 : count <= 15 ? 3 : 4];
#endif
    CALL(mt_jobs, jobs, count, op, field == eliminate ? INVALID_ID : eliminate, depth, __lace_worker->worker);
    uint32_t result = INVALID_ID;
#ifdef PNDD_PROFILE
    uint64_t merge_start = now_ns();
#endif
    if (!atomic_load(&G->failure)) {
        if (field == eliminate) {
            result = 0;
            for (size_t i = 0; i < count && !atomic_load(&G->failure); ++i) {
                uint32_t weighted = mt_edge_weight(jobs[i].target, field, jobs[i].label, depth);
                result = CALL(mt_apply_rec, PMT_PLUS, result, weighted, INVALID_ID, depth);
            }
        } else {
            for (size_t i = 0; i < count; ++i) out[i] = (Edge){jobs[i].label, jobs[i].target};
            result = make_node(field, out, count);
        }
    }
    root_pop(&jr); deallocate(jobs); deallocate(out);
#ifdef PNDD_PROFILE
    profile->numeric_merge_ns += now_ns() - merge_start;
#endif
    pndd_cache_put(key, a, b, result); return result;
}
/* Sum abstraction reuses the fused recurrence with the identity factor,
 * avoiding a separate multiplication or an intermediate product diagram. */
pndd_engine *pmt_open(const pndd_config *config, const pndd_field *fields, size_t count, pmt_domain domain) {
    if (domain != PMT_RATIONAL && domain != PMT_DOUBLE) { open_error = "Invalid numeric domain"; return NULL; }
    pndd_engine *e = pndd_open(config, fields, count);
    if (e) { pthread_mutex_lock(&admission); e->numeric_double = domain == PMT_DOUBLE; pthread_mutex_unlock(&admission); }
    return e;
}
pndd_handle pmt_fraction(pndd_engine *e, int64_t n, int64_t d) {
    pthread_mutex_lock(&admission); pndd_handle h = PNDD_INVALID;
    if (begin(e)) { if (G->numeric_double) fail(5); else h = own(mt_leaf(1, n, d, 0)); }
    pthread_mutex_unlock(&admission); return h;
}
pndd_handle pmt_double(pndd_engine *e, double v) {
    pthread_mutex_lock(&admission); pndd_handle h = PNDD_INVALID;
    if (begin(e)) { if (!G->numeric_double) fail(5); else h = own(mt_leaf(2, 0, 1, v)); }
    pthread_mutex_unlock(&admission); return h;
}
static pndd_handle mt_public_apply(pndd_engine *e, pmt_op op, pndd_handle a, pndd_handle b, uint32_t field, int fused) {
    pthread_mutex_lock(&admission); pndd_handle h = PNDD_INVALID;
    if (begin(e)) {
        uint32_t x = resolve(a), y = resolve(b);
        if (op < PMT_AND || op > PMT_DIVIDE || (fused && field >= G->field_count)) fail(3);
        if (!atomic_load(&G->failure) && op < PMT_PLUS &&
                ((x >= 2 && !node(x)->boolean_graph) || (y >= 2 && !node(y)->boolean_graph))) fail(5);
        if (!atomic_load(&G->failure)) {
#ifdef PNDD_BOOLEAN_TIMES
            if (!fused && op == PMT_TIMES && node(x)->boolean_graph && node(y)->boolean_graph)
                op = PMT_AND;
#endif
            if ((op == PMT_PLUS || op == PMT_TIMES || op == PMT_AND || op == PMT_OR) && x > y) { uint32_t t=x; x=y; y=t; }
            uint32_t direct=INVALID_ID; uint64_t cached;
            if (op == PMT_TIMES && (!x || !y)) direct=0;
            if (!fused) {
                if (op == PMT_TIMES && x == 1) direct=y;
                if (op == PMT_TIMES && y == 1) direct=x;
                if (op == PMT_PLUS && !x) direct=y;
                if ((op == PMT_PLUS || op == PMT_MINUS) && !y) direct=x;
                if (op == PMT_MINUS && x == y) direct=0;
                if (op == PMT_DIVIDE && y == 1) direct=x;
                if (op == PMT_AND && !x) direct=0;
                if (op == PMT_AND && x == 1) direct=y;
                if ((op == PMT_AND || op == PMT_OR) && x == y) direct=x;
                if (op == PMT_OR && !x) direct=y;
                if (op == PMT_OR && y == 1) direct=1;
                if (op == PMT_DIFF && (!x || x == y)) direct=0;
                if (op == PMT_DIFF && !y) direct=x;
                if (node_field(x) == G->field_count && node_field(y) == G->field_count)
                    direct=mt_scalar(op,x,y);
            }
            uint32_t key=op < PMT_PLUS ? op+1 : fused ? 0x21000+field*16+op : 0x20000+op;
            if (direct != INVALID_ID) h=own(direct);
            else if (!atomic_load(&G->failure) && pndd_cache_get(key,x,y,&cached)) h=own((uint32_t)cached);
            else if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs,1); h=own(RUN(mt_apply_rec,op,x,y,field,0)); }
        }
    }
    pthread_mutex_unlock(&admission); return h;
}
pndd_handle pmt_apply(pndd_engine *e, pmt_op op, pndd_handle a, pndd_handle b) { return mt_public_apply(e, op, a, b, INVALID_ID, 0); }
pndd_handle pmt_multiply_sum(pndd_engine *e, pndd_handle a, pndd_handle b, uint32_t field) { return mt_public_apply(e, PMT_TIMES, a, b, field, 1); }
pndd_handle pmt_sum_abstract(pndd_engine *e, pndd_handle a, uint32_t field) {
    /* Retain the Boolean identity under admission without a second RUN. */
    pthread_mutex_lock(&admission); pndd_handle h = PNDD_INVALID;
    if (begin(e)) {
        uint32_t x = resolve(a); if (field >= G->field_count) fail(3);
        if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); h = own(RUN(mt_apply_rec, PMT_TIMES, x, 1, field, 0)); }
    }
    pthread_mutex_unlock(&admission); return h;
}
static uint32_t mt_assignment(uint32_t id, const uint64_t *values) {
    while (node_field(id) < G->field_count) {
        Node *n = node(id); uint32_t next = 0;
        for (size_t i = 0; i < n->count; ++i) if (label_matches(n->field, n->edges[i].label, values[n->field])) { next = n->edges[i].target; break; }
        id = next;
    }
    return id;
}
int pmt_terminal(pndd_engine *e, pndd_handle h, pmt_number *out) {
    pthread_mutex_lock(&admission); int ok = 0;
    if (begin(e)) {
        uint32_t id = resolve(h);
        if (!out || (id != INVALID_ID && node_field(id) != G->field_count)) fail(3);
        if (!atomic_load(&G->failure)) { *out = mt_number(id); ok = 1; }
    }
    pthread_mutex_unlock(&admission); return ok;
}
int pmt_evaluate(pndd_engine *e, pndd_handle h, const uint64_t *values, size_t count, pmt_number *out) {
    pthread_mutex_lock(&admission); int ok = 0;
    if (begin(e)) {
        uint32_t id = resolve(h); if (!values || !out || count != G->field_count) fail(3);
        for (size_t f = 0; f < count && !atomic_load(&G->failure); ++f)
            if (G->fields[f].spec.width < 64 && values[f] >= (UINT64_C(1) << G->fields[f].spec.width)) fail(3);
        if (!atomic_load(&G->failure)) { *out = mt_number(mt_assignment(id, values)); ok = 1; }
    }
    pthread_mutex_unlock(&admission); return ok;
}
static double mt_count(uint32_t id, double *memo, unsigned char *seen) {
    if (node_field(id) == G->field_count) return id != 0;
#ifdef PNDD_EXPERIMENT_SATCOUNT_CACHE
    if (node(id)->sat_count_valid) return node(id)->sat_count;
#else
    if (seen[id]) return memo[id];
#endif
    Node *n = node(id); double value = 0;
    for (size_t i = 0; i < n->count; ++i) {
        Edge edge = n->edges[i];
        value += label_count(n->field, edge.label, G->fields[n->field].start) * gap(n->field + 1, node_field(edge.target)) * mt_count(edge.target, memo, seen);
    }
#ifdef PNDD_EXPERIMENT_SATCOUNT_CACHE
    n->sat_count = value; n->sat_count_valid = 1; return value;
#else
    seen[id] = 1; return memo[id] = value;
#endif
}
TASK_DECL_1(double, mt_count_task, uint32_t);
TASK_IMPL_1(double, mt_count_task, uint32_t, id) {
    if (!id) return 0; /* Avoid infinity * zero for very wide empty domains. */
#ifdef PNDD_EXPERIMENT_SATCOUNT_CACHE
    return gap(0, node_field(id)) * mt_count(id, NULL, NULL);
#else
    double *memo = allocate((size_t)G->node_high * sizeof(double)); unsigned char *seen = allocate(G->node_high);
    if (!memo || !seen) { deallocate(memo); deallocate(seen); return NAN; }
    double value = gap(0, node_field(id)) * mt_count(id, memo, seen);
    deallocate(memo); deallocate(seen); return value;
#endif
}
double pmt_satcount(pndd_engine *e, pndd_handle h) {
    pthread_mutex_lock(&admission); double value = NAN;
    if (begin(e)) { uint32_t id = resolve(h); if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); value = RUN(mt_count_task, id); } }
    pthread_mutex_unlock(&admission); return value;
}
TASK_DECL_3(uint32_t, mt_exist_rec, uint32_t, uint32_t, uint32_t);
TASK_IMPL_3(uint32_t, mt_exist_rec, uint32_t, id, uint32_t, field, uint32_t, depth) {
    YIELD_NEWFRAME(); if (atomic_load(&G->failure)) return INVALID_ID;
    uint32_t nf = node_field(id);
    if (nf > field) return id;
    Node *n = node(id); uint32_t result = 0;
    if (nf == field) {
        for (size_t i = 0; i < n->count && !atomic_load(&G->failure); ++i)
            result = CALL(mt_apply_rec, PMT_OR, result, n->edges[i].target, INVALID_ID, depth);
    } else {
        Edge *out = allocate(n->count * sizeof(Edge)); if (!out) return INVALID_ID;
        for (size_t i = 0; i < n->count; ++i) out[i] = (Edge){n->edges[i].label, CALL(mt_exist_rec, n->edges[i].target, field, depth + 1)};
        result = make_node(nf, out, n->count); deallocate(out);
    }
    return result;
}
pndd_handle pmt_exist(pndd_engine *e, pndd_handle h, uint32_t field) {
    pthread_mutex_lock(&admission); pndd_handle out = PNDD_INVALID;
    if (begin(e)) {
        uint32_t id = resolve(h); if (field >= G->field_count) fail(3);
        if (id != INVALID_ID && id >= 2 && !node(id)->boolean_graph) fail(5);
        if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); out = own(RUN(mt_exist_rec, id, field, 0)); }
    }
    pthread_mutex_unlock(&admission); return out;
}
VOID_TASK_DECL_6(mt_batch_rec, Job *, size_t, uint32_t, uint32_t, uint32_t, uint32_t);
VOID_TASK_IMPL_6(mt_batch_rec, Job *, jobs, size_t, count, uint32_t, op, uint32_t, depth, uint32_t, owner, uint32_t, unused) {
    (void)unused;
    if (__lace_worker->worker != owner) atomic_fetch_add(&G->stolen, 1);
    if (atomic_load(&G->failure)) return;
    if (G->config.batch_parallel && count > G->config.chunk_size && depth < G->config.spawn_depth) {
        size_t half = count / 2; atomic_fetch_add(&G->tasks, 1);
        SPAWN(mt_batch_rec, jobs, half, op, depth + 1, __lace_worker->worker, 0);
        CALL(mt_batch_rec, jobs + half, count - half, op, depth + 1, __lace_worker->worker, 0);
        SYNC(mt_batch_rec);
    } else for (size_t i = 0; i < count && !atomic_load(&G->failure); ++i)
        jobs[i].target = CALL(mt_apply_rec, op, jobs[i].a, jobs[i].b, INVALID_ID, 0);
}
int pmt_apply_batch(pndd_engine *e, pmt_op op, const pndd_handle *a, const pndd_handle *b, size_t count, pndd_handle *out) {
    pthread_mutex_lock(&admission); int ok = 0;
    if (begin(e)) {
        if (op < PMT_AND || op > PMT_DIVIDE || (count && (!a || !b || !out)) || count > SIZE_MAX / sizeof(Job)) fail(3);
        if (!atomic_load(&G->failure)) for (size_t i = 0; i < count; ++i) out[i] = PNDD_INVALID;
        Job *jobs = atomic_load(&G->failure) ? NULL : allocate(count * sizeof(Job));
        if (jobs) {
            for (size_t i = 0; i < count; ++i) {
                jobs[i] = (Job){0, resolve(a[i]), resolve(b[i]), INVALID_ID};
                if (!atomic_load(&G->failure) && op < PMT_PLUS &&
                        ((jobs[i].a >= 2 && !node(jobs[i].a)->boolean_graph) || (jobs[i].b >= 2 && !node(jobs[i].b)->boolean_graph))) fail(5);
            }
            for (size_t i = 0; i < count; ++i) out[i] = PNDD_INVALID;
            if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); RUN(mt_batch_rec, jobs, count, op, 0, 0, 0); }
            if (!atomic_load(&G->failure)) {
                size_t i = 0; for (; i < count; ++i) { out[i] = own(jobs[i].target); if (out[i] == PNDD_INVALID) break; }
                if (i == count) ok = 1;
                else for (size_t j = 0; j < i; ++j) { --node(jobs[j].target)->refs; out[j] = PNDD_INVALID; }
            }
            deallocate(jobs);
        }
    }
    pthread_mutex_unlock(&admission); return ok;
}
TASK_DECL_5(uint32_t, mt_reduce_rec, uint32_t, uint32_t *, size_t, int, uint32_t);
TASK_IMPL_5(uint32_t, mt_reduce_rec, uint32_t, op, uint32_t *, ids, size_t, count, int, tree, uint32_t, depth) {
    YIELD_NEWFRAME(); if (atomic_load(&G->failure)) return INVALID_ID;
    if (!count) return op == PMT_TIMES || op == PMT_AND ? 1 : 0;
    if (count == 1) return ids[0];
    if (tree) {
        size_t half = count / 2; uint32_t a, b;
        if (G->config.batch_parallel && count > G->config.chunk_size && depth < G->config.spawn_depth) {
            atomic_fetch_add(&G->tasks, 1); SPAWN(mt_reduce_rec, op, ids, half, tree, depth + 1);
            b = CALL(mt_reduce_rec, op, ids + half, count - half, tree, depth + 1); a = SYNC(mt_reduce_rec);
        } else { a = CALL(mt_reduce_rec, op, ids, half, tree, depth + 1); b = CALL(mt_reduce_rec, op, ids + half, count - half, tree, depth + 1); }
        return CALL(mt_apply_rec, op, a, b, INVALID_ID, depth);
    }
    uint32_t result = ids[0];
    for (size_t i = 1; i < count && !atomic_load(&G->failure); ++i) result = CALL(mt_apply_rec, op, result, ids[i], INVALID_ID, depth);
    return result;
}
pndd_handle pmt_reduce(pndd_engine *e, pmt_op op, const pndd_handle *values, size_t count, int tree) {
    pthread_mutex_lock(&admission); pndd_handle out = PNDD_INVALID;
    if (begin(e)) {
        if ((op != PMT_PLUS && op != PMT_TIMES && op != PMT_AND && op != PMT_OR) || (count && !values) || count > SIZE_MAX / sizeof(uint32_t)) fail(3);
        uint32_t *ids = atomic_load(&G->failure) ? NULL : allocate(count * sizeof(uint32_t));
        if (ids) {
            for (size_t i = 0; i < count; ++i) {
                ids[i] = resolve(values[i]);
                if (op < PMT_PLUS && ids[i] != INVALID_ID && ids[i] >= 2 && !node(ids[i])->boolean_graph) fail(5);
            }
            if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); out = own(RUN(mt_reduce_rec, op, ids, count, tree, 0)); }
            deallocate(ids);
        }
    }
    pthread_mutex_unlock(&admission); return out;
}

TASK_DECL_3(uint32_t, mt_import_rec, const pmt_number *, size_t, uint32_t);
TASK_IMPL_3(uint32_t, mt_import_rec, const pmt_number *, values, size_t, count, uint32_t, field) {
    YIELD_NEWFRAME(); if (atomic_load(&G->failure)) return INVALID_ID;
    if (field == G->field_count) {
        if (values->domain != (G->numeric_double ? PMT_DOUBLE : PMT_RATIONAL)) { fail(5); return INVALID_ID; }
        return G->numeric_double ? mt_leaf(2, 0, 1, values->value) : mt_leaf(1, values->numerator, values->denominator, 0);
    }
    size_t size = (size_t)1 << G->fields[field].spec.width, stride = count / size;
    if (size > SIZE_MAX / sizeof(Edge)) { fail(1); return INVALID_ID; }
    Edge *edges = allocate(size * sizeof(Edge)); if (!edges) return INVALID_ID;
    RootFrame roots; root_push(&roots, field, edges, sizeof(Edge), 0);
    size_t kept = 0;
    for (size_t i = 0; i < size && !atomic_load(&G->failure); ++i) {
        uint32_t child = CALL(mt_import_rec, values + i * stride, stride, field + 1);
        if (!child || child == INVALID_ID) continue;
        uint32_t condition = CALL(construct, field, 0, i, 1);
        if (condition == INVALID_ID) break;
        edges[kept] = (Edge){node(condition)->edges[0].label, child}; roots.count = ++kept;
    }
    uint32_t result = make_node(field, edges, kept);
    root_pop(&roots); deallocate(edges); return result;
}
pndd_handle pmt_from_array(pndd_engine *e, const pmt_number *values, size_t count) {
    pthread_mutex_lock(&admission); pndd_handle out = PNDD_INVALID;
    if (begin(e)) {
        size_t bits = 0;
        for (size_t f = 0; f < G->field_count; ++f) bits += G->fields[f].spec.width;
        if (!values || bits >= sizeof(size_t) * CHAR_BIT || count != ((size_t)1 << bits)) fail(3);
        if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); out = own(RUN(mt_import_rec, values, count, 0)); }
    }
    pthread_mutex_unlock(&admission); return out;
}

static int mt_matches_bits(uint32_t field, uint64_t value, const unsigned char *bits) {
    Field *f = &G->fields[field]; pndd_backend mode = f->spec.backend;
    if (mode == PNDD_BITSET) {
        uint64_t assignment = 0;
        for (uint32_t i = 0; i < f->spec.width; ++i) assignment = (assignment << 1) | bits[i];
        return label_matches(field, value, assignment);
    }
    if (mode == PNDD_ZDD) {
        for (uint32_t var = f->start; var < label_end(field); ++var) {
            value = branch(field, value, var, bits[var - f->start]);
            if (!value) return 0;
        }
    } else while (value && value != truth(field)) {
        uint32_t var = label_var(field, value);
        uint32_t bit = reversed(field) ? f->spec.width - 1 - var : var - f->start;
        value = branch(field, value, var, bits[bit]);
    }
    return value == truth(field);
}
int pmt_evaluate_bits(pndd_engine *e, pndd_handle handle, const unsigned char *bits, size_t count, pmt_number *out) {
    pthread_mutex_lock(&admission); int ok = 0;
    if (begin(e)) {
        size_t offsets[257] = {0};
        for (uint32_t f = 0; f < G->field_count; ++f) offsets[f + 1] = offsets[f] + G->fields[f].spec.width;
        if (!bits || !out || count != offsets[G->field_count]) fail(3);
        else for (size_t i = 0; i < count; ++i) if (bits[i] > 1) { fail(3); break; }
        uint32_t id = resolve(handle);
        if (!atomic_load(&G->failure)) {
            while (node_field(id) < G->field_count) {
                Node *n = node(id); uint32_t next = 0;
                for (size_t i = 0; i < n->count; ++i)
                    if (mt_matches_bits(n->field, n->edges[i].label, bits + offsets[n->field])) { next = n->edges[i].target; break; }
                id = next;
            }
            *out = mt_number(id); ok = 1;
        }
    }
    pthread_mutex_unlock(&admission); return ok;
}
static int mt_greater(uint32_t a, uint32_t b) {
    pmt_number x = mt_number(a), y = mt_number(b);
    return G->numeric_double ? x.value > y.value : (__int128)x.numerator * y.denominator > (__int128)y.numerator * x.denominator;
}
TASK_DECL_2(uint32_t, mt_max_rec, uint32_t, uint32_t *);
TASK_IMPL_2(uint32_t, mt_max_rec, uint32_t, id, uint32_t *, memo) {
    if (node_field(id) == G->field_count) return id;
    if (memo[id] != INVALID_ID) return memo[id];
    Node *n = node(id); uint32_t result = INVALID_ID;
    for (size_t i = 0; i < n->count; ++i) {
        uint32_t v = CALL(mt_max_rec, n->edges[i].target, memo);
        if (result == INVALID_ID || mt_greater(v, result)) result = v;
    }
    /* Missing labels represent zero, including for an all-negative diagram. */
    uint64_t cover = coverage(n->field, n->edges, n->count);
    if (cover != G->fields[n->field].universe && (result == INVALID_ID || mt_greater(0, result))) result = 0;
    memo[id] = result; return result;
}
int pmt_max_terminal(pndd_engine *e, pndd_handle handle, pmt_number *out) {
    pthread_mutex_lock(&admission); int ok = 0;
    if (begin(e)) {
        uint32_t id = resolve(handle);
        if (!out) fail(3);
        uint32_t *memo = atomic_load(&G->failure) ? NULL : allocate((size_t)G->node_high * sizeof(uint32_t));
        if (memo) {
            memset(memo, 255, (size_t)G->node_high * sizeof(uint32_t));
            atomic_fetch_add(&G->runs, 1); uint32_t result = RUN(mt_max_rec, id, memo);
            if (!atomic_load(&G->failure)) { *out = mt_number(result); ok = 1; }
            deallocate(memo);
        }
    }
    pthread_mutex_unlock(&admission); return ok;
}
VOID_TASK_DECL_5(mt_contract_batch, Job *, size_t, uint32_t, uint32_t, uint32_t);
VOID_TASK_IMPL_5(mt_contract_batch, Job *, jobs, size_t, count, uint32_t, field, uint32_t, depth, uint32_t, owner) {
    if (__lace_worker->worker != owner) atomic_fetch_add(&G->stolen, 1);
    if (atomic_load(&G->failure)) return;
    if (G->config.batch_parallel && count > G->config.chunk_size && depth < G->config.spawn_depth) {
        size_t half = count / 2; atomic_fetch_add(&G->tasks, 1);
        SPAWN(mt_contract_batch, jobs, half, field, depth + 1, __lace_worker->worker);
        CALL(mt_contract_batch, jobs + half, count - half, field, depth + 1, __lace_worker->worker);
        SYNC(mt_contract_batch);
    } else for (size_t i = 0; i < count && !atomic_load(&G->failure); ++i)
        jobs[i].target = CALL(mt_apply_rec, PMT_TIMES, jobs[i].a, jobs[i].b, field, 0);
}
static int mt_contract_public(pndd_engine *e, const pndd_handle *a, const pndd_handle *b, size_t count, uint32_t field, pndd_handle *out) {
    pthread_mutex_lock(&admission); int ok = 0;
    if (begin(e)) {
        if (field >= G->field_count || (count && (!a || !out)) || count > SIZE_MAX / sizeof(Job) || (count && (out == a || (b && out == b)))) fail(3);
        if (!atomic_load(&G->failure)) for (size_t i = 0; i < count; ++i) out[i] = PNDD_INVALID;
        if (!count && !atomic_load(&G->failure)) ok = 1;
        Job *jobs = !count || atomic_load(&G->failure) ? NULL : allocate(count * sizeof(Job));
        if (jobs) {
            for (size_t i = 0; i < count; ++i) jobs[i] = (Job){0, resolve(a[i]), b ? resolve(b[i]) : 1, INVALID_ID};
            if (!atomic_load(&G->failure)) { atomic_fetch_add(&G->runs, 1); RUN(mt_contract_batch, jobs, count, field, 0, 0); }
            if (!atomic_load(&G->failure)) {
                size_t i = 0;
                for (; i < count; ++i) { out[i] = own(jobs[i].target); if (out[i] == PNDD_INVALID) break; }
                if (i == count) ok = 1;
                else for (size_t j = 0; j < i; ++j) { --node(jobs[j].target)->refs; out[j] = PNDD_INVALID; }
            }
            deallocate(jobs);
        }
    }
    pthread_mutex_unlock(&admission); return ok;
}
int pmt_sum_abstract_batch(pndd_engine *e, const pndd_handle *a, size_t count, uint32_t field, pndd_handle *out) {
    return mt_contract_public(e, a, NULL, count, field, out);
}
int pmt_multiply_sum_batch(pndd_engine *e, const pndd_handle *a, const pndd_handle *b, size_t count, uint32_t field, pndd_handle *out) {
    if (count && !b) { pthread_mutex_lock(&admission); if (begin(e)) fail(3); pthread_mutex_unlock(&admission); return 0; }
    return mt_contract_public(e, a, b, count, field, out);
}
