#include "mtpndd.h"
#include "mtpndd_common.h"
#include "mtpndd_node.h"
#include "mtpndd_memory_pool.h"

#include <stdatomic.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <inttypes.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

typedef struct {
    size_t size;
    uint32_t *field_ids;
} nqueens_ctx_t;

typedef struct {
    size_t size;
    uint64_t expected;
    uint64_t solutions;
    double seconds;
    uint64_t mtpndd_nodes;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    uint64_t mtpndd_nodes_created;
    uint64_t mtpndd_nodes_reused;
    uint64_t mtpndd_nodes_collected;
    uint64_t mtpndd_max_edges;
    uint64_t cache_hits;
    uint64_t cache_misses;
#endif
} nqueens_metrics_t;

static void nqueens_ctx_destroy(nqueens_ctx_t *ctx);
static uint64_t expected_solutions(size_t size);
static double timespec_diff_seconds(const struct timespec *start, const struct timespec *end);

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
static void log_formula_progress(const char *phase, size_t current, size_t total);
static void print_run_stats(const mtpndd_stats_t *stats);
#else
static inline void log_formula_progress(const char *phase, size_t current, size_t total) {
    (void)phase;
    (void)current;
    (void)total;
}
static inline void print_run_stats(const mtpndd_stats_t *stats) {
    (void)stats;
}
#endif

#ifdef MTPNDD_NQUEENS_ENABLE_DOT
static void dump_dot_file(const char *filename, mtpndd_t *node);
#else
static inline void dump_dot_file(const char *filename, mtpndd_t *node) {
    (void)filename;
    (void)node;
}
#endif

static mtpndd_t *build_row_at_least_one(const nqueens_ctx_t *ctx, size_t row) {
    mtpndd_t *accum = &MTPNDD_FALSE;
    mtpndd_ref(accum);

    for (size_t col = 0; col < ctx->size; ++col) {
        uint32_t field = ctx->field_ids[row];
        mtpndd_t *cell = mtpndd_get_var(field, (uint32_t)col);
        mtpndd_ref(cell);
        mtpndd_t *old = accum;
        mtpndd_t *next = mtpndd_or(old, cell);
        mtpndd_deref(cell);
        mtpndd_ref(next);
        mtpndd_deref(old);
        accum = next;
    }

    return accum;
}

static void append_implication(mtpndd_t **accum, mtpndd_t *guard_neg, mtpndd_t *neg_literal) {
    mtpndd_ref(neg_literal);
    mtpndd_t *imp = mtpndd_or(guard_neg, neg_literal);
    mtpndd_deref(neg_literal);
    mtpndd_ref(imp);
    mtpndd_t *old = *accum;
    mtpndd_t *next = mtpndd_and(old, imp);
    mtpndd_deref(imp);
    mtpndd_ref(next);
    mtpndd_deref(old);
    *accum = next;
}

static mtpndd_t *build_cell_implication(const nqueens_ctx_t *ctx, size_t row, size_t col) {
    mtpndd_t *accum = &MTPNDD_TRUE;
    mtpndd_ref(accum);

    mtpndd_t *guard_neg = mtpndd_get_not_var((uint32_t)(row + 1), (uint32_t)col);
    mtpndd_ref(guard_neg);

    size_t n = ctx->size;

    for (size_t other_col = 0; other_col < n; ++other_col) {
        if (other_col == col) continue;
        append_implication(&accum, guard_neg,
                                mtpndd_get_not_var((uint32_t)(row + 1), (uint32_t)other_col));
    }

    for (size_t other_row = 0; other_row < n; ++other_row) {
        if (other_row == row) continue;
        append_implication(&accum, guard_neg,
                                mtpndd_get_not_var((uint32_t)(other_row + 1), (uint32_t)col));
    }

    for (size_t other_row = 0; other_row < n; ++other_row) {
        if (other_row == row) continue;
        long diag_up = (long)other_row - (long)row + (long)col;
        if (diag_up >= 0 && (size_t)diag_up < n) {
            append_implication(&accum, guard_neg,
                                    mtpndd_get_not_var((uint32_t)(other_row + 1), (uint32_t)diag_up));
        }
        long diag_down = (long)row + (long)col - (long)other_row;
        if (diag_down >= 0 && (size_t)diag_down < n) {
            append_implication(&accum, guard_neg,
                                    mtpndd_get_not_var((uint32_t)(other_row + 1), (uint32_t)diag_down));
        }
    }

    mtpndd_deref(guard_neg);
    return accum;
}

