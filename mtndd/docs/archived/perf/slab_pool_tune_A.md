# Slab Pool Tune A (Refill Batch + Local Max)

## Goal
Test whether larger per-worker slab pool refill batches and local cache capacity reduce contention and improve N=12 scaling.

## Code Change
File: `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c`
- `MTPNDD_POOL_REFILL_BATCH`: 32 -> 128
- `MTPNDD_POOL_LOCAL_MAX`: 256 -> 1024

## Method
- Build with DOT disabled to avoid I/O overhead.
- Run `mtpndd_nqueens_test 12` with taskset for 1-4 workers.
- Record timings from program output and shell `time`.

Commands (per worker):
```
/taskset -c <cpu-list> ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```

## Results (program output)
Baseline is the previously recorded N=12 runtime before Tune A.

| workers | baseline(s) | tune A(s) | slowdown (x) |
|---:|---:|---:|---:|
| 1 | 12.128 | 17.841 | 1.47x |
| 2 | 9.359 | 12.942 | 1.38x |
| 3 | 8.455 | 10.846 | 1.28x |
| 4 | 8.308 | 10.172 | 1.22x |

All runs produced correct solutions=14200.

## Interpretation
- Tune A **regressed performance** across 1-4 workers.
- Larger local caches likely increase reuse distance or reduce cross-worker reuse, and/or increase memory footprint and cache pressure.
- Despite higher batch sizes, global lock pressure did not translate into lower wall time for N=12.

## Why It Likely Slowed Down (Hypotheses)
- **Working-set inflation:** Bigger per-worker caches retain more freed objects, which raises cache footprint and evicts hot data. This can reduce IPC and increase cache misses enough to dominate any lock reduction.
- **Cross-worker reuse reduction:** Keeping objects in local caches decreases sharing opportunities and increases the volume of fresh allocations or cacheline ownership transfers.
- **Bottleneck shift:** Evidence shows refcount/cacheline bouncing is a major hotspot; slab pool tuning alone does not address refcount pressure, so the overall critical path barely improves and may degrade.

These are hypotheses based on observed regression; confirm via perf stat (cache-miss/IPC) and pool hit/refill counters if needed.

## Next Steps
- Treat Tune A as a negative result; keep for paper.
- Proceed to Tune B (per-pool tuning) only if a targeted hypothesis exists; otherwise prioritize alternative mitigation (e.g., refcount cacheline reduction).
