# MTBDD Per-Worker Refs Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make MTBDD external refs per-worker and signed-count capable, with GC merge, to reduce contention and improve parallel throughput.

**Architecture:** Replace the single `mtbdd_refs` table with a per-worker table array, allow signed counts in `refs_table_t`, and merge all worker tables into a persistent merge table during GC to decide roots by net count > 0.

**Tech Stack:** C (Sylvan + Lace), CMake/CTest, MTBDD GC hooks.

---

### Task 1: Add signed-count iteration helper

**Files:**
- Modify: `sylvan/src/sylvan/sylvan_refs.h`
- Modify: `sylvan/src/sylvan/sylvan_refs.c`
- Test: `sylvan/test/test_refs_signed.c`

**Step 1: Write the failing test**
```c
// test_refs_signed.c
// Create refs table, call refs_down on new key, then refs_up twice;
// verify bucket count transitions -1 -> 1 -> 0 via refs_next_full
```

**Step 2: Run test to verify it fails**
Run: `ctest --test-dir sylvan/build -R test_refs_signed -V`
Expected: FAIL (symbol not found or incorrect behavior)

**Step 3: Write minimal implementation**
- Add `refs_next_full(refs_table_t*, uint64_t **bucket, size_t end, int32_t *count_out)`.
- Ensure `refs_modify` can insert negative counts when `dir < 0` and key not found.
- Treat high 24 bits as signed; handle overflow/underflow (clamp or assert in debug).

**Step 4: Run test to verify it passes**
Run: `ctest --test-dir sylvan/build -R test_refs_signed -V`
Expected: PASS

**Step 5: Commit**
```bash
git add sylvan/src/sylvan/sylvan_refs.h sylvan/src/sylvan/sylvan_refs.c sylvan/test/test_refs_signed.c
git commit -m "feat(refs): support signed counts and full iteration"
```

---

### Task 2: Per-worker MTBDD refs table

**Files:**
- Modify: `sylvan/src/sylvan/sylvan_mtbdd.c`
- Modify: `sylvan/src/sylvan/sylvan_mtbdd.h`

**Step 1: Write the failing test**
```c
// Add a minimal MTBDD test: create node, ref/deref on worker 0
// and ensure mtbdd_count_refs aggregates correctly.
```

**Step 2: Run test to verify it fails**
Run: `ctest --test-dir sylvan/build -R test_mtbdd_refs -V`
Expected: FAIL (old single-table path)

**Step 3: Write minimal implementation**
- Replace `refs_table_t mtbdd_refs` with `refs_table_t *mtbdd_refs` array sized by `lace_workers()`.
- Add `refs_table_t mtbdd_refs_merge`.
- In `mtbdd_ref/deref`, route to `mtbdd_refs[worker_id]` or worker 0 if not in lace.
- `mtbdd_count_refs` returns sum of `refs_count` across workers.

**Step 4: Run test to verify it passes**
Run: `ctest --test-dir sylvan/build -R test_mtbdd_refs -V`
Expected: PASS

**Step 5: Commit**
```bash
git add sylvan/src/sylvan/sylvan_mtbdd.c sylvan/src/sylvan/sylvan_mtbdd.h sylvan/test/test_mtbdd_refs.c
git commit -m "feat(mtbdd): shard external refs per worker"
```

---

### Task 3: GC merge and root marking

**Files:**
- Modify: `sylvan/src/sylvan/sylvan_mtbdd.c`

**Step 1: Write the failing test**
```c
// Force GC with refs distributed across workers; verify net count > 0 nodes remain.
```

**Step 2: Run test to verify it fails**
Run: `ctest --test-dir sylvan/build -R test_mtbdd_gc_refs -V`
Expected: FAIL (no merge)

**Step 3: Write minimal implementation**
- Clear merge table each GC (`memset`).
- For each worker table, iterate with `refs_next_full` and accumulate into merge.
- Mark only keys with net count > 0.

**Step 4: Run test to verify it passes**
Run: `ctest --test-dir sylvan/build -R test_mtbdd_gc_refs -V`
Expected: PASS

**Step 5: Commit**
```bash
git add sylvan/src/sylvan/sylvan_mtbdd.c sylvan/test/test_mtbdd_gc_refs.c
git commit -m "feat(mtbdd): merge per-worker refs during GC"
```

---

### Task 4: Performance validation + docs

**Files:**
- Modify: `docs/archived/perf/plot_data.md`
- Create: `docs/archived/perf/mtbdd_per_worker_refs.md`

**Step 1: Run performance tests**
Run:
```bash
./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 8
./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```
(Repeat with workers=1–4; record wall time and refs stats.)

**Step 2: Document results**
- Add before/after table to `plot_data.md`.
- Write narrative + charts input in `mtbdd_per_worker_refs.md`.

**Step 3: Commit**
```bash
git add docs/archived/perf/plot_data.md docs/archived/perf/mtbdd_per_worker_refs.md
git commit -m "docs(perf): add per-worker refs results"
```
