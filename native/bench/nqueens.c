#include "pndd.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
static double seconds(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: %s N BDD|BCDD|ZDD|BITSET|MIXED workers [compact=0] [tree=0] [memoryMiB=4096] [ndd=1] [labels=0] [batch=1] [threshold=4] [chunk=2] [cache=262144]\n", argv[0]); return 2; }
    unsigned n = (unsigned)strtoul(argv[1], NULL, 10), workers = (unsigned)strtoul(argv[3], NULL, 10);
    if (!n || n > 64) return 2;
    const char *names[] = {"BDD", "BCDD", "ZDD", "BITSET"}; int mode = -1, mixed = !strcmp(argv[2], "MIXED");
    for (int i = 0; i < 4; ++i) if (!strcmp(argv[2], names[i])) mode = i;
    if (mode < 0 && !mixed) return 2;
    int compact = argc > 4 ? atoi(argv[4]) : 0, tree = argc > 5 ? atoi(argv[5]) : 0;
    unsigned width = n; if (compact) { width = 1; while ((1u << width) < n) ++width; }
    pndd_field fields[64]; for (unsigned i = 0; i < n; ++i) fields[i] = (pndd_field){width, mixed ? (pndd_backend)(i % 4) : (pndd_backend)mode};
    pndd_config c = pndd_default_config(); c.workers = workers;
    c.memory_bytes = (size_t)(argc > 6 ? strtoull(argv[6], NULL, 10) : 4096) * 1024 * 1024;
    if (argc > 7) c.ndd_parallel = atoi(argv[7]); if (argc > 8) c.label_parallel = atoi(argv[8]); if (argc > 9) c.batch_parallel = atoi(argv[9]);
    if (argc > 10) c.task_threshold = (uint32_t)atoi(argv[10]); if (argc > 11) c.chunk_size = (uint32_t)atoi(argv[11]); if (argc > 12) c.cache_entries = strtoull(argv[12], NULL, 10);
    double start = seconds(); pndd_engine *e = pndd_open(&c, fields, n); double init = seconds() - start;
    if (!e) { fprintf(stderr, "%s\n", pndd_error(NULL)); return 1; }
    start = seconds(); double solutions = pndd_nqueens(e, n, compact, tree); double compute = seconds() - start;
    pndd_stats s = pndd_get_stats(e);
    if (isnan(solutions)) { fprintf(stderr, "%s\n", pndd_error(e)); pndd_close(e); return 1; }
    start = seconds(); pndd_close(e); double cleanup = seconds() - start;
    printf("{\"n\":%u,\"backend\":\"%s\",\"workers\":%u,\"compact\":%d,\"tree\":%d,\"solutions\":%.0f,\"init_s\":%.9f,\"compute_s\":%.9f,\"cleanup_s\":%.9f,\"nodes_live\":%llu,\"nodes_created\":%llu,\"tasks\":%llu,\"stolen_tasks\":%llu,\"cache_hits\":%llu,\"cache_misses\":%llu,\"gc\":%llu,\"external_runs\":%llu,\"merge_ns\":%llu,\"native_bytes\":%llu,\"peak_native_bytes\":%llu,\"label_gc\":%llu,\"build_s\":%.9f,\"reduce_s\":%.9f,\"count_s\":%.9f}\n", n, argv[2], workers, compact, tree, solutions, init, compute, cleanup, (unsigned long long)s.nodes_live, (unsigned long long)s.nodes_created, (unsigned long long)s.tasks, (unsigned long long)s.stolen_tasks, (unsigned long long)s.cache_hits, (unsigned long long)s.cache_misses, (unsigned long long)s.collections, (unsigned long long)s.external_runs, (unsigned long long)s.merge_ns, (unsigned long long)s.native_bytes, (unsigned long long)s.peak_native_bytes, (unsigned long long)s.label_collections, s.build_ns / 1e9, s.reduce_ns / 1e9, s.count_ns / 1e9);
    return 0;
}
