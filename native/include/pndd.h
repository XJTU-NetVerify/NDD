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
    int reverse_labels; /* BDD/BCDD LSB-first compatibility with legacy Java. */
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
/* Ordered priority groups, exclusive ends. Results correspond to [start,count).
 * Each result is budget & input[i], excluding all earlier groups; without
 * PNDD_PRIORITY_MULTIPATH, earlier inputs in the same group are excluded too.
 * PNDD_PRIORITY_INTERSECT_FIRST changes evaluation order, preserving semantics.
 * Inputs are borrowed, outputs are owned; one top-level Lace RUN per batch. */
enum { PNDD_PRIORITY_MULTIPATH = 1, PNDD_PRIORITY_INTERSECT_FIRST = 2 };
int pndd_priority_select(pndd_engine *, pndd_handle budget, const pndd_handle *,
                         size_t count, const uint32_t *ends, size_t groups,
                         size_t start, int flags, pndd_handle *out);
/* Independent complete lists, one admission/RUN. Arrays remain borrowed until
 * return. Output arrays must not overlap. Each out has count-start slots,
 * each receiving one owned reference.
 * Failure returns no owned outputs, including when a later list is invalid. */
typedef struct {
    pndd_handle budget;
    const pndd_handle *input;
    size_t count;
    const uint32_t *ends;
    size_t groups, start;
    int flags;
    pndd_handle *out;
} pndd_priority_request;
int pndd_priority_batch(pndd_engine *, const pndd_priority_request *, size_t);
pndd_handle pndd_reduce(pndd_engine *, pndd_op, const pndd_handle *, size_t, int tree);
int pndd_collect(pndd_engine *);
pndd_stats pndd_get_stats(pndd_engine *);
/* Inspection returns owned (target, field-condition) pairs; free the array and
 * release each handle. Conditions keep backend-specific labels opaque. */
int pndd_snapshot(pndd_engine *, pndd_handle, uint32_t *field,
                  size_t *count, pndd_handle **pairs);
int pndd_reachable(pndd_engine *, const pndd_handle *roots, size_t root_count,
                   const pndd_handle *candidates, size_t count, unsigned char *mask);
int pndd_min_label_cost(pndd_engine *, pndd_handle condition, uint32_t field, int costly_value);
/* Header cubes use -1 for don't-care, 0/1 for a fixed bit, MSB first.
 * Caller frees cubes; enumeration is bounded by limit. */
int pndd_label_cubes(pndd_engine *, pndd_handle condition, uint32_t field,
                     size_t limit, size_t *count, signed char **cubes);
int pndd_fingerprint(pndd_engine *, pndd_handle, uint64_t *digest);
/* Benchmark-only entry: identical solver without per-operation external RUN. */
double pndd_nqueens(pndd_engine *, uint32_t n, int compact, int tree);
#ifdef __cplusplus
}
#endif
#endif
