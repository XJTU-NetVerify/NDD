# memset + 对齐：降低初始化与跨 cacheline 访问的微优化

**对应提交:** `5e17821` (2025-12-27) `feat: memset optimize and doc for other optimization analysis`
**目标:** 在热点数据结构初始化与复制路径上减少循环开销与 cacheline 跨越，改善常数项。

## 背景：bucket 初始化/清空很频繁

在 edge_map/nodetable 等哈希结构中，常见操作包括：
- 创建新 map 时清空 buckets
- clone/reset 时清空 buckets

如果用 for 循环逐个置零：
- 分支与循环控制有额外开销
- 编译器未必能生成最优的 memset/memclr 指令序列

## 方案 1：用 memset 代替逐桶循环

典型改动点（示例）：
- `sylvan/src/sylvan/mtpndd/mtpndd_node.c`
  - `mtpndd_edge_map_init` / `mtpndd_edge_map_deep_clone` / `mtpndd_edge_map_reset`
  - 使用 `memset(edges->buckets, 0, bucket_cnt * sizeof(...))`

收益预期：
- 简化指令路径，通常更容易命中 libc/编译器的优化实现
- 对大 bucket 数（例如 2^k）尤其明显

## 方案 2：热数组按 cacheline 对齐，减少跨 cacheline 访问

对于频繁访问的“指针数组”，对齐可减少跨 cacheline 带来的额外访存：
- `node_tables_by_field`（field_id -> nodetable 指针）在 mk 热路径上会被频繁索引

实现要点（示例）：
- `posix_memalign(&ptr, 64, bytes)` 分配 64B 对齐数组
- 扩容时用 `memcpy` 搬迁旧内容，再对新增区间 `memset` 清零

该类优化通常不改变算法复杂度，但能改善：
- cacheline 命中率
- false sharing 风险（多线程时更关键）

## 复现实验建议（论文复现）

微优化通常需要更严格的测量方法：
- 固定 CPU 频率/绑定核心（`taskset -c`）
- 重复运行取中位数
- 使用 `perf stat` 观察 `task-clock`、cache miss（若可用事件）变化

建议用 NQueens N=12 作为固定 workload，同时观察：
- 初始化阶段（init/ctx）时间变化
- `mk_hash_ns`/`nodetable_hash_ns` 等常数项是否下降（RECORDING=ON）
