// MTPNDD fraction-leaf API smoke test.

#include "mtpndd.h"
#include "mtpndd_memory_pool.h"
#include "mtpndd_leaf_table.h"
#include "sylvan.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

static void assert_success(mtpndd_error_t err) {
    if (err != MTPNDD_SUCCESS) {
        fprintf(stderr, "MTPNDD error %d (%s)\n", err, mtpndd_error_string(err));
        abort();
    }
}

static mtpndd_pal_config_t default_config(void) {
    mtpndd_pal_config_t c = {
        .n_workers = 4,
        .lace_dqsize = 1 << 18,
        .bdd_nodetable_size = 1 << 16,
        .mtpndd_nodetable_size = 1 << 14,
        .op_cache_size = 1 << 12,
        .edge_bucket_count = 16,
        .nodetable_bucket_count = 1 << 10,
        .node_slab_capacity = 1024,
        .edge_entry_slab_capacity = 4096,
        .nodetable_entry_slab_capacity = 2048,
        .edge_map_slab_capacity = 1024,
    };
    return c;
}

static void test_canonicalization(void) {
    // Same value via different (numer, denom) inputs must return the same pointer.
    mtpndd_t *a = mtpndd_make_fraction(2, 4);
    mtpndd_t *b = mtpndd_make_fraction(1, 2);
    mtpndd_t *c = mtpndd_make_fraction(-3, -6);
    assert(a == b);
    assert(a == c);
    assert(mtpndd_get_numer(a) == 1);
    assert(mtpndd_get_denom(a) == 2);

    // Sign moves to the numerator.
    mtpndd_t *neg = mtpndd_make_fraction(3, -4);
    assert(mtpndd_get_numer(neg) == -3);
    assert(mtpndd_get_denom(neg) == 4);

    // Zero canonicalizes to 0/1 == MTPNDD_FALSE.
    mtpndd_t *z1 = mtpndd_make_fraction(0, 7);
    mtpndd_t *z2 = mtpndd_make_fraction(0, 1);
    assert(z1 == &MTPNDD_FALSE);
    assert(z2 == &MTPNDD_FALSE);

    // 1/1 == MTPNDD_TRUE.
    mtpndd_t *one = mtpndd_make_fraction(3, 3);
    assert(one == &MTPNDD_TRUE);

    // denom == 0 → error.
    mtpndd_t *bad = mtpndd_make_fraction(1, 0);
    assert(bad == NULL);

    printf("  canonicalization OK\n");
}

static void test_leaf_arithmetic(void) {
    mtpndd_t *a = mtpndd_make_fraction(1, 3);
    mtpndd_t *b = mtpndd_make_fraction(1, 6);

    mtpndd_t *sum = mtpndd_plus(a, b);
    assert(sum && mtpndd_get_numer(sum) == 1 && mtpndd_get_denom(sum) == 2);

    mtpndd_t *diff = mtpndd_minus(a, b);
    assert(diff && mtpndd_get_numer(diff) == 1 && mtpndd_get_denom(diff) == 6);

    mtpndd_t *prod = mtpndd_times(a, b);
    assert(prod && mtpndd_get_numer(prod) == 1 && mtpndd_get_denom(prod) == 18);

    mtpndd_t *quot = mtpndd_divide(a, b);
    assert(quot && mtpndd_get_numer(quot) == 2 && mtpndd_get_denom(quot) == 1);

    // a + 0 = a
    assert(mtpndd_plus(a, &MTPNDD_FALSE) == a);
    // a * 1 = a
    assert(mtpndd_times(a, &MTPNDD_TRUE) == a);
    // a - a = 0
    assert(mtpndd_minus(a, a) == &MTPNDD_FALSE);
    // a / 0 = error
    assert(mtpndd_divide(a, &MTPNDD_FALSE) == NULL);

    printf("  leaf arithmetic OK\n");
}

