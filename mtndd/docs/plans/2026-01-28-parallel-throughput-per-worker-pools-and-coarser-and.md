# Parallel Throughput: Per-Worker Pools + Coarser AND Tasks Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Improve multi-worker scaling (<= 4 cores) by reducing allocator/lock contention and reducing Lace task overhead in `mtpndd_and`.

**Architecture:** (1) Make MTPNDD slab pools use per-worker caches with batched refill/spill to a global pool. (2) Coarsen `mtpndd_and` spawn granularity from per-(edge_a, edge_b) to per-(edge_a chunk) so we keep enough stealable work without exploding task count.

**Tech Stack:** C, Lace (Sylvan), pthreads (spinlock/mutex), existing `MTPNDD_LOG_LEVEL>=2` stats.

---

## Task 0: Baseline + Evidence Capture

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/test/nqueens.c` (optional: add CLI workers argument)

**Step 1:** Record baseline for N=12 with 1c/2c/4c

Run:
```bash
cd sylvan
taskset -c 0   ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
taskset -c 0-1 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
taskset -c 0-3 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```

Expected:
- solutions=14200 always
- capture: `.. stats: time ...`, `and spawns ...`, Lace counters (Tasks/Steals/Leaps)

**Step 2:** Capture contention signals

Run (needs ptrace permission):
```bash
strace -f -c taskset -c 0-3 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12 >/dev/null
perf stat -e task-clock,context-switches,cpu-migrations,page-faults taskset -c 0-3 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12 >/dev/null
```

---

## Task 1: Per-Worker Slab Pool Caches (Reduce Global Mutex Contention)

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c`
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.h`
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_common.c` (init/shutdown)

**Design (core idea):**
- Keep the existing global pool (backed by slab blocks) protected by a mutex.
- Add a per-worker cache for each pool: a small LIFO free-list + counters.
- Fast path (no locks): allocate/free from the current worker cache.
- Slow path (locks, batched): when cache empty, refill by popping N items from global pool; when cache too large, spill N items back to global pool.

**Step 1: Add worker id helper**
- Implement `static inline int mtpndd_worker_id(void)`:
  - `WorkerP *w = lace_get_worker();`
  - if `w == NULL` (non-Lace thread) return `-1` and use global-lock path.
  - else return `w->worker` (0..n_workers-1).

**Step 2: Define per-worker cache structs**
- Example:
  - `mtpndd_pool_local_t { void *free_list; size_t count; }`
  - `mtpndd_pool_locals_t { size_t n; mtpndd_pool_local_t *locals; }`
- Create one `locals` array per pool (node, edge_entry, nodetable_entry, edge_map, etc).
- Allocate `locals` sized `lace_workers()` (or `g_mtpndd_pal_config.n_workers` once Lace is started).

**Step 3: Implement batched refill/spill**
- Tuneables:
  - `MTPNDD_POOL_REFILL_BATCH` (e.g. 32)
  - `MTPNDD_POOL_LOCAL_MAX` (e.g. 256)
- Refill: under global mutex, pop up to batch items from global free_list, push into local list.
- Spill: under global mutex, move batch items from local to global.

**Step 4: Wire fast paths**
- In `mtpndd_memory_acquire_*`:
  - try local pop; else refill; else allocate new slab under global lock (existing behavior).
- In `mtpndd_memory_release_*`:
  - push to local; if exceeds max, spill.

**Step 5: Verification**
Run:
```bash
cd sylvan && cmake --build build -j
ctest --test-dir build
taskset -c 0-3 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```
Expected:
- solutions=14200
- reduced `pthread_mutex_lock` presence in `perf report` for 4c.

---

## Task 2: Coarsen `mtpndd_and` Spawn Granularity (Reduce Task Explosion)

**Files:**
- Modify: `sylvan/src/sylvan/mtpndd/mtpndd_node.c`

**Problem statement (what we observed):**
- With fine-grained tasks, Lace overhead can dominate:
  - Too many tasks -> higher spawn/sync overhead and more time in `lace_leapfrog`/steal paths.
  - More parallel threads -> more pressure on shared caches/nodetables/pools.
  - Symptoms: more workers => slower wall time, high steals/leaps, high syscall counts (futex/yield/sleep).

**Hypothesis:**
- Current per-(edge_a, edge_b) tasks are too fine for `n=12`:
  - Task count increases superlinearly with edges, and a single `mtpndd_and` call can enqueue thousands of tasks.
  - Many tasks are "cheap" (quickly return false label / cache hit), so overhead per task is not amortized.
- Coarsening to per-outer-entry (or per-chunk) tasks reduces total tasks while keeping enough stealable work for <=4 cores.

**Design constraints (must keep):**
- Correctness: any ref/GC-sensitive values returned from tasks must be protected until merged.
- No concurrent writes to `res_edges` / `temp_refs` in v1 (avoid edge_map thread safety and temp_refs atomic index issues).
- Error handling: if a subtask fails, must clean up owned refs without running lots of extra work.

### Recommended design: “outer-chunk tasks” + serial merge

**Key idea:** spawn *K* tasks, each task processes a slice of the outer loop sequentially and produces a local list of emitted edges. Parent does a single serial merge after syncing tasks.

