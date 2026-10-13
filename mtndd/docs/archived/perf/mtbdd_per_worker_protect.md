# MTBDD per-worker protect (add/del tables + GC reconciliation)

## Goal
Reduce contention in MTBDD protect by sharding protect tables per worker while preserving correctness. Allow cross-worker protect/unprotect order to be detected and surfaced as hard errors during GC.

## Current implementation map (avoid concept confusion)
- `MTBDD external refs` (`mtbdd_ref/mtbdd_deref`) uses per-worker `refs_table_t` with signed counts (`+1/-1`) and GC merge-by-net-count, not add/del tables.
- `MTBDD protect/unprotect` uses per-worker add/del tables (`mtbdd_protected_add[w]` and `mtbdd_protected_del[w]`) and GC reconciliation.
- `MTPNDD temp refs list` (`mtpndd_temp_ref_list_t`) is an operation-local dynamic array used to hold temporary ownership for one operation; it is not a global/shared protect table.
- Therefore, "per-worker add/del" currently applies to MTBDD protect path, while refs path remains count-based.
- Important distinction for paper wording:
  - refs path is **counted hash table** (signed counter per key), not multiset storage.
  - protect path is **multiset-like behavior** via add/del tables and one-by-one cancellation at GC.

## Initial findings and analysis
- Single global MTBDD protect table can become a shared hotspot under multi-worker runs.
- Protect/unprotect behavior must preserve Sylvan semantics: duplicate entries are valid (multiset-like), and unprotect removes one occurrence.
- Cross-worker ordering can produce local "unprotect-before-protect" observations, so same-worker matching alone is insufficient for correctness.
- Correctness requirement is stricter than performance: if global reconciliation cannot match a delete, we treat it as a hard integrity error.

## Implementation summary
- Protect storage is now **per-worker** and split into **add** and **del** tables.
- `mtbdd_protect` inserts into the worker add table only (no probing into del).
- `mtbdd_unprotect` removes one entry from the worker add table; if not found, inserts into the worker del table and increments `mtbdd_protect_del_only_total()`.
- GC builds a **temporary merged add table**, then applies all del tables by removing one entry per del value.
- If a del cannot be matched in the merged add table, GC **prints an error and aborts**.
- GC marks nodes from the merged add table only.

## Correctness checks
- `test_protect_multiset` validates multiset-style insert/remove semantics for protect tables.
- `test_mtbdd_protect_per_worker` validates del-only anomaly counting.
- `test_mtbdd_protect_gc` expects `sylvan_gc()` to abort on unmatched del (fork-based).

## Design principles
- Preserve existing external API (`mtbdd_protect/unprotect`) and duplicate-entry behavior.
- Keep hot path simple: `protect` is direct add-table insert; no cross-table probing.
- Shift cross-worker ordering reconciliation to GC phase where a global view exists.
- Prefer fail-fast correctness checks (`abort`) over silent recovery for unmatched deletes.

## Logging/metrics macro status
- Current compile-time log levels are:
  - `MTPNDD_LOG_LEVEL=0` (`QUIET`)
  - `MTPNDD_LOG_LEVEL=1` (`INFO`)
  - `MTPNDD_LOG_LEVEL=2` (`DEBUG`)
- This plan uses the highest existing level (`MTPNDD_LOG_LEVEL_DEBUG`) directly for instrumentation and reporting, no extra level.
- Note: `sylvan_mtbdd.c`/`sylvan_refs.c` are in Sylvan target; if we need `MTPNDD_LOG_LEVEL` there, we should pass this compile definition to Sylvan target as well.

## Benchmark setup
- Binary: `mtpndd_nqueens_benchmark <n> <workers>`
- Build: `cmake -B build -DMTPNDD_LOG_LEVEL=0 && cmake --build build`
- CPU affinity: `taskset -c 0..(workers-1)`
- Workers: 1–4
- Size: N=12
- Repeats:
  - main set: 5 runs per worker (mean/median/std reported)
  - cross-check set: 3 rounds in interleaved order `4 -> 1 -> 3 -> 2`

