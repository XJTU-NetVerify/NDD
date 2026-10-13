# MTPNDD 优化路线图

本文档汇总已完成的优化、实验结论和待实施方案，供论文写作和后续开发参考。

---

## 1. 已实施优化总结

### 1.1 内存管理：gc_protect → temp_refs（feature/serial → feature/c）

| 指标 | gc_protect 版本 | temp_refs 版本 | 提升 |
|------|----------------|---------------|------|
| 总时间 (N=12, 1w) | 61.8s | 28.1s | **54.6%** |
| MK 时间 | 46.4s | 11.4s | 75.4% |
| MK gc/protect | 36.7s | 0.055s | 99.9% |
| nodetable avg_steps | 238.72 | 1.77 | - |

**关键改进**：用线程局部 `temp_ref_list` 替代全局 `gc_protect` 哈希表，消除高频保护操作开销。

### 1.2 无锁操作缓存

将 `pthread_rwlock` 替换为 `atomic_load/store_explicit`（CAS + seqlock 模式）。

- RECORDING=OFF: +3.78%
- RECORDING=ON: +2.33%

### 1.3 动态 rehashing

edge_map 和 nodetable 支持自动扩容（load_factor 触发，2x 扩容，对齐到 2 的幂次）。edge collision 稳定在 34.7%。

### 1.4 代码清理（de8b022）

- 合并 3 处重复 `round_up_pow2` 为 `static inline mtpndd_round_up_pow2`（`mtpndd_common.h`）
- 移除无用错误码、重复声明、dead code
- Debug-only 统计变量用 `#if` 守护
- 翻译中文注释为英文

---

## 2. Worker Wrapper 实验

### 2.1 背景

新版 Lace（Sylvan 2025）移除了 `LACE_ME` 宏。从非 worker 线程调用 `RUN()` 需经过慢速路径（`lace_resume` → mutex → semaphore → `lace_suspend`）。

`mtpndd_run_in_worker(callback, arg)` 将整个用户计算封装到一个 Lace worker 任务中，使后续所有 `RUN()` 调用走快速路径（`lace_get_worker() != NULL`，直接函数调用）。

### 2.2 实验设计

在 `nqueens_benchmark` 中添加 `--worker-wrap` 标志，对比两种执行模式：
- **默认模式**：每次 `mtpndd_and()` 调用 `RUN(mtpndd_and_rec, a, b)`，主线程走慢速路径
- **Worker-wrap 模式**：`mtpndd_run_in_worker()` 将 `declare_fields + 构建公式 + satcount` 包裹在 worker 内，`mtpndd_init/quit` 在外部

### 2.3 实验结果（N=12, RECORDING=OFF, 2026-03-01）

| Workers | 默认 (s) | Worker-wrap (s) | 差异 |
|---------|---------|----------------|------|
| 1 | 12.87 | 12.81 | ~0% |
| 2 | 8.08 | 8.95 | ~0% |
| 3 | 6.33 | 6.29 | ~0% |
| 4 | 5.89 | 5.92 | ~0% |
| 5 | 5.06 | 5.04 | ~0% |
| 6 | 5.02 | 5.07 | ~0% |

### 2.4 结论

Worker-wrap **对当前架构几乎没有影响**。原因：

1. `mtpndd_and()` 已经将递归核心封装为 `RUN(mtpndd_and_rec, a, b)`——每次顶层 AND 调用只有 **一次** 慢速路径 RUN
2. 在 `mtpndd_and_rec` 内部，所有 SPAWN/SYNC 调用已在 worker 线程上下文中执行（快速路径）
3. 慢速路径开销（~微秒级/次）相对于总计算时间（秒级）可忽略

**对比 Sylvan 的做法**：Sylvan `examples/nqueens.c` 用 `VOID_TASK_0(run)` + `RUN(run)` 将**整个**计算包裹在 worker 中。但 Sylvan 的 `sylvan_and()` 本身不是一个 Lace task——它在内部递归中使用 SPAWN/SYNC。因此 Sylvan 必须从 worker 内部调用才能触发并行。MTPNDD 的架构不同：`mtpndd_and` 本身就是一个 `RUN()` 入口点。

**结论**：已验证无收益，`mtpndd_run_in_worker` API 已移除。对 JNI 场景同样无意义——Java 线程逐步调用 JNI（`and`/`or`/`not` 分别调），无法整体包裹进单个 worker 回调。