static mtpndd_bdd_t bit_cube(uint32_t field_id, uint32_t bit_width, uint32_t value) {
    // Build the cube that matches `value` on the field's bits.
    mtpndd_bdd_t acc = sylvan_ref(sylvan_true);
    for (uint32_t bit = 0; bit < bit_width; ++bit) {
        mtpndd_bdd_t lit = (value & (1u << bit))
                ? sylvan_ref(mtpndd_get_bdd_var(field_id, bit))
                : sylvan_ref(mtpndd_get_bdd_not_var(field_id, bit));
        mtpndd_bdd_t next = sylvan_ref(sylvan_and(acc, lit));
        sylvan_deref(acc);
        sylvan_deref(lit);
        acc = next;
    }
    return acc;
}

// Build a 1-field MTPNDD with a fraction leaf at cube `value` and FALSE elsewhere.
// This uses the low-level edge-map construction via mtpndd_mk.
static mtpndd_t *build_indicator(uint32_t field_id, uint32_t bit_width, uint32_t value, mtpndd_t *leaf) {
    mtpndd_edge_t *edges = mtpndd_memory_acquire_edge_map();
    mtpndd_edge_map_init(edges);
    mtpndd_bdd_t cube = bit_cube(field_id, bit_width, value);
    assert_success(mtpndd_add_edge(edges, leaf, cube));
    mtpndd_t *res = NULL;
    mtpndd_mk(field_id, edges, &res);
    if (!res) mtpndd_edge_map_free(edges);
    return res;
}

static void test_overflow(void) {
    mtpndd_t *big = mtpndd_make_fraction(INT32_MAX, 1);
    mtpndd_t *one = mtpndd_make_fraction(1, 1);
    // INT32_MAX + 1 overflows int32 — should return NULL.
    mtpndd_t *sum = mtpndd_plus(big, one);
    assert(sum == NULL);

    // INT32_MAX * 2 overflows.
    mtpndd_t *two = mtpndd_make_fraction(2, 1);
    assert(mtpndd_times(big, two) == NULL);

    // 1/INT32_MAX + 1/INT32_MAX = 2/INT32_MAX. Numer product fits; denom
    // product (INT32_MAX^2) temporarily exceeds int32 but gcd should
    // reduce it back.
    mtpndd_t *tiny = mtpndd_make_fraction(1, INT32_MAX);
    mtpndd_t *two_tiny = mtpndd_plus(tiny, tiny);
    assert(two_tiny);
    assert(mtpndd_get_numer(two_tiny) == 2);
    assert(mtpndd_get_denom(two_tiny) == INT32_MAX);

    printf("  overflow detection OK\n");
}

static void test_minus_residual(void) {
    // a has 1/2 at cube 00; b has 1/3 at cube 01 (disjoint support).
    // a - b: cube 00 → 1/2, cube 01 → -1/3, else 0.
    // This exercises the B-only residual via negate.
    mtpndd_t *half = mtpndd_make_fraction(1, 2);
    mtpndd_t *third = mtpndd_make_fraction(1, 3);
    mtpndd_t *a = build_indicator(1, 2, 0, half);
    mtpndd_t *b = build_indicator(1, 2, 1, third);
    mtpndd_ref(a); mtpndd_ref(b);

    mtpndd_t *diff = mtpndd_minus(a, b);
    assert(diff);
    // Reachable leaves: 1/2 (from a-side), -1/3 (from negated b-side).
    size_t lc = mtpndd_leafcount(diff);
    assert(lc == 2);
    // satcount: 2 cubes have nonzero values.
    assert(mtpndd_satcount(diff) == 2.0);

    // negate via 0 - a: should produce -1/2 at cube 00.
    mtpndd_t *neg_a = mtpndd_minus(&MTPNDD_FALSE, a);
    assert(neg_a);
    assert(mtpndd_leafcount(neg_a) == 1);
    assert(mtpndd_satcount(neg_a) == 1.0);

    mtpndd_deref(a); mtpndd_deref(b);
    printf("  minus residual (negate path) OK\n");
}