static bool nqueens_ctx_init(nqueens_ctx_t *ctx, size_t size) {
    ctx->size = size;
    ctx->field_ids = (uint32_t *)calloc(size, sizeof(uint32_t));
    if (!ctx->field_ids) return false;
    for (size_t row = 0; row < size; ++row) {
        if (mtpndd_declare_field((uint32_t)size) != MTPNDD_SUCCESS) {
            return false;
        }
        ctx->field_ids[row] = (uint32_t)(row + 1);
    }
    if (mtpndd_generate_fields() != MTPNDD_SUCCESS) {
        return false;
    }
    return true;
}

static bool build_nqueens_formula(const nqueens_ctx_t *ctx, mtpndd_t **out_formula) {
    size_t n = ctx->size;
    bool ok = false;
    mtpndd_t *formula = NULL;
    mtpndd_t *base = &MTPNDD_TRUE;
    mtpndd_ref(base);
    formula = base;

    for (size_t row = 0; row < n; ++row) {
        mtpndd_t *at_least = build_row_at_least_one(ctx, row);
        if (!at_least) goto cleanup;
#ifdef MTPNDD_NQUEENS_ENABLE_DOT
        if (ctx->size <= 12) {
            char name[128];
            snprintf(name, sizeof(name), "n%zu_row_atleast_%zu.dot", ctx->size, row);
            dump_dot_file(name, at_least);
        }
#endif
        mtpndd_t *old = formula;
        mtpndd_t *next = mtpndd_and(old, at_least);
        mtpndd_deref(at_least);
        if (!next) {
            mtpndd_deref(old);
            formula = NULL;
            goto cleanup;
        }
        mtpndd_ref(next);
        mtpndd_deref(old);
        formula = next;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        log_formula_progress("row_at_least_one", row + 1, n);
#endif
    }

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    size_t total_cells = n * n;
    size_t progress = 0;
#endif
    for (size_t row = 0; row < n; ++row) {
        for (size_t col = 0; col < n; ++col) {
            mtpndd_t *imp = build_cell_implication(ctx, row, col);
            if (!imp) goto cleanup;
#ifdef MTPNDD_NQUEENS_ENABLE_DOT
            if (ctx->size <= 12) {
                char name[160];
                snprintf(name, sizeof(name), "n%zu_imp_row%zu_col%zu.dot", ctx->size, row, col);
                dump_dot_file(name, imp);
            }
#endif
            mtpndd_t *old = formula;
            mtpndd_t *next = mtpndd_and(old, imp);
            mtpndd_deref(imp);
            if (!next) {
                mtpndd_deref(old);
                formula = NULL;
                goto cleanup;
            }
            mtpndd_ref(next);
            mtpndd_deref(old);
            formula = next;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
            progress++;
            log_formula_progress("cell_implications", progress, total_cells);
#endif
        }
    }

    *out_formula = formula;
    formula = NULL;
    ok = true;

cleanup:
    if (formula) mtpndd_deref(formula);
    return ok;
}

static bool run_case(size_t size, nqueens_metrics_t *metrics) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    printf("== solving n=%zu\n", size);
    fflush(stdout);
