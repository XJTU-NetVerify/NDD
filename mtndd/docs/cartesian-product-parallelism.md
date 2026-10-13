# MTPNDD 笛卡尔积并行：现状分析与改进方案

## 1. 现状

### 1.1 NDD AND 操作的核心结构

`mtpndd_and_rec(A, B)` 处理 same-field case 时，需要对 A 和 B 的
edge map 做笛卡尔积：

```
mtpndd_and_rec(A, B):       // A.field_id == B.field_id
  res_edges = new edge_map
  pending = 0

  FOR_EACH entry_a IN A.edges:                  // |E_a| 次
    FOR_EACH entry_b IN B.edges:                // |E_b| 次
      if should_spawn(entry_a.child, entry_b.child):
        SPAWN(and_same_field_item, entry_a, entry_b)
        pending++
      else:
        item = CALL(and_same_field_item, entry_a, entry_b)
        merge(res_edges, item)                  // 串行写入 res_edges

      if pending >= 256:
        drain(pending / 2)                      // 串行 SYNC + merge

  drain(all_remaining)
  return mk_node(field_id, res_edges)
```

每个 `and_same_field_item` 做两件事：

```
and_same_field_item(entry_a, entry_b):
  label = sylvan_and(entry_a.label, entry_b.label)    // BDD 合并
  if label == false: return empty                       // 剪枝
  child = mtpndd_and_rec_CALL(entry_a.child, entry_b.child)  // 递归
  return (child, label)
```

### 1.2 并行决策

`mtpndd_should_spawn(child_a, child_b)` 控制是 SPAWN 还是 CALL：

```c
static inline bool mtpndd_should_spawn(mtpndd_t *a, mtpndd_t *b) {
    if (lace_workers() <= 1) return false;
    if (mtpndd_is_terminal(a) || mtpndd_is_terminal(b)) return false;
    if (a->field_id <= 2 && b->field_id <= 2) return true;   // 顶层无条件并行
    size_t prod = edges_a * edges_b;
    return prod >= MTPNDD_SPAWN_THRESHOLD;                    // 默认 >= 2
}
```

判断依据是 **child 节点** 的 edge count 乘积（即下一层的工作量），
不是当前层的 pair 数量。

### 1.3 Drain 机制

SPAWN 的 task 推入 Lace deque。当 pending 达到 `FLUSH_THRESHOLD`
(256) 时，drain 一半：

```c
mtpndd_and_drain_same_field_items(..., max_drain, res_edges, temp_refs):
  for i in 0..max_drain:
    item = SYNC(and_same_field_item)      // 等待完成
    merge(res_edges, item)                // 串行写入共享 edge map
```

drain 是 **LIFO 顺序**（从 deque tail 回退），且 merge 操作修改
共享的 `res_edges`，必须串行。

### 1.4 OR 操作的额外复杂度

`mtpndd_or_rec` 的 same-field case 更复杂：

```
or_same_field_item(entry_a, entry_b, ctx):
  intersect = sylvan_and(label_a, label_b)
  if intersect == false: return empty
  notIntersect = sylvan_not(intersect)
  // 原子操作：从 residual 中扣除 intersect 部分
  residual_apply_mask(ctx.residualA[entry_a.child], notIntersect)
  residual_apply_mask(ctx.residualB[entry_b.child], notIntersect)
  child = mtpndd_or_rec_CALL(entry_a.child, entry_b.child)
  return (child, intersect)
```

OR 多了 residual 的原子 CAS 操作，这对并行 SPAWN 是安全的
（`_Atomic(mtpndd_bdd_t)` + CAS loop），但增加了每个 item 的开销。

### 1.5 Diff-field case

当 `A.field_id != B.field_id` 时，不需要笛卡尔积——只遍历
field_id 更小一侧的 edges，每条边递归 `and_rec(edge.child, B)` 或
`and_rec(edge.child, A)`。这是线性 O(E) 而非 O(E^2)。

---

## 2. 问题分析

### 2.1 笛卡尔积的串行瓶颈

核心问题：**E_a × E_b 个 pair 的遍历、SPAWN 决策、drain 都在
同一个 worker 线程上串行执行**。