static void test_times_disjoint(void) {
    // Disjoint support: intersection is empty → result is FALSE everywhere.
    mtpndd_t *half = mtpndd_make_fraction(1, 2);
    mtpndd_t *third = mtpndd_make_fraction(1, 3);
    mtpndd_t *a = build_indicator(1, 2, 0, half);
    mtpndd_t *b = build_indicator(1, 2, 1, third);
    mtpndd_ref(a); mtpndd_ref(b);

    mtpndd_t *prod = mtpndd_times(a, b);
    assert(prod == &MTPNDD_FALSE);

    // Overlapping: a * a → 1/4 at cube 00.
    mtpndd_t *sq = mtpndd_times(a, a);
    assert(sq);
    assert(mtpndd_leafcount(sq) == 1);
    assert(mtpndd_satcount(sq) == 1.0);

    mtpndd_deref(a); mtpndd_deref(b);
    printf("  times OK\n");
}

static void test_divide(void) {
    // a = 1/2 at cube 00, b = 1/4 at cube 00 (same support).
    // a / b = 2 at cube 00.
    mtpndd_t *half = mtpndd_make_fraction(1, 2);
    mtpndd_t *quarter = mtpndd_make_fraction(1, 4);
    mtpndd_t *a = build_indicator(1, 2, 0, half);
    mtpndd_t *b = build_indicator(1, 2, 0, quarter);
    mtpndd_ref(a); mtpndd_ref(b);

    mtpndd_t *q = mtpndd_divide(a, b);
    assert(q);
    assert(mtpndd_satcount(q) == 1.0);

    // a / b when a's support is wider than b → division by zero error.
    mtpndd_t *wide_a = mtpndd_plus(a, build_indicator(1, 2, 1, half));
    assert(wide_a);
    mtpndd_ref(wide_a);
    mtpndd_t *bad = mtpndd_divide(wide_a, b);
    assert(bad == NULL);

    mtpndd_deref(a); mtpndd_deref(b); mtpndd_deref(wide_a);
    printf("  divide OK\n");
}

// Build an MTPNDD with one cube per value in `cubes[]`, each pointing to
// its own fraction leaf.  Used to exercise the parallel plus pair-loop.
static mtpndd_t *build_multi_indicator(uint32_t field_id, uint32_t bit_width,
                                       const uint32_t *cubes, size_t n,
                                       mtpndd_t *leaf) {
    mtpndd_edge_t *edges = mtpndd_memory_acquire_edge_map();
    mtpndd_edge_map_init(edges);
    for (size_t i = 0; i < n; ++i) {
        mtpndd_bdd_t cube = bit_cube(field_id, bit_width, cubes[i]);
        assert_success(mtpndd_add_edge(edges, leaf, cube));
    }
    mtpndd_t *res = NULL;
    mtpndd_mk(field_id, edges, &res);
    if (!res) mtpndd_edge_map_free(edges);
    return res;
}

