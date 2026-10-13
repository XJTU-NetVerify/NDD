# MTPNDD 性能分析报告：C实现 vs Java版本

## 1. 结论与当前目标（最新更新：2026-01-08）

### 1.1 核心结论
- **版本差异**：Sylvan/Lace 版本差异是当前性能差距的主因。基于对比，**bundled Lace 的版本表现最好**。
- **gc_protect 优化**：移除 `gc_protect` 集合并改为临时节点 `ref/deref` 后，NQueens 性能显著提升（61.8s → 28.1s，提升 54.6%）。
- **串行化收益**：从并行任务改为串行实现，消除 LACE 同步开销，节点表查找平均步数从 238.72 降至 1.77。

### 1.2 当前性能基线（feature/c 并行版本，代码清理 + SPAWN_THRESHOLD=3）
- **测试环境**：NQueens N=12，6 CPU 核心
- **性能指标（RECORDING=OFF, 2026-03-01）**：
  - 1 worker：12.79s
  - 2 workers：7.82s
  - 3 workers：5.53s
  - 4 workers：4.93s
  - 5 workers：3.82s（**当前最佳**）
  - 6 workers：3.94s
- **对比优化前（gc_protect 版本）**：
  - 总时间：61.8s → 12.79s（1 worker, 提升 **79.3%**）
  - 总时间：61.8s → 3.82s（5 workers, 提升 **93.8%**）
- **并行扩展性**：1→5 workers 加速 3.35x

### 1.3 下一步目标
1. **性能对比**：对比三套 NDD 实现的关键步骤耗时（MTPNDD / Java-JDD / Java-JSylvan）
2. **瓶颈分析**：重点分析 **BDD 调用**（7.0s）和 **节点表查找**（4.0s）的优化空间
3. **缓存优化**：研究操作缓存命中率（12.7%）提升方案
4. **并行恢复**：在保持 temp_refs 优化的基础上，研究如何安全地重新引入并行

### 1.4 约束
- 目前不引入"BDD 边复用"，保持与 `reference/NDD` main 分支一致的行为
- 保留 operation_cache 的 pthread_rwlock，支持未来并行执行

## 2. 关键原因（版本级差异）
1. **旧版 Sylvan 使用 `CALL` + `LACE_ME`**：在旧版 Lace 中，`LACE_ME` 允许当前线程作为 worker 执行，`CALL` 路径接近函数调用。
2. **新版 Sylvan 使用 `RUN`**：`RUN` 在外部线程路径上会触发 `lace_run_task` 的同步开销（resume/suspend、锁、信号量），在 NQueens 高频 BDD 调用下成本显著累积。
3. **JSylvan 新版适配移除 `LACE_ME`**：在新 Lace 语义下无法在 Java 线程创建 worker 上下文，导致所有操作走慢路径。

## 3. MTPNDD 当前基线（NQueens N=12）
- 命令：`./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_benchmark 12`（`MTPNDD_LOG_LEVEL=2`）
- 结果：solutions=14200，总耗时 **28.143s**
- AND 时间分解：
  - and/or/not 总时间：27.822 / 0.032 / 0.001 s
  - and 分解：fast=0.127，cache=2.924，build_edges=0.466，diff=0.670，mk=11.420 s
  - same-field 细分：outer=1.084，inner=0.847，label=0.742，bdd_op=7.034，add_edge=2.187 s (total=11.893)
- MK 内部分解：
  - mk call/cache/other：7.793 / 8.975 / 0.028 s
  - mk 细分：hash=0.720，lookup=4.046，fast=0.016，reuse=0.928，ref=0.141，gc=0.055，alloc_node=0.120，alloc_entry=0.147 s
  - mk scan/link/collision：0.120 / 0.678 / 0.000 s
  - mk other/total：0.260 / 7.230 s
- 节点表查找细分：
  - nodetable hash/bucket/compare = 0.172 / 3.031 / 0.600 s
  - lookup hits/misses = 7,650,659 / 1,848,510，avg_steps=1.77，max_steps=12
  - edge_entries = 11,290,059

> 说明：本次统计不包含 `satcount` 的转换成本。

## 3.1 Worker 数量对性能的影响（N=12, RECORDING=OFF）

### 测试配置
- CPU 核心数：6
- 测试方法：每个 worker 数运行 3 次取平均值
- 代码版本：feature/c (temp_refs 优化后)

### 测试结果（旧数据，代码清理前）

