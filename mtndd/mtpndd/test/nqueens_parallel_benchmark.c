// nqueens_parallel_benchmark.c
// N-Queens benchmark with outer-level operation parallelism.
//
// Compared to nqueens_benchmark.c (which builds sub-problems sequentially and
// relies solely on intra-operation Lace parallelism), this benchmark exposes
// all n row-OR terms and all n*n cell implication terms as independent Lace
// tasks that are spawned concurrently.  The final combination uses
// mtpndd_and_reduce for a parallel tree-reduction.
//
// Usage: <binary> <n> [workers]
// Output (tab-separated, same as nqueens_benchmark): \t<elapsed_s>\t<solutions>

#include "mtpndd.h"
#include "mtpndd_common.h"
#include <sylvan.h>
#include "sylvan_stats.h"
#include <lace.h>

#include <inttypes.h>
#include <limits.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static inline double timespec_diff_seconds(const struct timespec *start, const struct timespec *end) {
    return (double)(end->tv_sec - start->tv_sec) + (double)(end->tv_nsec - start->tv_nsec) / 1e9;
}

static inline size_t next_pow2(size_t v) {
    if (v <= 1) return 1;
    v--;
    v |= v >> 1; v |= v >> 2; v |= v >> 4; v |= v >> 8; v |= v >> 16;
#if ULONG_MAX > 0xffffffff
    v |= v >> 32;
#endif
    return ++v;
}

// Returns and(a, b) with +1 ref on the result and -1 ref on a.
// Returns NULL without touching refs on failure.
static inline mtpndd_t *mtpndd_and_to(mtpndd_t *a, mtpndd_t *b) {
    mtpndd_t *res = mtpndd_and(a, b);
    if (!res) return NULL;
    mtpndd_ref(res);
    mtpndd_deref(a);
    return res;
}

// Returns or(a, b) with +1 ref on the result and -1 ref on a.
// Returns NULL without touching refs on failure.
static inline mtpndd_t *mtpndd_or_to(mtpndd_t *a, mtpndd_t *b) {
    mtpndd_t *res = mtpndd_or(a, b);
    if (!res) return NULL;
    mtpndd_ref(res);
    mtpndd_deref(a);
    return res;
}

// guard → conseq  ≡  ¬guard ∨ conseq.  Returns +1 ref or NULL.
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
    return imp;  /* +1 ref if non-NULL */
}

// Build all implications for a queen at (row, col).  Returns +1 ref or NULL.
// Identical logic to nqueens_benchmark.c::build_cell.
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

    /* same column */
    for (size_t l = 0; l < n; ++l) {
        if (l == col) continue;
        mtpndd_t *mp = make_implication(guard, mtpndd_get_not_var((uint32_t)(row + 1), (uint32_t)l));
        if (!mp) goto error;
        mtpndd_t *next = mtpndd_and_to(a, mp);
        mtpndd_deref(mp);
        if (!next) goto error;
        a = next;
    }

    /* same row */
    for (size_t k = 0; k < n; ++k) {
        if (k == row) continue;
        mtpndd_t *mp = make_implication(guard, mtpndd_get_not_var((uint32_t)(k + 1), (uint32_t)col));
        if (!mp) goto error;
        mtpndd_t *next = mtpndd_and_to(b, mp);
        mtpndd_deref(mp);
        if (!next) goto error;
        b = next;
    }

    /* up-right diagonal */
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

    /* down-right diagonal */
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

    {
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
    }

    return a;  /* +1 ref */

error:
    mtpndd_deref(a);
    mtpndd_deref(b);
    mtpndd_deref(c);
    mtpndd_deref(d);
    return NULL;
}

static bool declare_fields(size_t n) {
    for (size_t i = 0; i < n; ++i) {
        if (mtpndd_declare_field((uint32_t)n) != MTPNDD_SUCCESS) return false;
    }
    return mtpndd_generate_fields() == MTPNDD_SUCCESS;
}

/*
 * Lace task: compute OR of all n column vars for the given row.
 *
 * Returns a pointer with +1 ref, or NULL on error.
 * Safe to call from within a Lace worker: mtpndd_or() expands to
 * RUN() = LACE_ME + CALL() which works correctly from worker context.
 */
