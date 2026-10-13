# Slab Pool Tune B (Per-Pool Static Parameters)

## Goal
Test per-pool cache sizing to reduce contention in the hottest pools without inflating global working set.

## Code Change
File: `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c`
- Added per-pool parameters: `refill_batch`, `local_max` in `mtpndd_slab_pool_t`.
- edge_map + edge_entry: `refill_batch=64`, `local_max=512`.
- node + nodetable_entry: keep defaults `32 / 256`.

## Method
- Build with `MTPNDD_LOG_LEVEL=1` and `MTPNDD_NQUEENS_ENABLE_DOT=OFF`.
- Run `mtpndd_nqueens_test 12` with taskset for 1-4 workers.

Commands (per worker):
```
/taskset -c <cpu-list> ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```

## Results (program output)
Baseline is the previously recorded N=12 runtime before Tune B.

| workers | baseline(s) | tune B(s) | speedup (x) |
|---:|---:|---:|---:|
| 1 | 12.128 | 11.664 | 1.04x |
| 2 | 9.359 | 9.017 | 1.04x |
| 3 | 8.455 | 8.127 | 1.04x |
| 4 | 8.308 | 7.907 | 1.05x |

All runs produced correct solutions=14200.

## Interpretation
- Tune B provides a small but consistent improvement (~4-5%) across 1-4 workers.
- Constraining the larger cache to edge_map/edge_entry avoids the working-set inflation seen in Tune A.
- This suggests edge_map/edge_entry pool contention is a real limiter; targeted cache sizing is preferable to global increases.

## Next Steps
- Compare with Tune C (worker-scaled sizing) to see if gains can be amplified without regressions.
- If Tune C regresses, keep Tune B as the preferred configuration.
