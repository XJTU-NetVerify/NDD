# MTPNDD AND: Batched-SYNC “Item Task” Refactor

**Date:** 2026-01-28
**Status:** worktree change (not yet recorded as a git commit at the time of writing)
**Goal:** Increase effective parallelism in `mtpndd_and` by spawning independent subcomputations and performing batched `SYNC`/merge, while keeping shared result structures (`res_edges`, `temp_refs`) merged serially for correctness.

## Motivation / Symptom

The desired behavior for an `and` node build is:
- Spawn many independent subcomputations (sub-ANDs) with no required ordering.
- Avoid “spawn immediately sync” patterns that collapse parallelism into serial execution.
- Merge results into a single edge_map at the end (or in controlled batches), to avoid concurrent writes.

## Implementation Approach

**Design:** convert per-subproblem work into Lace tasks that *return a value* describing an emitted edge.

Key pieces:
- Define `mtpndd_and_item_t` (task return struct):
  - `status` (error propagation)
  - `emit` (0/1; whether this pair contributes an edge)
  - `child` (MTPNDD child node, returned with `mtpndd_ref`)
  - `label` (BDD edge label, returned with `sylvan_ref`)
- Add two task types:
  - same-field: `mtpndd_and_same_field_item(entry_a, entry_b) -> mtpndd_and_item_t`
  - diff-field: `mtpndd_and_diff_field_item(entry_a, b) -> mtpndd_and_item_t`
- Parent behavior inside `mtpndd_and_rec`:
  - spawn tasks only when `mtpndd_should_spawn(...)` is true
  - keep a `pending` counter (number of queued tasks)
  - perform `SYNC` later (end of loop, and/or when threshold exceeded) and merge each returned item serially:
    - `temp_refs_push_owned(child)` (already ref'd by the task)
    - `mtpndd_add_edge(res_edges, child, label)` (serial add, avoids edge_map thread safety requirements)

**Error handling / cancellation:**
- If an error happens while building edges:
  - for stolen tasks: must `SYNC` to retrieve and free owned refs (child/label)
  - for non-stolen tasks: use `lace_drop` to avoid executing unnecessary work

**Spawn heuristic / throttling:**
- `mtpndd_should_spawn(...)` is used to avoid task explosion.
- A `MTPNDD_AND_PENDING_FLUSH_THRESHOLD` is used to bound the number of outstanding tasks, with partial draining to keep some parallel slack.

**Files changed:**
- `sylvan/src/sylvan/mtpndd/mtpndd_node.c`

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

## Performance Results (Representative)

N=12:
- `taskset -c 0 ... 12` => ~12.197s
- `taskset -c 0-3 ... 12` => ~8.214s

The Lace counters show non-zero steals/leaps on multi-core runs, indicating that tasks are being shared across workers (i.e., not collapsing into pure “fast-sync” on worker 0).

## Notes / Follow-ups

- This refactor enables batched `SYNC`/merge, but task granularity still matters. If tasks are too fine-grained, overhead can dominate. A next-step design is “outer-chunk tasks” (documented in `docs/plans/2026-01-28-parallel-throughput-per-worker-pools-and-coarser-and.md`).
- This design deliberately keeps `res_edges`/`temp_refs` merged serially. Moving to concurrent merges would require:
  - thread-safe edge_map insertion (bucket locks + resize control)
  - atomic/partitioned temp refs allocation