TASK_DECL_2(mtpndd_t *, par_build_row_or, size_t, size_t)
TASK_IMPL_2(mtpndd_t *, par_build_row_or, size_t, row, size_t, n)
{
    mtpndd_t *cond = &MTPNDD_FALSE;
    mtpndd_ref(cond);
    for (size_t j = 0; j < n; ++j) {
        mtpndd_t *v = mtpndd_get_var((uint32_t)(row + 1), (uint32_t)j);
        mtpndd_t *next = mtpndd_or(cond, v);
        if (!next) {
            mtpndd_deref(cond);
            return NULL;
        }
        mtpndd_ref(next);
        mtpndd_deref(cond);
        cond = next;
    }
    return cond;  /* +1 ref */
}

/*
 * Lace task: compute the implication formula for a queen at (row, col).
 *
 * Returns a pointer with +1 ref, or NULL on error.
 */
TASK_DECL_3(mtpndd_t *, par_build_cell, size_t, size_t, size_t)
TASK_IMPL_3(mtpndd_t *, par_build_cell, size_t, row, size_t, col, size_t, n)
{
    return build_cell(row, col, n);  /* +1 ref */
}

/* ------------------------------------------------------------------ */

static size_t g_n_workers = 0;

/*
 * Lace 1.6.x requires that all code invoking Sylvan/MTPNDD TASKs
 * runs inside a Lace worker context. See nqueens_benchmark.c for the
 * same pattern. The main thread does setup (lace_start + sylvan_init
 * + mtpndd_init) then enters worker context once via RUN(bench_work),
 * and tears down after.
 */
VOID_TASK_DECL_3(par_bench_work, size_t, bool*, struct timespec*)

static bool run_parallel_benchmark(size_t n) {

    /*
     * BDD table sizing for parallel build.
     *
     * The serial benchmark formula is tuned for sequential cell building where
     * BDD intermediate nodes are GC'd between cells.  In the parallel build,
     * all n*n cells are alive simultaneously so we floor at 128K to prevent
     * overflow on small N while keeping the same formula for large N (≥10)
     * where the serial formula already gives several megabytes.
     */
    size_t bdd_size = 1 + (size_t)fmax(131072.0, pow(4.4, (double)n - 6.0) * 1000.0);
    size_t bdd_cache = 320000;
    size_t ndd_size = 100000000;
    bdd_size = next_pow2(bdd_size);
    bdd_cache = next_pow2(bdd_cache);
    ndd_size = next_pow2(ndd_size);

    /* Pre-allocate nodetable buckets to lower the initial load factor and
     * reduce avg lookup chain length (~30% faster lookup for N>=11).
     * Only applied when bdd_size >= 1M: below that the init cost > savings. */
    /* Pre-allocate nodetable buckets at 1/4 the BDD table size.
     * Cap at 8M (library hard limit `1<<23`). Only for bdd_size >= 1M. */
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
        .edge_bucket_count = 0,  /* library default (8); settable via cfg */
        .nodetable_bucket_count = nodetable_init_buckets,
        .node_slab_capacity = 0,
        .edge_entry_slab_capacity = 0,
        .nodetable_entry_slab_capacity = 0,
        .edge_map_slab_capacity = 0,
    };

    if (mtpndd_init(&cfg) != MTPNDD_SUCCESS) {
        fprintf(stderr, "mtpndd_init failed: %s\n",
                mtpndd_error_string(mtpndd_get_last_error().code));
        return false;
    }

    /* Optionally set BDD spawn depth cutoff via environment variable.
     * BDD_SPAWN_DEPTH_CUTOFF=0 disables all BDD-internal spawning. */
    {
        const char *cutoff_env = getenv("BDD_SPAWN_DEPTH_CUTOFF");
        if (cutoff_env) {
            int cutoff = atoi(cutoff_env);
            sylvan_set_spawn_depth_cutoff(cutoff);
            fprintf(stderr, "[bench] BDD spawn depth cutoff = %d\n", cutoff);
        }
    }

    struct timespec start_ts = {0};
    clock_gettime(CLOCK_MONOTONIC, &start_ts);

    bool ok = false;
    RUN(par_bench_work, n, &ok, &start_ts);
    mtpndd_quit();
    return ok;
}