调用链：
```
Worker 0 执行 mtpndd_and_rec:
  for pair (i, j) in E_a × E_b:
    [串行] 判断 should_spawn             ~5ns
    [串行] SPAWN 或 CALL                 ~10ns (SPAWN) / 变长 (CALL)
    [串行] 检查 pending >= threshold
    [串行] drain: SYNC + merge           变长
```

其他 worker 只能偷走已经 SPAWN 的 sub-task。但：
- SPAWN 和 drain 交替进行，deque 中任务数被 flush threshold 限制
- drain 时 Worker 0 在做 SYNC + merge，不产生新任务
- 如果大部分 pair 不满足 `should_spawn`（child 太小），全部 CALL，
  其他 worker 完全空闲

### 2.2 递归嵌套在 sub-task 内部

每个 `and_same_field_item` 内部的 `mtpndd_and_rec_CALL` 是
**CALL 不是 SPAWN**——子递归在当前 task 的执行栈上完成。如果子树
很大，这个 task 会长时间占据 worker，不产生可被偷走的工作。

### 2.3 Benchmark 数据印证

sre-ndd fattree12 MF=1:

| Workers | Time (s) | vs 1w |
|---------|----------|-------|
| 1       | 24.55    | 1.00x |
| 2       | 25.36    | 0.97x |
| 3       | 26.01    | 0.94x |

**worker 越多越慢**。额外 worker 不仅没有加速，反而增加了 Lace
调度开销和 cache 竞争。这说明当前的并行粒度无法有效利用多核。

---

## 3. 改进方案

### 方案 A：两阶段拆分（Filter-then-Recurse）

**核心思想**：将 cheap 的 BDD label 过滤和 expensive 的递归分离。

```
Phase 1 — Filter (可高度并行):
  pairs = []
  FOR_EACH (entry_a, entry_b) IN E_a × E_b:
    label = sylvan_and(entry_a.label, entry_b.label)
    if label != false:
      pairs.append((entry_a.child, entry_b.child, label))

Phase 2 — Recurse (tree-reduce 并行):
  results = parallel_map(pairs, (child_a, child_b, label) => {
    child_result = mtpndd_and_rec(child_a, child_b)
    return (child_result, label)
  })

Phase 3 — Merge:
  for (child, label) in results:
    add_edge(res_edges, child, label)
```

**优势**：

1. Phase 1 每个 item 只做 `sylvan_and`（很快，< 1μs），可以全部
   SPAWN 或用向量化循环。False label 被提前剪枝，减少 Phase 2 工作量。

2. Phase 2 的每个 item 是独立的递归，可以像 `mtpndd_and_reduce`
   一样用 tree-split 并行。递归不再锁在 producer 线程内。

3. Phase 3 的 merge 是串行的，但 pairs 数组长度 ≤ E_a × E_b，
   且已经过滤掉了 false 项。

**代价**：

- 需要临时数组存储 surviving pairs（O(E_a × E_b) 空间）
- Phase 1 和 Phase 2 之间有一个同步点
- 实现复杂度中等

**适用场景**：Same-field case 且 E_a × E_b 较大（≥ 16）时使用，
小规模仍然用当前的串行循环。

### 方案 B：分块并行遍历

**核心思想**：将 E_a × E_b 的笛卡尔积空间分块，每块独立处理。

```
// 将 A.edges 平均分为 k 块（k = worker 数）
chunks = split(A.edges, k)

// 每块独立处理所有 B.edges
SPAWN(process_chunk, chunks[0], B.edges)
SPAWN(process_chunk, chunks[1], B.edges)
...
CALL(process_chunk, chunks[k-1], B.edges)

// 每个 chunk 产生独立的 local_edges
results = SYNC all chunks

// 合并所有 local_edges 到 res_edges
for chunk_edges in results:
  merge(res_edges, chunk_edges)
```

每个 `process_chunk` 内部走当前的串行循环逻辑（CALL），但
不同 chunk 在不同 worker 上并行执行。

**优势**：

- 改动最小——chunk 内部复用现有代码
- 天然并行度 = min(k, E_a)
- 无需额外 filter 阶段

**代价**：

