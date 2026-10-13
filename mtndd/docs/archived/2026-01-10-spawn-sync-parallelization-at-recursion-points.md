# 在递归边构造点引入 SPAWN/SYNC：并行化尝试与评估方法

**对应提交:** `f7241fc` (2026-01-10) `feat: add SPAWN/SYNC parallelization at internal edge recursion points`
**目标:** 在 `mtpndd_and` 等递归操作中，把互不依赖的子递归作为 Lace 任务并行执行，提升多核吞吐。

## 背景：哪里可以并行

在同 field 的 `and` 中，存在典型的双层循环：
- 外层遍历 `A.edges`
- 内层遍历 `B.edges`

每个 pair `(entry_a, entry_b)` 的子问题：
- 计算组合 label（BDD AND）
- 递归计算 child（`mtpndd_and_rec(child_a, child_b)`）

对子任务顺序通常没有要求，因此理论上可以并行。

## 方案：在递归点 spawn 子任务，并通过 sync 收集

设计的关键是：
- spawn 产生足够多的 stealable task，避免“spawn 立刻 sync”变相串行
- 控制任务数量，避免 task explosion（开销 > 收益）

Lace 语义注意：
- `SPAWN/SYNC` 会移动本地 `__lace_dq_head` 指针；如果在不同函数层级 spawn/sync，需要确保 dq_head 语义一致（通常在同一栈帧内管理，或显式传指针）。

## 评估方法（论文复现建议）

建议同时评估三类指标：

### 1) 性能（wall time）
- NQueens N=12
- worker=0/1/2/4（固定 pin：`taskset -c`）

### 2) Lace 开销（任务系统）
- Lace counters（Tasks/Steals/Leaps）
- 重点观察：
  - Tasks 是否爆炸式增长
  - Leaps（等待 stolen task 完成）的 tries 是否异常高

### 3) 共享结构争用
- perf：热点是否从“业务计算”漂移到“同步/锁/steal loop”
- strace：futex/yield/sleep 变化趋势（争用上升通常会抬高 futex 或 yield/sleep）

## 结论性的经验（适用于后续设计）

即使递归子问题“理论独立”，也不意味着细粒度 task 一定更快：
- 子任务过小会被 spawn/sync/steal 的固定成本吞掉
- 多 worker 下共享结构（nodetable/opcache/pools）容易变成瓶颈

因此更推荐的方向是：
- 以“chunk”为单位的粗粒度 task（outer-chunk），控制任务数量并提高单 task 的计算密度
- 相关设计已进一步整理在 `docs/plans/2026-01-28-parallel-throughput-per-worker-pools-and-coarser-and.md`