static void test_abstract_plus(void) {
    // Field 1, 2 bits, 4 cubes. Build:
    //   ndd: cube 0 → 1/2, cube 2 → 1/4, else 0
    // abstract_plus(ndd, 1) should produce 1/2 + 1/4 = 3/4 (a constant leaf).
    mtpndd_t *half = mtpndd_make_fraction(1, 2);
    mtpndd_t *quarter = mtpndd_make_fraction(1, 4);

    mtpndd_edge_t *edges = mtpndd_memory_acquire_edge_map();
    mtpndd_edge_map_init(edges);
    assert_success(mtpndd_add_edge(edges, half, bit_cube(1, 2, 0)));
    assert_success(mtpndd_add_edge(edges, quarter, bit_cube(1, 2, 2)));
    mtpndd_t *ndd = NULL;
    mtpndd_mk(1, edges, &ndd);
    assert(ndd);
    mtpndd_ref(ndd);

    mtpndd_t *summed = mtpndd_abstract_plus(ndd, 1);
    assert(summed);
    // Result should be the leaf 3/4.
    assert(mtpndd_is_fraction_leaf(summed));
    assert(mtpndd_get_numer(summed) == 3);
    assert(mtpndd_get_denom(summed) == 4);

    // Constant leaf: abstract_plus(leaf, k) = leaf * 2^bits(k).
    mtpndd_t *const_leaf = mtpndd_make_fraction(1, 5);
    mtpndd_t *scaled = mtpndd_abstract_plus(const_leaf, 1);
    // 1/5 * 4 = 4/5.
    assert(scaled && mtpndd_get_numer(scaled) == 4 && mtpndd_get_denom(scaled) == 5);

    // Invalid field → NULL.
    assert(mtpndd_abstract_plus(ndd, 0) == NULL);
    assert(mtpndd_abstract_plus(ndd, 999) == NULL);

    mtpndd_deref(ndd);
    printf("  abstract_plus OK\n");
}

static void test_boolean_arithmetic_cache_isolation(void) {
    mtpndd_t *a = mtpndd_get_var(1, 0), *b = mtpndd_get_var(1, 1);
    mtpndd_t *boolean_union = mtpndd_or(a, b);
    assert(boolean_union); mtpndd_ref(boolean_union);
    mtpndd_t *sum = mtpndd_plus(a, b);
    assert(sum); mtpndd_ref(sum);
    assert(sum != boolean_union);
    mtpndd_t *all = mtpndd_abstract_plus(sum, 1);
    assert(all && mtpndd_get_numer(all) == 4 && mtpndd_get_denom(all) == 1);
    assert(mtpndd_or(a, b) == boolean_union);
    mtpndd_t *booleans = mtpndd_abstract_plus(boolean_union, 1);
    assert(booleans && mtpndd_get_numer(booleans) == 3);
    mtpndd_deref(sum); mtpndd_deref(boolean_union);
}
static void test_double_abstract(void) {
    mtpndd_t *half = mtpndd_make_double(0.5);
    mtpndd_t *sum = mtpndd_abstract_plus(half, 1);
    assert(sum && mtpndd_is_double_leaf(sum) && mtpndd_get_double(sum) == 2.0);
    mtpndd_t *a = build_indicator(1, 2, 0, half);
    mtpndd_t *b = build_indicator(1, 2, 1, half);
    mtpndd_ref(a); mtpndd_ref(b);
    mtpndd_t *both = mtpndd_plus(a, b); assert(both); mtpndd_ref(both);
    sum = mtpndd_abstract_plus(both, 1);
    assert(sum && mtpndd_is_double_leaf(sum) && mtpndd_get_double(sum) == 1.0);
    mtpndd_deref(both); mtpndd_deref(a); mtpndd_deref(b);
}

static void test_leaf_gc(void) {
    // Create a batch of temporary fraction leaves that are NOT referenced
    // by any live internal node.  After GC they should all be reclaimed
    // except the sentinels MTPNDD_TRUE / MTPNDD_FALSE.
    extern mtpndd_leaf_table_t *mtpndd_get_leaf_table(void);
    mtpndd_leaf_table_t *frac = mtpndd_get_leaf_table();
    size_t before = mtpndd_leaf_table_size(frac);

    // Create 50 unique fractions that are not reachable from any DAG.
    for (int i = 1; i <= 50; ++i) {
        mtpndd_t *f = mtpndd_make_fraction(i, 101);
        (void)f;  // no reference kept
    }
    size_t after_create = mtpndd_leaf_table_size(frac);
    assert(after_create >= before + 50);

    // Create one DAG-embedded leaf that MUST survive GC.
    mtpndd_t *keeper_leaf = mtpndd_make_fraction(7, 13);
    mtpndd_t *keeper = build_indicator(1, 2, 0, keeper_leaf);
    mtpndd_ref(keeper);

    size_t reclaimed = mtpndd_leaf_gc();
    // All 50 loose leaves should be gone; keeper leaf and sentinels survive.
    size_t after_gc = mtpndd_leaf_table_size(frac);
    assert(after_gc < after_create);
    assert(after_gc <= before + 1);  // +1 for keeper_leaf (7/13)

    // keeper must still be usable after GC — its leaf is still reachable.
    assert(mtpndd_satcount(keeper) == 1.0);
    assert(mtpndd_is_fraction_leaf(keeper_leaf));
    assert(mtpndd_get_numer(keeper_leaf) == 7);

    mtpndd_deref(keeper);
    printf("  leaf GC (reclaimed %zu) OK\n", reclaimed);
}

