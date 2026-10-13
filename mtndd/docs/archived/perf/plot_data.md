# Plot-ready data (MTPNDD contention study)

## N-Queens runtime (program output)
### N=8
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 0.094 | 92 |
| 2 | 0.060 | 92 |
| 3 | 0.057 | 92 |
| 4 | 0.057 | 92 |

### N=12
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 12.128 | 14200 |
| 2 | 9.359 | 14200 |
| 3 | 8.455 | 14200 |
| 4 | 8.308 | 14200 |

### N=12 (slab pool tune A: refill=128, local_max=1024)
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 17.841 | 14200 |
| 2 | 12.942 | 14200 |
| 3 | 10.846 | 14200 |
| 4 | 10.172 | 14200 |

### N=12 (slab pool tune B: edge_map/edge_entry=64/512, others=32/256)
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 11.664 | 14200 |
| 2 | 9.017 | 14200 |
| 3 | 8.127 | 14200 |
| 4 | 7.907 | 14200 |

### N=12 (slab pool tune C: edge pools scale with workers)
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 11.742 | 14200 |
| 2 | 8.976 | 14200 |
| 3 | 7.845 | 14200 |
| 4 | 7.943 | 14200 |

### N-Queens benchmark (per-worker refs, signed counts + GC merge)
Source: `mtpndd_nqueens_benchmark <n> <workers>`
#### N=8
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 0.033 | 92 |
| 2 | 0.027 | 92 |
| 3 | 0.023 | 92 |
| 4 | 0.022 | 92 |

#### N=12
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 11.849 | 14200 |
| 2 | 7.523 | 14200 |
| 3 | 5.824 | 14200 |
| 4 | 5.647 | 14200 |

#### N=12 (reboot rerun, 3x mean/sd, workers 1-6)
| workers | mean_time(s) | sd(s) | solutions |
|---:|---:|---:|---:|
| 1 | 11.892 | 0.054 | 14200 |
| 2 | 7.533 | 0.017 | 14200 |
| 3 | 5.864 | 0.012 | 14200 |
| 4 | 5.566 | 0.037 | 14200 |
| 5 | 4.642 | 0.011 | 14200 |
| 6 | 4.595 | 0.019 | 14200 |

### JNI N-Queens (onehot only)
Source: `java ... org.ants.mtpndd.NQueensMTPNDD 12 [workers]`
| workers | time(s) | solutions | note |
|---:|---:|---:|---|
| 1 | 12.991 | 14200 | onehot-only JNI test |
| 6 | 5.443 | 14200 | onehot-only JNI test |

### N=12 reboot rerun (LOG_LEVEL=0, 5 repeats, workers 1-6)
Native source:
- `taskset -c 0..(workers-1) ./sylvan/build-log0/src/sylvan/mtpndd/mtpndd_nqueens_benchmark 12 <workers>`

JNI onehot source:
- `taskset -c 0..(workers-1) java -cp target/test-classes:target/classes -Dorg.ants.mtpndd.library.path=$PWD/build-log0/libmtpnddjni.so org.ants.mtpndd.NQueensMTPNDD 12 <workers>`

| workers | native mean(s) | native std(s) | jni mean(s) | jni std(s) | jni/native |
|---:|---:|---:|---:|---:|---:|
| 1 | 12.041 | 0.008 | 12.759 | 0.030 | 1.060 |
| 2 | 7.634 | 0.034 | 8.516 | 0.377 | 1.116 |
| 3 | 5.953 | 0.023 | 6.611 | 0.176 | 1.111 |
| 4 | 5.607 | 0.037 | 5.878 | 0.075 | 1.048 |
| 5 | 4.742 | 0.032 | 5.392 | 0.044 | 1.137 |
| 6 | 4.665 | 0.023 | 4.951 | 0.047 | 1.061 |

Notes:
- all runs returned `14200`.
- this dataset is intended for low-overhead comparison (`LOG_LEVEL=0`) after reboot + clean rebuild.