#endif

    bool ok = false;
    nqueens_ctx_t ctx = {0};
    mtpndd_t *formula = NULL;

    size_t bdd_size = 1 << 18;
    size_t ndd_size = 1 << 18;
    size_t cache_size = 1 << 19; // smaller op cache to reduce overhead
    size_t edge_bucket_count = 16;
    size_t node_slab_capacity = 1024;
    size_t edge_entry_slab_capacity = 2048;
    size_t nodetable_entry_slab_capacity = 1024;
    size_t edge_map_slab_capacity = 512;

    if (size > 6 && size <= 8) {
        bdd_size = 1 << 19;
        ndd_size = 1 << 19;
        cache_size = 1 << 19;
        edge_bucket_count = 16;
        node_slab_capacity = 1536;
        edge_entry_slab_capacity = 3072;
        nodetable_entry_slab_capacity = 1536;
        edge_map_slab_capacity = 768;
    } else if (size > 8 && size <= 10) {
        bdd_size = 1 << 20;
        ndd_size = 1 << 19; // reduce nodetable buckets to be closer to node count
        cache_size = 1 << 20;
        edge_bucket_count = 16;
        node_slab_capacity = 2048;
        edge_entry_slab_capacity = 4096;
        nodetable_entry_slab_capacity = 2048;
        edge_map_slab_capacity = 1024;
    } else if (size > 10) {
        bdd_size = 1 << 20;
        // For N>=12, MTPNDD can exceed ~5e5 nodes while building the formula.
        // Keep capacity comfortably above that to avoid triggering GC/grow mid-parallel tasks.
        ndd_size = 1 << 22;
        cache_size = 1 << 20;
        edge_bucket_count = 16;
        node_slab_capacity = 3072;
        edge_entry_slab_capacity = 6144;
        nodetable_entry_slab_capacity = 3072;
        edge_map_slab_capacity = 1280;
    }

    // Hash-table buckets are per-field; keep this moderate even if capacity is larger.
    size_t nodetable_bucket_count = ndd_size;
    if (size > 10) {
        nodetable_bucket_count = 1 << 19;
    }

    mtpndd_pal_config_t config = {
        .n_workers = 0,  // Auto worker count (Lace decides), not single-worker mode.
        .lace_dqsize = 1 << 20,
        .bdd_nodetable_size = bdd_size,
        .mtpndd_nodetable_size = ndd_size,
        .op_cache_size = cache_size,
        .quick_growth_threshold = 0.1,
        .edge_bucket_count = edge_bucket_count,
        .nodetable_bucket_count = nodetable_bucket_count,
        .node_slab_capacity = node_slab_capacity,
        .edge_entry_slab_capacity = edge_entry_slab_capacity,
        .nodetable_entry_slab_capacity = nodetable_entry_slab_capacity,
        .edge_map_slab_capacity = edge_map_slab_capacity,
    };

    struct timespec run_start = {0}, run_finish = {0}, init_finish = {0}, ctx_finish = {0};
    clock_gettime(CLOCK_MONOTONIC, &run_start);

    if (mtpndd_init(&config) != MTPNDD_SUCCESS) {
        fprintf(stderr, "mtpndd_init failed for size %zu: %s\n", size,
                mtpndd_error_string(mtpndd_get_last_error().code));
        return false;
    }

#if LACE_COUNT_EVENTS
    lace_count_reset();
#endif
    clock_gettime(CLOCK_MONOTONIC, &init_finish);

    if (!nqueens_ctx_init(&ctx, size)) {
        fprintf(stderr, "Failed to initialise context for size %zu.\n", size);
        goto cleanup;
    }
    clock_gettime(CLOCK_MONOTONIC, &ctx_finish);

    // Build the formula directly (serial NDD calling parallel Sylvan)
    // This matches Java NDD behavior where each BDD call goes through Lace
    if (!build_nqueens_formula(&ctx, &formula)) {
        fprintf(stderr, "Failed to build formula for size %zu.\n", size);
        goto cleanup;
    }

    double satcount_value = mtpndd_satcount(formula);

    clock_gettime(CLOCK_MONOTONIC, &run_finish);
    double elapsed = timespec_diff_seconds(&run_start, &run_finish);

    uint64_t solutions = (uint64_t)llround(satcount_value);
    uint64_t expected = expected_solutions(size);
    if (expected != UINT64_MAX && solutions != expected) {
        fprintf(stderr, "Unexpected solution count for size %zu: got %" PRIu64 ", expected %" PRIu64 ".\n",
                size, solutions, expected);
        goto cleanup;
    }

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    const mtpndd_stats_t *stats = mtpndd_get_stats();
    print_run_stats(stats);

    mtpndd_memory_pool_stats_t pool_stats = {0};
    mtpndd_memory_pools_snapshot(&pool_stats);

    printf(".. mem: node slabs=%zu in_use=%zu slabCap=%zu | edge_entry slabs=%zu in_use=%zu\n",
           pool_stats.node_slabs, pool_stats.node_in_use, pool_stats.node_capacity_per_slab,
           pool_stats.edge_entry_slabs, pool_stats.edge_entry_in_use);
#endif

    if (metrics) {
        metrics->size = size;
        metrics->expected = expected;
        metrics->solutions = solutions;
        metrics->seconds = elapsed;
        metrics->mtpndd_nodes = __atomic_load_n(&g_mtpndd_node_count, __ATOMIC_RELAXED);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        const mtpndd_stats_t *stats = mtpndd_get_stats();
        if (stats) {
            metrics->mtpndd_nodes_created = stats->nodes_created_total;
            metrics->mtpndd_nodes_reused = stats->nodes_reused_total;
            metrics->mtpndd_nodes_collected = stats->nodes_collected_last;
            metrics->mtpndd_max_edges = stats->max_edges_per_node;
            metrics->cache_hits = stats->cache_lookup_hits;
            metrics->cache_misses = stats->cache_lookup_misses;
        } else {
            metrics->mtpndd_nodes_created = 0;
            metrics->mtpndd_nodes_reused = 0;
            metrics->mtpndd_nodes_collected = 0;
            metrics->mtpndd_max_edges = 0;
            metrics->cache_hits = 0;
            metrics->cache_misses = 0;
        }
#endif
    }

    ok = true;

    elapsed = timespec_diff_seconds(&run_start, &init_finish);
    printf("init %8.3f\n", elapsed);
    elapsed = timespec_diff_seconds(&init_finish, &ctx_finish);
    printf("ctx  %8.3f\n", elapsed);
    elapsed = timespec_diff_seconds(&ctx_finish, &run_finish);
    printf("run  %8.3f\n", elapsed);