| Workers | 平均时间 (s) | 最小值 | 最大值 | 相比 0-worker |
|---------|--------------|--------|--------|---------------|
| 0       | 28.002       | 27.264 | 28.874 | baseline      |
| 1       | 25.422       | 25.391 | 25.464 | **+10.2%**    |
| 2       | 25.778       | 25.691 | 25.880 | +8.6%         |
| 3       | 26.078       | 25.816 | 26.564 | +7.4%         |
| 4       | 25.904       | 25.856 | 25.965 | +8.1%         |
| 6       | 27.711       | 27.426 | 27.964 | +1.0%         |
| 8       | 36.163       | 34.546 | 38.141 | **-22.6%**    |

### 测试结果（2026-03-01，代码清理 + MTPNDD_SPAWN_THRESHOLD=3）

| Workers | 时间 (s) | 相比 1-worker |
|---------|---------|--------------|
| 1       | 12.79   | baseline     |
| 2       | 7.82    | **1.64x**    |
| 3       | 5.53    | **2.31x**    |
| 4       | 4.93    | **2.59x**    |
| 5       | 3.82    | **3.35x**    |
| 6       | 3.94    | **3.24x**    |

### 关键发现（更新）

1. **并行扩展性大幅改善**：1→5 workers 加速 3.35x，对比旧数据（并行反而更慢）
   - spawn 阈值从 64 降至 3，更多子任务参与并行
   - 1 worker 基线从 25.4s 降至 12.8s（提升 ~50%）

2. **最佳配置变化**：5 workers 现在最优（3.82s），不再是 1 worker
   - 多线程并行产生显著收益

3. **5→6 workers 轻微退化**：Amdahl 极限或调度开销抵消收益

### 推荐配置
- **生产环境**：使用 4-5 workers 以获得最佳性能
- **开发/调试**：使用 1 worker + RECORDING=ON 以获取详细统计

## 3.2 优化前后对比（N=12）
| 指标 | gc_protect 版本 | temp_refs 版本 | 提升 |
|------|----------------|---------------|------|
| 总时间 | 61.848s | 28.143s | **54.6%** |
| AND 时间 | 61.484s | 27.822s | 54.8% |
| MK 时间 | 46.442s | 11.420s | **75.4%** |
| MK gc/protect | 36.697s | 0.055s | **99.9%** |
| gc_protect avg_steps | 238.72 | - | - |
| nodetable avg_steps | - | 1.77 | - |

**关键改进**：
- 移除全局 gc_protect 哈希表，改用局部 temp_ref_list
- 从并行任务改为串行实现，减少 LACE 同步开销
- 节点表查找平均步数从 238.72 降至 1.77

## 4. 性能剖析建议
```bash
perf record ./mtpndd_nqueens_test 10
perf report
# 或
valgrind --tool=callgrind ./mtpndd_nqueens_test 10
kcachegrind callgrind.out.*
```

## 5. 附录：已完成的优化与后续建议

### 5.0 已完成的核心优化（feature/c 分支）

#### 基础设施优化（2025-12）
- ✓ **Sylvan 版本升级**：更新到 v1.9.1 并集成 bundled Lace
  - 提交：734cd1e / 83c0c37
  - 收益：建立稳定的性能基线，支持后续优化

- ✓ **并行框架兼容**：让 MTPNDD 运行时完全兼容 bundled Lace
  - 提交：21dcf54 / 83831ee
  - 收益：消除版本差异导致的性能损失

#### 内存管理优化（2026-01）
- ✓ **移除 gc_protect 机制**：改用轻量级 temp_ref_list
  - 提交：56e7a78 / a27e901
  - 收益：**总时间提升 54.6%**（61.8s → 28.1s），MK 时间提升 75.4%
  - 详情：
    - 替换全局哈希表为局部数组，平均查找步数从 238.72 降至 1.77
    - 消除 gc_protect 的 36.7s 开销，降至 0.055s
    - 避免每次操作后清空保护集的固定成本

- ✓ **串行化实现**：移除 LACE 并行构造（TASK_IMPL, SPAWN, SYNC）
  - 提交：56e7a78 / a27e901
  - 收益：消除高频 BDD 调用下的 LACE 同步开销
  - 说明：保留 operation_cache 锁以支持未来并行