### N-Queens benchmark (per-worker protect add/del tables)
Source: `taskset -c 0..(workers-1) mtpndd_nqueens_benchmark 12 <workers>` (5 repeats, mean)
#### N=12
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 12.060 | 14200 |
| 2 | 7.662 | 14200 |
| 3 | 6.017 | 14200 |
| 4 | 5.627 | 14200 |

### N=12 branch-to-branch recheck (5 repeats each, workers 1-6)
Source:
- baseline: `feature/c`
- candidate: `feature/mtbdd-per-worker-protect` (`f9637b7` state + follow-up doc run)

| workers | feature/c mean(s) | feature/c std(s) | per-worker mean(s) | per-worker std(s) | delta(s) | delta(%) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12.219 | 0.029 | 12.236 | 0.116 | +0.017 | +0.14 |
| 2 | 7.732 | 0.043 | 7.675 | 0.046 | -0.057 | -0.74 |
| 3 | 6.007 | 0.048 | 5.991 | 0.024 | -0.016 | -0.27 |
| 4 | 5.693 | 0.053 | 5.606 | 0.024 | -0.087 | -1.53 |
| 5 | 4.802 | 0.033 | 4.718 | 0.020 | -0.084 | -1.75 |
| 6 | 4.861 | 0.053 | 4.761 | 0.147 | -0.100 | -2.06 |

Solutions note:
- `feature/c`: 30/30 runs `14200`.
- `feature/mtbdd-per-worker-protect`: 29/30 runs `14200`, one run at `workers=3` returned `14199`.

### N=12 branch-to-branch recheck (high-load batch, LOG_LEVEL=0, 5 repeats each, workers 1-6)
Environment snapshot during run:
- `/proc/loadavg`: `18.09 17.99 14.05` on `6` CPUs.
- spot-check under same load: `feature/c w=1 = 33.107s`, `worktree w=1 = 32.863s`.

| workers | feature/c mean(s) | feature/c std(s) | worktree mean(s) | worktree std(s) | delta(s) | delta(%) | feature/c bad | worktree bad |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 16.028 | 1.553 | 29.528 | 2.093 | +13.500 | +84.23 | 0/5 | 0/5 |
| 2 | 13.983 | 0.518 | 19.076 | 1.575 | +5.093 | +36.42 | 0/5 | 0/5 |
| 3 | 13.066 | 0.439 | 16.010 | 0.732 | +2.944 | +22.53 | 0/5 | 1/5 |
| 4 | 12.121 | 0.168 | 15.418 | 0.572 | +3.298 | +27.21 | 1/5 | 0/5 |
| 5 | 11.793 | 0.686 | 14.938 | 0.222 | +3.144 | +26.66 | 0/5 | 0/5 |
| 6 | 14.863 | 0.278 | 14.878 | 0.473 | +0.015 | +0.10 | 0/5 | 0/5 |

Interpretation:
- This batch is for fluctuation characterization only; high machine load dominates timing.
- Use this table to justify repeated-measure design and load recording in the paper.

### N=12 branch-to-branch interleaved recheck (high-load, LOG_LEVEL=0, 5 repeats each, workers 1-6)
Method note:
- run order alternates each repeat: odd `feature->worktree`, even `worktree->feature`.
- this is for order-bias control under the same noisy environment.

Load note:
- `load1` range: `21.48 .. 26.25` (mean `23.78`) on 6 CPUs.

| workers | feature/c mean(s) | feature/c std(s) | worktree mean(s) | worktree std(s) | delta(s) | delta(%) | feature/c bad | worktree bad |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 42.651 | 2.041 | 42.643 | 1.668 | -0.009 | -0.02 | 0/5 | 0/5 |
| 2 | 27.782 | 0.749 | 24.768 | 2.063 | -3.014 | -10.85 | 0/5 | 0/5 |
| 3 | 20.332 | 0.764 | 20.761 | 0.319 | +0.429 | +2.11 | 0/5 | 0/5 |
| 4 | 19.025 | 0.686 | 19.182 | 0.560 | +0.157 | +0.82 | 0/5 | 0/5 |
| 5 | 19.263 | 0.545 | 18.847 | 0.675 | -0.416 | -2.16 | 0/5 | 1/5 |
| 6 | 19.002 | 0.243 | 18.719 | 0.533 | -0.283 | -1.49 | 0/5 | 0/5 |

