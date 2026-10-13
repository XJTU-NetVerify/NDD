# AND 任务粒度“加粗”(outer-chunk) 并行化尝试：修改方案与评估方法（分析稿）

**Date:** 2026-01-28
**Status:** analysis / proposal (intended for an experiment branch)
**Scope:** 只讨论 `mtpndd_and` 的任务粒度设计与如何评估它是否有效；不在本文直接落代码。

## 1. 背景：为什么要考虑“加粗任务粒度”

当前 `mtpndd_and` 的并行路径已经能在 <=4 核看到一定收益，但仍存在两个风险：

1) **任务过细 -> Lace 调度开销上升**
如果把子问题切得太碎（例如 per-(edge_a, edge_b) 级别），任务数量可能随 `|A|*|B|` 增长，导致：
- Tasks 数大、Steals/Leaps 频繁
- 更多 `SPAWN/SYNC` 固定成本
- 更高的共享结构压力（nodetable/op cache/内存池/edge_map 相关）

2) **NQueens 不足以证明普遍有效**
NQueens 是结构高度规则、且并行空间有限的实例：即使实现正确，多核收益也可能被 “共享结构 + 调度 + 内存带宽” 抵消。
因此需要一个更“可控/可解释”的 benchmark 组合来评估“任务粒度策略”本身的好坏。

## 2. 问题定义：我们要优化什么指标

对“任务粒度”优化，核心不是只看 wall time，而是要看**并行效率**与**系统开销占比**：

- **吞吐/延迟**：`mtpndd_nqueens_test 12` 的 run time（仍然要保正确性 solutions=14200）
- **并行效率**：`perf stat` 的 CPUs utilized（4 核 pin 时希望接近 3~4）
- **Lace 开销**：Tasks/Steals/Leaps 以及 “Tasks per steal”
- **共享结构压力**：`and mk ...`、`mk lookup/ref/alloc ...`，以及锁/等待行为（可用 perf/strace 辅助）

“加粗任务粒度”期待的现象是：
- Tasks 总数显著下降，但 Steals 仍足够（有 stealable slack）
- Leaps tries 降低（少在等待 stolen task 上忙等）
- wall time 更稳定、且 4 核收益更接近线性（至少不出现多核更慢）

## 3. 关键约束：必须保证的语义与实现边界

本尝试必须保持以下约束（否则会把“任务粒度问题”变成“并发写共享结构问题”）：

1) **`res_edges` 和 `temp_refs` 不并发写**
先坚持串行 merge：所有任务返回 item 列表，父线程统一 merge 到一个 edge_map。

2) **任务返回的 child/label 必须具备正确的生命周期**
任务产生的 `(child,label)` 在 merge 前必须被保护：
- `child` 必须 `mtpndd_ref`（避免 GC/回收或 refcount 归零）
- `label` 必须 `sylvan_ref`
父线程 merge 后负责接管/释放，错误路径必须逐个清理，避免泄漏。

3) **避免改变缓存语义**
AND 是交换律操作，但当前实现未必做了 `(a,b)` 的 canonicalization。
为了不引入“cache hit 率变化”干扰评估，本尝试**尽量不交换 `a/b` 节点本身**，只在“调度层面”选择 outer 列表（见下一节）。

## 4. 方案：outer-chunk tasks（粗粒度）怎么做

### 4.1 基本想法

在 same-field 的 AND 中通常存在双层循环：
- outer: 遍历 `A.edges`
- inner: 遍历 `B.edges`

outer-chunk 的做法是：
- 先把 edge_map 的 bucket 链表**扁平化**成 `edge_bucket_entry_t*` 数组（只复制指针，不复制对象）
- 把 outer 数组按 chunk 切片，spawn **每个 chunk 一个 Lace task**
- 每个 task 在本地顺序执行该 chunk 的所有 pair 运算，并把 “需要 emit 的结果”写进一个本地 items 数组
- 父线程在所有 chunk 完成后统一 SYNC，串行 merge items 到 `res_edges` + `temp_refs`

### 4.2 “确保 A 边不少于 B 边”的解释与做法

你提出的约束是正确的：如果我们固定用 A 作为 outer，而 `|A| < |B|`，那么：
- 可并行的 outer slice 更少（可 spawn 的 chunk 任务数更少）
- 负载更容易不均衡（某些 task 很快结束，其他 task 很重）

因此 **same-field** 下应当保证 outer 列表对应的边数量尽量大：

- 不交换 `a/b` 节点（避免影响 op cache key），而是在构造列表时选择：
  - `outer_list = (edge_count(a) >= edge_count(b)) ? list_a : list_b`
  - `inner_list = (outer_list == list_a) ? list_b : list_a`