---

## 3. 并行扩展性基线

### 3.1 当前性能数据（N=12, RECORDING=OFF, MTPNDD_SPAWN_THRESHOLD=3, 2026-03-01）

| Workers | 时间 (s) | 解数量 | 相比 1-worker |
|---------|---------|--------|--------------|
| 1 | 12.79 | 14200 | baseline |
| 2 | 7.82 | 14200 | 1.64x |
| 3 | 5.53 | 14200 | 2.31x |
| 4 | 4.93 | 14200 | 2.59x |
| 5 | 3.82 | 14200 | **3.35x** |
| 6 | 3.94 | 14200 | 3.24x |

### 3.2 分析

- 1→2 workers: 加速 1.64x，合理
- 2→5 workers: 接近线性扩展，5 workers 达 3.35x
- 5→6 workers: 轻微退化，达到 Amdahl 极限或调度开销抵消收益
- 相比 threshold=64（5 workers 2.58x），降低阈值到 3 后并行度提升显著（+30%）
- 相比历史数据（1 worker 最优，多 worker 更慢），当前版本并行扩展性大幅改善

---

## 4. 待实施优化方案

### 4.1 Spawn 策略优化（已部分实施）

**现状**：`mtpndd_should_spawn()` 使用 `edges_a * edges_b >= MTPNDD_SPAWN_THRESHOLD` 决定是否 SPAWN 子任务。

**已完成**：
- 将硬编码 `64` 提取为编译期宏 `MTPNDD_SPAWN_THRESHOLD`（`mtpndd_common.h`），默认值调整为 3
- 可通过 `-DMTPNDD_SPAWN_THRESHOLD=16` 调整

**阈值对比实验（N=12）**：

| Workers | threshold=64 (s) | threshold=3 (s) | 提升 |
|---------|-----------------|----------------|------|
| 1 | 12.82 | 12.79 | ~0%（串行无变化） |
| 2 | 8.03 | 7.82 | +3% |
| 3 | 6.22 | 5.53 | +12% |
| 4 | 5.88 | 4.93 | +16% |
| 5 | 4.97 | 3.82 | **+24%** |
| 6 | 5.02 | 3.94 | **+22%** |

结论：threshold=64 过于保守，大部分子问题 edge 乘积 < 64 导致未被并行化。threshold=3 使更多子任务参与并行，5 workers 从 2.58x 提升到 3.35x。

**Sylvan 的 granularity 参数对比**：
- Sylvan 的 `sylvan_set_granularity(g)` 控制的是**操作缓存使用频率**（`prev_level / g != level / g` 时才查/写缓存），不是 spawn 决策
- MTPNDD 的 spawn 决策基于 edge map 大小乘积——更接近于工作量估算
- 两者解决不同问题：Sylvan 减少缓存开销，MTPNDD 控制任务粒度

**后续实验方向**：
- 测试更多阈值（1, 2, 4, 8, 16）找到最优点
- 考虑引入类似 Sylvan 的缓存频率控制到 MTPNDD 操作缓存

### 4.2 OR/NOT 并行化（De Morgan 律）

**现状**：`mtpndd_or` 和 `mtpndd_not` 为纯串行递归实现，不使用 Lace 任务。

**方案**：利用 De Morgan 律将 OR 转化为 AND：
```
a ∨ b = ¬(¬a ∧ ¬b)
```

这样 OR 操作可复用 `mtpndd_and` 的并行实现。NOT 仍为 O(n) 遍历但通常节点较少。

**预期收益**：
- 对 OR-heavy 工作负载（如 N-Queens 的 `build_row_at_least_one`）可获得并行加速
- 无需为 OR 单独实现 Lace task

**注意事项**：
- 需要额外两次 NOT 操作的开销
- 需要 benchmark 对比直接 OR vs De Morgan 转换的实际效果
- 如果 NOT 本身成为瓶颈，需要并行化 NOT

### 4.3 操作缓存分区

**现状**：全局操作缓存，CAS + seqlock 无锁模式，命中率 ~12.7%。GC 时全清。

**方案**：按 field_id 或哈希分区，每个分区独立：
- 减少多线程 CAS 竞争
- GC 时可选择性清除（只清理被 GC 影响的 field 的分区）
- 提高缓存局部性

