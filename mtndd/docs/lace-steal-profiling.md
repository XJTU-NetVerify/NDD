# Lace Work-Stealing Profiling: sre-ndd fattree12

## 实验配置

- **Workload**: sre-ndd BGP verification, fattree12 k=1 (MF=1)
- **Platform**: 6-core, Linux 6.19.9
- **Build**: RelWithDebInfo, `LACE_PIE_TIMES=1 LACE_COUNT_TASKS=1 LACE_COUNT_STEALS=1 LACE_IDLE_STATS=1 SYLVAN_STATS=1`
- **MTPNDD LOG_LEVEL=2**

## 原始数据

### 配置 A: w=1 (串行基准)

```
Wall time:              21.50s
MTPNDD total:           14.75s
  AND calls:            7,427,345    (9.19s)
  OR calls:             1,412,767    (3.05s)
  NOT calls:            1,216,703    (0.99s)
  satCount calls:       1,357,618    (1.12s)

Tasks (sum):            6,944,376
Steal tries:            0
Steal search (sum):     0ms
CPU time (sum):         21,238ms

BDD and count:          26,451,427
AND spawn count:        0
```

### 配置 B: w=4 (BDD 并行，默认)

```
Wall time:              23.19s          (+7.9% vs w=1)
MTPNDD total:           16.35s          (+10.8% vs w=1)

Tasks (sum):            14,565,327      (2.1x vs w=1)
AND spawn count:        5,149,791

Steal tries (sum):      240,243,054
Steal success (sum):    1,930,646
Steal success rate:     0.80%
Steal busy (sum):       1,089,654
Tasks per steal (sum):  4

CPU time (sum):         92.45s

Per-worker time breakdown (ms):
                        Worker 0   Worker 1   Worker 2   Worker 3
Startup                 0          2,087      2,083      2,088
Steal work              17,683     816        822        825
Leap work               1,960      580        559        577
Steal overhead          0          160        163        164
Steal search            134        18,144     18,169     18,143
Leap search             3,052      1,118      1,112      1,109
Exit                    0          0          0          0
```

### 配置 C: w=4 + BDD cutoff=0 (BDD 串行)

```
Wall time:              22.42s          (+4.3% vs w=1)
MTPNDD total:           15.64s          (+6.0% vs w=1)

Tasks (sum):            8,257,750       (1.19x vs w=1, vs 2.1x in B)
AND spawn count:        (MTPNDD only, no BDD spawns)

Steal tries (sum):      200,732,323
Steal success (sum):    1,782,869
Steal success rate:     0.89%
Tasks per steal (sum):  2

CPU time (sum):         ~86s

Per-worker time breakdown (ms):
                        Worker 0   Worker 1   Worker 2   Worker 3
Startup                 0          2,064      2,062      2,059
Steal work              17,795     744        738        711
Steal search            122        17,979     17,973     18,083
Leap search             2,409      796        802        757
```

---

## 分析

### 1. Worker 负载极度不均衡

Worker 0（主线程）做了 ~95% 的实际工作（17.7s steal work + 2.0s leap
work），worker 1-3 各只做了 ~0.8s。原因：

Java 端通过 JNI **串行**调用 `mtpndd_and` / `mtpndd_or` 等操作，每次
调用从 worker 0 开始执行。Worker 0 内部虽然 SPAWN 了 sub-task 到
deque，但 sub-task 粒度很细，worker 1-3 大部分时间在 steal search
（18s/worker）里找不到工作。

### 2. Steal search 是最大的浪费

4 个 worker 的 steal search 合计 **54.6s CPU 时间**（占总 CPU 92.5s
的 59%）。每个 worker 1-3 各花了 ~18s 在遍历其他 worker 的 deque。

Lace 的 steal 循环是 spin loop（`lace_steal_loop`），在没有 GC barrier
需要处理时一直尝试偷任务。这在 workload 充分并行时是对的——spin 可以
最快速度发现新任务。但当 workload 集中在一个 worker 上时，spin 变成了
纯粹的 CPU 浪费和 cache 污染。

### 3. 每次 steal 只偷 1 个 task

Lace 的 steal 机制是 **单 task steal**（见 `lace.h:778`）：

```c
ts_new.ts.tail++;  // 只前进 1 位
if (__sync_bool_compare_and_swap(&victim->ts.v, ts.v, ts_new.v)) {
    Task *t = &victim->dq[ts.ts.tail];
    t->f(self, __dq_head, t);  // 执行这 1 个任务
    return LACE_STOLEN;
}
```