- label 与 child 的组合仍然按 pair 语义执行（AND 交换律成立，pair 的顺序不影响结果）。

注意：**diff-field** 分支不一定能自由选择 outer：
- 通常 result field 是较小的 field_id，所以必须遍历该节点的 edges 来构造结果边。
- 这时 outer 选择空间有限，只能在该 outer 上做 chunking（仍可做，但 A>=B 的优化不一定成立）。

### 4.3 Task 返回结构（建议）

为了保持 res_edges/temp_refs 串行 merge，task 返回一个结果结构：

```c
typedef struct {
  mtpndd_error_t status;
  size_t n;
  mtpndd_and_item_t *items; // heap array owned by parent after SYNC
} mtpndd_and_chunk_result_t;
```

其中 `mtpndd_and_item_t` 持有：
- `emit` (0/1)
- `mtpndd_t *child`（已 mtpndd_ref）
- `mtpndd_bdd_t label`（已 sylvan_ref）

### 4.4 Chunk 大小的选择（为什么你不看好“过粗”是合理的）

过粗的 chunk 可能导致：
- 任务数太少，steal 不足，多核利用率下降（CPU utilized < 2.5/4）
- “最重 chunk” 决定总体时间（尾部拖延），负载不均衡

因此本尝试需要一个保守的 chunk 策略，优先保证有足够 stealable slack：

推荐初始策略（same-field）：
- `A = outer_len`
- `W = lace_workers()`
- `tasks_target = clamp(W * 8, 1, A)`  （8 是经验值：足够让 steal 有空间）
- `chunk = ceil(A / tasks_target)`  （常见会得到 1 或 2）

进一步的改进（可选，但应在实验二阶段再做）：
- **自适应拆分**：如果一个 chunk 内发现 `(end-start)*inner_len` 过大，可在任务内部再 spawn 子 chunk（层级并行）。
- **outer-entry tasks**：先尝试 1 task per outer entry（比 per-pair 粗，但比 chunk 更细）。

## 5. 评估方法：为什么不能只用 NQueens，以及我们怎么补齐

### 5.1 NQueens 仍要跑，但只能作为 sanity + 回归

必跑：
- `taskset -c 0   mtpndd_nqueens_test 12`（正确性 + baseline）
- `taskset -c 0-3 mtpndd_nqueens_test 12`（观察是否有合理的并行收益）

但 NQueens 的局限：
- 结构强依赖、共享结构热区明显
- BDD 操作占比高、且可并行空间有限

### 5.2 增加“可控形状”的 AND micro-benchmark（建议新增）

为了隔离 “任务粒度策略”：
- 构造一批人工 MTPNDD 节点对 `(a,b)`，控制：
  - same-field / diff-field
  - `|A|`、`|B|`、以及 `|A|/|B|` 比例
  - child 深度（浅/深递归）
  - label 真假比例（决定 emit 密度）
- 重复执行 `mtpndd_and(a,b)` 多次（warm cache），测量中位数

该 benchmark 的价值：
- 能明确观察 “chunk 太粗/太细” 对 Tasks/Steals/Leaps 的影响
- 能专门验证你提到的 “保证 outer 的边更多（A>=B）” 是否显著改善 steal slack 与负载均衡

### 5.3 统计维度（每次实验必须记录）

建议每组实验都记录：
- workers = 1/2/4（固定 pin）
- wall time（中位数）
- Lace counters（Tasks/Steals/Leaps + Tasks per steal）
- `and spawns total/same/diff`（用于证明任务数是否降低）
- `mk lookup/ref/alloc` 细分（判断是否把压力转移到了 mk）
- 可选：`perf stat` CPUs utilized

## 6. 实施建议：新建实验分支/工作树进行尝试

该方案属于“策略性改动 + 可能需要多次调参”，建议在单独分支/工作树进行：
- branch name 建议：`experiment/and-outer-chunk`
- 先实现最小版本（扁平化 + chunk tasks + 串行 merge）
- 跑 NQueens + micro-benchmark 矩阵
- 若无收益/收益不稳定，再考虑更细粒度（outer-entry）或自适应拆分

---

## 7. 本文结论（现阶段）

outer-chunk 方案的价值在于：**以更少的任务数换取更高的单任务计算密度**，从而减少 Lace 调度开销与共享结构背景噪声。
但它是否有效取决于：
- outer 列表是否足够大（same-field 下应确保 outer 的边更多，即 A>=B 的调度策略）
- chunk 是否过粗导致 steal 不足
- micro-benchmark 的形状是否符合真实工作负载

因此必须在实验分支上，用 “NQueens + 可控 micro-benchmark” 的组合来评估，而不能只依赖 NQueens 单点结论。
