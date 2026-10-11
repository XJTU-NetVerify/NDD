#include "pndd.h"
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
static double clock_s(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
int main(int argc, char **argv) {
    unsigned workers = argc > 1 ? (unsigned)atoi(argv[1]) : 1;
    unsigned count = argc > 2 ? (unsigned)atoi(argv[2]) : 10000, batch = argc > 3 ? (unsigned)atoi(argv[3]) : 100;
    if (!count || !batch || count % batch) return 2;
    pndd_config c = pndd_default_config(); c.workers = workers; c.gc_interval = 0; c.batch_parallel = 0;
    pndd_field fields[] = {{2, PNDD_BDD}, {2, PNDD_BDD}};
    pndd_engine *e = pndd_open(&c, fields, 2); if (!e) return 1;
    pndd_handle a = pndd_value(e, 0, 1), b = pndd_value(e, 1, 2), warm = pndd_apply(e, PNDD_AND, a, b);
    if (pndd_satcount(e, warm) != 1) return 1; pndd_release(e, warm);
    for (unsigned i = 0; i < 2000; ++i) { pndd_handle h = pndd_apply(e, PNDD_AND, a, b); pndd_release(e, h); }
    double start = clock_s();
    for (unsigned i = 0; i < count; ++i) { pndd_handle h = pndd_apply(e, PNDD_AND, a, b); if (h == PNDD_INVALID) return 1; pndd_release(e, h); }
    double scalar = clock_s() - start;
    pndd_handle *left = malloc(batch * sizeof(*left)), *right = malloc(batch * sizeof(*right)), *out = malloc(batch * sizeof(*out));
    if (!left || !right || !out) return 1;
    for (unsigned i = 0; i < batch; ++i) { left[i] = a; right[i] = b; }
    start = clock_s();
    for (unsigned i = 0; i < count / batch; ++i) {
        if (!pndd_apply_batch(e, PNDD_AND, left, right, batch, out)) return 1;
        for (unsigned j = 0; j < batch; ++j) pndd_release(e, out[j]);
    }
    double batched = clock_s() - start;
    printf("{\"engine\":\"c\",\"workers\":%u,\"operations\":%u,\"batch_size\":%u,\"scalar_s\":%.9f,\"batch_s\":%.9f}\n", workers, count, batch, scalar, batched);
    free(left); free(right); free(out); pndd_release(e, a); pndd_release(e, b); pndd_close(e); return 0;
}
