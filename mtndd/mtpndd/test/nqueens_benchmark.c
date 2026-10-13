#include "mtpndd.h"
#include "mtpndd_common.h"
#include <sylvan.h>
#include "sylvan_stats.h"
#include <lace.h>

#include <inttypes.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static inline double timespec_diff_seconds(const struct timespec *start, const struct timespec *end) {
    time_t sec_diff = end->tv_sec - start->tv_sec;
    long nsec_diff = end->tv_nsec - start->tv_nsec;
    return (double)sec_diff + (double)nsec_diff / 1e9;
}

static inline size_t next_pow2(size_t v) {
    if (v <= 1) return 1;
    v--;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
#if ULONG_MAX > 0xffffffff
    v |= v >> 32;
#endif
    v++;
    return v;
}

static inline mtpndd_t *mtpndd_and_to(mtpndd_t *a, mtpndd_t *b) {
    mtpndd_t *res = mtpndd_and(a, b);
    if (!res) return NULL;
    mtpndd_ref(res);
    mtpndd_deref(a);
    return res;
}

static inline mtpndd_t *mtpndd_or_to(mtpndd_t *a, mtpndd_t *b) {
    mtpndd_t *res = mtpndd_or(a, b);
    if (!res) return NULL;
    mtpndd_ref(res);
    mtpndd_deref(a);
    return res;
}

static mtpndd_t *make_implication(mtpndd_t *guard, mtpndd_t *conseq) {
    mtpndd_ref(guard);
    mtpndd_ref(conseq);
    mtpndd_t *neg_guard = mtpndd_not(guard);
    if (!neg_guard) {
        mtpndd_deref(guard);
        mtpndd_deref(conseq);
        return NULL;
    }
    mtpndd_ref(neg_guard);
    mtpndd_t *imp = mtpndd_or(neg_guard, conseq);
    if (imp) mtpndd_ref(imp);
    mtpndd_deref(guard);
    mtpndd_deref(conseq);
    mtpndd_deref(neg_guard);
    return imp;
}

static mtpndd_t *build_cell(size_t row, size_t col, size_t n) {
    mtpndd_t *a = &MTPNDD_TRUE;
    mtpndd_t *b = &MTPNDD_TRUE;
    mtpndd_t *c = &MTPNDD_TRUE;
    mtpndd_t *d = &MTPNDD_TRUE;
    mtpndd_ref(a);
    mtpndd_ref(b);
    mtpndd_ref(c);
    mtpndd_ref(d);

    mtpndd_t *guard = mtpndd_get_var((uint32_t)(row + 1), (uint32_t)col);
    if (!guard) goto error;

    // same column
    for (size_t l = 0; l < n; ++l) {
        if (l == col) continue;
        mtpndd_t *mp = make_implication(guard, mtpndd_get_not_var((uint32_t)(row + 1), (uint32_t)l));
        if (!mp) goto error;
        mtpndd_t *next = mtpndd_and_to(a, mp);
        mtpndd_deref(mp);
        if (!next) goto error;
        a = next;
    }

    // same row
    for (size_t k = 0; k < n; ++k) {
        if (k == row) continue;
        mtpndd_t *mp = make_implication(guard, mtpndd_get_not_var((uint32_t)(k + 1), (uint32_t)col));
        if (!mp) goto error;
        mtpndd_t *next = mtpndd_and_to(b, mp);
        mtpndd_deref(mp);
        if (!next) goto error;
        b = next;
    }

    // up-right diagonal
    for (size_t k = 0; k < n; ++k) {
        long ll = (long)k - (long)row + (long)col;
        if (ll < 0 || (size_t)ll >= n || k == row) continue;
        mtpndd_t *mp = make_implication(guard, mtpndd_get_not_var((uint32_t)(k + 1), (uint32_t)ll));
        if (!mp) goto error;
        mtpndd_t *next = mtpndd_and_to(c, mp);
        mtpndd_deref(mp);
        if (!next) goto error;
        c = next;
    }

    // down-right diagonal
    for (size_t k = 0; k < n; ++k) {
        long ll = (long)row + (long)col - (long)k;
        if (ll < 0 || (size_t)ll >= n || k == row) continue;
        mtpndd_t *mp = make_implication(guard, mtpndd_get_not_var((uint32_t)(k + 1), (uint32_t)ll));
        if (!mp) goto error;
        mtpndd_t *next = mtpndd_and_to(d, mp);
        mtpndd_deref(mp);
        if (!next) goto error;
        d = next;
    }

    mtpndd_t *tmp = mtpndd_and_to(c, d);
    mtpndd_deref(d);
    if (!tmp) goto error;
    c = tmp;

    tmp = mtpndd_and_to(b, c);
    mtpndd_deref(c);
    if (!tmp) goto error;
    b = tmp;

    tmp = mtpndd_and_to(a, b);
    mtpndd_deref(b);
    if (!tmp) goto error;
    a = tmp;

    return a;

error:
    mtpndd_deref(a);
    mtpndd_deref(b);
    mtpndd_deref(c);
    mtpndd_deref(d);
    return NULL;
}