Order-effect summary:
- `feature/c`: first `24.836s`, second `24.436s`
- `worktree`: first `24.118s`, second `24.177s`

### N=12 branch-to-branch interleaved recheck (low-load custom CPU set, LOG_LEVEL=0, 5 repeats each)
CPU sets:
- `w1=5`, `w2=4,5`, `w3=2,4,5`, `w4=1,2,4,5`, `w5=0,1,2,4,5`, `w6=0,1,2,3,4,5`.

Load note:
- `load1` range `1.95..5.73`, mean `3.26`.

| workers | feature/c mean(s) | feature/c std(s) | worktree mean(s) | worktree std(s) | delta(s) | delta(%) | feature/c bad | worktree bad |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12.378 | 0.027 | 12.476 | 0.021 | +0.097 | +0.79 | 0/5 | 0/5 |
| 2 | 7.786 | 0.024 | 7.827 | 0.032 | +0.041 | +0.53 | 0/5 | 0/5 |
| 3 | 6.037 | 0.027 | 6.049 | 0.059 | +0.012 | +0.21 | 1/5 | 0/5 |
| 4 | 5.711 | 0.019 | 5.696 | 0.026 | -0.015 | -0.26 | 0/5 | 0/5 |
| 5 | 4.819 | 0.021 | 4.863 | 0.016 | +0.045 | +0.93 | 0/5 | 0/5 |
| 6 | 4.830 | 0.021 | 4.843 | 0.022 | +0.013 | +0.27 | 0/5 | 0/5 |

### N=12 DEBUG instrumentation snapshot (workers 1-6, one run each)
Build: `-DMTPNDD_LOG_LEVEL=2` (debug counters enabled in `mtpndd` and `sylvan`).
| workers | time(s) | solutions | temp_refs_grow_total | temp_refs_peak | refs_resize | protect_resize | refs_merge_drop | refs_net_positive | refs_net_negative | protect_add | unprotect | hit | miss | del_only |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 21.098 | 14200 | 8603624 | 32 | 3 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 2 | 20.379 | 14200 | 8605155 | 32 | 5 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 3 | 18.616 | 14200 | 8604726 | 32 | 7 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 4 | 17.051 | 14200 | 8615328 | 32 | 9 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 5 | 16.923 | 14200 | 8620103 | 32 | 11 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 6 | 16.391 | 14200 | 8610439 | 32 | 13 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |

### N=12 DEBUG instrumentation after Step A (inline temp refs buffer, workers 1-6, one run each)
Build: `-DMTPNDD_LOG_LEVEL=2`.
| workers | time(s) | solutions | temp_refs_grow_total | temp_refs_peak | refs_resize | protect_resize |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 26.520 | 14200 | 0 | 32 | 3 | 0 |
| 2 | 20.058 | 14200 | 0 | 32 | 6 | 0 |
| 3 | 18.819 | 14200 | 0 | 32 | 7 | 0 |
| 4 | 17.671 | 14200 | 0 | 32 | 9 | 0 |
| 5 | 16.355 | 14200 | 0 | 32 | 11 | 0 |
| 6 | 15.747 | 14200 | 0 | 32 | 13 | 0 |

Interpretation:
- Step A removes observed dynamic-growth events in this workload (`grow_total` from ~8.6M to 0).
- DEBUG times are instrumentation-heavy and not directly used for final speedup claims.

### N=12 DEBUG instrumentation after Step B (per-worker temp refs pool + frame)
Build: `-DMTPNDD_LOG_LEVEL=2`.
| workers | time(s) | solutions | temp_refs_grow_total | temp_refs_peak | refs_resize | protect_resize |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 68.251 | 14200 | 1 | 64 | 3 | 0 |
| 4 | 29.667 | 14200 | 0 | 32 | 9 | 0 |

