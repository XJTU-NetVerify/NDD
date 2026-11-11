/* C2B: legacy field-local Post semantics, using checked C-NDD primitives.
 * The outer traversal is serial; existing apply/reduction scheduling is reused.
 * No whole-set BDD conversion, new raw edge-pointer tasks, or MTjoin. */
/* Compile-time controls for isolated image experiments. Neither changes the
 * representation, public ABI, or the configured shared cache byte budget. */
#ifndef PNDD_MC_IMAGE_PAIR_CACHE
#define PNDD_MC_IMAGE_PAIR_CACHE 0
#endif
#if PNDD_MC_IMAGE_PAIR_CACHE
/* The field is part of the key; domain bits are fixed for the engine lifetime.
 * This range is label-valued, so existing label-GC invalidation clears it. */
#define CACHE_MC_IMAGE_PAIR_BASE 0x60000u
#endif
#ifdef PNDD_MC_IMAGE_DIAGNOSTIC
static uint64_t mc_image_calls, mc_image_pair_hits;
__attribute__((destructor)) static void mc_image_report(void) {
    fprintf(stderr, "MC_IMAGE calls=%llu pair_hits=%llu\n",
            (unsigned long long)mc_image_calls, (unsigned long long)mc_image_pair_hits);
}
#endif
#ifdef PNDD_MC_TEST_GC
/* Test builds only: two distinct temporary-root boundaries per uncached Post.
 * A top-level admitted Post is serial, so these flags have one task owner. */
static unsigned mc_test_gc_pending;
#endif

TASK_DECL_3(uint64_t, mc_image_rename, uint32_t, uint64_t, uint32_t);
TASK_IMPL_3(uint64_t, mc_image_rename, uint32_t, field, uint64_t, value, uint32_t, bits) {
    YIELD_NEWFRAME();
    if (atomic_load(&G->failure) || value == PNDD_INVALID) return PNDD_INVALID;
    if (!value || value == truth(field)) return value;
    uint64_t context = ((uint64_t)bits << 32) | field, cached;
    if (pndd_cache_get(CACHE_MC_IMAGE, value, context, &cached)) return cached;
    uint32_t var = label_var(field, value);
    if (var > 2 * bits) { fail(3); return PNDD_INVALID; }
    uint64_t temp[3] = {value, 0, 0}; RootFrame roots;
    root_push(&roots, field, temp, sizeof(uint64_t), 3);
    temp[1] = CALL(mc_image_rename, field, branch(field, value, var, 0), bits);
#ifdef PNDD_MC_TEST_GC
    if (!atomic_load(&G->failure) && temp[1] && temp[1] != truth(field) &&
        (mc_test_gc_pending & 1)) {
        mc_test_gc_pending &= ~1u; CALL(sylvan_gc);
    }
#endif
    temp[2] = CALL(mc_image_rename, field, branch(field, value, var, 1), bits);
    /* Eliminate current variables (even) and scope (also even). Keep each next
     * variable, renamed down by one. With interleaved order, descendant next
     * variables still map strictly after the retained parent variable. */
    uint64_t result = (var & 1) ? make_label(field, var - 1, temp[1], temp[2]) :
                                 labels(field, PNDD_OR, temp[1], temp[2]);
    if (atomic_load(&G->failure)) result = PNDD_INVALID;
    pndd_cache_put(CACHE_MC_IMAGE, value, context, result);
    root_pop(&roots); return result;
}
static uint64_t mc_image(uint32_t field, uint64_t states, uint64_t relation, uint32_t bits) {
    LACE_VARS;
#ifdef PNDD_MC_IMAGE_DIAGNOSTIC
    ++mc_image_calls;
#endif
#if PNDD_MC_IMAGE_PAIR_CACHE
    uint64_t cached;
    if (pndd_cache_get(CACHE_MC_IMAGE_PAIR_BASE + field, states, relation, &cached)) {
#ifdef PNDD_MC_IMAGE_DIAGNOSTIC
        ++mc_image_pair_hits;
#endif
        return cached;
    }
#endif
    uint64_t temp[3] = {states, relation, 0}; RootFrame roots;
    root_push(&roots, field, temp, sizeof(uint64_t), 3);
    temp[2] = labels(field, PNDD_AND, states, relation);
    uint64_t result = CALL(mc_image_rename, field, temp[2], bits);
#if PNDD_MC_IMAGE_PAIR_CACHE
    pndd_cache_put(CACHE_MC_IMAGE_PAIR_BASE + field, states, relation, result);
#endif
    root_pop(&roots); return result;
}