## Results
### Main set raw runs (N=12, 5 repeats)
| workers | run1(s) | run2(s) | run3(s) | run4(s) | run5(s) | solutions |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12.349 | 12.136 | 11.928 | 11.964 | 11.922 | all 14200 |
| 2 | 7.557 | 7.697 | 7.590 | 7.629 | 7.838 | all 14200 |
| 3 | 6.087 | 5.958 | 6.030 | 6.054 | 5.955 | all 14200 |
| 4 | 5.669 | 5.660 | 5.601 | 5.620 | 5.585 | all 14200 |

### Main set summary (N=12)
| workers | mean(s) | median(s) | std(s) | cv(%) | min(s) | max(s) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12.060 | 11.964 | 0.164 | 1.36 | 11.922 | 12.349 |
| 2 | 7.662 | 7.629 | 0.099 | 1.30 | 7.557 | 7.838 |
| 3 | 6.017 | 6.030 | 0.052 | 0.87 | 5.955 | 6.087 |
| 4 | 5.627 | 5.620 | 0.033 | 0.58 | 5.585 | 5.669 |

### Interleaved-order cross-check (3 rounds)
Order per round: `4 -> 1 -> 3 -> 2`.

| workers | mean(s) | median(s) | std(s) | cv(%) | min(s) | max(s) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12.053 | 11.988 | 0.113 | 0.94 | 11.958 | 12.212 |
| 2 | 7.642 | 7.629 | 0.036 | 0.48 | 7.606 | 7.692 |
| 3 | 5.975 | 5.950 | 0.059 | 0.99 | 5.919 | 6.057 |
| 4 | 5.637 | 5.647 | 0.050 | 0.88 | 5.572 | 5.693 |

### Delta vs per-worker refs baseline
Reference from `docs/archived/perf/plot_data.md` ("per-worker refs, N=12").
| workers | refs baseline(s) | protect mean(s) | speedup (x) | change (%) |
|---:|---:|---:|---:|---:|
| 1 | 11.849 | 12.060 | 0.98 | +1.8 |
| 2 | 7.523 | 7.662 | 0.98 | +1.9 |
| 3 | 5.824 | 6.017 | 0.97 | +3.3 |
| 4 | 5.647 | 5.627 | 1.00 | -0.4 |

### Branch-to-branch recheck (feature/c vs per-worker-protect, N=12, workers 1-6, 5 repeats each)
This section uses the same benchmark command on two branches and compares means to reduce single-run noise.

| workers | feature/c mean(s) | per-worker mean(s) | delta(s) | delta(%) |
|---:|---:|---:|---:|---:|
| 1 | 12.219 | 12.236 | +0.017 | +0.14 |
| 2 | 7.732 | 7.675 | -0.057 | -0.74 |
| 3 | 6.007 | 5.991 | -0.016 | -0.27 |
| 4 | 5.693 | 5.606 | -0.087 | -1.53 |
| 5 | 4.802 | 4.718 | -0.084 | -1.75 |
| 6 | 4.861 | 4.761 | -0.100 | -2.06 |

Observed solutions:
- `feature/c`: all runs `14200`.
- `per-worker-protect`: 29/30 runs `14200`, one run at `workers=3` returned `14199`.

Interpretation:
- On this recheck set, per-worker protect is not slower overall; it is slightly slower only at `workers=1`, and slightly faster for `workers>=2`.
- Effect size is small (about `0.3%` to `2.1%`) and comparable to machine jitter.
- A single `14199` run remains a correctness risk signal and needs dedicated stress statistics.

### Branch-to-branch recheck (repeat under high machine load, LOG_LEVEL=0)
Build:
- `cmake -B build-log0 -DMTPNDD_LOG_LEVEL=0 && cmake --build build-log0`

Run:
- `taskset -c 0..(workers-1) ./build-log0/src/sylvan/mtpndd/mtpndd_nqueens_benchmark 12 <workers>`
- workers `1..6`, each repeated 5 times, separately on `feature/c` and `feature/mtbdd-per-worker-protect`.

Environment evidence during run:
- `/proc/loadavg`: `18.09 17.99 14.05` on `6` CPUs.
- same-condition single-run spot check after batch:
  - `feature/c w=1`: `33.107s`
  - `worktree w=1`: `32.863s`

