# Slab Memory Pools：用批量分配降低 malloc/free 与提升局部性

**相关提交:**
- `8d6c391` (2025-11-04) `feat(memory): Introduce slab memory pools`
- `21a4f43` (2025-11-19) `refactor(mtpndd): Streamline node & memory management`
- `b8ae1d2` (2025-11-20) `refactor(memory): Streamline pool & locking`

**目标:** 在 MTPNDD 高频构造阶段（NQueens N=12 等）减少系统分配器压力，降低碎片与锁开销，并提升数据局部性。

## 背景：为什么直接 malloc/free 会慢

在 `mtpndd_and`/`mtpndd_mk` 热路径中，频繁创建/释放的对象包括：
- `mtpndd_node_t`（节点）
- `edge_bucket_entry_t`（边条目）
- `mtpndd_nodetable_bucket_entry_t`（nodetable 条目）
- `mtpndd_edge_t`（edge map 容器）

若直接使用 `malloc/free`：
- 分配器元数据访问频繁（cache miss + 线程竞争）
- 产生碎片，长期运行下性能不稳定
- 对象分散在内存中，遍历时局部性差

## 方案：slab block + free list

实现位置：
- `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.c`
- `sylvan/src/sylvan/mtpndd/mtpndd_memory_pool.h`

核心结构：
- 每个 pool 维护：
  - `blocks`：slab 链表（每次 grow 分配一整块）
  - `free_list`：可用对象链表（对象本身首字保存 next 指针）
  - `object_size` / `objects_per_slab`：控制 slab 布局

关键路径：
- acquire：
  - free_list 非空：pop 一个对象（O(1)）
  - free_list 为空：grow 分配新 slab，并把 slab 中对象全部串进 free_list
- release：
  - push 回 free_list（O(1)）

## 重要细节：edge_map “对象 + bucket 数组”合并分配

edge_map 传统实现会额外 `malloc(buckets[])`，导致：
- 每个 edge_map 至少两次分配（容器 + buckets）
- buckets 指针追逐更重，局部性更差

本项目的实现采用“合并分配”：
- pool 的 object_size 不是 `sizeof(mtpndd_edge_t)`，而是：
  - `sizeof(mtpndd_edge_t)` + 对齐后的 `sizeof(edge_bucket_entry_t*) * bucket_count`
- `edges->buckets` 指向对象内部的 bucket 数组区域（通过预计算 offset）

对应代码要点（当前实现）：
- `g_edge_bucket_array_offset`：对齐后的 buckets 起始偏移
- `mtpndd_memory_acquire_edge_map()`：把 `edges->buckets` 指向 `base + offset`

收益：
- 少一次 malloc/free
- buckets 更连续，遍历 bucket 时 cache 更友好

## 观测与统计

在 RECORDING 打开时，测试会打印 pool 统计（示例字段）：
- node slabs / in_use / slabCap
- edge_entry slabs / in_use

这类统计适合在论文中说明：
- slab 机制把“分配次数”从 O(#objects) 下降到 O(#slabs)
- 内存增长呈现阶梯状（每次 grow 一块）

## 后续演进：从“全局锁 slab”到“per-worker caches”

slab pools 是基础设施优化；在并行场景下进一步的优化是：
- per-worker 本地 free_list（无锁 fast path）
- 全局池仅用于批量 refill/spill 与扩容

该优化已单独记录在：
- `docs/archived/2026-01-28-per-worker-slab-pool-caches.md`