每次成功 steal 只拿到 1 个 task，执行完后又回到 steal loop。如果这个
task 很小（例如一个 `mtpndd_and_same_field_item` 只做一次 BDD and +
一次递归，~1μs），那 steal 的 overhead（CAS + cache miss + context
switch）可能超过 task 本身的执行时间。

统计数据验证：配置 B 中 worker 1-3 各成功 steal ~64 万次，各做了
~800ms 工作，即**每次 steal 的平均工作量 = 800ms / 640k = 1.25μs**。
这比 steal 的搜索开销（18s / 640k = 28μs/次成功）小 **22 倍**。

### 4. BDD cutoff=0 减少了 task 数量但没减少 steal search

配置 C（BDD cutoff=0）将 task 总数从 14.6M 降到 8.3M（-43%），因为
BDD 内部不再 SPAWN。但 steal search 时间几乎没变（54.6s → 54.2s）。

原因：task 数量减少意味着 deque 中可偷的 task 更少，worker 1-3 的
steal 成功率从 0.80% 提升到 0.89%，但搜索轮次没减少——空闲 worker
仍然在 spin loop 里持续搜索。

**关键发现**：steal search 时间主要由 **worker 空闲时间**决定，不由
task 数量决定。只要 worker 空闲就一直 spin，无论 deque 里有多少 task。

### 5. Worker 1-3 的 2s startup 延迟

Worker 1-3 各有 ~2s 的 startup time。这是 Lace 线程创建和初始化的
时间。对于 21s 的 workload，2s 的 startup 占了 ~10%。这意味着 worker
1-3 在前 2s 完全不可用。

---

## 根因总结

| 问题 | 影响 | 根因 |
|------|------|------|
| Worker 1-3 只做了 ~4% 的工作 | 多 worker 无加速 | Java 串行调 JNI，工作集中在 worker 0 |
| 54s CPU 浪费在 steal search | 多 worker 更慢 | 空闲 worker 的 spin loop 消耗 CPU |
| 每次 steal 只拿 1 个 task | steal ROI 极低 | Lace 原生设计，单 task steal |
| 2s worker startup | 短 workload 效率低 | Lace 线程初始化开销 |

---

## 改进方向

### 方向 1: 提升 MTPNDD SPAWN 粒度

当前 `MTPNDD_SPAWN_THRESHOLD = 2`（edges_a * edges_b >= 2 就 SPAWN）。
这产生了大量微小 task（平均 1.25μs/task）。

提高阈值（例如 16 或 64）可以减少小 task 的数量，让每个 SPAWN 的
sub-tree 更大，steal 后的 ROI 更高。

**预期效果**：减少 task 总数 → 减少 steal tries → steal work 占比提高。
**风险**：过高阈值会减少可偷 task，限制并行度。

### 方向 2: Batch steal / Half steal

Lace 当前每次 steal 1 个 task。如果改为偷走 victim deque 的一半 task
（类似 Cilk 的 half-steal 策略），每次成功 steal 可以获得更多工作：

```c
// 现在: tail++ (偷 1 个)
ts_new.ts.tail++;

// 改为: tail = (tail + split) / 2 (偷一半)
ts_new.ts.tail = (ts.ts.tail + ts.ts.split) / 2;
```

**但 Lace 不支持 batch steal**。Lace 的 deque 是 LIFO 结构，task 之间
有 parent-child 依赖（SPAWN 的 task 在 SYNC 时需要按顺序回收）。偷走
多个 task 会破坏 SYNC 的正确性。

实际上 Lace 的 split 机制已经在做类似的事：victim 通过 `movesplit` 信号
调整 split point，使更多 task 暴露给 thief。但这依赖 victim 主动响应。

**结论**：在不修改 Lace 核心的前提下，batch steal 不可行。

### 方向 3: 减少 idle spin 的开销

Lace 的 steal loop 在无任务时持续 spin。可以加入 exponential backoff
或 `sched_yield()`，减少空闲 worker 的 CPU 消耗：

现有的 idle backoff（`lace_idle_backoff_stats`）采样间隔为 1/1024，
但主循环仍然是热 spin。可以在连续 N 次 steal 失败后让出 CPU。

**预期效果**：减少 CPU 浪费，可能略微增加 steal 延迟。
**风险**：对于真正有并行工作的 workload，backoff 会增加 steal 响应时间。

### 方向 4: 将并行从 MTPNDD 内部提升到 Java 层

当前 Java 串行调用 JNI（一次一个 `mtpndd_and`），并行只在单次调用内部。
如果在 Java 层用线程池并行调用多个独立的 NDD 操作（例如 BGP 的不同
路由器的计算），可以从根本上增加并行度。