VOID_TASK_IMPL_3(par_bench_work, size_t, n, bool*, ok_out, struct timespec*, start_ts)
{
    struct timespec end_ts = {0};
    mtpndd_t **or_batch = NULL;
    mtpndd_t **imp_batch = NULL;
    size_t total_cells = n * n;

    if (!declare_fields(n)) {
        fprintf(stderr, "declare_fields failed\n");
        *ok_out = false;
        return;
    }

    or_batch = (mtpndd_t **)calloc(n, sizeof(mtpndd_t *));
    imp_batch = (mtpndd_t **)calloc(total_cells, sizeof(mtpndd_t *));
    if (!or_batch || !imp_batch) {
        fprintf(stderr, "allocation failed\n");
        free(or_batch);
        free(imp_batch);
        *ok_out = false;
        return;
    }

    /* We are inside a TASK (RUN'd from run_parallel_benchmark), so
     * __lace_worker / __lace_dq_head are already in scope and
     * SPAWN / SYNC / CALL work directly. Lace 1.6 removed LACE_ME. */

    /* ---- Phase 1: row-OR terms in parallel ---- */
    if (n == 1) {
        or_batch[0] = CALL(par_build_row_or, (size_t)0, n);
    } else {
        for (size_t i = 0; i < n - 1; i++) {
            SPAWN(par_build_row_or, i, n);
        }
        or_batch[n - 1] = CALL(par_build_row_or, n - 1, n);
        /* SYNC in LIFO order (reverse of SPAWN). */
        for (int i = (int)n - 2; i >= 0; i--) {
            or_batch[i] = SYNC(par_build_row_or);
        }
    }
    for (size_t i = 0; i < n; i++) {
        if (!or_batch[i]) goto build_fail;
    }

    /* ---- Phase 2: n*n cell implication terms in parallel ---- */
    if (total_cells == 1) {
        imp_batch[0] = CALL(par_build_cell, (size_t)0, (size_t)0, n);
    } else {
        for (size_t k = 0; k < total_cells - 1; k++) {
            SPAWN(par_build_cell, k / n, k % n, n);
        }
        imp_batch[total_cells - 1] = CALL(par_build_cell, n - 1, n - 1, n);
        for (int k = (int)total_cells - 2; k >= 0; k--) {
            imp_batch[k] = SYNC(par_build_cell);
        }
    }
    for (size_t k = 0; k < total_cells; k++) {
        if (!imp_batch[k]) goto build_fail;
    }

    /* ---- Phase 3: sequential left-fold AND ---- */
    {
        mtpndd_t *queen = &MTPNDD_TRUE;
        mtpndd_ref(queen);

        for (size_t i = 0; i < n; i++) {
            mtpndd_t *next = mtpndd_and(queen, or_batch[i]);
            if (next) mtpndd_ref(next);
            mtpndd_deref(queen);
            mtpndd_deref(or_batch[i]);
            or_batch[i] = NULL;
            if (!next) goto build_fail;
            queen = next;
        }
        for (size_t k = 0; k < total_cells; k++) {
            mtpndd_t *next = mtpndd_and(queen, imp_batch[k]);
            if (next) mtpndd_ref(next);
            mtpndd_deref(queen);
            mtpndd_deref(imp_batch[k]);
            imp_batch[k] = NULL;
            if (!next) goto build_fail;
            queen = next;
        }

        free(or_batch);
        free(imp_batch);
        or_batch = NULL;
        imp_batch = NULL;

        double satcount_val = mtpndd_satcount(queen);
        uint64_t solutions = (uint64_t)llround(satcount_val);
        mtpndd_deref(queen);

        clock_gettime(CLOCK_MONOTONIC, &end_ts);
        double elapsed = timespec_diff_seconds(start_ts, &end_ts);

        printf("\t%.3f\t%" PRIu64 "\n", elapsed, solutions);
        fflush(stdout);

        sylvan_stats_report(stdout);
        (void)start_ts;  /* for older compilers; start_ts is read via *start_ts */
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
        {
            mtpndd_stats_t *stats = &g_mtpndd_stats;
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
        }
#endif
        *ok_out = true;
        return;
    }

build_fail:
    for (size_t i = 0; i < n; i++) {
        if (or_batch && or_batch[i]) mtpndd_deref(or_batch[i]);
    }
    for (size_t k = 0; k < total_cells; k++) {
        if (imp_batch && imp_batch[k]) mtpndd_deref(imp_batch[k]);
    }
    free(or_batch);
    free(imp_batch);
    *ok_out = false;
    return;
}

int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "Usage: %s <n> [workers]\n", argv[0]);
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

    if (!run_parallel_benchmark(n)) {
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