- 每个 chunk 需要独立的 `edge_map`，最后合并
- edge_map 合并可能涉及 BDD label 的 OR 操作（同一 child 出现在多个 chunk）
- 对 OR 操作不直接适用（residual 是共享状态）

### 方案 C：改进 drain 策略（渐进优化）

不改变整体结构，只优化 drain 效率：

1. **延迟 merge**：drain 时只 SYNC 收集 item 到数组，不立即
   `add_edge`。全部收集完后批量 merge，减少 edge map 的碎片化开销。

2. **非阻塞 drain**：当前 drain 是 SYNC（阻塞等待）。改为
   先检查 `TASK_IS_STOLEN`——未被偷走的 task 直接 local execute，
   已被偷走的等待完成。

3. **提高 flush threshold**：当前 256 可能过低。在 edge map 大小
   允许的前提下，增大到 1024 或 2048 可以让 deque 中积累更多 task，
   增加被偷走的概率。

**优势**：改动最小，风险最低，可以立即测试效果。
**代价**：不解决根本问题（串行遍历 + merge 串行瓶颈）。

---

## 4. 推荐方案与实施路径

### 优先级：A > C > B

**方案 A（Filter-then-Recurse）** 最有潜力：

1. 它把 O(E_a × E_b) 的 BDD 过滤和 O(surviving) 的递归彻底分离
2. 递归阶段的并行度由 surviving pair 数决定，不受笛卡尔积遍历的串行约束
3. 对 AND 操作直接适用，OR 需要额外处理 residual（可先只改 AND）

**方案 C** 作为 low-hanging fruit 可以先实施，验证 drain 优化是否
有边际改善。

**方案 B** 改动大且 edge_map 合并复杂（尤其是 OR 的 residual），
优先级最低。

### 实施步骤

1. **Phase 0**（方案 C）：提高 flush threshold → 测试。延迟 merge → 测试。
2. **Phase 1**（方案 A，AND only）：实现两阶段 AND，保留旧路径作 fallback。
   用 `E_a * E_b >= THRESHOLD` 决定是否走新路径。
3. **Phase 2**（方案 A，OR）：扩展到 OR。需要在 Phase 1 中处理 residual
   的并行安全问题。
4. **Benchmark**：每个 phase 后对比 sre-ndd fattree12 的 scalability。

---

## 5. 实现细节草案（方案 A：AND same-field）

```c
TASK_IMPL_2(mtpndd_t*, mtpndd_and_rec, mtpndd_t*, a, mtpndd_t*, b) {
    // ... terminal cases, cache lookup ...

    if (same_field_case) {
        size_t ea = a->edges->edge_count;
        size_t eb = b->edges->edge_count;

        if (lace_workers() > 1 && ea * eb >= AND_TWO_PHASE_THRESHOLD) {
            // === Two-phase path ===
            return mtpndd_and_two_phase(a, b, res_edges, &temp_refs);
        }

        // === Original path (small cases) ===
        // ... current FOR_EACH_ENTRY nested loop ...
    }
}

static mtpndd_t* mtpndd_and_two_phase(
    mtpndd_t *a, mtpndd_t *b,
    mtpndd_edge_t *res_edges,
    mtpndd_temp_ref_list_t *temp_refs)
{
    // --- Phase 1: Filter ---
    // Collect all (child_a, child_b, label) where label != false
    size_t max_pairs = a->edges->edge_count * b->edges->edge_count;
    mtpndd_and_pair_t *pairs = alloca(max_pairs * sizeof(mtpndd_and_pair_t));
    // (or heap allocate if too large)
    size_t npairs = 0;

    edge_bucket_entry_t *ea, *eb;
    FOR_EACH_ENTRY_IN_ALL_BUCKETS(a->edges, ea) {
        FOR_EACH_ENTRY_IN_ALL_BUCKETS(b->edges, eb) {
            mtpndd_bdd_t label = sylvan_and(
                mtpndd_edge_label_load(ea),
                mtpndd_edge_label_load(eb));
            if (label != sylvan_false) {
                sylvan_ref(label);
                pairs[npairs++] = (mtpndd_and_pair_t){
                    .child_a = ea->child,
                    .child_b = eb->child,
                    .label = label
                };
            }
        }
    }

    if (npairs == 0) {
        // All pairs filtered out
        goto build_node;
    }

    // --- Phase 2: Parallel recurse ---
    // SPAWN/CALL each surviving pair independently
    for (size_t i = 0; i < npairs; i++) {
        // SPAWN each as independent task
        SPAWN(and_recurse_item, &pairs[i]);
    }
    // SYNC all and merge results
    for (size_t i = npairs; i > 0; i--) {
        mtpndd_and_item_t item = SYNC(and_recurse_item);
        merge(res_edges, temp_refs, &item);
    }

build_node:
    // ... mk_node, cache store ...
}
```

