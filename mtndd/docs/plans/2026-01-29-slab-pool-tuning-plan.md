# Slab Pool Tuning (B/C) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Evaluate per-pool and worker-scaled slab pool tuning to improve MTPNDD parallel efficiency without regressing N=12 runtime.

**Architecture:** Adjust slab pool refill/local cache parameters per pool and/or scale them with worker count, then measure N=12 runtimes and perf counters to validate impact.

**Tech Stack:** C (MTPNDD/Sylvan), Lace, CMake, perf/stat, taskset.

### Task 1: Prep + Baseline

**Files:**
- Read: `docs/archived/perf/plot_data.md`
- Read: `docs/archived/perf/slab_pool_tune_A.md`

**Step 1: Confirm baseline commands**
Run: `taskset -c 0-3 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12`
Expected: solutions=14200, runtime near baseline table.

**Step 2: Record baseline into progress**
Update `progress.md` if re-run differs from recorded baseline.

### Task 2: Tune B (per-pool static parameters)

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c`
- Update: `docs/archived/perf/slab_pool_tune_B.md`
- Update: `docs/archived/perf/plot_data.md`

**Step 1: Implement per-pool constants**
Change pool structs to allow per-pool `refill_batch` and `local_max` (edge_map vs edge_entry vs node pools).

**Step 2: Build**
Run: `cd sylvan && cmake -B build && cmake --build build`
Expected: build succeeds.

**Step 3: Run N=12 for 1-4 workers**
Commands:
- `taskset -c 0 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12`
- `taskset -c 0-1 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12`
- `taskset -c 0-2 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12`
- `taskset -c 0-3 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12`
Expected: solutions=14200 each run.

**Step 4: Record results**
Update `docs/archived/perf/slab_pool_tune_B.md` and append table to `docs/archived/perf/plot_data.md`.

### Task 3: Tune C (scale with worker count)

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c`
- Update: `docs/archived/perf/slab_pool_tune_C.md`
- Update: `docs/archived/perf/plot_data.md`

**Step 1: Implement worker-scaled params**
Adjust `refill_batch` and `local_max` as a function of `lace_workers()` (or config value), with caps to prevent blow-up.

**Step 2: Build**
Run: `cd sylvan && cmake -B build && cmake --build build`
Expected: build succeeds.

**Step 3: Run N=12 for 1-4 workers**
Same commands as Task 2.

**Step 4: Record results**
Update `docs/archived/perf/slab_pool_tune_C.md` and append table to `docs/archived/perf/plot_data.md`.

### Task 4: Comparison + Decision

**Files:**
- Update: `docs/archived/perf/plot_data.md`
- Update: `findings.md`

**Step 1: Compare baseline vs Tune B/C**
Compute speedup/slowdown ratios and summarize.

**Step 2: Decide next move**
If neither improves N=12 (1-4 workers), stop further tuning and prioritize refcount/c2c or scheduling hypotheses.
