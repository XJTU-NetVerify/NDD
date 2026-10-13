# 无锁 Operation Cache：用原子读写替代 pthread_rwlock

**对应提交:**
- `dacc55c` (2026-01-10) `perf: replace pthread_rwlock with lock-free atomic operations in operation cache`
- `1d9d3aa` (2026-01-10) `perf: increase operation cache size for better hit rate`

**目标:** 降低缓存访问的锁开销与共享状态争用，提升 `and/or/not` 的重复子问题复用效率。

## 背景：为什么 operation cache 是瓶颈候选

在 NQueens N=12 中，`mtpndd_and` 会大量重复访问操作缓存：
- 命中：直接复用结果，避免递归与 mk
- 未命中：继续计算，并在返回时写回缓存

如果缓存实现依赖 `pthread_rwlock`：
- 每次 lookup/store 都会触发锁相关的共享内存访问（读写锁元数据 + futex 路径）
- 多 worker 下，锁竞争会让缓存从“加速器”变成“共享热点”

## 方案 A：无锁单槽缓存（lock-free, direct-mapped）

实现位置：
- `sylvan/src/sylvan/mtpndd/mtpndd_operation_cache.c`
- `sylvan/src/sylvan/mtpndd/mtpndd_operation_cache.h`

核心设计：
- cache 是 direct-mapped（一个 hash index 对应一个 slot）
- slot 里存 `operands[]` 与 `result`，都用 `_Atomic` 指针
- 写入顺序保证一致性：
  1) 先写 operands（relaxed）
  2) 最后写 result（release），把 result 作为“entry 有效”的标志
- 读取顺序（acquire）：
  - 先读 operands + result，若 operands 匹配且 result 非 NULL，则 hit

这是一种典型的“单槽无锁缓存”策略：
- 不保证强一致（可能覆盖/丢失），但对性能而言可接受
- 完全避免锁的元数据争用

## 方案 B：增大 cache 容量提升命中率

对应提交 `1d9d3aa` 的说明（commit message）：
- 将 operation cache 从 16,384 增加到 524,288（32x）
- NQueens N=12, Worker=1：命中率从约 70-80% 提升到 90%+，性能提升约 3-4%

## 性能结果（来自 `docs/PERFORMANCE_ANALYSIS.md` 汇总）

无锁操作缓存的收益（同一份报告中的总结）：
- RECORDING=OFF 提升 **3.78%**
- RECORDING=ON 提升 **2.33%**

> 该优化主要减少了“锁本身”和“锁导致的额外 cache miss”成本；对高频 cache lookup/store 的工作负载更明显。

## 设计权衡

优点：
- lookup/store 不再触发 rwlock，减少共享状态争用
- 访问路径更短（少一次锁调用链与潜在 futex）
- 更容易扩展到多 worker（争用主要来自数据本身而非锁）

缺点 / 风险：
- direct-mapped + overwrite：冲突时会覆盖旧值（命中率受 hash 与容量影响）
- 对极端并发场景，仍可能出现“读到新 operands 但旧 result”类的弱一致窗口，因此写入顺序必须严格保证（result 最后 release）

## 复现实验建议（论文复现）

建议记录以下组合（每组至少 3 次取中位数）：
```bash
taskset -c 0   ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
taskset -c 0-3 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```

并记录：
- `cache hits/misses`、`cache stores/overwrites`
- `and_cache_hit_ns`（若 RECORDING=ON）
- perf/strace 观察 rwlock/futex 是否下降（锁版本 vs 原子版本）
