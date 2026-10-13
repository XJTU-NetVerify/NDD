#include "mtpndd.h"
#include "sylvan.h"
#include "mtpndd_memory_pool.h"
#include <assert.h>
#include <stdlib.h>
#include <math.h>

static mtpndd_bdd_t cube(unsigned field, unsigned value) {
    mtpndd_bdd_t label = sylvan_ref(sylvan_true);
    for (unsigned bit = 0; bit < 4; ++bit) {
        mtpndd_bdd_t v = (value >> bit) & 1 ? mtpndd_get_bdd_var(field, bit) : mtpndd_get_bdd_not_var(field, bit);
        mtpndd_bdd_t next = sylvan_ref(sylvan_and(label, v));
        sylvan_deref(label); label = next;
    }
    return label;
}
static mtpndd_t *dense(int second, int doubles) {
    mtpndd_edge_t *top = mtpndd_memory_acquire_edge_map(); assert(top);
    mtpndd_edge_map_init(top);
    mtpndd_t *held[16];
    for (unsigned i = 0; i < 16; ++i) {
        mtpndd_edge_t *row = mtpndd_memory_acquire_edge_map(); assert(row);
        mtpndd_edge_map_init(row);
        for (unsigned j = 0; j < 16; ++j) {
            unsigned k = i * 16 + j;
            int numerator = second ? 512 - k : k + 1, denominator = second ? 3 : 2;
            mtpndd_t *leaf = doubles ? mtpndd_make_double((double)numerator / denominator) : mtpndd_make_fraction(numerator, denominator);
            assert(leaf);
            mtpndd_bdd_t label = cube(2, j);
            assert(mtpndd_add_edge(row, leaf, label) == MTPNDD_SUCCESS); sylvan_deref(label);
        }
        mtpndd_t *child = NULL; mtpndd_mk(2, row, &child);
        assert(child && !mtpndd_is_terminal(child)); mtpndd_ref(child); held[i] = child;
        mtpndd_bdd_t label = cube(1, i);
        assert(mtpndd_add_edge(top, child, label) == MTPNDD_SUCCESS); sylvan_deref(label);
    }
    mtpndd_t *root = NULL; mtpndd_mk(1, top, &root);
    assert(root && root->edges->edge_count == 16); mtpndd_ref(root);
    for (unsigned i = 0; i < 16; ++i) mtpndd_deref(held[i]);
    return root;
}
static mtpndd_t *evaluate(mtpndd_t *node, unsigned i, unsigned j) {
    while (!mtpndd_is_terminal(node)) {
        mtpndd_bdd_t assignment = cube(node->field_id, node->field_id == 1 ? i : j);
        mtpndd_t *child = NULL;
        for (size_t k = 0; k < node->edges->bucket_count; ++k)
            for (edge_bucket_entry_t *e = node->edges->buckets[k]; e; e = e->next)
                if (sylvan_and(atomic_load(&e->label), assignment) != sylvan_false) { assert(!child); child = e->child; }
        sylvan_deref(assignment); assert(child); node = child;
    }
    return node;
}
int main(int argc, char **argv) {
    mtpndd_pal_config_t cfg = { .n_workers = argc > 1 ? atoi(argv[1]) : 1,
        .lace_dqsize = 1 << 16, .bdd_nodetable_size = 1 << 16,
        .mtpndd_nodetable_size = 1 << 16, .op_cache_size = 64,
        .edge_bucket_count = 32, .nodetable_bucket_count = 1 << 12,
        .node_slab_capacity = 1024, .edge_entry_slab_capacity = 4096,
        .nodetable_entry_slab_capacity = 1024, .edge_map_slab_capacity = 1024 };
    assert(mtpndd_init(&cfg) == MTPNDD_SUCCESS);
    assert(mtpndd_declare_field(4) == MTPNDD_SUCCESS);
    assert(mtpndd_declare_field(4) == MTPNDD_SUCCESS);
    assert(mtpndd_generate_fields() == MTPNDD_SUCCESS);
    for (int doubles = 0; doubles <= 1; ++doubles) {
        mtpndd_t *a = dense(0, doubles), *b = dense(1, doubles);
        mtpndd_t *(*ops[])(mtpndd_t *, mtpndd_t *) = { mtpndd_plus, mtpndd_minus, mtpndd_times, mtpndd_divide };
        for (unsigned op = 0; op < 4; ++op) {
            mtpndd_t *result = ops[op](a, b); assert(result); mtpndd_ref(result);
            for (unsigned i = 0; i < 16; ++i) for (unsigned j = 0; j < 16; ++j) {
                int k = i * 16 + j, n, d;
                if (op == 0) { n = 3 * (k + 1) + 2 * (512 - k); d = 6; }
                else if (op == 1) { n = 3 * (k + 1) - 2 * (512 - k); d = 6; }
                else if (op == 2) { n = (k + 1) * (512 - k); d = 6; }
                else { n = 3 * (k + 1); d = 2 * (512 - k); }
                mtpndd_t *leaf = evaluate(result, i, j);
                if (!doubles) assert(leaf == mtpndd_make_fraction(n, d));
                else { double expected = (double)n / d; assert(mtpndd_is_double_leaf(leaf));
                    assert(fabs(mtpndd_get_double(leaf) - expected) <= 1e-12 * fmax(1, fabs(expected))); }
            }
            mtpndd_deref(result);
        }
        mtpndd_t *sum1 = mtpndd_abstract_plus(a, 1); assert(sum1); mtpndd_ref(sum1);
        mtpndd_t *total = mtpndd_abstract_plus(sum1, 2); assert(total);
        if (!doubles) assert(total == mtpndd_make_fraction(16448, 1));
        else assert(mtpndd_get_double(total) == 16448.0);
        mtpndd_deref(sum1); mtpndd_deref(a); mtpndd_deref(b);
    }
    assert(mtpndd_quit() == MTPNDD_SUCCESS);
    return 0;
}
