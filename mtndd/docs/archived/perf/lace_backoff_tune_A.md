# Lace backoff tuning A (N=12)

## Goal
Reduce futile steal/leap activity and idle backoff overhead by making workers yield/sleep earlier.

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

### Tune A (more aggressive backoff)
```
LACE_STEAL_BACKOFF_YIELD_ITERS=64
LACE_STEAL_BACKOFF_SLEEP_ITERS=512
LACE_STEAL_BACKOFF_SLEEP_NS=10000
```

## Results (sum across workers)
| workers | wall_ms (base) | wall_ms (A) | idle_ms_per_s (base) | idle_ms_per_s (A) | nowork_rate (base) | nowork_rate (A) | tasks_per_try (base) | tasks_per_try (A) | tasks_per_steal (base) | tasks_per_steal (A) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 11559 | 11532 | 0.0 | 0.0 | 0.0000 | 0.0000 | 0.000000 | 0.000000 | n/a | n/a |
| 2 | 8711 | 8803 | 182.5 | 162.5 | 0.9962 | 0.9903 | 0.1159 | 0.3182 | 31 | 33 |
| 3 | 7811 | 7838 | 566.0 | 660.1 | 0.9960 | 0.9912 | 0.0647 | 0.1795 | 17 | 21 |
| 4 | 8048 | 7816 | 1004.4 | 942.8 | 0.9959 | 0.9918 | 0.0458 | 0.1322 | 12 | 17 |

## Deltas (A vs baseline)
- Workers=2: wall +1.06%, idle_ms_per_s -10.95%, nowork_rate -0.0059, tasks_per_try +174.6%
- Workers=3: wall +0.35%, idle_ms_per_s +16.62%, nowork_rate -0.0048, tasks_per_try +177.4%
- Workers=4: wall -2.88%, idle_ms_per_s -6.14%, nowork_rate -0.0041, tasks_per_try +188.6%

## Interpretation
- Tune A **reduces nowork_rate** across 2–4 workers and **substantially increases tasks_per_try** (fewer futile tries per task), especially at higher worker counts.
- Wall time impact is mixed: slight regression at 2–3 workers, but improvement at 4 workers.
- Idle/backoff time per second drops for 2 & 4 workers but rises for 3 workers, suggesting sensitivity to backoff thresholds.

## Next
- If you want a single “best” setting, run 2–3 nearby points (e.g., YIELD_ITERS=128, SLEEP_ITERS=1024, SLEEP_NS=20000) and compare to see if 3-worker regression can be avoided.
