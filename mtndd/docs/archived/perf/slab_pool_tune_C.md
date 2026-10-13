# Slab Pool Tune C (Worker-Scaled Parameters)

## Goal
Test scaling edge_map/edge_entry cache sizes with worker count to reduce contention without excessive working-set growth.

## Code Change
File: `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c`
- Added worker-scaled parameters for edge_map/edge_entry:
  - `refill_batch = min(64 * workers, 256)`
  - `local_max   = min(512 * workers, 2048)`
- node + nodetable_entry keep defaults 32/256.

## Method
- Build with `MTPNDD_LOG_LEVEL=1` and `MTPNDD_NQUEENS_ENABLE_DOT=OFF`.
- Run `mtpndd_nqueens_test 12` with taskset for 1-4 workers.

Commands (per worker):
```
/taskset -c <cpu-list> ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```

## Results (program output)
Baseline is the previously recorded N=12 runtime before Tune C.

| workers | baseline(s) | tune C(s) | speedup (x) |
|---:|---:|---:|---:|
| 1 | 12.128 | 11.742 | 1.03x |
| 2 | 9.359 | 8.976 | 1.04x |
| 3 | 8.455 | 7.845 | 1.08x |
| 4 | 8.308 | 7.943 | 1.05x |

All runs produced correct solutions=14200.

## Interpretation
- Tune C improves 1-4 worker runtimes modestly, with the largest gain at 3 workers (~7.8%).
- Scaling edge pools with worker count avoids the regression seen in Tune A while providing slightly more benefit than Tune B at 3 workers.
- If stability holds across repeats, Tune C can replace Tune B as the preferred configuration.

## Next Steps
- Compare against Tune B across multiple runs to confirm consistency.
- If variability is high, pick the safer configuration (Tune B).
