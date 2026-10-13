# Edge Map cached_hash：减少哈希/比较的随机内存访问

**对应提交:** `cb3599d` (2026-01-10) `feat: edge map cached hash value for fast lookup (like Java HashMap)`
**目标:** 在 `mk` / nodetable lookup 的热点路径上减少“重复哈希计算 + 深比较”的成本（尤其是 cache miss 与指针追逐）。

## 背景：为什么 edge_map 比较很贵

在 MTPNDD 中，节点的“结构身份”由其 `edges`（edge_map：child->label 的映射）决定。`mtpndd_mk` 的核心是：
1) 计算 edges 的 hash（用于 nodetable 桶定位）
2) 在 nodetable bucket 内查找等价 edges（必要时做深比较）

如果没有 cached_hash：
- 每次需要 hash 时都要遍历 edge_map，包含大量随机读（bucket 链表、child 指针、label 指针）
- bucket 内深比较会做更多随机访问

## 方案：cached_hash + 快速拒绝（像 Java HashMap）

设计要点：
- 在 `mtpndd_edge_t` 中新增 `cached_hash` 字段，用于缓存 edge_map 的 hash。
- 在需要做“等价判断”前，先比较：
  - `cached_hash`（不相等可直接判 false）
  - `edge_count`（不相等可直接判 false）
- 只有在 hash 和 count 都相等时，才进入逐条边的深比较。

涉及代码（代表性）：
- `sylvan/src/sylvan/mtpndd/mtpndd_node.h`
  - `mtpndd_edge_t.cached_hash`
- `sylvan/src/sylvan/mtpndd/mtpndd_node.c`
  - `mtpndd_edge_map_init` / `mtpndd_edge_map_reset` 初始化/清空 `cached_hash`
  - 深拷贝 `mtpndd_edge_map_deep_clone` 传播 `cached_hash`
- `sylvan/src/sylvan/mtpndd/mtpndd_nodetable.h`
  - 在 `nodetable_edges_equal` 中先用 `cached_hash` 与 `edge_count` 快速拒绝

## 为什么这是“减少内存访问次数”的优化

对热点路径的影响：
- `cached_hash` 把 “O(#edges) 的 hash 遍历” 变为 “O(1) 读一个字段”
- 快速拒绝让大量“不等价”的候选不再触发深比较，从而减少 bucket 链表遍历与 label/child 的随机读

这类优化的收益通常体现为：
- `nodetable_hash_ns` 下降（hash 阶段更快）
- `nodetable_edge_compare_ns` 下降（深比较更少/更短）
- `nodetable_bucket_scan_ns` 的有效工作占比更高（少做无用比较）

## 观测指标（来自 `docs/PERFORMANCE_ANALYSIS.md` 示例）

在 N=12 的一组统计中（RECORDING=ON）：
- `nodetable hash/bucket/compare = 0.172 / 3.031 / 0.600 s`
- `avg_steps=1.77`，`max_steps=12`

这些指标可用于在论文中展示：
- hash 与 compare 各自占比
- cached_hash 带来的“深比较减少”趋势（需要对比启用前后的数据）

## 复现实验建议（论文复现）

建议对比两类版本：
1) 无 cached_hash 或不做快速拒绝
2) cached_hash + edge_count 快速拒绝

复现方式：
- 固定 N=12，固定 worker 数，重复 3 次取中位数
- 输出并记录：`nodetable_hash_ns` / `nodetable_bucket_scan_ns` / `nodetable_edge_compare_ns` / `avg_steps`
