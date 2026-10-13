# MTPNDD Temp Refs Stack Optimization Plan (N=12 Focus)

## Scope and motivation
- This document records the full chain from measurement to optimization design for MTPNDD temp refs handling.
- Goal: reduce repeated allocation overhead in `mtpndd_temp_ref_list_t` while preserving correctness and GC safety.
- Workload baseline: NQueens `N=12`, workers `1..6`, with both normal timing runs and DEBUG counter runs.

## What was measured
### 1) Branch-to-branch runtime recheck (5 repeats each)
- Compared `feature/c` vs `feature/mtbdd-per-worker-protect`.
- Result: per-worker-protect is not consistently slower; for workers `>=2` it is slightly faster in this batch.
- Effect size is small (roughly `0.3%` to `2.1%`), so single-run conclusions are unreliable.
- One low-frequency correctness anomaly remains (`14199` once).

### 2) DEBUG instrumentation snapshot (1 run per worker, N=12)
- Added counters for:
  - `temp_refs_grow_total`, `temp_refs_peak_capacity`
  - `refs_resize_total`, `protect_resize_total`
  - `refs_gc merge_drop/net_positive/net_negative`
  - `protect add/unprotect/hit/miss/del_only`
  - `protect_gc merge_add/del/remaining/unmatched`
- Observed values (workers `1..6`):
  - `temp_refs_grow_total`: ~8.60M to ~8.62M
  - `temp_refs_peak_capacity`: always `32`
  - `refs_resize_total`: `3,5,7,9,11,13`
  - protect-path counters mostly inactive for runtime path (`add/unprotect=24/0`, all others 0)
  - refs-GC anomaly counters are 0 in this sample (`merge_drop=0`, `net_negative=0`)

## Anomaly and interpretation
### Anomaly A: very high `temp_refs_grow_total` with fixed peak `32`
- Interpretation:
  - many operation-local temp lists are created;
  - each list starts at capacity 0 and grows at first push to 32;
  - lists are then freed at release;
  - this pattern causes repeated malloc/realloc/free churn.

### Anomaly B: per-worker protect metrics mostly idle on NQueens
- Interpretation:
  - NQueens runtime path hardly executes dynamic `mtbdd_protect/unprotect`;
  - therefore per-worker protect optimization is not the dominant lever for this workload.

## Why temp refs is currently not reused
- `mtpndd_temp_ref_list_t` is operation-local and stack-scoped in several operations (`and/or/not/exist`).
- `mtpndd_temp_refs_init` sets `capacity=0`.
- first push triggers `realloc(..., 32)`.
- `mtpndd_temp_refs_release` always frees the backing array.
- This is simple and safe, but not allocation-efficient.

## Chosen direction
- Keep semantics of temp refs ownership/release.
- Replace per-operation "allocate then free" behavior with reusable stack-like buffers.
- Use a two-step rollout for low risk and easy rollback.

## Step A (low risk): inline small buffer
### Design
- Add embedded storage for 32 entries inside temp refs list struct.
- On init: point `items` to inline storage, `capacity=32`.
- Only allocate heap when exceeding inline capacity.
- On release:
  - deref all items;
  - if heap was used, keep or free by policy (initially keep simple free policy acceptable);
  - reset count to 0.

### Expected impact
- remove the first dynamic growth for most operations;
- sharply reduce `temp_refs_grow_total`;
- minimal behavior change and easy to validate.

### Step A implementation status
- Implemented in `sylvan/src/sylvan/mtpndd/mtpndd_node.c`:
  - embedded `inline_items[32]` in temp refs list;
  - init uses inline storage directly (`capacity=32`);
  - dynamic allocation occurs only when count exceeds 32;
  - release resets to inline storage.

### Step A measured result (DEBUG, N=12, workers 1..6, one run each)
- Before Step A (`temp_refs` dynamic-first strategy): `temp_refs_grow_total` around `8.60M`.
- After Step A (`inline 32`):
  - `temp_refs_grow_total=0` for all workers in this snapshot.
  - `temp_refs_peak_capacity=32` unchanged.

| workers | before grow_total | after grow_total | after peak_capacity |
|---:|---:|---:|---:|
| 1 | 8603624 | 0 | 32 |
| 2 | 8605155 | 0 | 32 |
| 3 | 8604726 | 0 | 32 |
| 4 | 8615328 | 0 | 32 |
| 5 | 8620103 | 0 | 32 |
| 6 | 8610439 | 0 | 32 |

Note:
- DEBUG-mode wall times in this batch are noisy and not used as final speedup evidence.
- This checkpoint validates allocation-churn reduction, not final runtime gain.

## Step B (main optimization): per-worker temp refs stack with frames
### Design
- Maintain one reusable temp-refs buffer per Lace worker.
- API idea:
  - `begin_frame()` -> returns frame base index
  - `push(node)` -> append to worker-local buffer
  - `end_frame(base)` -> deref from current top down to base, then pop
- Frame model supports nested/recursive operations without cross-frame corruption.