typedef struct { uint32_t *ids; size_t count, capacity; uint32_t small[32]; } McNextBranches;
static int mc_next_push(McNextBranches *b, uint32_t id) {
    if (id == INVALID_ID || atomic_load(&G->failure)) return 0;
    if (!id) return 1;
    if (b->count == b->capacity) {
        if (b->capacity > SIZE_MAX / (2 * sizeof(uint32_t))) { fail(1); return 0; }
        size_t capacity = b->capacity * 2; uint32_t *ids = allocate(capacity * sizeof(uint32_t));
        if (!ids) return 0;
        memcpy(ids, b->ids, b->count * sizeof(uint32_t));
        if (b->ids != b->small) deallocate(b->ids);
        b->ids = ids; b->capacity = capacity;
    }
    b->ids[b->count++] = id; return 1;
}
TASK_DECL_3(uint32_t, mc_next_rec, uint32_t, uint32_t, uint32_t);
static int mc_next_branch(McNextBranches *out, uint32_t field, uint64_t image,
                           uint32_t states, uint32_t relation, uint32_t bits) {
    if (image == PNDD_INVALID || atomic_load(&G->failure)) return 0;
    if (!image) return 1;
    LACE_VARS;
    RootFrame roots; root_push(&roots, field, &image, sizeof(image), 1);
#ifdef PNDD_MC_TEST_GC
    if (mc_test_gc_pending & 2) { mc_test_gc_pending &= ~2u; CALL(sylvan_gc); }
#endif
    /* Child recursion can allocate labels/collect, so image remains rooted
     * until make_node publishes it. NDD IDs are not recycled inside this RUN. */
    uint32_t child = CALL(mc_next_rec, states, relation, bits);
    Edge edge = {image, child}; uint32_t branch_id = make_node(field, &edge, 1);
    root_pop(&roots); return mc_next_push(out, branch_id);
}
TASK_IMPL_3(uint32_t, mc_next_rec, uint32_t, states, uint32_t, relation, uint32_t, bits) {
    YIELD_NEWFRAME();
    if (atomic_load(&G->failure) || states == INVALID_ID || relation == INVALID_ID) return INVALID_ID;
    if (!states || !relation) return 0;
    if (relation == 1) return states; /* Omitted suffix is identity. */
    /* Domain width is fixed for the lifetime of this engine/cache. Projection
     * only interprets unconstrained fields; the output DAG is independent of it.
     * Keep ordered (state, relation) operands: these roles are not symmetric. */
    uint64_t cached;
    if (pndd_cache_get(CACHE_MC_NEXT, states, relation, &cached)) return (uint32_t)cached;
    McNextBranches out = {.count = 0, .capacity = 32}; out.ids = out.small;
    uint32_t sf = node_field(states), rf = node_field(relation); int ok = 1;
    if (sf < rf) {
        Node *s = node(states);
        for (size_t i = 0; i < s->count && ok; ++i)
            ok = mc_next_branch(&out, sf, s->edges[i].label, s->edges[i].target, relation, bits);
    } else if (rf < sf) {
        Node *r = node(relation);
        for (size_t i = 0; i < r->count && ok; ++i) {
            uint64_t image = mc_image(rf, truth(rf), r->edges[i].label, bits);
            ok = mc_next_branch(&out, rf, image, states, r->edges[i].target, bits);
        }
    } else {
        Node *s = node(states), *r = node(relation);
        for (size_t i = 0; i < s->count && ok; ++i) for (size_t j = 0; j < r->count && ok; ++j) {
            uint64_t image = mc_image(sf, s->edges[i].label, r->edges[j].label, bits);
            ok = mc_next_branch(&out, sf, image, s->edges[i].target, r->edges[j].target, bits);
        }
    }
    /* Images from different pairs can overlap and point at different children.
     * A bare make_node over those edges would be wrong. Reduce complete branch
     * NDDs with the existing canonical OR, retaining a balanced merge tree. */
    uint32_t result = ok ? CALL(reduction, PNDD_OR, out.ids, out.count, 1) : INVALID_ID;
    if (out.ids != out.small) deallocate(out.ids);
    if (atomic_load(&G->failure)) return INVALID_ID;
    pndd_cache_put(CACHE_MC_NEXT, states, relation, result); return result;
}
TASK_DECL_3(uint32_t, mc_next_top, uint32_t, uint32_t, uint32_t);
TASK_IMPL_3(uint32_t, mc_next_top, uint32_t, states, uint32_t, relation, uint32_t, bits) {
    if (!collect_deferred()) return INVALID_ID;
#ifdef PNDD_MC_TEST_GC
    mc_test_gc_pending = 3;
#endif
    return CALL(mc_next_rec, states, relation, bits);
}
int pndd_mc_set_next(pndd_mc_set *dst, const pndd_mc_set *src, const pndd_mc_rel *r) {
    if (!dst) return 0;
    pndd_mc_domain *d = dst->domain; pthread_mutex_lock(&admission);
    if (!begin_deferred(d->engine, 1)) {
        d->error = pndd_error(d->engine); pthread_mutex_unlock(&admission); return 0;
    }
    d->error = "Success";
    int ok = r && mc_layout(dst, src) && mc_subset(&r->path, src);
    if (!ok) mc_invalid(d, "Next requires equal source/destination projections and a contained relation projection");
    else {
        uint32_t states = resolve(src->root), relation = resolve(r->path.root);
        if (atomic_load(&G->failure)) ok = 0;
        else {
            atomic_fetch_add(&G->runs, 1);
            ok = mc_replace(dst, RUN(mc_next_top, states, relation, d->bits));
        }
    }
    return mc_leave(d, ok);
}
