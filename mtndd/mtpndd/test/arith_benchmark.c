// MTPNDD arithmetic scaling benchmark.
//
// Runs plus / times on a 2-field dense structure and sweeps worker counts
// and cube sizes internally, printing a formatted table with speedup and
// parallel efficiency columns.  Invoke with:
//
//   mtpndd_arith_benchmark [-w 1,2,4,6] [-k 6,7,8] [-t fraction|double]
//                         [-r runs] [-o plus|times|both]
//
// Defaults: -w 1,2,4,6 -k 6,7,8 -t double -r 3 -o both
//
// Each (workers, k) config runs `runs` samples; the median wall
// time is reported.  A fresh mtpndd_init/quit cycle brackets each worker
// count since the worker pool size is fixed per init.

#include "mtpndd.h"
#include "mtpndd_memory_pool.h"
#include "sylvan.h"

#include <assert.h>
#include <getopt.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static void assert_success(mtpndd_error_t err) {
    if (err != MTPNDD_SUCCESS) {
        fprintf(stderr, "MTPNDD error %d (%s)\n", err, mtpndd_error_string(err));
        abort();
    }
}

static double seconds_since(struct timespec start) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (double)(now.tv_sec - start.tv_sec)
            + 1e-9 * (double)(now.tv_nsec - start.tv_nsec);
}