static void test_abstract_plus_validate(void) {
    // Valid case: build_indicator uses only field 1's cube BDDs at a
    // field-1 node, so validation should pass.
    mtpndd_t *half = mtpndd_make_fraction(1, 2);
    mtpndd_t *ok_node = build_indicator(1, 2, 0, half);
    mtpndd_ref(ok_node);
    assert(mtpndd_abstract_plus_validate(ok_node));

    // Invalid case: attach an edge at field 1 whose label uses BDD var
    // 100 — well outside field 1's legal range [0, 2).  Validation must
    // reject this.  (Using sylvan_ithvar directly avoids needing a
    // second field declared in the engine.)
    mtpndd_edge_t *bad_edges = mtpndd_memory_acquire_edge_map();
    mtpndd_edge_map_init(bad_edges);
    mtpndd_bdd_t bad_label = sylvan_ref(sylvan_ithvar(100));
    assert_success(mtpndd_add_edge(bad_edges, half, bad_label));
    mtpndd_t *bad_node = NULL;
    mtpndd_mk(1, bad_edges, &bad_node);
    assert(bad_node);
    mtpndd_ref(bad_node);
    assert(!mtpndd_abstract_plus_validate(bad_node));

    mtpndd_deref(ok_node);
    mtpndd_deref(bad_node);
    printf("  abstract_plus validate OK\n");
}

static void test_parallel_plus(void) {
    // Both operands cover the same 2 cubes → 4 intersection pairs (2 empty,
    // 2 non-empty), exercising the SPAWN path.
    mtpndd_t *half = mtpndd_make_fraction(1, 2);
    mtpndd_t *third = mtpndd_make_fraction(1, 3);

    uint32_t cubes[] = {0, 1};
    mtpndd_t *a = build_multi_indicator(1, 2, cubes, 2, half);
    mtpndd_t *b = build_multi_indicator(1, 2, cubes, 2, third);
    mtpndd_ref(a); mtpndd_ref(b);

    mtpndd_t *sum = mtpndd_plus(a, b);
    assert(sum);
    // Result: both cubes → 1/2 + 1/3 = 5/6.  One distinct leaf.
    assert(mtpndd_leafcount(sum) == 1);
    assert(mtpndd_satcount(sum) == 2.0);

    mtpndd_deref(a); mtpndd_deref(b);
    printf("  parallel plus OK\n");
}