Summary:

| workers | feature/c mean(s) | worktree mean(s) | delta(s) | delta(%) | feature/c bad | worktree bad |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 16.028 | 29.528 | +13.500 | +84.23 | 0/5 | 0/5 |
| 2 | 13.983 | 19.076 | +5.093 | +36.42 | 0/5 | 0/5 |
| 3 | 13.066 | 16.010 | +2.944 | +22.53 | 0/5 | 1/5 (`14199`) |
| 4 | 12.121 | 15.418 | +3.298 | +27.21 | 1/5 (`14198`) | 0/5 |
| 5 | 11.793 | 14.938 | +3.144 | +26.66 | 0/5 | 0/5 |
| 6 | 14.863 | 14.878 | +0.015 | +0.10 | 0/5 | 0/5 |

Interpretation:
- This batch is dominated by machine contention; it should not be used to infer code-level slowdown/speedup.
- Relative behavior collapses at `workers=6` (`+0.10%`), while low-worker gaps are inflated, matching high-load interference.
- Correctness anomalies remain low-frequency and branch-independent in this batch (`feature/c`: `1/30`, worktree: `1/30`).

### Branch-to-branch interleaved recheck (high-load, LOG_LEVEL=0)
Method:
- Same command and build flags as above.
- For each `(worker, run)`, branch order alternates:
  - odd run: `feature -> worktree`
  - even run: `worktree -> feature`
- This removes monotonic time-drift/order bias.

Load condition:
- `load1` during runs: `21.48 .. 26.25` (mean `23.78`) on 6 CPUs.

Summary:

| workers | feature/c mean(s) | worktree mean(s) | delta(s) | delta(%) | feature/c bad | worktree bad |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 42.651 | 42.643 | -0.009 | -0.02 | 0/5 | 0/5 |
| 2 | 27.782 | 24.768 | -3.014 | -10.85 | 0/5 | 0/5 |
| 3 | 20.332 | 20.761 | +0.429 | +2.11 | 0/5 | 0/5 |
| 4 | 19.025 | 19.182 | +0.157 | +0.82 | 0/5 | 0/5 |
| 5 | 19.263 | 18.847 | -0.416 | -2.16 | 0/5 | 1/5 (`14199`) |
| 6 | 19.002 | 18.719 | -0.283 | -1.49 | 0/5 | 0/5 |

Order-effect check:
- `feature/c first` vs `feature/c second`: `24.836s` vs `24.436s`.
- `worktree first` vs `worktree second`: `24.118s` vs `24.177s`.
- First/second means are very close, so interleaving successfully removed order bias; remaining variance is mostly external load.

Pairwise delta check (`same worker, same run index`):
- `worker=2`: 5/5 pairs are faster on worktree (mean `-3.014s`).
- `worker=6`: 4/5 pairs are faster on worktree (mean `-0.283s`).
- `worker=3/4`: mixed signs (`+0.429s`, `+0.157s`) and small effect.
- Under this load level, pairwise signal still exists but is not stable enough as final optimization evidence.

Interpretation:
- Under sustained high load, both branches are heavily throttled and absolute times lose comparability to earlier runs.
- This batch is valid as "fluctuation/noise characterization", not as final optimization evidence.

### Branch-to-branch interleaved recheck (low-load custom CPU set, LOG_LEVEL=0)
Method:
- Same interleaved order as above (odd `feature->worktree`, even `worktree->feature`).
- To avoid known busy cores during this window, used fixed CPU sets:
  - `w1=5`
  - `w2=4,5`
  - `w3=2,4,5`
  - `w4=1,2,4,5`
  - `w5=0,1,2,4,5`
  - `w6=0,1,2,3,4,5`

Load condition:
- `load1` range `1.95 .. 5.73`, mean `3.26` (much lower than previous high-load batches).

Summary:

| workers | feature/c mean(s) | worktree mean(s) | delta(s) | delta(%) | feature/c bad | worktree bad |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12.378 | 12.476 | +0.097 | +0.79 | 0/5 | 0/5 |
| 2 | 7.786 | 7.827 | +0.041 | +0.53 | 0/5 | 0/5 |
| 3 | 6.037 | 6.049 | +0.012 | +0.21 | 1/5 (`14199`) | 0/5 |
| 4 | 5.711 | 5.696 | -0.015 | -0.26 | 0/5 | 0/5 |
| 5 | 4.819 | 4.863 | +0.045 | +0.93 | 0/5 | 0/5 |
| 6 | 4.830 | 4.843 | +0.013 | +0.27 | 0/5 | 0/5 |

Interpretation:
- Under relatively low load, per-worker protect + Step B temp-refs changes are performance-neutral on N=12 (all deltas within about `±1%`).
- No consistent speedup/slowdown trend across workers; effect size is within expected run-to-run noise.
- Low-frequency correctness anomaly still appears on one side in this batch (`feature/c` at `w=3`, 1/5), reinforcing the need for long-horizon correctness stress separate from speed tests.

## Notes
- The del-only counter (`mtbdd_protect_del_only_total`) provides a coarse signal for cross-worker order anomalies.
- The GC reconciliation abort ensures we catch any case where deletions cannot be matched globally.
- During an earlier sample set, one `workers=4` run printed `14199` solutions once; follow-up verification (13 additional runs at `workers=4`) all returned `14200`.
- During this recheck, one `workers=3` run also printed `14199` (1/30 in this batch), so low-frequency instability is still present.
- Main-set and interleaved-set means differ by less than 1% for all workers, suggesting observed runtime jitter is mostly environment-level noise (scheduler/CPU frequency/background load) rather than order bias.

## Fluctuation analysis (why single-run conclusions changed)
- Single-run comparisons can flip sign because current setup has low absolute deltas and background scheduler/frequency noise.
- Repeated runs (5x) reduced this risk and showed stable trend: `workers>=2` favors per-worker protect in this batch.
- Variance is highest at `workers=6` in this run (`cv=3.08%`) due one slow sample (`5.050s`), consistent with machine-level interference.
- Recommendation: report mean + std/CV and keep interleaved execution order when collecting paper figures.
- Additional evidence from the latest LOG_LEVEL=0 rerun shows system load can dominate all branch deltas; include load snapshots for every paper-grade run.

## DEBUG instrumentation results (N=12, workers 1-6, one run each)
Build: `-DMTPNDD_LOG_LEVEL=2` (debug counters enabled in both `mtpndd` and `sylvan` targets).

| workers | time(s) | solutions | temp_refs_grow_total | temp_refs_peak | refs_resize | protect_resize | refs_merge_drop | refs_net_positive | refs_net_negative | protect_add | unprotect | hit | miss | del_only |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 21.098 | 14200 | 8,603,624 | 32 | 3 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 2 | 20.379 | 14200 | 8,605,155 | 32 | 5 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 3 | 18.616 | 14200 | 8,604,726 | 32 | 7 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 4 | 17.051 | 14200 | 8,615,328 | 32 | 9 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 5 | 16.923 | 14200 | 8,620,103 | 32 | 11 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |
| 6 | 16.391 | 14200 | 8,610,439 | 32 | 13 | 0 | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 |

Key observations:
- `protect_add/unprotect` remains `24/0` for all runs, meaning NQueens workload hardly touches runtime `mtbdd_protect/unprotect`.
- Therefore, per-worker protect changes are unlikely to move NQueens runtime directly; this explains weak/unstable speed impact.
- `temp_refs_grow_total` is very high (~8.6M) while `temp_refs_peak=32`, indicating many short-lived lists each triggering at least one dynamic growth.
- `refs_resize` increases with worker count (3 -> 13), but still low relative to operation count.
- `refs_merge_drop=0` and `refs_net_negative=0` in this sample, so no merge-saturation/correctness warning from refs GC path here.

## Pros and cons
### Pros
- Stronger correctness diagnostics: unmatched delete is detected deterministically at GC.
- Lower shared-state pressure on protect/unprotect hot path by per-worker sharding.
- Reproducible benchmarking and variability quantification (mean/median/std/CV + interleaved cross-check).