**Flatten step (cheap, predictable):**
- Convert the edge_map linked lists into contiguous arrays of pointers:
  - `edge_bucket_entry_t **a_list` (size `|A|`)
  - same-field: `edge_bucket_entry_t **b_list` (size `|B|`)
  - diff-field: only `a_list` needed
- This reduces pointer chasing in workers and makes chunk slicing trivial.
- Allocation strategy:
  - Prefer stack for very small lists (e.g., <=64 via alloca) OR
  - Use a small scratch allocator / `malloc` (freed at end of `mtpndd_and_rec`).

**Task API (same-field):**
- Input: `a_list`, `a_start`, `a_end`, `b_list`, `b_len`
- Output: `mtpndd_and_chunk_result_t`:
  - `mtpndd_error_t status;`
  - `size_t n;`
  - `mtpndd_and_item_t *items;`  (each item: `{emit, child, label}`, already ref'd)
  - optional: `size_t cap;` if using growable buffer

**Task algorithm (same-field):**
1) Allocate a local items buffer sized pessimistically, but keep it reasonable:
   - Upper bound for a chunk is `(a_end-a_start) * b_len` but that can be huge.
   - Use a small initial cap (e.g. 64) and grow with `realloc` (only within task).
2) For each `i in [a_start, a_end)`:
   - For each `j in [0, b_len)`:
     - compute label (sylvan_and); if false -> continue
     - compute child = `mtpndd_and_rec_CALL(...)`
     - if success and emit:
       - `mtpndd_ref(child)` and `sylvan_ref(label)` and store in local items
3) Return result. Parent owns the items array and must free it after merging.

**Parent merge algorithm:**
- After spawning all chunk tasks, do `SYNC` on each:
  - If `status != SUCCESS`:
    - release owned refs in that chunk (labels + children)
    - free items buffer
    - abort build (and cancel remaining tasks where possible)
- If ok:
  - For each item:
    - push child into `temp_refs` (owned ref)
    - `mtpndd_add_edge(res_edges, child, label)` (serial, so edge_map remains non-threadsafe)
  - Free items buffer.

**Diff-field variant:**
- Same chunking, but inner loop is absent:
  - Each `entry_a` produces at most one emitted edge (label_a + and_rec(child_a, b)).
  - Items buffer can be fixed-size == chunk_size.

### Chunk sizing strategy (how to choose chunk_size)

**Cost model:**
- same-field work per `entry_a` is roughly proportional to `|B|` inner iterations.
- diff-field work per `entry_a` is roughly constant.

**Heuristic:**
- Compute `A=len(a_list)`, `B=len(b_list)` (same-field).
- Target number of tasks: ~`4 * workers` to ~`16 * workers` (enough stealing slack, not huge overhead).
- same-field chunk_size:
  - `chunk = max(1, A / (workers*8))` clamped to `[1, 32]` as a starting point
- diff-field chunk_size:
  - `chunk = max(1, A / (workers*16))` clamped to `[1, 128]`

**Tuning signals:**
- If `perf stat` shows CPUs utilized < ~2.5 on 4 cores and steals are near zero -> chunk too big (not enough tasks).
- If Lace Tasks grows massively and wall time worsens -> chunk too small (too many tasks).
- If `and spawns total` is high but steals are low -> tasks not being shared (consider `LACE_MAKE_ALL_SHARED()` or shared split tuning).

### Failure modes / pitfalls (call these out explicitly)

- **Owned ref leaks:** each task that returns `child/label` must return them with refs held; parent must always deref on error/abort paths.
- **Task cancellation:** on error, avoid executing a large number of not-yet-stolen tasks (prefer `lace_drop` where safe). For stolen tasks you must `SYNC` to collect and free their owned refs.
- **Temp refs pressure:** if tasks return many items, `temp_refs` might grow large; keep merge serial and release temp refs right after `mk`.
- **Memory churn inside tasks:** repeated `realloc` per chunk can be expensive; prefer a small-object pool for item buffers or fixed-size blocks + linked list if it becomes hot.

### Measurement plan (to prove coarsening helps)

Run matrix (median of 3):
```bash
taskset -c 0   ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
taskset -c 0-1 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
taskset -c 0-3 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```

Record for each:
- wall time + Lace counters (Tasks/Steals/Leaps)
- `and spawns total/same/diff`
- `perf stat` CPUs utilized
- optional: `perf record` top symbols (ensure time moves from Lace overhead back into useful `mtpndd_and_rec` / `mtpndd_mk`)

**Step 1: Add instrumentation (optional)**
- Track:
  - spawned tasks count
  - average chunk size
  - emitted items per task

**Step 2: Implement same-field coarsening**
- Replace nested spawn-with-item per pair with spawn per chunk.

**Step 3: Implement diff-field coarsening**
- Spawn per chunk of `a->edges` entries.

**Step 4: Verification + tuning loop**
Run:
```bash
taskset -c 0   ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
taskset -c 0-3 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```
Expected:
- solutions=14200
- 4c time improves vs 1c
- Lace counters show fewer Tasks overall with similar or slightly lower steal counts (enough to keep cores busy).

---

## Execution Options

1) Implement Task 1 first (pools), re-measure, then Task 2.
2) Implement Task 2 first (spawn coarsening), re-measure, then Task 1.
