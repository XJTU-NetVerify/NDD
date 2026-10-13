# 动态 Nodetable 扩容与 Rehash：控制碰撞与避免 GC/扩容抖动

**对应提交:** `1a60d81` (2025-11-12) `feat(mtpndd): Implement dynamic nodetable growth`
**目标:** 在 NQueens N=12 等大规模构造场景中，避免 nodetable 负载过高导致的碰撞与查找退化，并在必要时触发 grow/GC，使运行更稳定。

## 背景：固定容量的 nodetable 会在 N=12 下退化

`mtpndd_mk` 需要在 nodetable 中按 edges 查找/插入节点。如果 bucket 数不足或负载因子过高：
- bucket 链表变长（碰撞多），导致 lookup 退化
- 更多深比较（访问 edge_map/label/child），随机内存访问增多
- 构造阶段时间显著上升，且波动更大

## 方案：按负载阈值 rehash + 按容量阈值 grow

核心流程（代表性实现位于 `sylvan/src/sylvan/mtpndd/mtpndd_nodetable.c`）：
- `mtpndd_nodetable_maybe_rehash`：
  - 当 `entry_count >= load_threshold` 时，将 bucket 数扩为 2x 并 rehash
  - `load_threshold` 通常设置为 `bucket_count * 0.75` 附近
- `gcOrGrow`：
  - 当 `node_count > mtpndd_nodetable_size`，触发一次 GC
  - 如果 GC 后剩余容量不足（用 `quick_growth_threshold` 判断），则执行 `grow_internal`：
    - 对每个 field 的 nodetable 执行 rehash 到更大 bucket 数
    - 同步提升 `mtpndd_nodetable_size`

并行环境注意点（当时的策略）：
- grow/GC 期间通过 `lace_suspend()` 暂停 worker，结束后 `lace_resume()`，避免并发修改结构。

## 为什么这是“减少内存访问/提高局部性”的优化

rehash 的根本作用是缩短 bucket 链表：
- 查找时更少指针跳转
- 更少深比较触发（尤其当 cached_hash/edge_count 快速拒绝存在时）
- 减少 cache miss 与分支预测失误

## 可观测指标（建议记录）

运行时统计（RECORDING=ON）可用于量化效果：
- `nodetable_collision_total`
- `nodetable_lookup_hits/misses`
- `avg_steps` / `max_steps`
- `nodetable_rehash_total` / `nodetable_max_buckets`

`docs/PERFORMANCE_ANALYSIS.md` 中给出了示例（N=12 时 avg_steps 约 1.77，max_steps=12），说明在合理 bucket 数下查找可维持较短路径。

## 复现实验建议（论文复现）

对比两组参数：
1) 固定 bucket 数（不 rehash / 不 grow）
2) 启用 rehash + grow（当前策略）

建议固定：
- N=12
- worker 数（0/1/4）
- op_cache_size
- edge_bucket_count / nodetable_bucket_count 初始值

观察：
- 总时间 + mk 细分（hash/lookup/compare）
- 碰撞率与 avg_steps 的变化趋势