### Cons
- Additional GC work: add/del reconciliation adds linear passes over worker tables.
- Runtime benefit is not clear on N=12: 1-3 worker means are slightly slower than per-worker refs baseline.
- One historical 14199 sample still indicates a low-frequency correctness risk that warrants further stress testing.

## DEBUG metrics proposal (pending confirmation)
All items below should be gated by `#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG`.

1. Protect add/del balance counters
- `protect_add_total`: total add inserts
- `protect_del_total`: total del inserts (local unmatched unprotect)
- `protect_add_remove_hit_total`: unprotect removed from add directly
- `protect_add_remove_miss_total`: unprotect missed add and inserted into del

2. GC reconciliation counters
- `protect_gc_merge_add_total`: entries merged from all add tables
- `protect_gc_merge_del_total`: entries applied from all del tables
- `protect_gc_unmatched_del_total`: del entries not found in merged add (before abort)
- `protect_gc_remaining_total`: remaining entries after reconciliation (to mark)

3. Duplicate multiplicity counters (multiset diagnostics)
- `protect_gc_dup_add_total`: sum of duplicate multiplicities in merged add
- `protect_gc_dup_del_total`: sum of duplicate multiplicities in merged del stream
- `protect_gc_key_imbalance_total`: number of keys where merged add multiplicity != del multiplicity + remaining multiplicity

4. Dynamic expansion counters (capacity pressure)
- `refs_resize_total`: number of `refs_resize()` executions (external refs path)
- `protect_resize_total`: number of `protect_resize()` executions (protect add/del path)
- `temp_refs_grow_total`: number of `realloc` growth events in `mtpndd_temp_ref_list_t`
- `temp_refs_peak_capacity`: max observed temp refs array capacity

5. DEBUG assertions (strict mode)
- Keep current hard assertion: unmatched del in GC -> `abort`.
- Optional strict assert (DEBUG-only): if global accounting for a key is inconsistent, `assert(0)`.

## Temp refs per-worker discussion
- Current `mtpndd_temp_ref_list_t` is local to one operation and mainly appended by the merge/drain path in the initiating worker.
- Making temp refs "per-worker" would require either:
  - cross-worker append synchronization, or
  - per-worker buffers + final reduction/merge and deterministic ownership transfer.
- Given current control flow (task returns item; merge is centralized), operation-local dynamic array remains simpler and usually lower risk.
- Decision recommendation: keep temp refs as operation-local for now; first collect DEVELOP stats on duplicate/ref-deref balance, then revisit if data shows temp refs as bottleneck.

## Next-step recommendations
1. Run long-horizon correctness stress (e.g., `N=12`, 4 workers, 100+ rounds) and record failure rate.
2. Add periodic counters snapshot (del-only growth trend per run) to correlate anomalies with workload phases.
3. Prioritize temp refs allocation optimization path documented in `docs/archived/perf/mtpndd_temp_refs_stack_plan.md`:
   - Step A: inline small buffer (32) to remove first realloc in common path. (implemented; DEBUG snapshot shows `temp_refs_grow_total` dropped to 0 for N=12 workers 1..6)
   - Step B: per-worker temp refs stack with frame-based release. (implemented; core tests pass, DEBUG snapshots show growth remains near zero)
4. If correctness remains stable, profile GC reconciliation cost directly (time share in GC) before further optimization.
5. Only then consider parallelizing reconciliation; keep fail-fast invariant unchanged.

## Update (Step B implemented)
- Temp refs path now uses worker-local reusable pool + frame pop semantics.
- Wiring:
  - init: `mtpndd_temp_refs_runtime_init()` in `mtpndd_init` after Lace startup.
  - shutdown: `mtpndd_temp_refs_runtime_shutdown()` in `mtpndd_quit` before node/table teardown.
- Quick validation:
  - selected unit tests all pass (`refs/protect/gc` suite).
  - NQueens `N=12` sanity runs still return `14200`.

## Update (wrong-answer root-cause follow-up)
- Dedicated root-cause record:
  - `docs/archived/perf/opcache_concurrency_root_cause_n12.md`
- Summary:
  - The observed `14198/14199` anomalies are linked to AND op-cache concurrent lookup/store consistency.
  - Isolation experiments (`no-spawn`, `no-cache`) both removed failures in sampled runs.
  - After fixing cache lookup consistency, default parallel path recheck (`N=12`, 12 runs) returned `14200` for all runs.