Notes:
- These are sanity/diagnostic snapshots under high machine load, not final speedup measurements.
- Growth remains near zero compared with pre-StepA baseline (millions of growth events).
## perf stat (N=12, taskset 1–4 workers)
Units: cycles/instructions in G, cache-misses in M.
| workers | elapsed(s) | cycles(G) | instructions(G) | cache-misses(M) |
|---:|---:|---:|---:|---:|
| 1 | 12.347 | 45.03 | 52.66 | 338.5 |
| 2 | 9.381 | 61.06 | 55.23 | 365.1 |
| 3 | 8.656 | 75.42 | 55.50 | 372.2 |
| 4 | 8.525 | 92.36 | 55.78 | 367.9 |

## strace -c futex (N=12)
| workers | futex calls | futex time(s) | usec/call | errors |
|---:|---:|---:|---:|---:|
| 1 | 2 | 0.000002 | 1 | 0 |
| 2 | 35,779 | 0.071044 | 1 | 3,801 |
| 3 | 57,922 | 0.238981 | 4 | 6,581 |
| 4 | 75,069 | 0.432329 | 5 | 8,686 |

## strace -tt futex detail (N=12, 4 workers)
| metric | value |
|---|---:|
| total futex calls | 73,896 |
| FUTEX_WAKE_PRIVATE | 45,368 |
| FUTEX_WAIT_PRIVATE | 28,528 |
| EAGAIN (WAIT) | 43 |

## perf futex tracepoint (N=12, 4 workers)
### Top uaddr sample shares
| uaddr | sample share (%) |
|---|---:|
| 0x5601809db6f0 | 59.70 |
| 0x5601809db5f0 | 35.24 |
| 0x5601809db670 | 1.73 |
| 0x5601809db570 | 1.60 |

### Op type shares
| op | sample share (%) |
|---|---:|
| 0x00000081 (WAKE) | 59.90 |
| 0x00000080 (WAIT) | 38.38 |

## N=12 correctness stress (workers=3, taskset `2,4,5`)
Purpose: isolate low-frequency wrong-answer issue and validate op-cache fix.

| case | command condition | sample size | failures | notes |
|---|---|---:|---:|---|
| baseline (`feature/c`) | default | 24 runs | 1 | fail example: `14199` |
| worktree (before op-cache read fix) | default | 14 runs | 1 | fail example: `14198` |
| temporary isolation A | `MTPNDD_FORCE_NO_SPAWN=1` | 5 runs | 0 | points to parallel path involvement |
| temporary isolation B | `MTPNDD_FORCE_NO_CACHE=1` | 8 runs | 0 | points to AND op-cache involvement |
| worktree (after op-cache read fix) | default | 12 runs | 0 | all `14200` |

Raw data files in `/tmp` used for this table:
- `/tmp/w3_featurec_solution_check.tsv`
- `/tmp/w3_worktree_solution_check.tsv`
- `/tmp/n12_w3_nospawn.tsv`
- `/tmp/n12_w3_nocache.tsv`
- `/tmp/n12_default_after_opcache_fix.tsv`

### Runtime snapshot (LOG_LEVEL=0, same CPU set)
| case | runs | mean(s) | std(s) |
|---|---:|---:|---:|
| pre-fix reference (`/tmp/w3_worktree_solution_check.tsv`, first 12 successful runs) | 12 | 6.300 | 0.024 |
| post-fix (`/tmp/n12_log0_after_opcache_fix.tsv`) | 12 | 6.411 | 0.027 |

## Futex address mapping (ASLR-robust)
| futex uaddr (low 12 bits) | mapped pool lock |
|---|---|
| 0x6f0 | g_edge_map_pool.lock |
| 0x5f0 | g_edge_entry_pool.lock |
| 0x670 | g_nodetable_entry_pool.lock |
| 0x570 | g_node_pool.lock |

Notes: mapping derivation is in `docs/archived/perf/futex_addr_mapping.md`.

## perf futex pool-lock sample share (N=12, 4 workers)
Derived by mapping uaddr sample shares to pool locks.
| pool lock | sample share (%) |
|---|---:|
| g_edge_map_pool.lock | 59.70 |
| g_edge_entry_pool.lock | 35.24 |
| g_nodetable_entry_pool.lock | 1.73 |
| g_node_pool.lock | 1.60 |

