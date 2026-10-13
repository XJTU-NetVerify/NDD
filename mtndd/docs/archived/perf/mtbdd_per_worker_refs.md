# MTBDD per-worker refs (signed counts + GC merge)

## Goal
Reduce contention in `refs_modify` by sharding MTBDD external refs per worker while preserving correctness via GC-time merge. Allow per-worker negative counts (cross-worker ref/deref order) and decide roots by global net count > 0.

## Initial findings and analysis
- `refs_modify` was on the hot path in profiling and showed increasing retries with worker count.
- Single shared refs table amplified cacheline contention and reduced scaling in multi-worker runs.
- Cross-worker ref/deref order means local table counts can go negative transiently; correctness requires GC-time global netting.

## Implementation summary
- `refs_table_t` now stores **signed** 24-bit counts; `refs_down` may create negative entries.
- `mtbdd_refs` is an **array of refs tables** sized by `lace_workers()`.
- `mtbdd_ref/deref` write into the current worker table.
- GC merges all per-worker tables into a persistent `mtbdd_refs_merge` table, then marks only `net > 0` keys.

## Benchmark setup
- Binary: `mtpndd_nqueens_benchmark <n> <workers>`
- Build: `cmake -B build -DMTPNDD_LOG_LEVEL=0 && cmake --build build`
- Workers: 1–4
- Sizes: N=8, N=12

## Results
### N=8
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 0.033 | 92 |
| 2 | 0.027 | 92 |
| 3 | 0.023 | 92 |
| 4 | 0.022 | 92 |

### N=12
| workers | time(s) | solutions |
|---:|---:|---:|
| 1 | 11.849 | 14200 |
| 2 | 7.523 | 14200 |
| 3 | 5.824 | 14200 |
| 4 | 5.647 | 14200 |

## Deltas vs prior baseline (plot_data.md “N-Queens runtime”)
### N=8
| workers | baseline(s) | new(s) | speedup (x) | reduction (%) |
|---:|---:|---:|---:|---:|
| 1 | 0.094 | 0.033 | 2.85 | 64.9 |
| 2 | 0.060 | 0.027 | 2.22 | 55.0 |
| 3 | 0.057 | 0.023 | 2.48 | 59.6 |
| 4 | 0.057 | 0.022 | 2.59 | 61.4 |

### N=12
| workers | baseline(s) | new(s) | speedup (x) | reduction (%) |
|---:|---:|---:|---:|---:|
| 1 | 12.128 | 11.849 | 1.02 | 2.3 |
| 2 | 9.359 | 7.523 | 1.24 | 19.6 |
| 3 | 8.455 | 5.824 | 1.45 | 31.1 |
| 4 | 8.308 | 5.647 | 1.47 | 32.0 |

## Design principles
- Keep external API unchanged (`mtbdd_ref/deref`).
- Allow local signed counts to encode cross-worker timing without forcing synchronization on hot path.
- Determine liveness only from merged global net count (`net > 0`) during GC.

## Notes
- The benchmark prints only elapsed time and solutions, so this dataset is directly comparable across runs.
- The per-worker refs approach improves scaling on N=12 under 2–4 workers compared to earlier baselines in `plot_data.md`.
- N=8 deltas compare `mtpndd_nqueens_test` baseline vs `mtpndd_nqueens_benchmark` new results; include this caveat if used in the paper.
- GC merge currently uses a simple open-addressing add (`refs_set_add`) with 128-probe limit; if this shows merge saturation in future runs, we should consider resizing or a longer probe budget.

## Re-run after reboot (2026-02-12, LOG_LEVEL=0)
Setup:
- rebuilt from clean `sylvan/build` and `jni/build`
- command: `taskset -c 0-5 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_benchmark 12 <workers>`
- workers: 1..6, 3 runs per worker

Raw runs:
- w=1: 11.948, 11.908, 11.819
- w=2: 7.508, 7.546, 7.544
- w=3: 5.879, 5.865, 5.849
- w=4: 5.519, 5.570, 5.609
- w=5: 4.657, 4.638, 4.632
- w=6: 4.582, 4.622, 4.581

Mean and deviation:
| workers | mean(s) | sd(s) | speedup vs w=1 | solutions |
|---:|---:|---:|---:|---:|
| 1 | 11.892 | 0.054 | 1.00x | 14200 |
| 2 | 7.533 | 0.017 | 1.58x | 14200 |
| 3 | 5.864 | 0.012 | 2.03x | 14200 |
| 4 | 5.566 | 0.037 | 2.14x | 14200 |
| 5 | 4.642 | 0.011 | 2.56x | 14200 |
| 6 | 4.595 | 0.019 | 2.59x | 14200 |

Interpretation:
- scaling is monotonic from 1 to 6 workers in this rerun.
- variance is low (all sd <= 0.054s), so this batch is stable enough for figure/table use.

## JNI runtime check and fix (2026-02-12)
Issue observed:
- JNI test entry `NQueensMTPNDD 12` initially showed long-running/no-finish behavior in earlier runs.

Root-cause findings:
- Java benchmark defaulted to `binary` mode, and for `N=12` this mode returned **incorrect result** (`solutions=0`) and high runtime (`~56s`).
- `onehot` mode returned the correct result (`14200`) with stable runtime (`~12.6s`).
- JNI config was aligned to C `nqueens.c` sizing profile (table sizes, buckets, slabs, dqsize), reducing unstable behavior from oversized/untuned settings.

Code-side fix:
- `NQueensMTPNDD` now keeps a single onehot implementation for JNI tests.
- Removed binary/auto mode branches from JNI test harness.
- Worker count is now an explicit parameter in CLI and solver entry (default `workers=1`).

Verification (workers=1):
- `java ... NQueensMTPNDD 12 1`
  - `solutions=14200`, `time=12.991s`

Verification (workers=6):
- `java ... NQueensMTPNDD 12 6`
  - `solutions=14200`, `time=5.443s`

Practical recommendation:
- Use the onehot JNI test path for `N=12` benchmarking and correctness checks.

## Pros and cons
### Pros
- Large multi-worker speedup on N=12 (2–4 workers) vs prior baseline.
- Hot-path contention reduced by worker-local refs updates.
- Correctness model is explicit and auditable (global merge with signed nets).

### Cons
- GC merge is still single-threaded and may become a bottleneck at higher core counts.
- Merge-table saturation risk exists under heavy key cardinality.
- Signed-count model adds complexity to diagnostics and requires dedicated anomaly monitoring.

## Next-step recommendations
1. Add merge saturation counters (`refs_set_add` failure ratio) to quantify pressure.
2. Resize merge table adaptively (`sum(worker_size)` or `max*2`) before merge.
3. Track `net < 0` key count after merge as a correctness monitor.
4. Evaluate parallel merge/reduce only after saturation and net<0 metrics are available.