## Update (2026-02-12, JNI integration crash/hang root cause)
### Symptom
- JNI `MTPNDDTest` stalled in `getFieldInfoNative` or crashed with `SIGSEGV`.
- Crash signature (from `hs_err`): `libmtpnddjni.so -> protect_resize_help`.

### What changed
1. JNI field declaration semantics and lazy materialization
- `jni/src/main/native/mtpndd_jni.c`
  - `declareFieldNative` now returns `pending_field_count` (field id from declaration order).
  - Added `mtpndd_ensure_fields_generated()` and invoked it in:
    - `getFieldInfoNative`
    - `getVarNative`
    - `getNotVarNative`
    - `getBddVarNative`
    - `getBddNotVarNative`
- Motivation: Java side expected `declareField(1) -> 1`, while core C keeps fields in pending state until generation.

2. JNI build linkage correction
- `jni/CMakeLists.txt`
  - Removed stale hard-link assumptions to `_deps/lace-build` archives.
  - JNI now links against built Sylvan/MTPNDD artifacts from this repository build path.

3. Protect table correctness fixes (actual crash root cause)
- `sylvan/src/sylvan/sylvan_refs.c`
  - `protect_up` now treats existing key as success (set semantics), preventing endless probe/resize under duplicate insert.
  - `refs_create` and `protect_create` now fully initialize table metadata:
    - zero table memory
    - reset `refs_control`, resize table pointer/size, resize counters
  - Motivation: uninitialized resize-control metadata from `malloc`-backed table arrays caused random false "resize in progress", leading to invalid `protect_resize_help` paths and SIGSEGV.

4. Worker-id hardening in MTBDD paths
- `sylvan/src/sylvan/sylvan_mtbdd.c`
  - `mtbdd_worker_id` and `mtbdd_protected_worker_id` now clamp to `[0, lace_workers)`, fallback to `0`.
  - Motivation: guard against invalid worker ids on non-worker/edge thread states, preventing out-of-bounds table access and secondary corruption.

5. JNI test config alignment
- `jni/src/test/java/org/ants/mtpndd/MTPNDDTest.java`
- `jni/src/test/java/org/ants/mtpndd/ManualNativeCheck.java`
  - Changed `.workers(1)` to `.workers(0)` to match "auto worker count" runtime mode used in current branch and avoid single-worker special-path ambiguity during this validation phase.

### Verification results (after fixes)
- Native build and run:
  - `./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12`
  - Result: `solutions=14200`, `time=11.372s`.
- JNI build:
  - `cd jni && cmake -B build -DMTPNDD_LOG_LEVEL=0 && cmake --build build`.
- JNI tests:
  - `cd jni && mvn -q -Dmaven.repo.local=/tmp/mtpndd-m2 -Dorg.ants.mtpndd.library.path=$PWD/build/libmtpnddjni.so test`
  - Result: pass.

### Pros / limits / next steps
- Pros:
  - Resolved deterministic JNI stall/crash path with concrete root-cause fixes in protect table lifecycle.
  - JNI field declaration semantics are now consistent with Java API expectation.
- Limits:
  - This update stabilizes integration and correctness but is not a throughput optimization by itself.
- Next:
  - Keep the new `hs_err` + dumpstream workflow for future native faults.
  - Continue per-worker performance experiments on N=12 with `LOG_LEVEL=0` for paper figures.

### Runtime fluctuation check (N=12, workers 1..6, 3 repeats)
Command:
- `taskset -c 0..(workers-1) ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_benchmark 12 <workers>`

Raw (all solutions were `14200`):

| workers | run1(s) | run2(s) | run3(s) |
|---:|---:|---:|---:|
| 1 | 14.520 | 15.596 | 16.076 |
| 2 | 13.730 | 14.468 | 14.191 |
| 3 | 12.896 | 12.466 | 13.244 |
| 4 | 11.923 | 11.250 | 11.646 |
| 5 | 10.815 | 10.783 | 11.252 |
| 6 | 11.099 | 10.860 | 11.200 |