static bool declare_fields(size_t n) {
    for (size_t i = 0; i < n; ++i) {
        if (mtpndd_declare_field((uint32_t)n) != MTPNDD_SUCCESS) {
            return false;
        }
    }
    if (mtpndd_generate_fields() != MTPNDD_SUCCESS) {
        return false;
    }
    return true;
}

static size_t g_n_workers = 0;

/*
 * Lace 1.6.x requires that all code invoking Sylvan/MTPNDD TASKs
 * (mtpndd_and, sylvan_and, ...) runs inside a Lace worker context.
 * Calling RUN() repeatedly from the main thread goes through the
 * external-task dispatch path, which deadlocks/hangs. The main-thread
 * code therefore does only setup (lace_start, sylvan_init, mtpndd_init),
 * then enters worker context once via RUN(bench_do_work, ...), and
 * tears down after.
 */
VOID_TASK_DECL_3(bench_do_work, size_t, bool*, struct timespec*)

static bool run_benchmark(size_t n) {
    size_t bdd_size = 1 + (size_t)fmax(1000.0, pow(4.4, (double)n - 6.0) * 1000.0);
    size_t bdd_cache = 320000;
    size_t ndd_size = 100000000;

    bdd_size = next_pow2(bdd_size);
    bdd_cache = next_pow2(bdd_cache);
    ndd_size = next_pow2(ndd_size);

    /* Pre-allocate nodetable buckets to lower the initial load factor and
     * reduce avg lookup chain length, cutting nodetable lookup time by ~30%.
     * Only applied when bdd_size >= 1M (N>=11): below that threshold the
     * bucket array initialization cost exceeds the lookup savings.
     * The internal hard cap is 2^21 = 2097152 buckets per field. */
    /* Pre-allocate nodetable buckets at 1/4 the BDD table size so the
     * initial per-field load factor ≈ nodes/bdd_size × 4 ≈ 0.9.  This keeps
     * avg lookup chain length near 1.0 without excess init cost.
     * Cap at 8M (= new library hard limit `1<<23`).
     * Only applied when bdd_size >= 1M (N>=11): below that the init cost
     * exceeds the lookup savings. */
    size_t nodetable_init_buckets = 0;
    if (bdd_size >= (size_t)1048576) {
        nodetable_init_buckets = bdd_size / 4;
        if (nodetable_init_buckets > (size_t)8388608)
            nodetable_init_buckets = (size_t)8388608;
    }

    mtpndd_pal_config_t cfg = {
        .n_workers = g_n_workers,
        .lace_dqsize = 1 << 20,
        .bdd_nodetable_size = bdd_size,
        .mtpndd_nodetable_size = ndd_size,
        .op_cache_size = bdd_cache,
        /* 0 = use library default MTPNDD_DEFAULT_EDGE_BUCKET_COUNT (8).
         * Workloads with denser edge distributions can set this to a
         * power of 2 up to 64 via cfg.edge_bucket_count (or
         * MTPNDDConfig.edgeBucketCount on the Java side). */
        .edge_bucket_count = 0,
        .nodetable_bucket_count = nodetable_init_buckets,
        .node_slab_capacity = 0,
        .edge_entry_slab_capacity = 0,
        .nodetable_entry_slab_capacity = 0,
        .edge_map_slab_capacity = 0,
    };

    if (mtpndd_init(&cfg) != MTPNDD_SUCCESS) {
        fprintf(stderr, "mtpndd_init failed: %s\n", mtpndd_error_string(mtpndd_get_last_error().code));
        return false;
    }

    /* Optional: control BDD-internal spawn depth cutoff for A/B testing.
     * <0 (default) = unlimited SPAWN (upstream); 0 = force CALL everywhere;
     * >0 = cap nested SPAWN depth. Set MTPNDD_BDD_SPAWN_CUTOFF to override. */
    const char *cutoff_env = getenv("MTPNDD_BDD_SPAWN_CUTOFF");
    if (cutoff_env && *cutoff_env) {
        sylvan_set_spawn_depth_cutoff(atoi(cutoff_env));
    }

    struct timespec start_ts = {0};
    clock_gettime(CLOCK_MONOTONIC, &start_ts);

    bool ok = false;
    RUN(bench_do_work, n, &ok, &start_ts);
    mtpndd_quit();
    return ok;
}

