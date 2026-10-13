# Per-Worker Slab Pool Caches (MTPNDD)

**Date:** 2026-01-28
**Status:** worktree change (not yet recorded as a git commit at the time of writing)
**Goal:** Reduce allocator/lock contention on hot allocation paths when running with multiple Lace workers (<=4 cores) while preserving correctness.

## Motivation / Symptom

MTPNDD uses slab-backed pools for frequently allocated objects:
- `mtpndd_node_t`
- `edge_bucket_entry_t`
- `mtpndd_nodetable_bucket_entry_t`
- `mtpndd_edge_t` (edge map container)

In the multi-worker case, these allocations happen at very high frequency during `mtpndd_and`/`mtpndd_mk`. With a single global mutex per pool, each alloc/free forces `pthread_mutex_lock`, causing lock contention and increased futex activity.

## Root Cause (Evidence)

The allocator fast path in `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c` originally took a global mutex per acquire/release. On a 4-core pinned run, `strace -f -c` showed a large amount of futex activity attributable to lock contention.

Baseline syscall profile (N=12, 4 cores pinned, `strace -f -c` tail):
```text
% time     seconds  usecs/call     calls    errors syscall
------ ----------- ----------- --------- --------- ------------------
 41.41    2.109115           2    846539    211715 futex
 33.87    1.725138           3    470023           sched_yield
 20.18    1.027921          41     24936           clock_nanosleep
...
100.00    5.093497           3   1359707    211729 total
```

## Implementation Approach

**Design:** Keep the existing global slab pools, but add a per-worker cache in front.

Key ideas:
- Each pool owns `locals[worker_id]`, a per-worker LIFO free list and a small counter.
- Fast path (no locks): Lace worker alloc/free uses its own `locals[worker_id]`.
- Slow path (batched, locked):
  - If local is empty, refill by moving up to `MTPNDD_POOL_REFILL_BATCH` objects from global free list under the global mutex.
  - If local grows beyond `MTPNDD_POOL_LOCAL_MAX`, spill objects back to global under the global mutex.
- Non-Lace threads (e.g., external callers) fall back to the global-lock path:
  - worker id is derived via `lace_get_worker()`; if it returns NULL, we do not touch `locals[]`.

Important details:
- `mtpndd_slab_pool_t.in_use` is now `_Atomic size_t` so snapshot/recording remains data-race free even when alloc/free is lockless locally.
- `locals[]` is allocated after Lace is started (so `lace_workers()` is valid), inside `mtpndd_memory_pools_init()`.

**Tuning knobs (current defaults):**
- `MTPNDD_POOL_REFILL_BATCH = 32`
- `MTPNDD_POOL_LOCAL_MAX = 256`

**Files changed:**
- `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c`

## Verification

Commands:
```bash
cd sylvan
ctest --test-dir build
taskset -c 0   ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
taskset -c 0-3 ./build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```

Expected / observed:
- `solutions=14200` for `n=12` in all cases.

## Performance Results (Before/After)

### Wall time (N=12)

Pinned runs:
- Before per-worker caches:
  - `taskset -c 0-3 ... 12` => **8.908s**
- After per-worker caches:
  - `taskset -c 0-3 ... 12` => **8.214s**

### Syscall contention (N=12, 4 cores pinned)

After per-worker caches (N=12, 4 cores pinned, `strace -f -c` tail):
```text
% time     seconds  usecs/call     calls    errors syscall
------ ----------- ----------- --------- --------- ------------------
 47.27    1.143327           2    427329           sched_yield
 29.61    0.716237          35     20334           clock_nanosleep
 14.26    0.344808           5     66558      6104 futex
...
100.00    2.418613           4    532441      6118 total
```

The reduction in futex calls indicates significantly fewer contended mutex operations on the hot allocation paths.

## Risks / Follow-ups

- Local caches can temporarily increase memory footprint (objects sit in `locals[]` instead of returning immediately to global).
- Work-stealing means “free on worker B of memory allocated on worker A” is common; this is safe, but can skew cache balance. The spill/refill path provides eventual balancing.
- Future tuning: use per-pool batch/max values (nodes vs edge entries have very different allocation rates).
