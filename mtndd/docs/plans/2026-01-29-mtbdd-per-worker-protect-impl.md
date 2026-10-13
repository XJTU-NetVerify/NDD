# MTBDD Per-Worker Protect Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement per-worker MTBDD protect using add/del tables, preserve multiset semantics, and hard-fail on unmatched deletes during GC reconciliation.

**Architecture:** Replace the single `mtbdd_protected` table with per-worker `protect_add`/`protect_del` tables. `protect` inserts into add; `unprotect` removes from add or inserts into del with anomaly tracking. GC reconciles add/del globally and asserts on unmatched delete entries.

**Tech Stack:** C (Sylvan + Lace), CMake/CTest, MTBDD GC hooks.

---

### Task 1: Add protect add/del helpers + tests for multiset semantics

**Files:**
- Modify: `sylvan/src/sylvan/sylvan_refs.h`
- Modify: `sylvan/src/sylvan/sylvan_refs.c`
- Create: `sylvan/test/test_protect_multiset.c`
- Modify: `sylvan/test/CMakeLists.txt`

**Step 1: Write the failing test**
```c
// test_protect_multiset.c
// Verify duplicate protect entries are allowed and unprotect removes one occurrence at a time.
// Sequence: protect(ptr), protect(ptr), unprotect(ptr) -> still present once; unprotect again -> gone.
```

**Step 2: Run test to verify it fails**
Run: `ctest --test-dir sylvan/build -R test_protect_multiset -V`
Expected: FAIL (missing helper or incorrect behavior)

**Step 3: Write minimal implementation**
- Add helper(s) in `sylvan_refs.c`:
  - `protect_add_insert(tbl, ptr)`
  - `protect_add_remove_one(tbl, ptr)` (returns bool)
  - `protect_del_insert(tbl, ptr)`
- Expose prototypes in `sylvan_refs.h` if needed (or keep static and test via mtbdd_protect later).

**Step 4: Run test to verify it passes**
Run: `ctest --test-dir sylvan/build -R test_protect_multiset -V`
Expected: PASS

**Step 5: Commit**
```bash
git add sylvan/src/sylvan/sylvan_refs.h sylvan/src/sylvan/sylvan_refs.c sylvan/test/test_protect_multiset.c sylvan/test/CMakeLists.txt
git commit -m "feat(protect): add multiset helpers for add/del tables"
```

---

### Task 2: Per-worker MTBDD protect tables + anomaly counters

**Files:**
- Modify: `sylvan/src/sylvan/sylvan_mtbdd.c`
- Modify: `sylvan/src/sylvan/sylvan_mtbdd.h`
- Create: `sylvan/test/test_mtbdd_protect_per_worker.c`
- Modify: `sylvan/test/CMakeLists.txt`

**Step 1: Write the failing test**
```c
// test_mtbdd_protect_per_worker.c
// Protect/unprotect a pointer; verify del-only counter increments on unprotect without prior protect.
// Ensure add table holds duplicates and remove-one works.
```

**Step 2: Run test to verify it fails**
Run: `ctest --test-dir sylvan/build -R test_mtbdd_protect_per_worker -V`
Expected: FAIL

**Step 3: Write minimal implementation**
- Replace `mtbdd_protected` with per-worker arrays: `mtbdd_protected_add[w]`, `mtbdd_protected_del[w]`.
- Add counters (e.g., `mtbdd_protect_del_only_total`, `mtbdd_protect_del_only_unique`).
- `mtbdd_protect` inserts into add table only.
- `mtbdd_unprotect` removes from add or inserts into del + increments counters.
- Update init/quit paths to create/free both tables per worker.

**Step 4: Run test to verify it passes**
Run: `ctest --test-dir sylvan/build -R test_mtbdd_protect_per_worker -V`
Expected: PASS

**Step 5: Commit**
```bash
git add sylvan/src/sylvan/sylvan_mtbdd.c sylvan/src/sylvan/sylvan_mtbdd.h sylvan/test/test_mtbdd_protect_per_worker.c sylvan/test/CMakeLists.txt
git commit -m "feat(mtbdd): shard protect tables per worker"
```

---

### Task 3: GC reconciliation with hard-fail on unmatched deletes

**Files:**
- Modify: `sylvan/src/sylvan/sylvan_mtbdd.c`
- Create: `sylvan/test/test_mtbdd_protect_gc.c`
- Modify: `sylvan/test/CMakeLists.txt`

**Step 1: Write the failing test**
```c
// test_mtbdd_protect_gc.c
// Protect on worker A, unprotect on worker B -> del_only increments; GC reconciliation should succeed.
// Unprotect without prior protect -> GC reconciliation should assert (optional negative test).
```

**Step 2: Run test to verify it fails**
Run: `ctest --test-dir sylvan/build -R test_mtbdd_protect_gc -V`
Expected: FAIL

**Step 3: Write minimal implementation**
- Build global add multiset from all workers’ add tables.
- Apply all del entries by removing one from global add.
- If any delete misses → record `del_global_miss` and `assert(0)`.
- Mark remaining entries with `mtbdd_gc_mark_rec`.

**Step 4: Run test to verify it passes**
Run: `ctest --test-dir sylvan/build -R test_mtbdd_protect_gc -V`
Expected: PASS

**Step 5: Commit**
```bash
git add sylvan/src/sylvan/sylvan_mtbdd.c sylvan/test/test_mtbdd_protect_gc.c sylvan/test/CMakeLists.txt
git commit -m "feat(mtbdd): reconcile protect add/del in GC"
```

---

### Task 4: Documentation updates

**Files:**
- Modify: `docs/archived/perf/plot_data.md`
- Modify: `docs/archived/perf/mtbdd_per_worker_refs.md`
- Create: `docs/archived/perf/mtbdd_per_worker_protect.md`

**Step 1: Run performance/tests**
Run:
```bash
ctest --test-dir sylvan/build -R "test_protect_multiset|test_mtbdd_protect_per_worker|test_mtbdd_protect_gc" -V
```

**Step 2: Document results**
- Record anomaly counters and any GC reconciliation outcomes.
- Add guidance on interpreting `del_only` and `del_global_miss`.

**Step 3: Commit**
```bash
git add docs/archived/perf/plot_data.md docs/archived/perf/mtbdd_per_worker_refs.md docs/archived/perf/mtbdd_per_worker_protect.md
git commit -m "docs(perf): add per-worker protect notes"
```
