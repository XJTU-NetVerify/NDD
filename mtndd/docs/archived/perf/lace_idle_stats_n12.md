# Lace idle/backoff stats (N=12)

## Method
- Build flags: `LACE_IDLE_STATS=1`, `LACE_COUNT_STEALS=1`, `MTPNDD_LOG_LEVEL=1`, `SYLVAN_REFS_STATS=OFF`, `MTPNDD_NQUEENS_ENABLE_DOT=OFF`.
- Sample rate: 1/1024 (CLOCK_MONOTONIC) on each `LACE_NOWORK` backoff.
- Workload: `mtpndd_nqueens_test 12` with `taskset -c 0..(workers-1)`.
- Outputs captured from `/tmp/lace_idle_w{1..4}.out` (stderr) and `/tmp/lace_idle_w{1..4}.stdout` (time).

## Summary (sum across workers)
| workers | idle_steal_nowork | idle_steal_est_ms | idle_leap_nowork | idle_leap_est_ms | wall_time_ms | idle_total_ms | idle_total/wall | idle_total/(wall*workers) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 0 | 0.00 | 0 | 0.00 | 11661 | 0.00 | 0.00 | 0.00 |
| 2 | 689069 | 1691.64 | 161941 | 61.54 | 8912 | 1753.18 | 0.20 | 0.10 |
| 3 | 1309155 | 4317.49 | 242333 | 291.95 | 7807 | 4609.44 | 0.59 | 0.20 |
| 4 | 1772825 | 7535.16 | 292993 | 171.07 | 7812 | 7706.23 | 0.99 | 0.25 |

## Nowork ratios (sum across workers, derived from LACE_COUNT_STEALS)
| workers | steal_nowork | steal_tries | steal_nowork_rate | leap_nowork | leap_tries | leap_nowork_rate |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 0 | 0 | 0.0000 | 0 | 0 | 0.0000 |
| 2 | 693933 | 696474 | 0.9964 | 161941 | 162497 | 0.9966 |
| 3 | 1316381 | 1321658 | 0.9960 | 242333 | 243321 | 0.9959 |
| 4 | 1781504 | 1789472 | 0.9955 | 292993 | 293898 | 0.9969 |

## Rates (sum across workers, per wall-second)
| workers | steal_nowork_per_s | leap_nowork_per_s | idle_total_ms_per_s |
|---:|---:|---:|---:|
| 1 | 0.0 | 0.0 | 0.0 |
| 2 | 77865.0 | 18171.1 | 196.7 |
| 3 | 168615.5 | 31040.5 | 590.4 |
| 4 | 228047.1 | 37505.5 | 986.5 |

## Task/steal effectiveness (sum across workers)
| workers | tasks | success_steal+leap | tries_total | nowork_total | nowork_rate | success_rate | tasks_per_try | tasks_per_steal |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 98560 | 0 | 0 | 0 | 0.0000 | 0.0000 | 0.000000 | n/a |
| 2 | 101147 | 2917 | 857825 | 854823 | 0.9965 | 0.0034 | 0.117911 | 34 |
| 3 | 100989 | 6104 | 1580884 | 1574111 | 0.9957 | 0.0039 | 0.063881 | 16 |
| 4 | 100827 | 9106 | 2237525 | 2227641 | 0.9956 | 0.0041 | 0.045062 | 11 |

## Combined indicators (cross-run context)
For paper figures, see `docs/archived/perf/plot_data.md` → “combined indicators (N=12, separate runs)”.

## Per-worker idle counts (steal loop)
| workers | w0 | w1 | w2 | w3 |
|---:|---:|---:|---:|---:|
| 2 | 0 | 689069 | - | - |
| 3 | 0 | 644284 | 664871 | - |
| 4 | 0 | 619221 | 587945 | 565659 |

## Notes
- `idle_steal_est_ms` and `idle_leap_est_ms` are **estimated** by scaling sampled time by the sample rate (1/1024).
- `idle_total/wall` uses sum idle time across all workers divided by wall time; values can approach 1 even when overall CPU utilization is high.