static void test_double_leaves(void) {
    mtpndd_t *a = mtpndd_make_double(0.5);
    mtpndd_t *b = mtpndd_make_double(0.25);
    mtpndd_t *c = mtpndd_make_double(0.5);
    assert(a == c);                             // canonical
    assert(a != b);
    assert(mtpndd_is_double_leaf(a));
    assert(!mtpndd_is_fraction_leaf(a));
    assert(mtpndd_is_leaf(a));
    assert(mtpndd_get_double(a) == 0.5);

    // Double arithmetic.
    mtpndd_t *sum = mtpndd_plus(a, b);
    assert(mtpndd_is_double_leaf(sum));
    assert(mtpndd_get_double(sum) == 0.75);

    mtpndd_t *diff = mtpndd_minus(a, b);
    assert(mtpndd_is_double_leaf(diff));
    assert(mtpndd_get_double(diff) == 0.25);

    mtpndd_t *prod = mtpndd_times(a, b);
    assert(mtpndd_is_double_leaf(prod));
    assert(mtpndd_get_double(prod) == 0.125);

    mtpndd_t *quot = mtpndd_divide(a, b);
    assert(mtpndd_is_double_leaf(quot));
    assert(mtpndd_get_double(quot) == 2.0);

    // Mixed types should error.
    mtpndd_t *frac = mtpndd_make_fraction(1, 2);
    mtpndd_t *bad = mtpndd_plus(a, frac);
    assert(bad == NULL);

    // MTPNDD_FALSE (the canonical fraction zero) still short-circuits with
    // any leaf — 0 + x = x even if x is a double.
    mtpndd_t *id = mtpndd_plus(&MTPNDD_FALSE, a);
    assert(id == a);

    // Divide by double zero → error.
    mtpndd_t *zero = mtpndd_make_double(0.0);
    assert(mtpndd_divide(a, zero) == NULL);

    printf("  double leaves OK\n");
}

static void test_internal_plus(void) {
    // Field 1 has 2 bits → 4 cubes. Build a = (cube 0 → 1/2), b = (cube 1 → 1/3).
    mtpndd_t *half = mtpndd_make_fraction(1, 2);
    mtpndd_t *third = mtpndd_make_fraction(1, 3);
    mtpndd_t *a = build_indicator(1, 2, 0, half);
    mtpndd_t *b = build_indicator(1, 2, 1, third);
    mtpndd_ref(a); mtpndd_ref(b);

    mtpndd_t *sum = mtpndd_plus(a, b);
    assert(sum);
    // Reachable leaves in the DAG: 1/2 and 1/3. Uncovered cubes are implicit
    // (no edge to MTPNDD_FALSE in the representation).
    size_t lc = mtpndd_leafcount(sum);
    assert(lc == 2);

    // Disjoint plus: cube 0 + cube 1 → satcount should be 2 (two non-zero cubes)
    double sat = mtpndd_satcount(sum);
    assert(sat == 2.0);

    // Overlapping plus: a + a should double each leaf value
    mtpndd_t *doubled = mtpndd_plus(a, a);
    assert(doubled);
    // cube 0 now has value 1, which collapses to MTPNDD_TRUE.
    // Not a leaf at the internal level, still an internal node with 1 cube → TRUE.
    double sat_d = mtpndd_satcount(doubled);
    assert(sat_d == 1.0);

    mtpndd_deref(a); mtpndd_deref(b);
    printf("  internal plus OK (leafcount=%zu, sat=%.1f)\n", lc, sat);
}

int main(void) {
    mtpndd_pal_config_t cfg = default_config();
    const char *workers = getenv("MTNDD_TEST_WORKERS");
    if (workers) { cfg.n_workers = atoi(workers); assert(cfg.n_workers > 0 && cfg.n_workers <= 64); }
    assert_success(mtpndd_init(&cfg));
    assert_success(mtpndd_declare_field(2));  // field 1, 2 bits
    assert_success(mtpndd_generate_fields());

    printf("fraction tests:\n");
    test_canonicalization();
    test_leaf_arithmetic();
    test_overflow();
    test_double_leaves();
    test_internal_plus();
    test_minus_residual();
    test_times_disjoint();
    test_divide();
    test_abstract_plus();
    test_abstract_plus_validate();
    test_parallel_plus();
    test_boolean_arithmetic_cache_isolation();
    test_double_abstract();
    test_leaf_gc();

    assert_success(mtpndd_quit());
    printf("all fraction tests passed\n");
    return 0;
}
