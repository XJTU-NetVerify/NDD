# refs_init_size tuning (N=12)

## Goal
Test whether increasing the initial refs table size reduces refcount contention (retries/avg_time) and improves wall time.

## Method
Build with `SYLVAN_REFS_STATS=ON`, `MTPNDD_LOG_LEVEL=1`, `MTPNDD_NQUEENS_ENABLE_DOT=OFF` and varying `SYLVAN_REFS_INIT_SIZE`.
- Sizes tested: 1024 (baseline), 4096, 16384.
- Workload: `mtpndd_nqueens_test 12` with `taskset -c 0..(workers-1)` for workers=1..4.
- Note: SYLVAN_REFS_STATS adds overhead; wall times are for **relative comparison** only.

## Results
| refs_init_size | workers | wall_ms | refs_avg_time_ns | retries_per_million | avg_probes |
|---:|---:|---:|---:|---:|---:|
| 1024 | 1 | 14028 | 56.5 | 0.0 | 1.091 |
| 1024 | 2 | 11416 | 117.6 | 623.7 | 1.099 |
| 1024 | 3 | 10554 | 187.8 | 1346.5 | 1.077 |
| 1024 | 4 | 10366 | 251.7 | 2490.6 | 1.186 |
| 4096 | 1 | 14030 | 56.6 | 0.0 | 1.090 |
| 4096 | 2 | 11593 | 123.3 | 599.6 | 1.093 |
| 4096 | 3 | 10518 | 180.2 | 1520.3 | 1.077 |
| 4096 | 4 | 10284 | 244.5 | 2200.1 | 1.095 |
| 16384 | 1 | 13956 | 55.6 | 0.0 | 1.024 |
| 16384 | 2 | 11392 | 118.8 | 662.3 | 1.027 |
| 16384 | 3 | 10652 | 181.5 | 1479.0 | 1.026 |
| 16384 | 4 | 10279 | 249.0 | 2046.7 | 1.035 |

## Interpretation
- Larger refs tables reduce avg_probes and retries slightly at 4 workers, but the improvement is modest.
- Wall time differences are small under SYLVAN_REFS_STATS overhead; no clear win at 2–3 workers.

## Next
- If we want a clearer signal, repeat with SYLVAN_REFS_STATS off and only measure wall time + perf c2c for ref hot lines, or test a much larger size (e.g. 65536).