### Why per-worker, not one global stack
- A single global stack would require locking or atomics on every push/pop.
- With task stealing and nested operations, ownership and release order become error-prone.
- Per-worker buffers avoid centralized contention and keep ownership local.

### Correctness constraints
- `end_frame(base)` must always execute on all exits (success and error paths).
- no cross-worker direct mutation of worker-local stack.
- preserve one-to-one temporary ref accounting: every pushed ref must be released by frame close.

### Step B implementation status
- Implemented in `sylvan/src/sylvan/mtpndd/mtpndd_node.c` and wired in `sylvan/src/sylvan/mtpndd/mtpndd_common.c`.
- Data-structure change:
  - from operation-local dynamic array
  - to `per-worker reusable pool + frame handle`.
- API behavior at call sites remains unchanged (`mtpndd_temp_refs_init/push/release`), but semantics are now:
  - `init`: capture worker pool pointer and frame base;
  - `push`: append to worker pool;
  - `release`: pop back to frame base and deref only frame-local items.
- Runtime lifecycle:
  - `mtpndd_temp_refs_runtime_init()` after Lace startup;
  - `mtpndd_temp_refs_runtime_shutdown()` during `mtpndd_quit()` before node/table teardown.
- Safety fallback:
  - if no Lace worker context exists, a TLS pool is used (avoids cross-thread sharing for non-worker callers).

### Step B quick validation
- Build: success (`cmake --build`).
- Core tests passed:
  - `test_refs_signed`
  - `test_mtbdd_refs`
  - `test_mtbdd_gc_refs`
  - `test_protect_multiset`
  - `test_mtbdd_protect_per_worker`
  - `test_mtbdd_protect_gc`
- NQueens sanity (`N=12`):
  - worker=1: solutions `14200`, `temp_refs grow_total=1`, `peak_capacity=64`
  - worker=4: solutions `14200`, `temp_refs grow_total=0`, `peak_capacity=32`

Interpretation:
- Compared with pre-StepA baseline (millions of growth events), Step B keeps growth near zero in sampled runs.
- Occasional growth can still happen when a worker frame exceeds 32 entries; this is expected and much cheaper than per-operation malloc/free churn.

## Validation plan
### Functional
- Existing protect/refs unit tests must pass.
- NQueens solutions remain stable (`14200` for N=12).

### Performance/behavior
- Compare before vs after (N=12, workers 1..6):
  - wall time mean/median/std (repeat runs)
  - `temp_refs_grow_total` (must drop significantly)
  - `temp_refs_peak_capacity` (monitor for workload growth)
  - `refs_resize_total` and other counters as side signals
- Include both:
  - normal batch (if load is stable),
  - interleaved batch with load snapshot when machine is noisy.

### Latest LOG_LEVEL=0 timing status (high-load batch)
- A full `feature/c` vs worktree rerun (workers 1..6, 5 repeats each) was executed.
- During that run, system load was very high (`/proc/loadavg` around `18/18/14` on 6 CPUs).
- Observed timings were significantly inflated and branch deltas were not stable enough for code-level attribution.
- Decision: keep this batch as "environment fluctuation evidence", and collect final speedup data only under low-load/isolated conditions.

### Follow-up LOG_LEVEL=0 timing status (interleaved high-load batch)
- Added order-controlled rerun (odd run `feature->worktree`, even run `worktree->feature`) to remove branch-order bias.
- Load remained high (`load1` in `21.48..26.25`, mean `23.78` on 6 CPUs), and absolute times were still heavily inflated.
- First/second slot means are close for both branches, supporting that order bias was controlled.
- Conclusion unchanged: this is valid as noise characterization, not final speedup evidence.

### Correctness stress
- Run repeated N=12 rounds to monitor low-frequency anomalies.
- Track whether anomaly frequency changes after temp refs optimization.

## Checkpoint and rollback policy
- Create a checkpoint commit before Step A.
- Create a second checkpoint before Step B.
- If Step B regresses correctness or introduces instability, rollback to Step A checkpoint.

## Next actions after Step B
1. Re-run `N=12, workers=1..6` under low load with `MTPNDD_LOG_LEVEL=0` for final speed table.
2. Run DEBUG instrumentation sweep (`workers=1..6`) to capture `grow_total/peak_capacity` after Step B.
3. Decide whether to raise inline capacity macro (e.g., 64) based on observed growth frequency vs memory tradeoff.

## Step B low-load timing update
- Completed interleaved low-load rerun (`LOG_LEVEL=0`, workers `1..6`, 5 repeats each).
- Used fixed CPU sets to avoid known busy cores in this measurement window.
- Result: branch delta stays within about `-0.26% .. +0.93%` (performance-neutral on N=12).
- This indicates Step B did not introduce measurable regression in the current benchmark setup.

## Related documents
- `docs/archived/perf/mtbdd_per_worker_protect.md`
- `docs/archived/perf/mtbdd_per_worker_refs.md`
- `docs/archived/perf/plot_data.md`
- `docs/archived/perf/optimization_docs_audit_2026-02-09.md`
