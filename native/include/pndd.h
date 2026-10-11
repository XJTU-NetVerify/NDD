#ifndef PNDD_H
#define PNDD_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct pndd_engine pndd_engine;
typedef uint64_t pndd_handle;
#define PNDD_INVALID UINT64_MAX
typedef enum { PNDD_BDD, PNDD_BCDD, PNDD_ZDD, PNDD_BITSET } pndd_backend;
typedef enum { PNDD_AND, PNDD_OR, PNDD_DIFF, PNDD_XOR, PNDD_IMP,
               PNDD_EQUIV, PNDD_NAND, PNDD_NOR } pndd_op;
typedef struct { uint32_t width; pndd_backend backend; } pndd_field;
typedef struct {
    uint32_t workers, task_threshold, chunk_size, spawn_depth;
    int ndd_parallel, label_parallel, batch_parallel;
    size_t memory_bytes, cache_entries;
    uint32_t gc_interval;
} pndd_config;
typedef struct {
    uint64_t nodes_live, nodes_created, labels_live, labels_created;
    uint64_t tasks, stolen_tasks, cache_hits, cache_misses, collections;
    uint64_t external_runs, merge_ns, native_bytes, peak_native_bytes;
    uint64_t label_collections, build_ns, reduce_ns, count_ns;
} pndd_stats;
pndd_config pndd_default_config(void);
/* One active engine per process. All public calls are serialized by admission. */
pndd_engine *pndd_open(const pndd_config *, const pndd_field *, size_t);
void pndd_close(pndd_engine *);
const char *pndd_error(pndd_engine *);
/* Results own one reference; inputs are borrowed. INVALID denotes an error. */
pndd_handle pndd_constant(pndd_engine *, int);
pndd_handle pndd_literal(pndd_engine *, uint32_t field, uint32_t bit, int positive);
pndd_handle pndd_value(pndd_engine *, uint32_t field, uint64_t value);
pndd_handle pndd_apply(pndd_engine *, pndd_op, pndd_handle, pndd_handle);
pndd_handle pndd_not(pndd_engine *, pndd_handle);
pndd_handle pndd_exist(pndd_engine *, pndd_handle, uint32_t field);
pndd_handle pndd_retain(pndd_engine *, pndd_handle);
int pndd_release(pndd_engine *, pndd_handle);
double pndd_satcount(pndd_engine *, pndd_handle);
int pndd_evaluate(pndd_engine *, pndd_handle, const uint64_t *, size_t);
int pndd_apply_batch(pndd_engine *, pndd_op, const pndd_handle *,
                     const pndd_handle *, size_t, pndd_handle *);
pndd_handle pndd_reduce(pndd_engine *, pndd_op, const pndd_handle *, size_t, int tree);
int pndd_collect(pndd_engine *);
pndd_stats pndd_get_stats(pndd_engine *);
/* Benchmark-only entry: identical solver without per-operation external RUN. */
double pndd_nqueens(pndd_engine *, uint32_t n, int compact, int tree);
#ifdef __cplusplus
}
#endif
#endif