#### 数据结构优化（2026-01）
- ✓ **动态 rehashing**：edge_map 和 nodetable 支持自动扩容
  - 提交：f45a95b
  - 收益：减少哈希碰撞，edge collision 稳定在 34.7%
  - 实现：
    - edge_map: load_factor = 0.875，触发时扩容 2x
    - nodetable: load_factor = 0.75，触发时扩容 2x
    - 桶数量自动对齐到 2 的幂次

- ✓ **Reduction 规则修复**：单边 TRUE 归约和引用计数释放
  - 提交：bbab060
  - 收益：对齐 Java 行为，避免引用计数泄漏
  - 详情：
    - 正确处理唯一边标签为 TRUE 的情况
    - GC 释放节点时完整释放所有边的子节点

#### 统计与调试（2025-12 ~ 2026-01）
- ✓ **详细性能统计**：细化 AND/MK/nodetable 各阶段耗时
  - 提交：c0af93a / e93d922 / f89acce
  - 收益：精确定位性能瓶颈，支持优化决策
  - 指标：包含 hash/lookup/bdd_op/add_edge 等 20+ 维度

- ✓ **Edge map 缓存哈希值**：类似 Java HashMap 设计
  - 提交：48939bc / ea009da
  - 收益：减少重复哈希计算，提升边比较效率

- ✓ **无锁操作缓存**：使用原子操作替代 pthread_rwlock（2026-01）
  - 提交：e9b3e69
  - 收益：消除锁开销，RECORDING=OFF 提升 3.78%，RECORDING=ON 提升 2.33%
  - 详情：
    - 将缓存 entry 改用 _Atomic 指针
    - 使用 atomic_load/store_explicit 替代锁
    - 移除 ~100 行锁管理代码
    - 更好的缓存局部性和可扩展性

### 5.1 操作缓存
- 命中率低但可接受（约 12.7%），当前不作为优化目标。

### 5.2 碰撞与 rehash
- edge collision 约 34.7%，nodetable collision 约 1273 次；本轮不作为主线目标。
- 后续如需验证：调整 `edge_bucket_count` 或 rehash 阈值后对比 `edge_collisions` 与 `and_*` 时间。

### 5.3 待评估的优化方向

#### 高优先级（可能带来显著收益）
1. **BDD 调用优化**（当前耗时 7.0s，占 AND 时间 25%）
   - 研究 BDD 操作的批量化执行
   - 考虑缓存常用 BDD 表达式
   - 分析 Sylvan BDD 调用开销的来源

2. **操作缓存命中率提升**（当前 12.7%）
   - 分析缓存失效模式
   - 研究哈希函数优化
   - 考虑多级缓存或分区缓存

3. **并行执行恢复**
   - 在保持 temp_refs 优化的基础上
   - 研究粗粒度并行（如按 field 并行）
   - 需要细致的临界区设计以避免 gc_protect 问题

#### 中优先级（可能带来中等收益）
4. **节点表查找优化**（当前耗时 4.0s）
   - 研究更高效的哈希函数
   - 考虑 SIMD 加速边比较
   - 优化桶扫描的内存访问模式

5. **减少 BDD 引用计数操作**
   - 批量 ref/deref 或复用局部 label
   - 研究引用计数的延迟更新策略

6. **缓存行对齐**
   - 关键数据结构按 64 字节对齐
   - 减少多线程场景下的 false sharing

#### 低优先级（精细调优）
7. **分支预测优化**
   - 热点路径添加 `__builtin_expect` 提示
   - 重排条件判断顺序

8. **边合并优化**
   - 小边集合用线性扫描合并
   - 减少排序/去重的固定成本

### 5.4 相关代码位置
| 模块 | 文件 | 关键函数 |
|------|------|---------|
| 操作缓存 | `sylvan/src/sylvan/mtpndd/mtpndd_operation_cache.c` | `lookup_binary`, `store_binary` |
| AND 操作 | `sylvan/src/sylvan/mtpndd/mtpndd_node.c` | `mtpndd_and_rec` |
| OR/NOT | `sylvan/src/sylvan/mtpndd/mtpndd_node.c` | `mtpndd_or_rec`, `mtpndd_not_rec` |
| 节点创建 | `sylvan/src/sylvan/mtpndd/mtpndd_nodetable.c` | `mtpndd_mk` |
| GC Protect | `sylvan/src/sylvan/mtpndd/mtpndd_common.c` | `mtpndd_gc_protect_*` |
| NQueens 测试 | `sylvan/src/sylvan/mtpndd/test/nqueens.c` | `run_case` |