## perf c2c intensity metrics (N=12, 4 workers)
| metric | value |
|---|---:|
| total records | 209,626 |
| load operations | 103,093 |
| store operations | 106,533 |
| load local HITM | 1,238 |
| locked load/store ops | 4,869 |
| total shared cache lines | 41 |
| total merged records | 12,850 |
| locked access on shared lines | 4,028 |
| top cacheline address | 0x5632175b6b00 |
| top cacheline HITM | 970 |
| top cacheline HITM share | 78.35% |
| HITM per load | 1.20% |
| HITM per store | 1.16% |
| locked shared access ratio | 31.35% |

## refs_stats instrumentation (N=12, SYLVAN_REFS_STATS=ON)
| workers | modify_calls | ups | downs | updates | misses | retries | avg_probes |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 50,732,756 | 27,007,243 | 23,725,513 | 50,732,753 | 8,999 | 0 | 1.091 |
| 2 | 50,751,716 | 27,016,723 | 23,734,993 | 50,751,706 | 9,014 | 19,824 | 1.071 |
| 3 | 50,746,094 | 27,013,912 | 23,732,182 | 50,746,089 | 9,021 | 57,904 | 1.221 |
| 4 | 50,739,666 | 27,010,698 | 23,728,968 | 50,739,661 | 9,019 | 77,701 | 1.084 |

## refs_stats derived (N=12, SYLVAN_REFS_STATS=ON)
| workers | retries_per_million_modify |
|---:|---:|
| 1 | 0.00 |
| 2 | 390.61 |
| 3 | 1141.05 |
| 4 | 1531.37 |

## refs_stats timing (N=12, SYLVAN_REFS_STATS=ON, sample_rate=1/1024)
| workers | time_samples | time_ns | avg_time_ns |
|---:|---:|---:|---:|
| 1 | 49,544 | 2,806,657 | 56.6 |
| 2 | 49,563 | 5,302,180 | 107.0 |
| 3 | 49,558 | 7,499,678 | 151.3 |
| 4 | 49,552 | 9,900,557 | 199.8 |

## refs_stats imbalance (N=12, SYLVAN_REFS_STATS=ON)
| workers | max/min modify share | cv |
|---:|---:|---:|
| 1 | 1.00 | 0.000 |
| 2 | 1.29 | 0.128 |
| 3 | 1.58 | 0.225 |
| 4 | 1.87 | 0.288 |

## lace idle stats (N=12, LACE_IDLE_STATS=ON, sample_rate=1/1024)
| workers | idle_steal_nowork | idle_steal_est_ms | idle_leap_nowork | idle_leap_est_ms | wall_time_ms | idle_total_ms | idle_total/wall | idle_total/(wall*workers) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 0 | 0.00 | 0 | 0.00 | 11661 | 0.00 | 0.00 | 0.00 |
| 2 | 689069 | 1691.64 | 161941 | 61.54 | 8912 | 1753.18 | 0.20 | 0.10 |
| 3 | 1309155 | 4317.49 | 242333 | 291.95 | 7807 | 4609.44 | 0.59 | 0.20 |
| 4 | 1772825 | 7535.16 | 292993 | 171.07 | 7812 | 7706.23 | 0.99 | 0.25 |

## lace nowork ratios (N=12, LACE_COUNT_STEALS=ON)
| workers | steal_nowork | steal_tries | steal_nowork_rate | leap_nowork | leap_tries | leap_nowork_rate |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 0 | 0 | 0.0000 | 0 | 0 | 0.0000 |
| 2 | 693933 | 696474 | 0.9964 | 161941 | 162497 | 0.9966 |
| 3 | 1316381 | 1321658 | 0.9960 | 242333 | 243321 | 0.9959 |
| 4 | 1781504 | 1789472 | 0.9955 | 292993 | 293898 | 0.9969 |

## lace nowork rates (N=12, per wall-second)
| workers | steal_nowork_per_s | leap_nowork_per_s | idle_total_ms_per_s |
|---:|---:|---:|---:|
| 1 | 0.0 | 0.0 | 0.0 |
| 2 | 77865.0 | 18171.1 | 196.7 |
| 3 | 168615.5 | 31040.5 | 590.4 |
| 4 | 228047.1 | 37505.5 | 986.5 |