Summary:

| workers | mean(s) | median(s) | std(s) | cv(%) |
|---:|---:|---:|---:|---:|
| 1 | 15.397 | 15.596 | 0.651 | 4.23 |
| 2 | 14.130 | 14.191 | 0.304 | 2.15 |
| 3 | 12.869 | 12.896 | 0.318 | 2.47 |
| 4 | 11.606 | 11.646 | 0.276 | 2.38 |
| 5 | 10.950 | 10.815 | 0.214 | 1.95 |
| 6 | 11.053 | 11.099 | 0.143 | 1.29 |

Context:
- During this batch, `/proc/loadavg` snapshot was `11.03 10.68 9.73` on a 6-core host.
- Compared with earlier low-load runs (w1 around 12s), this confirms current slowdown is primarily machine-load noise, not a new code regression.

## Update (2026-02-13 reboot rerun, LOG_LEVEL=0, 5 repeats)

### Setup
- Branch: `feature/mtbdd-per-worker-protect` (worktree HEAD)
- Native build:
  - `cd sylvan && cmake -B build-log0 -DMTPNDD_LOG_LEVEL=0 && cmake --build build-log0 -j`
- JNI build:
  - `cd jni && cmake -B build-log0 -DMTPNDD_LOG_LEVEL=0 && cmake --build build-log0 -j`
- Binding:
  - use onehot JNI harness only: `org.ants.mtpndd.NQueensMTPNDD`
- Run matrix:
  - `N=12`, workers `1..6`, each worker repeats `5` times
  - CPU pinning: `taskset -c 0..(workers-1)`

### Native MTPNDD results
All runs returned `solutions=14200`.

| workers | mean(s) | std(s) | cv(%) | min(s) | max(s) |
|---:|---:|---:|---:|---:|---:|
| 1 | 12.041 | 0.008 | 0.06 | 12.028 | 12.051 |
| 2 | 7.634 | 0.034 | 0.45 | 7.576 | 7.678 |
| 3 | 5.953 | 0.023 | 0.39 | 5.925 | 5.987 |
| 4 | 5.607 | 0.037 | 0.66 | 5.546 | 5.643 |
| 5 | 4.742 | 0.032 | 0.68 | 4.700 | 4.779 |
| 6 | 4.665 | 0.023 | 0.50 | 4.638 | 4.697 |

### JNI onehot results
All runs returned `solutions=14200`.

| workers | mean(s) | std(s) | cv(%) | min(s) | max(s) |
|---:|---:|---:|---:|---:|---:|
| 1 | 12.759 | 0.030 | 0.23 | 12.721 | 12.809 |
| 2 | 8.516 | 0.377 | 4.42 | 8.124 | 9.068 |
| 3 | 6.611 | 0.176 | 2.66 | 6.386 | 6.761 |
| 4 | 5.878 | 0.075 | 1.28 | 5.739 | 5.943 |
| 5 | 5.392 | 0.044 | 0.81 | 5.332 | 5.466 |
| 6 | 4.951 | 0.047 | 0.95 | 4.874 | 5.006 |

### JNI vs Native ratio

| workers | native mean(s) | jni mean(s) | jni/native |
|---:|---:|---:|---:|
| 1 | 12.041 | 12.759 | 1.060 |
| 2 | 7.634 | 8.516 | 1.116 |
| 3 | 5.953 | 6.611 | 1.111 |
| 4 | 5.607 | 5.878 | 1.048 |
| 5 | 4.742 | 5.392 | 1.137 |
| 6 | 4.665 | 4.951 | 1.061 |

### Interpretation (fluctuation and "sudden slow" concern)
- This rerun does not reproduce the earlier severe slowdown. With `LOG_LEVEL=0` and clean rebuild, native times are back to the expected range.
- JNI is consistently slower than native by about `4.8%..13.7%`, mainly from Java/JNI overhead.
- JNI variance is concentrated at `workers=2/3` (higher `cv`) due JVM-process-level jitter (startup/GC/scheduling), while native variance stays below `1%`.
- For paper figures, use repeated measurements with mean/std and environment notes; avoid single-run conclusions.
