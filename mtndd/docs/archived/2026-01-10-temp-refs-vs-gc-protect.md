# temp_refs vs gc_protect：临时引用列表替代全局保护集

**对应提交:** `442c4c0` (2026-01-10) `refactor: remove gc_protect and switch to temp_refs for memory management`
**目标:** 在构造/组合 MTPNDD 节点（尤其 `and/mk` 热路径）时，减少内存访问与同步/清理开销，提升 N=12 NQueens 性能。

## 背景：gc_protect 的问题是什么

早期实现用一个全局 `gc_protect`（哈希集合/表）来“保护”中间节点，避免在构造过程被 GC 回收。这个方案在高频构造/查找下有两个主要成本：
- **访问成本高**：保护集的插入/查找是哈希结构，伴随指针追逐、冲突链遍历、cache miss。
- **清理成本固定且大**：每次阶段结束都需要清空/维护保护集，成本随“中间节点数量”线性增长。

在 `docs/PERFORMANCE_ANALYSIS.md` 里，gc_protect 版本的 MK 时间里存在极大的 “gc/protect” 开销（见下文对比数据）。

## 方案：用 temp_refs（局部数组）承接生命周期

核心思路是“把保护从全局数据结构，缩小到单次操作的局部作用域”：
- 在一次 `mtpndd_and_rec`（以及 OR/NOT/EXIST 等）构造过程中，维护一个 `temp_refs` 列表（动态数组）。
- 每当产生一个中间 `mtpndd_t*` 结果，就 `mtpndd_ref(node)` 并 push 到 `temp_refs`。
- 当本次构造完成（成功 mk，或失败回滚）时，统一遍历 `temp_refs` 做 `mtpndd_deref`，然后释放数组。

实现位置（代表性）：
- `sylvan/src/sylvan/mtpndd/mtpndd_node.c`
  - `mtpndd_temp_ref_list_t` / `mtpndd_temp_refs_push` / `mtpndd_temp_refs_release`
  - `mtpndd_and_rec` 在 build_edges 阶段把中间 child 统一进 `temp_refs`

## 为什么这能减少“内存访问次数”

对比 gc_protect（哈希集合）：
- temp_refs 是 **线性数组**，push 是顺序写入，cache 友好。
- release 是一次线性扫描（顺序读），cache 友好。
- 不需要哈希计算、桶定位、链表/探测等随机内存访问。

额外收益：
- “保护”逻辑局部化后，不再需要全局结构的锁/同步（并行环境下尤其关键）。

## 前后实验对比（来自 `docs/PERFORMANCE_ANALYSIS.md`）

NQueens N=12（统计口径见原文）：

### 总时间与关键阶段

- 总时间：**61.848s → 28.143s（+54.6%）**
- AND 时间：**61.484s → 27.822s（+54.8%）**
- MK 时间：**46.442s → 11.420s（+75.4%）**

### “gc/protect” 成本几乎消失

- MK gc/protect：**36.697s → 0.055s（~99.9%）**

### 结构性指标变化

- gc_protect avg_steps：**238.72**
- nodetable avg_steps（temp_refs 版本下的查找行为）：**1.77**（用于说明“热点查找”从高步数结构转为更短路径）

> 注：上面 avg_steps 的对比来自同一份分析报告的汇总，用于定性说明“高步数/高随机访问的结构被替换”这一趋势。

## 对比思考：temp_refs vs “全局 GC root/保护集”

temp_refs 的适用边界：
- 适合“本次构造过程的临时对象生命周期”，一旦构造成功并被挂到最终节点/缓存里，应该回归常规 ref-count 语义。
- 不适合跨越多个独立操作的长期保护（长期保护应显式 `mtpndd_ref` 或由上层持有）。

如果未来要恢复更强并行：
- temp_refs 的 push 是局部数组写入，天然更容易做成 per-task/per-worker buffer（比全局哈希集更易扩展）。

## 复现实验建议（论文复现）

建议至少记录：
- 命令：`./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_benchmark 12`（RECORDING=ON）或对应测试入口
- 输出：AND/MK 分解统计、cache hit/miss、nodetable hash/bucket/compare
- 环境：CPU 核心数、worker 数、dqsize、编译开关（RECORDING on/off）