Phase 2 也可以用 tree-split 而非线性 SPAWN/SYNC：

```c
// Phase 2 alternative: tree-reduce style
TASK_DECL_3(mtpndd_edge_t*, and_recurse_batch,
            mtpndd_and_pair_t*, size_t, mtpndd_edge_t*);

TASK_IMPL_3(..., pairs, count, parent_edges) {
    if (count <= SEQUENTIAL_THRESHOLD) {
        // 串行处理小 batch
        for each pair: recurse + add_edge to local edges
        return local_edges;
    }
    size_t mid = count / 2;
    SPAWN(and_recurse_batch, pairs, mid, NULL);
    mtpndd_edge_t *right = CALL(and_recurse_batch, pairs+mid, count-mid, NULL);
    mtpndd_edge_t *left = SYNC(and_recurse_batch);
    return merge_edge_maps(left, right);
}
```

### 关键数据结构

```c
typedef struct {
    mtpndd_t *child_a;
    mtpndd_t *child_b;
    mtpndd_bdd_t label;  // pre-computed sylvan_and result, already ref'd
} mtpndd_and_pair_t;
```

### Phase 1 的内存开销

`max_pairs = E_a × E_b`。对于 `edge_bucket_count=16`，最大
16 × 16 = 256 pairs × 24 bytes = 6KB。可以用 `alloca` 放栈上。
如果 edge map 更大，fallback 到 heap。

---

## 6. 实际实现状态

### 6.1 两个独立的阈值

| 阈值 | 默认值 | 控制什么 |
|------|-------:|----------|
| `MTPNDD_SPAWN_THRESHOLD` | 2 | 每个 edge pair 是 SPAWN 还是 CALL（基于 **child** 的 edge count 乘积） |
| `MTPNDD_AND_TWO_PHASE_THRESHOLD` | 16 | 是否走两阶段路径（基于 **当前** 节点的 edge count 乘积 `E_a × E_b`） |

两阶段路径还需要 `lace_workers() > 1`。

### 6.2 触发条件

```c
// mtpndd_and_rec 的 same-field case 入口
if (lace_workers() > 1 && ea * eb >= MTPNDD_AND_TWO_PHASE_THRESHOLD) {
    mtpndd_and_two_phase_same_field(...)  // 两阶段
} else {
    // 原始单阶段
}
```

### 6.3 两阶段 vs 原始的区别

**原始单阶段**：BDD filter 和递归绑在同一个 task（`and_same_field_item`）里。
即使 BDD 结果是 false（这条边不存在），task 已经被 SPAWN 了。

```
and_same_field_item(entry_a, entry_b):
  label = sylvan_and(label_a, label_b)    ← BDD
  if label == false: return empty         ← 白 SPAWN 了
  child = mtpndd_and_rec_CALL(...)        ← 递归
  return (child, label)
```

**两阶段**：Phase 1 串行做所有 BDD filter，只有 surviving pairs 进入 Phase 2 被 SPAWN。

```
Phase 1 (串行):
  for each (entry_a, entry_b):
    label = sylvan_and(label_a, label_b)
    if label != false: pairs[n++] = {child_a, child_b, label}

Phase 2 (并行):
  for each pair in pairs:
    SPAWN(and_recurse_pair, pair)    ← 只做递归，不做 BDD

and_recurse_pair(pair):
  child = mtpndd_and_rec_CALL(pair->child_a, pair->child_b)
  return (child, pair->label)        ← label 已由 Phase 1 算好
```

### 6.4 BDD 调用统计（N-Queens N=12）