static mtpndd_bdd_t bit_cube(uint32_t field_id, uint32_t bit_width, uint32_t value) {
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

typedef enum { LEAF_DOUBLE = 0, LEAF_FRACTION = 1 } leaf_kind_t;
typedef enum { OP_PLUS = 1, OP_TIMES = 2 } op_mask_t;

// Build a 2-field dense MTPNDD with n_cubes cubes per field.  The leaf
// at (i, j) has value offset_* + unique factor so plus and times produce
// meaningful non-trivial output.
static mtpndd_t *build_two_field(leaf_kind_t kind, uint32_t bit_width,
                                 uint32_t n_cubes, double off_dbl, int32_t off_num) {
    mtpndd_edge_t *top_edges = mtpndd_memory_acquire_edge_map();
    mtpndd_edge_map_init(top_edges);
    double scale = 1.0 / (double)(n_cubes * n_cubes);

    for (uint32_t i = 0; i < n_cubes; ++i) {
        mtpndd_edge_t *sub = mtpndd_memory_acquire_edge_map();
        mtpndd_edge_map_init(sub);
        for (uint32_t j = 0; j < n_cubes; ++j) {
            mtpndd_t *leaf = NULL;
            if (kind == LEAF_DOUBLE) {
                double v = off_dbl + ((double)(i * n_cubes + j) + 1.0) * scale;
                leaf = mtpndd_make_double(v);
            } else {
                // Fractions: cap denom so d1*d2 stays < INT32_MAX after gcd.
                // Use denom = n_cubes (not n_cubes * n_cubes).
                int32_t numer = off_num + (int32_t)(i * n_cubes + j) + 1;
                leaf = mtpndd_make_fraction(numer, (int32_t)n_cubes);
            }
            assert(leaf);
            mtpndd_bdd_t cube2 = bit_cube(2, bit_width, j);
            assert_success(mtpndd_add_edge(sub, leaf, cube2));
        }
        mtpndd_t *sub_node = NULL;
        mtpndd_mk(2, sub, &sub_node);
        if (!sub_node) { mtpndd_edge_map_free(sub); abort(); }
        mtpndd_bdd_t cube1 = bit_cube(1, bit_width, i);
        assert_success(mtpndd_add_edge(top_edges, sub_node, cube1));
    }

    mtpndd_t *res = NULL;
    mtpndd_mk(1, top_edges, &res);
    if (!res) { mtpndd_edge_map_free(top_edges); abort(); }
    return res;
}

static mtpndd_pal_config_t make_config(int workers) {
    mtpndd_pal_config_t c = {
        .n_workers = workers,
        .lace_dqsize = 1 << 20,
        .bdd_nodetable_size = 1 << 20,
        .mtpndd_nodetable_size = 1 << 20,
        .op_cache_size = 1 << 20,
        .edge_bucket_count = 64,
        .nodetable_bucket_count = 1 << 16,
        .node_slab_capacity = 8192,
        .edge_entry_slab_capacity = 32768,
        .nodetable_entry_slab_capacity = 16384,
        .edge_map_slab_capacity = 8192,
    };
    return c;
}

typedef struct {
    int workers;
    int log2_cubes;
    double build_s;
    double plus_s;
    double times_s;
} bench_row_t;

static int cmp_double(const void *a, const void *b) {
    double da = *(const double *)a, db = *(const double *)b;
    return (da > db) - (da < db);
}

static double median_double(double *samples, size_t n) {
    qsort(samples, n, sizeof(double), cmp_double);
    if (n % 2 == 1) return samples[n / 2];
    return 0.5 * (samples[n / 2 - 1] + samples[n / 2]);
}

static void run_config(leaf_kind_t kind, int log2_cubes, int runs,
                       int ops_mask, bench_row_t *out) {
    uint32_t n_cubes = 1u << (uint32_t)log2_cubes;
    double build_samples[16] = {0};
    double plus_samples[16] = {0};
    double times_samples[16] = {0};
    if (runs > 16) runs = 16;

    for (int r = 0; r < runs; ++r) {
        // Vary offsets per run so each sample builds distinct a/b nodes —
        // otherwise mtpndd_mk's canonical dedup + the op cache would make
        // runs 2..N hit the cache and report near-zero time.
        double off_a_d = (double)(r * 1000);
        double off_b_d = (double)(r * 1000 + 100);
        int32_t off_a_n = (int32_t)(r * 1000);
        int32_t off_b_n = (int32_t)(r * 1000 + (int32_t)(n_cubes * 2));

        struct timespec t0;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        mtpndd_t *a = build_two_field(kind, (uint32_t)log2_cubes, n_cubes, off_a_d, off_a_n);
        mtpndd_t *b = build_two_field(kind, (uint32_t)log2_cubes, n_cubes, off_b_d, off_b_n);
        mtpndd_ref(a); mtpndd_ref(b);
        build_samples[r] = seconds_since(t0);

        if (ops_mask & OP_PLUS) {
            clock_gettime(CLOCK_MONOTONIC, &t0);
            mtpndd_t *sum = mtpndd_plus(a, b);
            plus_samples[r] = seconds_since(t0);
            assert(sum);
        }

        if (ops_mask & OP_TIMES) {
            clock_gettime(CLOCK_MONOTONIC, &t0);
            mtpndd_t *prod = mtpndd_times(a, b);
            times_samples[r] = seconds_since(t0);
            assert(prod);
        }

        mtpndd_deref(a); mtpndd_deref(b);
    }

    out->log2_cubes = log2_cubes;
    out->build_s = median_double(build_samples, (size_t)runs);
    out->plus_s  = (ops_mask & OP_PLUS)  ? median_double(plus_samples,  (size_t)runs) : 0.0;
    out->times_s = (ops_mask & OP_TIMES) ? median_double(times_samples, (size_t)runs) : 0.0;
}

static int parse_int_list(const char *s, int *out, int max_len) {
    int n = 0;
    const char *p = s;
    while (*p && n < max_len) {
        char *end;
        long v = strtol(p, &end, 10);
        if (end == p) break;
        out[n++] = (int)v;
        p = end;
        while (*p == ',' || *p == ' ') ++p;
    }
    return n;
}

static void usage(const char *prog) {
    fprintf(stderr,
            "usage: %s [-w W1,W2,...] [-k K1,K2,...] [-t double|fraction]\n"
            "           [-r runs] [-o plus|times|both]\n"
            "\n"
            "  -w  worker counts to sweep                (default 1,2,4,6)\n"
            "  -k  log2(cube counts per field) to sweep  (default 6,7,8)\n"
            "  -t  leaf type                              (default double)\n"
            "  -r  samples per config, median reported    (default 3)\n"
            "  -o  operation(s) to measure                (default both)\n",
            prog);
}

int main(int argc, char **argv) {
    int workers_arr[16] = {1, 2, 4, 6};
    int workers_n = 4;
    int cubes_arr[16] = {6, 7, 8};
    int cubes_n = 3;
    leaf_kind_t kind = LEAF_DOUBLE;
    int runs = 3;
    int ops_mask = OP_PLUS | OP_TIMES;

    int opt;
    while ((opt = getopt(argc, argv, "w:k:t:r:o:h")) != -1) {
        switch (opt) {
            case 'w': workers_n = parse_int_list(optarg, workers_arr, 16); break;
            case 'k': cubes_n = parse_int_list(optarg, cubes_arr, 16); break;
            case 't':
                if (strcmp(optarg, "double") == 0) kind = LEAF_DOUBLE;
                else if (strcmp(optarg, "fraction") == 0) kind = LEAF_FRACTION;
                else { fprintf(stderr, "unknown -t %s\n", optarg); return 1; }
                break;
            case 'r': runs = atoi(optarg); if (runs < 1) runs = 1; break;
            case 'o':
                if (strcmp(optarg, "plus") == 0) ops_mask = OP_PLUS;
                else if (strcmp(optarg, "times") == 0) ops_mask = OP_TIMES;
                else if (strcmp(optarg, "both") == 0) ops_mask = OP_PLUS | OP_TIMES;
                else { fprintf(stderr, "unknown -o %s\n", optarg); return 1; }
                break;
            case 'h':
            default: usage(argv[0]); return opt == 'h' ? 0 : 1;
        }
    }
    if (workers_n == 0 || cubes_n == 0) { usage(argv[0]); return 1; }

    printf("mtpndd arith scaling benchmark\n");
    printf("  leaf type : %s\n", kind == LEAF_DOUBLE ? "double" : "fraction");
    printf("  runs      : %d (median)\n", runs);
    printf("  ops       : %s%s%s\n",
           (ops_mask & OP_PLUS)  ? "plus "  : "",
           (ops_mask & OP_TIMES) ? "times" : "",
           "");
    printf("\n");

    // Header: one row block per cube size, columns = workers.
    for (int ci = 0; ci < cubes_n; ++ci) {
        int k = cubes_arr[ci];
        uint32_t n_cubes = 1u << (uint32_t)k;
        printf("k=%d  cubes=%u  (leaves=%u)\n", k, n_cubes, n_cubes * n_cubes);

        double base_plus = 0.0, base_times = 0.0;

        printf("  %-10s %10s %10s %10s %10s %10s\n",
               "workers", "build(s)",
               "plus(s)", "speedup",
               "times(s)", "speedup");
        printf("  ------------------------------------------------------------\n");

        for (int wi = 0; wi < workers_n; ++wi) {
            int w = workers_arr[wi];
            mtpndd_pal_config_t cfg = make_config(w);
            assert_success(mtpndd_init(&cfg));
            assert_success(mtpndd_declare_field((uint32_t)k));
            assert_success(mtpndd_declare_field((uint32_t)k));
            assert_success(mtpndd_generate_fields());

            bench_row_t row = {0};
            row.workers = w;
            run_config(kind, k, runs, ops_mask, &row);

            if (wi == 0) {
                base_plus = row.plus_s;
                base_times = row.times_s;
            }

            double sp_plus  = (row.plus_s  > 0.0 && base_plus  > 0.0) ? (base_plus  / row.plus_s)  : 0.0;
            double sp_times = (row.times_s > 0.0 && base_times > 0.0) ? (base_times / row.times_s) : 0.0;

            printf("  %-10d %10.3f", w, row.build_s);
            if (ops_mask & OP_PLUS)  printf(" %10.3f %9.2fx", row.plus_s,  sp_plus);
            else                     printf(" %10s %10s", "-", "-");
            if (ops_mask & OP_TIMES) printf(" %10.3f %9.2fx", row.times_s, sp_times);
            else                     printf(" %10s %10s", "-", "-");
            printf("\n");

            assert_success(mtpndd_quit());
        }
        printf("\n");
    }

    return 0;
}
