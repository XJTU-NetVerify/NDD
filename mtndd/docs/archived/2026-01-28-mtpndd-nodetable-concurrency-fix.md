# MTPNDD Nodetable 并行可扩展性：Bucket Sharding + Rehash 串行化

**Date:** 2026-01-28
**Status:** worktree change (not yet recorded as a git commit at the time of writing)
**Goal:** 让多 worker 下的 nodetable lookup/insert/rehash 行为可扩展且可预测，避免并行带来的结构性退化。

## Motivation / Symptom

当使用多个 Lace worker 时，`mtpndd_nqueens_test 12` 在某些实现状态下会显著变慢（甚至长时间看起来“卡住”）。在 <=4 核内，NQueens 仍然应当看到一定的并行收益，因此这类退化通常意味着共享结构成了瓶颈或行为不可预测。

In the “bad” state, `n=12` on 4 workers failed to complete within a 120s timeout (progress stuck around `cell_implications 118/144`), while the 1-core run completed in ~12s.

## Root Cause (Evidence)

`mtpndd_mk` uses a per-field nodetable (`mtpndd_nodetable_t`) to intern nodes by their edge map.

并行下的关键问题在于“同一份结构被多个线程同时访问/变形”，导致查找/rehash 的成本和行为不可控：
- `find_node_in_nodetable` 在没有 bucket 级互斥的情况下遍历 `nodetable->buckets[hash]`。
- `mtpndd_mk` 可能同时在同一 bucket 链表里插入。
- `mtpndd_nodetable_rehash` 会分配新 bucket 数组并替换 `table->buckets`，如果并发存在，rehash 会变成最重的热点路径，甚至产生不可预测行为。

这类组合在性能层面的表现是：
- rehash/lookup 的 CPU 占比异常升高（并行“越跑越慢”）
- bucket scan/compare 次数增加，随机内存访问更多

证据：
- `perf record` 抽样（慢的 4-worker run）里 `mtpndd_nodetable_rehash` 占据了很大比例（约一半采样 cycles），说明 rehash 在并行状态下成为主要吞吐瓶颈。

## Implementation Approach

**Design:** bucket 访问分片锁（sharding），并将 rehash 串行化。

Changes:
- Add `bucket_locks[]` (spinlocks) to `mtpndd_nodetable_t`:
  - lock index derived from `edges->cached_hash` (power-of-two mask)
  - protects both lookup and insert for the corresponding bucket region
- Add `rehash_mutex` to ensure only one thread performs rehash at a time.
- During rehash, take *all* bucket locks (“stop-the-world” at the nodetable level) to safely swap `table->buckets`.
- Make `entry_count` atomic, since it is updated outside the global locks and used for rehash thresholds.
- Add `mtpndd_nodetable_free()` and use it in `mtpndd_quit()` to free buckets and locks.

**Files changed:**
- `sylvan/src/sylvan/mtpndd/mtpndd_nodetable.c`
- `sylvan/src/sylvan/mtpndd/mtpndd_nodetable.h`
- `sylvan/src/sylvan/mtpndd/mtpndd_common.c`

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
- 4-core run completes quickly instead of timing out.

## Performance Results (Before/After)

- Before: `n=12` 在 4 核下可能超过 120s（timeout），且 `perf` 热点集中在 `mtpndd_nodetable_rehash`。
- After: `n=12` 在 4 核下可稳定完成（示例 run：**8.908s**），且 solutions 正确。

## Risks / Follow-ups

- Bucket 分片锁会给每次 mk lookup/insert 增加固定开销，但它换来的是“并行下可预测的吞吐与可控的 rehash 行为”。
- Rehash 被串行化，并在 swap 期间阻塞该 field 的 nodetable 活动；如果未来 rehash 仍然显著，可考虑：
  - pre-size buckets to avoid rehash in the hot path
  - adopt RCU-style rehash (complex)
  - shard by field (already done) + avoid cross-field sharing