**预期收益**：中等。当前命中率本身较低，分区主要改善并发扩展性而非命中率。

### 4.4 读写锁优化（Nodetable）

**现状**：Nodetable 使用 spinlock-sharded buckets。锁粒度细但在高并发下仍有竞争，特别是 `nodetable_edges_equal()` 持锁期间执行 O(edge_count) 比较。

**历史尝试**：曾尝试 `pthread_rwlock`，但锁创建开销过高（每个 bucket 一个 rwlock）。

**方案**：
- 实现轻量级读写锁（基于 atomic 的 seqlock 或 ticket-based rwlock）
- 读路径免锁或极低开销，写路径独占
- 减少 `find_node_in_nodetable` 读取时的锁竞争

**优先级**：中低。需要高效的锁实现才有意义。

### 4.5 GC 优化

**现状**：`gcOrGrow()` 顺序锁定所有 table shards，全局 stop-the-world。

**方案**：
- 分段 GC：每次只回收部分 field 的节点
- 增量标记：在操作间隙进行标记，减少 stop-the-world 时间
- 或借鉴 Sylvan 的 GC 策略（worker 协作式 GC）

**优先级**：低。当前 GC 停顿在 N=12 基准测试中占比很小（0.055s/28s）。

### 4.6 Cached Hash 原子化（并行版本适配）

**现状**：`cached_hash` 为普通 `uint64_t`，串行版本已验证 4.3x 加速（N=10），并行版本因竞态条件导致严重退化。

**方案**：使用 `_Atomic(uint64_t)` + `atomic_fetch_xor_explicit` 实现线程安全的增量哈希更新。详见 `docs/parallel_optimization_proposals.md` 方案 A。

**优先级**：高。已有完整设计，实现简单，预期收益显著。

---

## 5. 两套 NQueens 实现对比

### 5.1 nqueens.c（功能测试）

- **用途**：正确性验证 / smoke test
- **Worker 配置**：`.n_workers = 0`（自动）
- **构建方式**：`build_nqueens_formula()` 内联构建，顺序调用 `mtpndd_and`/`mtpndd_or`
- **特点**：
  - `build_row_at_least_one`: 逐列 OR
  - `build_cell_implication`: 对每个 (row, col) 构建约束，使用 `mtpndd_get_not_var` + `mtpndd_or`（隐含蕴含）
  - 每步调用一次顶层 `mtpndd_and`，整体串行

### 5.2 nqueens_benchmark.c（性能基准）

- **用途**：性能回归测试，并行扩展性评估
- **Worker 配置**：通过命令行参数指定
- **构建方式**：预构建 `or_batch[n]` 和 `imp_batch[n*n]`，然后顺序 AND
- **特点**：
  - `build_cell`: 分四个方向（列/行/两条对角线）构建约束
  - `make_implication`: `¬guard ∨ consequence`
  - 两阶段：先独立构建所有子公式（可并行化的自然点），再顺序合并
  - 支持 `--worker-wrap` 模式

### 5.3 对比 Sylvan 的 NQueens

Sylvan `examples/nqueens.c`：
- 用 `VOID_TASK_0(run)` 包裹整个计算，`RUN(run)` 从 main 调用
- 所有 `sylvan_and`/`sylvan_or` 在 worker 内直接调用
- `sylvan_set_granularity(3)` 控制缓存频率
- `sylvan_and` 内部使用 `bdd_refs_spawn(SPAWN(...))` 并行化递归

关键差异：Sylvan BDD 操作的并行性在**BDD 变量层级**递归中展开（二叉树），而 MTPNDD 的并行性在**edge map 笛卡尔积**中展开（多路图）。

---

## 6. 编译期配置参数

| 宏 | 默认值 | 说明 |
|----|--------|------|
| `MTPNDD_LOG_LEVEL` | 1 | 0=QUIET, 1=INFO, 2=DEBUG |
| `MTPNDD_AND_PENDING_FLUSH_THRESHOLD` | 256 | SPAWN 批量刷新阈值 |
| `MTPNDD_SPAWN_THRESHOLD` | 3 | edge 乘积 ≥ 此值才 SPAWN |
| `LARGE_NODETABLE` | (未定义) | 启用大 nodetable bucket 数 |

---

## 更新记录

- 2026-03-01: 初始版本。worker-wrap 实验、spawn 阈值提取、并行扩展性基线、优化路线图