| 指标 | w=1 | w=4 |
|------|----:|----:|
| BDD ops in same-field AND | 11,264,241 | 11,247,318 |
| False rate (pruned) | 29.1% | 29.1% |
| Two-phase triggers | 0 | 2,530 |
| Two-phase total pairs | 0 | 44,408 |
| Two-phase surviving | 0 | 22,222 (50%) |
| Sylvan BDD and total | 11,510,893 | 11,510,893 |

- w=1 不触发两阶段（单 worker 条件不满足）
- w=4 触发 2530 次，处理了 4.4 万 pairs 中的 2.2 万（50% Phase 1 剪枝率）
- 但这只是 N-Queens；sre-ndd 的剪枝率可能不同

### 6.5 N-Queens 上的效果

两阶段 AND 在 N-Queens 上与原始路径持平（±1%）。原因：
- N-Queens edge count 小（多为 1-8），`E_a * E_b >= 16` 触发率低
- 两阶段只处理了 4.4 万 / 1126 万 = 0.4% 的 BDD ops

### 6.6 sre-ndd 上的效果

两阶段 AND 在 sre-ndd 上的效果尚未和原始路径做严格隔离测试。
当前 sre-ndd 的改善主要来自 BDD spawn depth cutoff（-3% ~ -11%）。

---

## 7. BDD 运算开销估算

从 Lace stats profiling（fattree12 MF=3 w=4）：

| 指标 | baseline | cutoff=0 |
|------|--------:|--------:|
| Sylvan BDD and count | 1,588M | 1,527M |
| MTPNDD total time | 271.2s | 247.0s |
| MTPNDD AND calls | 7,427,345 | 7,427,345 |
| BDD and per MTPNDD AND | ~214 | ~206 |

每次 `mtpndd_and` 平均触发 ~210 次 `sylvan_and`。
MTPNDD AND 平均耗时 = 271.2s / 7.43M = **36.5 μs/次**（含 BDD + 递归 + merge 全部）。

BDD 单次 `sylvan_and` 的精确时间需要采样计时（per-call `clock_gettime`
对 15 亿次调用太贵 ~150ns/次 × 1.5B = 225s overhead）。粗略估算：
- 如果 BDD 占 MTPNDD AND 时间的 30%（基于 N-Queens profiling 中 `and_same_bdd_op_ns`
  占 `and_time_ns` 的比例），则 BDD 总时间 ≈ 81s
- 15.3 亿次 / 81s ≈ **53 ns/次** 平均

这和 BDD label 很小的预期一致——大部分 `sylvan_and` 在 terminal case
（true/false/cache hit）就 return 了，不进入递归。

---

## 8. twoPhaseThreshold 调优实验

在 sre-ndd 上扫描不同 threshold 值（cutoff=0 固定），取 best of runs。

### fattree08 MF=3 w=4

| threshold | time (s) | vs tpt=0 |
|----------:|--------:|---------:|
| 0 (禁用) | 32.46 | baseline |
| 4 | 31.07 | -4.3% |
| 8 | 31.38 | -3.3% |
| 16 | 32.30 | -0.5% |
| 32 | 30.88 | -4.9% |
| 64 | 32.09 | -1.1% |

### fattree12 MF=1 w=4

| threshold | time (s) | vs tpt=0 |
|----------:|--------:|---------:|
| 0 (禁用) | 25.92 | baseline |
| 4 | 25.46 | -1.8% |
| 8 | 25.27 | -2.5% |
| 16 | 25.69 | -0.9% |
| 32 | 25.52 | -1.6% |
| 64 | 25.49 | -1.7% |

### 结论

- tpt=0（禁用两阶段）始终最慢，两阶段有正向效果
- 最佳值在 4-32 之间波动，run-to-run 噪声 ±2% 内无法区分
- **默认值调整为 4**：在两个 workload 上都接近最优，且更积极地触发
  两阶段路径。运行时可通过 `-Dmtpndd.twoPhaseThreshold=N` 覆盖。

### 运行时 API

```c
// C API
mtpndd_set_two_phase_threshold(4);   // default
mtpndd_set_two_phase_threshold(0);   // disabled
```

```bash
# Java (sre-ndd)
java -Dmtpndd.twoPhaseThreshold=4 ...
```
