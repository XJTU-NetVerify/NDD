# Lace backoff tuning A2 (N=12)

## Goal
Try a middle ground between baseline and Tune A to reduce NOWORK overhead without regressing 2–3 worker wall time.

## Method
Two builds with identical instrumentation enabled:
- `LACE_IDLE_STATS=1`, `LACE_COUNT_STEALS=1`, `LACE_COUNT_TASKS=1`, `MTPNDD_LOG_LEVEL=1`, `SYLVAN_REFS_STATS=OFF`.
- Workload: `mtpndd_nqueens_test 12` with `taskset -c 0..(workers-1)` for workers=1..4.

### Baseline (current defaults)
```
LACE_STEAL_BACKOFF_YIELD_ITERS=256
LACE_STEAL_BACKOFF_SLEEP_ITERS=2048
LACE_STEAL_BACKOFF_SLEEP_NS=50000
```

### Tune A2 (moderate backoff)
```
LACE_STEAL_BACKOFF_YIELD_ITERS=128
LACE_STEAL_BACKOFF_SLEEP_ITERS=1024
LACE_STEAL_BACKOFF_SLEEP_NS=20000
```

## Results (sum across workers)
| workers | wall_ms (base) | wall_ms (A2) | idle_ms_per_s (base) | idle_ms_per_s (A2) | nowork_rate (base) | nowork_rate (A2) | tasks_per_try (base) | tasks_per_try (A2) | tasks_per_steal (base) | tasks_per_steal (A2) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 11559 | 11523 | 0.0 | 0.0 | 0.0000 | 0.0000 | 0.000000 | 0.000000 | n/a | n/a |
| 2 | 8711 | 8612 | 182.5 | 201.9 | 0.9962 | 0.9939 | 0.1159 | 0.1915 | 31 | 32 |
| 3 | 7811 | 7955 | 566.0 | 574.7 | 0.9960 | 0.9940 | 0.0647 | 0.1150 | 17 | 20 |
| 4 | 8048 | 7909 | 1004.4 | 962.7 | 0.9959 | 0.9941 | 0.0458 | 0.0829 | 12 | 15 |

## Deltas (A2 vs baseline)
- Workers=2: wall -1.14%, idle_ms_per_s +10.65%, nowork_rate -0.0023, tasks_per_try +65.3%
- Workers=3: wall +1.84%, idle_ms_per_s +1.54%, nowork_rate -0.0019, tasks_per_try +77.8%
- Workers=4: wall -1.73%, idle_ms_per_s -4.16%, nowork_rate -0.0017, tasks_per_try +80.9%

## Interpretation
- Tune A2 improves tasks_per_try and reduces nowork_rate, but still shows a 3-worker wall-time regression.
- Compared with Tune A, A2 is less aggressive and yields smaller improvements in tasks_per_try.

## Next
- If 3-worker regression is unacceptable, consider a small grid around A2 (e.g., YIELD_ITERS=96 or 160) or adjust only `SLEEP_NS` while keeping YIELD/SLEEP iters fixed.