这不需要改 Lace——每个 Java 线程通过 `LACE_ME` 成为一个 Lace worker，
直接在各自的 deque 上执行。

**预期效果**：最高效的并行方式，每个 worker 有自己的工作。
**限制**：需要应用层支持，不是所有计算都可以并行。

### 方向 5: 同时应用 BDD cutoff=0 + 提高 SPAWN 阈值

BDD cutoff=0 减少了 43% 的 task（从 14.6M 到 8.3M），但 steal search
没减少。如果同时提高 MTPNDD SPAWN 阈值，进一步减少 task 数量，每个
task 的工作量更大，steal 的 ROI 更高。

这是最简单的组合优化，不需要改 Lace，也不需要改 Java 层。

---

## 实验：SPAWN_THRESHOLD 调优

### 方法

在 sre-ndd fattree12 k=1 上测试不同 `MTPNDD_SPAWN_THRESHOLD` 值，
结合 BDD cutoff=0 / 默认。每个配置编译一次，跑 w=1 和 w=4。

### 结果

| Threshold | BDD cutoff | w | Wall time (s) | Tasks | Steal tries | Steal good |
|----------:|-----------:|--:|-----:|------:|------------:|-----------:|
| 2 | -1 | 1 | 21.76 | 6.98M | 0 | 0 |
| 2 | -1 | 4 | **23.18** | 14.53M | **240M** | 1.92M |
| 2 | 0 | 4 | **22.90** | 8.29M | **200M** | 1.79M |
| **16** | -1 | 1 | 21.99 | 6.99M | 0 | 0 |
| **16** | -1 | 4 | **21.64** | 7.09M | **2.5M** | 1,250 |
| **16** | 0 | 4 | **21.57** | 0.90M | **1.6M** | 11 |
| 64 | -1 | 4 | 21.70 | 7.03M | 3.2M | 989 |
| 256 | 0 | 4 | 21.60 | 0.91M | 0.7M | 8 |

### 分析

1. **Threshold=2 → 16 是决定性跳变**。w=4 从 23.18s 降到 21.64s
   （-6.6%）。Steal tries 从 2.4 亿降到 250 万（**-99%**）。

2. **Threshold ≥16 后无额外收益**。16/64/256 均为 ~21.6s。

3. **BDD cutoff=0 在 threshold ≥16 时效果极小**。因为 SPAWN 本身
   已经很少了，BDD 内部的 task 占比不大。

4. **Threshold=16 下 w=4 不再退化，但也无加速**。多次验证 w=1
   和 w=4 在 21.7s 完全持平。原因：threshold=16 几乎消除了 SPAWN，
   worker 1-3 无事可做。

5. **存在"粒度真空"**。edge_count 大多在 1-8 范围，`edges_a *
   edges_b` >= 16 的情况很少。在 threshold=2（太多微任务）和
   threshold=16（几乎不 SPAWN）之间没有平滑过渡。

### 结论

提高 SPAWN_THRESHOLD 可以**消除多 worker 的退化**（从 +7.9% 慢
降到 0%），但无法实现并行加速。根本原因是 sre-ndd 的 NDD 操作从
Java 层串行调用，MTPNDD 内部的笛卡尔积规模不够大（edge count 太小），
无法产生有效的并行工作。

**要实现真正的并行加速，需要在 Java 层（应用层）引入并行性**——
例如并行处理不同路由器的 BGP 计算，而不是依赖 MTPNDD 内部的
task-level 并行。

---

## 附录：Lace steal 机制详解

### Deque 结构

每个 worker 有一个 task deque（双端队列），head 端 push/pop（owner），
tail 端 steal（thief）。`split` 指针控制暴露给 thief 的 task 范围：

```
[tail ... split ... head]
  ^         ^        ^
  thief     可偷边界  owner push/pop
```

只有 `[tail, split)` 范围内的 task 可以被偷。Owner 通过
`lace_shrink_shared` / `lace_grow_shared` 调整 split。

### Steal 流程

1. Thief 随机选择一个 victim
2. 读取 victim 的 `(tail, split)`
3. 如果 `tail < split`：用 CAS 将 tail+1，偷走 1 个 task
4. 如果 `tail >= split`：设置 `victim->movesplit = 1`，请求 victim 扩大共享范围
5. 偷到后执行 task，执行完后回到 step 1

### Leapfrog

当 worker 的 SPAWN 的 task 被偷走后，该 worker 在 SYNC 时进入
leapfrog 模式：不等待结果，而是去偷 thief 的 work（帮 thief 干活），
直到原始 task 完成。这是 Lace 的核心调度策略。