## lace task/steal effectiveness (N=12, LACE_COUNT_TASKS=ON)
| workers | tasks | success_steal+leap | tries_total | nowork_total | nowork_rate | success_rate | tasks_per_try | tasks_per_steal |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 98560 | 0 | 0 | 0 | 0.0000 | 0.0000 | 0.000000 | n/a |
| 2 | 101147 | 2917 | 857825 | 854823 | 0.9965 | 0.0034 | 0.117911 | 34 |
| 3 | 100989 | 6104 | 1580884 | 1574111 | 0.9957 | 0.0039 | 0.063881 | 16 |
| 4 | 100827 | 9106 | 2237525 | 2227641 | 0.9956 | 0.0041 | 0.045062 | 11 |

## lace backoff tune A (N=12, baseline vs tune A)
Baseline: YIELD_ITERS=256, SLEEP_ITERS=2048, SLEEP_NS=50000
Tune A: YIELD_ITERS=64, SLEEP_ITERS=512, SLEEP_NS=10000
| workers | wall_ms_base | wall_ms_A | idle_ms_per_s_base | idle_ms_per_s_A | nowork_rate_base | nowork_rate_A | tasks_per_try_base | tasks_per_try_A | tasks_per_steal_base | tasks_per_steal_A |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 11559 | 11532 | 0.0 | 0.0 | 0.0000 | 0.0000 | 0.000000 | 0.000000 | n/a | n/a |
| 2 | 8711 | 8803 | 182.5 | 162.5 | 0.9962 | 0.9903 | 0.1159 | 0.3182 | 31 | 33 |
| 3 | 7811 | 7838 | 566.0 | 660.1 | 0.9960 | 0.9912 | 0.0647 | 0.1795 | 17 | 21 |
| 4 | 8048 | 7816 | 1004.4 | 942.8 | 0.9959 | 0.9918 | 0.0458 | 0.1322 | 12 | 17 |

## lace backoff tune A2 (N=12, baseline vs tune A2)
Baseline: YIELD_ITERS=256, SLEEP_ITERS=2048, SLEEP_NS=50000
Tune A2: YIELD_ITERS=128, SLEEP_ITERS=1024, SLEEP_NS=20000
| workers | wall_ms_base | wall_ms_A2 | idle_ms_per_s_base | idle_ms_per_s_A2 | nowork_rate_base | nowork_rate_A2 | tasks_per_try_base | tasks_per_try_A2 | tasks_per_steal_base | tasks_per_steal_A2 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 11559 | 11523 | 0.0 | 0.0 | 0.0000 | 0.0000 | 0.000000 | 0.000000 | n/a | n/a |
| 2 | 8711 | 8612 | 182.5 | 201.9 | 0.9962 | 0.9939 | 0.1159 | 0.1915 | 31 | 32 |
| 3 | 7811 | 7955 | 566.0 | 574.7 | 0.9960 | 0.9940 | 0.0647 | 0.1150 | 17 | 20 |
| 4 | 8048 | 7909 | 1004.4 | 962.7 | 0.9959 | 0.9941 | 0.0458 | 0.0829 | 12 | 15 |

## refs init size sweep (N=12, SYLVAN_REFS_STATS=ON)
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

## combined indicators (N=12, separate runs)
Note: values are compiled from separate instrumentation runs (refs_stats, lace idle, lace tasks). Use for trend comparison, not strict cross-run timing.
| workers | wall_time_ms | ref_avg_time_ns | ref_retries_per_million | idle_total_ms_per_s | steal_nowork_rate | tasks_per_try | tasks_per_steal |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 11661 | 56.6 | 0.00 | 0.0 | 0.0000 | 0.000000 | n/a |
| 2 | 8912 | 107.0 | 390.61 | 196.7 | 0.9965 | 0.117911 | 34 |
| 3 | 7807 | 151.3 | 1141.05 | 590.4 | 0.9957 | 0.063881 | 16 |
| 4 | 7812 | 199.8 | 1531.37 | 986.5 | 0.9956 | 0.045062 | 11 |

Top bucket_mod distributions are recorded in `docs/archived/perf/refs_stats_n12.md`.