cleanup:
    if (formula) {
        mtpndd_deref(formula);
        formula = NULL;
    }
    nqueens_ctx_destroy(&ctx);

#if LACE_COUNT_EVENTS
    // Report counters before mtpndd_quit(), which shuts down Lace workers.
    if (ok) {
        fprintf(stdout, "== lace counters ==\n");
        lace_count_report_file(stdout);
        fflush(stdout);
    }
#endif

    if (mtpndd_quit() != MTPNDD_SUCCESS) {
        fprintf(stderr, "mtpndd_quit reported an error for size %zu.\n", size);
        ok = false;
    }
    return ok;
}

int main(int argc, char **argv) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
#endif

    if (argc != 2) {
        fprintf(stderr, "Usage: %s <n>\n", argv[0]);
        return EXIT_FAILURE;
    }

    char *endptr = NULL;
    long parsed = strtol(argv[1], &endptr, 10);
    if (*argv[1] == '\0' || (endptr && *endptr != '\0') || parsed <= 0) {
        fprintf(stderr, "Invalid n value: %s\n", argv[1]);
        return EXIT_FAILURE;
    }
    size_t n = (size_t)parsed;

    nqueens_metrics_t metrics = {0};
    if (!run_case(n, &metrics)) {
        return EXIT_FAILURE;
    }

    printf("== result n=%zu -> solutions=%" PRIu64 ", time=%.3f s\n",
           metrics.size,
           metrics.solutions,
           metrics.seconds);
    fflush(stdout);

    printf("N-Queens results (n = %zu)\n", n);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    printf(" n  solutions  expected   time(s)  nodes(created/reused/collected)  maxEdges  cache(h/m)\n");
#else
    printf(" n  solutions  expected   time(s)  MTPNDD(nodes)\n");
#endif
    const nqueens_metrics_t *m = &metrics;
    char expected_buf[32];
    if (m->expected == UINT64_MAX) {
        expected_buf[0] = '-';
        expected_buf[1] = '\0';
    } else {
        snprintf(expected_buf, sizeof(expected_buf), "%" PRIu64, m->expected);
    }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    printf("%2zu %10" PRIu64 " %10s %8.3f  %10" PRIu64 " (%" PRIu64 "/%" PRIu64 "/%" PRIu64 ")  %7" PRIu64
           "  %10" PRIu64 "/%-10" PRIu64 "\n",
           m->size,
           m->solutions,
           expected_buf,
           m->seconds,
           m->mtpndd_nodes,
           m->mtpndd_nodes_created,
           m->mtpndd_nodes_reused,
           m->mtpndd_nodes_collected,
           m->mtpndd_max_edges,
           m->cache_hits,
           m->cache_misses);
#else
    printf("%2zu %10" PRIu64 " %10s %8.3f  %10" PRIu64 "\n",
           m->size,
           m->solutions,
           expected_buf,
           m->seconds,
           m->mtpndd_nodes);
#endif
    if (m->expected != UINT64_MAX && m->solutions != m->expected) {
        fprintf(stderr, "Mismatch detected for n=%zu.\n", m->size);
        return EXIT_FAILURE;
    }

    return EXIT_SUCCESS;
}

static void nqueens_ctx_destroy(nqueens_ctx_t *ctx) {
    if (!ctx) return;
    if (ctx->field_ids) {
        free(ctx->field_ids);
        ctx->field_ids = NULL;
    }
    ctx->size = 0;
}

static uint64_t expected_solutions(size_t size) {
    switch (size) {
        case 1: return 1;
        case 2:
        case 3: return 0;
        case 4: return 2;
        case 5: return 10;
        case 6: return 4;
        case 7: return 40;
        case 8: return 92;
        case 9: return 352;
        case 10: return 724;
        case 11: return 2680;
        case 12: return 14200;
        default: return UINT64_MAX;
    }
}