VOID_TASK_IMPL_3(bench_do_work, size_t, n, bool*, ok_out, struct timespec*, start_ts) {
    struct timespec end_ts = {0};

    if (!declare_fields(n)) {
        fprintf(stderr, "declare_fields failed\n");
        *ok_out = false;
        return;
    }

    mtpndd_t **or_batch = (mtpndd_t **)calloc(n, sizeof(mtpndd_t *));
    mtpndd_t **imp_batch = (mtpndd_t **)calloc(n * n, sizeof(mtpndd_t *));
    if (!or_batch || !imp_batch) {
        fprintf(stderr, "allocation failed\n");
        free(or_batch);
        free(imp_batch);
        *ok_out = false;
        return;
    }

    for (size_t i = 0; i < n; ++i) {
        mtpndd_t *cond = &MTPNDD_FALSE;
        mtpndd_ref(cond);
        for (size_t j = 0; j < n; ++j) {
            mtpndd_t *next = mtpndd_or_to(cond, mtpndd_get_var((uint32_t)(i + 1), (uint32_t)j));
            if (!next) {
                mtpndd_deref(cond);
                goto build_fail;
            }
            cond = next;
        }
        or_batch[i] = cond;
    }

    for (size_t i = 0; i < n; ++i) {
        for (size_t j = 0; j < n; ++j) {
            mtpndd_t *cell = build_cell(i, j, n);
            if (!cell) goto build_fail;
            imp_batch[i * n + j] = cell;
        }
    }

    mtpndd_t *queen = &MTPNDD_TRUE;
    mtpndd_ref(queen);
    for (size_t i = 0; i < n; ++i) {
        mtpndd_t *next = mtpndd_and_to(queen, or_batch[i]);
        mtpndd_deref(or_batch[i]);
        if (!next) goto build_fail;
        queen = next;
    }
    for (size_t i = 0; i < n; ++i) {
        for (size_t j = 0; j < n; ++j) {
            mtpndd_t *next = mtpndd_and_to(queen, imp_batch[i * n + j]);
            mtpndd_deref(imp_batch[i * n + j]);
            if (!next) goto build_fail;
            queen = next;
        }
    }

    double satcount_val = mtpndd_satcount(queen);
    uint64_t solutions = (uint64_t)llround(satcount_val);
    mtpndd_deref(queen);

    clock_gettime(CLOCK_MONOTONIC, &end_ts);
    double elapsed = timespec_diff_seconds(start_ts, &end_ts);

    printf("\t%.3f\t%" PRIu64 "\n", elapsed, solutions);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    const mtpndd_stats_t *stats = mtpndd_get_stats();
    if (stats) {
        printf(".. stats: created=%" PRIu64 ", reused=%" PRIu64 ", collected=%" PRIu64 "\n",
               stats->nodes_created_total, stats->nodes_reused_total, stats->nodes_collected_last);
        printf(".. stats: max_edges_per_node=%" PRIu64 ", cache hits/misses=%" PRIu64 "/%" PRIu64 "\n",
               stats->max_edges_per_node, stats->cache_lookup_hits, stats->cache_lookup_misses);
        printf(".. stats: time and/or/not = %.3f/%.3f/%.3f s\n",
               stats->and_time_ns / 1e9, stats->or_time_ns / 1e9, stats->not_time_ns / 1e9);
        printf(".. stats: and fast/cache/build/diff/mk = %.3f/%.3f/%.3f/%.3f/%.3f s\n",
               stats->and_fastpath_ns / 1e9,
               stats->and_cache_hit_ns / 1e9,
               stats->and_build_edges_ns / 1e9,
               stats->and_diff_field_ns / 1e9,
               stats->and_mk_ns / 1e9);
        printf(".. stats: and same outer/inner/label/bdd/add = %.3f/%.3f/%.3f/%.3f/%.3f s (total=%.3f)\n",
               stats->and_same_outer_loop_ns / 1e9,
               stats->and_same_inner_loop_ns / 1e9,
               stats->and_same_label_load_ns / 1e9,
               stats->and_same_bdd_op_ns / 1e9,
               stats->and_same_add_edge_ns / 1e9,
               stats->and_same_field_ns / 1e9);
        printf(".. stats: and mk call/cache/other = %.3f/%.3f/%.3f s\n",
               stats->and_mk_call_ns / 1e9,
               stats->and_mk_cache_store_ns / 1e9,
               stats->and_mk_other_ns / 1e9);
        printf(".. stats: mk hash/lookup/fast/reuse/ref/gc/alloc_node/alloc_entry = %.3f/%.3f/%.3f/%.3f/%.3f/%.3f/%.3f/%.3f s\n",
               stats->mk_hash_ns / 1e9,
               stats->mk_lookup_ns / 1e9,
               stats->mk_fast_return_ns / 1e9,
               stats->mk_reuse_cleanup_ns / 1e9,
               stats->mk_ref_children_ns / 1e9,
               stats->mk_gc_or_grow_ns / 1e9,
               stats->mk_alloc_node_ns / 1e9,
               stats->mk_alloc_entry_ns / 1e9);
        printf(".. stats: mk scan/link/collision = %.3f/%.3f/%.3f s\n",
               stats->mk_bucket_scan_ns / 1e9,
               stats->mk_link_ns / 1e9,
               stats->mk_collision_cleanup_ns / 1e9);
        printf(".. stats: mk other/total = %.3f/%.3f s\n",
               stats->mk_other_ns / 1e9,
               stats->mk_total_ns / 1e9);
        printf(".. stats: edge_map rehash=%" PRIu64 " max_buckets=%" PRIu64 " | nodetable rehash=%" PRIu64 " max_buckets=%" PRIu64 "\n",
               stats->edge_map_rehash_total, stats->edge_map_max_buckets,
               stats->nodetable_rehash_total, stats->nodetable_max_buckets);
        printf(".. stats: cache stores=%" PRIu64 ", overwrites=%" PRIu64 " (%.1f%%)\n",
               stats->cache_store_total, stats->cache_store_overwrites,
               stats->cache_store_total > 0 ? 100.0 * stats->cache_store_overwrites / stats->cache_store_total : 0.0);
        printf(".. stats: edge_inserts=%" PRIu64 ", edge_collisions=%" PRIu64 " (%.1f%%), nodetable_collisions=%" PRIu64 "\n",
               stats->edge_insert_total, stats->edge_collision_total,
               stats->edge_insert_total > 0 ? 100.0 * stats->edge_collision_total / stats->edge_insert_total : 0.0,
               stats->nodetable_collision_total);
        printf(".. stats: nodetable hash/bucket/compare = %.3f/%.3f/%.3f s\n",
               stats->nodetable_hash_ns / 1e9,
               stats->nodetable_bucket_scan_ns / 1e9,
               stats->nodetable_edge_compare_ns / 1e9);
        uint64_t nodetable_lookup_total = stats->nodetable_lookup_hits + stats->nodetable_lookup_misses;
        double nodetable_avg_steps = nodetable_lookup_total
            ? (double)stats->nodetable_edge_compare_steps_total / (double)nodetable_lookup_total
            : 0.0;
        printf(".. stats: nodetable lookup hits/misses=%" PRIu64 "/%" PRIu64 " edge_entries=%" PRIu64 " avg_steps=%.2f max_steps=%" PRIu64 "\n",
               stats->nodetable_lookup_hits,
               stats->nodetable_lookup_misses,
               stats->nodetable_edge_compare_entries,
               nodetable_avg_steps,
               stats->nodetable_edge_compare_max_steps);
        printf(".. stats: temp_refs grow_total=%" PRIu64 " peak_capacity=%" PRIu64 "\n",
               mtpndd_temp_refs_grow_total(),
               mtpndd_temp_refs_peak_capacity());
        printf(".. stats: resize refs/protect=%" PRIu64 "/%" PRIu64 "\n",
               refs_resize_total(),
               protect_resize_total());
        printf(".. stats: refs_gc merge_drop=%" PRIu64 " net_positive=%" PRIu64 " net_negative=%" PRIu64 "\n",
               mtbdd_refs_merge_drop_total(),
               mtbdd_refs_gc_net_positive_total(),
               mtbdd_refs_gc_net_negative_total());
        printf(".. stats: protect add/unprotect=%" PRIu64 "/%" PRIu64 " hit/miss=%" PRIu64 "/%" PRIu64 " del_only=%" PRIu64 "\n",
               mtbdd_protect_add_total(),
               mtbdd_unprotect_total(),
               mtbdd_protect_add_remove_hit_total(),
               mtbdd_protect_add_remove_miss_total(),
               mtbdd_protect_del_only_total());
        printf(".. stats: protect_gc merge_add/del/remaining/unmatched=%" PRIu64 "/%" PRIu64 "/%" PRIu64 "/%" PRIu64 "\n",
               mtbdd_protect_gc_merge_add_total(),
               mtbdd_protect_gc_merge_del_total(),
               mtbdd_protect_gc_remaining_total(),
               mtbdd_protect_gc_unmatched_total());
    }
#endif

    free(or_batch);
    free(imp_batch);
    sylvan_stats_report(stdout);
    *ok_out = true;
    return;

build_fail:
    for (size_t i = 0; i < n; ++i) {
        if (or_batch && or_batch[i]) mtpndd_deref(or_batch[i]);
    }
    for (size_t i = 0; i < n * n; ++i) {
        if (imp_batch && imp_batch[i]) mtpndd_deref(imp_batch[i]);
    }
    free(or_batch);
    free(imp_batch);
    *ok_out = false;
    return;
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "Usage: %s <n> [workers]\n", argv[0]);
        fprintf(stderr, "  n: board size (e.g., 12)\n");
        fprintf(stderr, "  workers: number of workers (default: 0)\n");
        return EXIT_FAILURE;
    }
    char *endptr = NULL;
    long parsed = strtol(argv[1], &endptr, 10);
    if (*argv[1] == '\0' || (endptr && *endptr != '\0') || parsed <= 0) {
        fprintf(stderr, "Invalid n value: %s\n", argv[1]);
        return EXIT_FAILURE;
    }
    size_t n = (size_t)parsed;

    if (argc == 3) {
        long workers_parsed = strtol(argv[2], &endptr, 10);
        if (*argv[2] == '\0' || (endptr && *endptr != '\0') || workers_parsed < 0) {
            fprintf(stderr, "Invalid workers value: %s\n", argv[2]);
            return EXIT_FAILURE;
        }
        g_n_workers = (size_t)workers_parsed;
    }

    if (!run_benchmark(n)) {
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