static double timespec_diff_seconds(const struct timespec *start, const struct timespec *end) {
    time_t sec_diff = end->tv_sec - start->tv_sec;
    long nsec_diff = end->tv_nsec - start->tv_nsec;
    return (double)sec_diff + (double)nsec_diff / 1e9;
}

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
static void log_formula_progress(const char *phase, size_t current, size_t total) {
    size_t nodes = __atomic_load_n(&g_mtpndd_node_count, __ATOMIC_RELAXED);
    printf("[nqueens] %s %zu/%zu nodes=%zu\n",
           phase ? phase : "phase",
           current, total ? total : 0, nodes);
    fflush(stdout);
}

static void print_run_stats(const mtpndd_stats_t *stats) {
    if (!stats) return;
    printf(".. stats: created=%" PRIu64 ", reused=%" PRIu64 ", collected=%" PRIu64 "\n",
           stats->nodes_created_total, stats->nodes_reused_total, stats->nodes_collected_last);
    printf(".. stats: max_edges_per_node=%" PRIu64 ", cache hits/misses=%" PRIu64 "/%" PRIu64 "\n",
           stats->max_edges_per_node, stats->cache_lookup_hits, stats->cache_lookup_misses);
    printf(".. stats: time and/or/not = %.3f/%.3f/%.3f s\n",
           stats->and_time_ns / 1e9, stats->or_time_ns / 1e9, stats->not_time_ns / 1e9);
    printf(".. stats: and spawns total/same/diff=%" PRIu64 "/%" PRIu64 "/%" PRIu64 " pending_flushes=%" PRIu64 "\n",
           stats->and_spawn_total,
           stats->and_spawn_same_total,
           stats->and_spawn_diff_total,
           stats->and_pending_flush_total);
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
    printf(".. stats: BDD ops in same-field AND: count=%" PRIu64 " false=%" PRIu64 " (%.1f%% pruned)\n",
           stats->and_same_bdd_op_count,
           stats->and_same_bdd_op_false_count,
           stats->and_same_bdd_op_count > 0
               ? 100.0 * stats->and_same_bdd_op_false_count / stats->and_same_bdd_op_count
               : 0.0);
    printf(".. stats: two-phase AND: triggers=%" PRIu64 " total_pairs=%" PRIu64 " surviving=%" PRIu64 " (%.1f%% pruned)\n",
           stats->and_two_phase_trigger_count,
           stats->and_two_phase_total_pairs,
           stats->and_two_phase_surviving,
           stats->and_two_phase_total_pairs > 0
               ? 100.0 * (stats->and_two_phase_total_pairs - stats->and_two_phase_surviving) / stats->and_two_phase_total_pairs
               : 0.0);
    if (stats->and_same_bdd_op_count > 0 && stats->and_same_bdd_op_ns > 0) {
        printf(".. stats: BDD op avg time = %.0f ns (total %.3f s / %" PRIu64 " ops)\n",
               (double)stats->and_same_bdd_op_ns / stats->and_same_bdd_op_count,
               stats->and_same_bdd_op_ns / 1e9,
               stats->and_same_bdd_op_count);
    }
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
    double avg_steps = nodetable_lookup_total > 0
        ? (double)stats->nodetable_edge_compare_steps_total / (double)nodetable_lookup_total
        : 0.0;
    printf(".. stats: nodetable lookup hits/misses=%" PRIu64 "/%" PRIu64 " edge_entries=%" PRIu64 " avg_steps=%.2f max_steps=%" PRIu64 "\n",
           stats->nodetable_lookup_hits,
           stats->nodetable_lookup_misses,
           stats->nodetable_edge_compare_entries,
           avg_steps,
           stats->nodetable_edge_compare_max_steps);
}

#endif  // MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG

#ifdef MTPNDD_NQUEENS_ENABLE_DOT

static const char *DOT_OUTPUT_DIR = "build/nqueens_dots";

static void ensure_dot_dir(void) {
    static bool created = false;
    if (created) return;
    if (mkdir(DOT_OUTPUT_DIR, 0777) != 0) {
        // Directory may already exist; ignore errors.
    }
    created = true;
}

static void dump_dot_file(const char *filename, mtpndd_t *node) {
    if (!filename || !node) return;
    ensure_dot_dir();
    char path[512];
    snprintf(path, sizeof(path), "%s/%s", DOT_OUTPUT_DIR, filename);
    mtpndd_print_dot(node, path);
}

#endif  // MTPNDD_NQUEENS_ENABLE_DOT
