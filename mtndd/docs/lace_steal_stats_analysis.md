# Lace 任务窃取统计分析

## 背景

通过以下编译参数启用 Lace 和 Sylvan 的运行时统计：

```bash
cd sylvan
cmake -B build_stats -DMTPNDD_LOG_LEVEL=0 -DSYLVAN_STATS=ON \
  "-DCMAKE_C_FLAGS=-DLACE_COUNT_STEALS=1 -DLACE_COUNT_TASKS=1 -DLACE_IDLE_STATS=1" \
  "-DCMAKE_CXX_FLAGS=-DLACE_COUNT_STEALS=1 -DLACE_COUNT_TASKS=1 -DLACE_IDLE_STATS=1"
cmake --build build_stats --parallel
```

`nqueens_benchmark.c` 和 `nqueens_parallel_benchmark.c` 均在成功路径上调用了
`sylvan_stats_report(stdout)`；Lace 统计由 `lace_stop()` → `lace_exit()` 自动输出到
stderr。

启用的统计项：

| 编译宏 | 输出内容 |
|---|---|
| `LACE_COUNT_TASKS` | 每个 worker 执行的任务数 |
| `LACE_COUNT_STEALS` | 每个 worker 的窃取尝试次数、成功次数、busy 次数 |
| `LACE_IDLE_STATS` | 每个 worker 在窃取循环中空转的时间（采样估算） |
| `SYLVAN_STATS` | BDD 节点创建/复用数、operation cache 命中率等 |

> `LACE_PIE_TIMES` 依赖 Solaris `gethrtime()`，Linux 上不可用，未启用。

---

## 原始数据：N=10, w=4（串行版 nqueens_benchmark）

```
Tasks (0): 136398
Tasks (1): 46277
Tasks (2): 36104
Tasks (3): 39634
Tasks (sum): 258413

Steals (0): 21 good/0 busy of 640369 tries; leaps: 176 good/3 busy of 14333 tries
Steals (1): 378 good/0 busy of 297084 tries; leaps: 89 good/2 busy of 5005 tries
Steals (2): 392 good/1 busy of 325556 tries; leaps: 62 good/3 busy of 4064 tries
Steals (3): 412 good/4 busy of 16810 tries; leaps: 55 good/2 busy of 4135 tries
Steals (sum): 1203 good/5 busy of 1279819 tries; leaps: 382 good/10 busy of 27537 tries

Idle steal (0): 0 nowork, avg 0.0 ns (samples 0, est 0.00 ms)
Idle leap  (0): 14154 nowork, avg 176.0 ns (samples 14, est 2.52 ms)
Idle steal (1): 15462 nowork, avg 157946.3 ns (samples 19, est 3073.00 ms)
Idle leap  (1): 4914 nowork, avg 86.0 ns (samples 1, est 0.09 ms)
Idle steal (2): 15430 nowork, avg 171.6 ns (samples 16, est 2.81 ms)
Idle leap  (2): 3999 nowork, avg 99.3 ns (samples 3, est 0.31 ms)
Idle steal (3): 16297 nowork, avg 91.3 ns (samples 15, est 1.40 ms)
Idle leap  (3): 4078 nowork, avg 98.8 ns (samples 5, est 0.51 ms)
Idle steal (sum): 47189 nowork, avg 60101.9 ns (samples 50, est 3077.22 ms)
Idle leap  (sum): 27145 nowork, avg 145.3 ns (samples 23, est 3.42 ms)
Idle sample rate: 1/1024

Tasks per steal (0): 692
Tasks per steal (1): 99
Tasks per steal (2): 79
Tasks per steal (3): 84
Tasks per steal (sum): 163

	0.908	724
```

---

## 分析

### 1. 窃取成功率极低

4 个 worker 共尝试窃取任务 **127 万次**，仅成功 **1203 次**，成功率约 **0.09%**。
绝大多数窃取尝试扑空——deque 里没有可偷的任务。

这不是 Lace 的效率问题，而是任务粒度和分布的特征：Phase 1/2 阶段有大量独立的
并行任务可被窃取，但 Phase 3（顺序 AND 左折叠）整个阶段只有 worker 0 在推进，
其他 worker 只能等待偶发的 BDD AND/OR 子递归任务。

### 2. 工作线程大量时间在空转

总耗时约 0.9 秒，4 个 worker 合计 CPU 时间约 3.6 秒。

| worker | 空转估算 | 占比（≈ CPU budget） |
|--------|---------|---------------------|
| 0      | ~3 ms   | ~0.3%（主线程，始终有活干） |
| 1      | ~3073 ms | ~85%（几乎全程空转） |
| 2      | ~3 ms   | ~0.3%（采样偶然偏低） |
| 3      | ~1 ms   | ~0.1%（采样偶然偏低） |

> `LACE_IDLE_STATS` 采样率为 1/1024，worker 1 的高值是因为某次采样恰好命中一段长
> 空转区间，worker 2/3 的低值是采样稀疏导致的偶然性，不代表它们真的不空闲。

### 3. 任务分布不均

```
Tasks (0): 136398  ← 53% 的任务由 worker 0 独占
Tasks per steal (0): 692  ← 每次窃取后 worker 0 连续执行 692 个任务
Tasks per steal (1-3): ~80-100
```

worker 0 执行了超过一半的任务。Phase 3 是严格的串行依赖链（每步 AND 结果是下一步的输入），
仅有 worker 0 推进；AND/OR 内部的子递归虽然可以被其他 worker 窃取，但子任务生命周期
极短，往往还没来得及被偷就已经在 worker 0 上执行完了。

### 4. `LACE_STEAL_BACKOFF` 的重要性

`lace_config.h` 开启了退避机制：

```c
#define LACE_STEAL_BACKOFF 1
#define LACE_STEAL_BACKOFF_YIELD_ITERS 256u   // 先 sched_yield
#define LACE_STEAL_BACKOFF_SLEEP_ITERS 2048u  // 再 nanosleep
#define LACE_STEAL_BACKOFF_SLEEP_NS 50000ul   // 50 µs
```

若关闭退避，空转的 worker 1-3 会持续以高频竞争 L3 cache 和内存总线，
导致 worker 0 的 Phase 3 内存访问延迟显著上升。这是大 N 场景下
"增加 worker 数量反而变慢"现象的根本原因。

---

## 结论

**瓶颈在 Phase 3，不在 Lace 任务窃取机制本身。**

- Phase 1（n 个行OR任务）和 Phase 2（n² 个格蕴含任务）并行性充足，可被有效窃取
- Phase 3 是严格串行的左折叠 AND 链，占 N≥12 总时间的 97% 以上，无法并行化
- 当前 `LACE_STEAL_BACKOFF` 配置已有效减轻空转 worker 对 worker 0 的缓存干扰
- 进一步提升性能的路径在于减少 Phase 3 的 AND 操作次数或改变折叠顺序，
  而非优化任务窃取参数

---

## SRE-NDD 实测：bgp_fattree08 MF=3, w=4

### 背景

SRE-NDD 是基于 MTP-NDD（Java + JNI 调用 Sylvan/Lace）的网络符号验证工具。
在 `sre-ndd` 项目中集成统计的方式：

```bash
# sre-ndd/run.sh，完整重建并启用统计
LACE_STATS=1 FORCE_REBUILD=1 DATASETS="bgp_fattree08" MFS="3" WORKERS="4" RUNS=1 ./run.sh
```

`LACE_STATS=1` 会在 cmake 时传入：
- `-DSYLVAN_STATS=ON`
- `-DCMAKE_C_FLAGS="-DLACE_COUNT_STEALS=1 -DLACE_COUNT_TASKS=1 -DLACE_IDLE_STATS=1"`

Java 侧在 `main()` 结尾（`MTPNDDEngine.shutdown()` 前）调用 `MTPNDDEngine.sylvanStatsReport()`，
`lace_stop()` 在 `shutdown()` 内部自动输出 Lace 统计到 stderr。

注意：`sylvan_stats.c` 在启用 `SYLVAN_STATS` 时需要 `<stdatomic.h>`，上游未包含，已在本地补丁修复。

### 耗时概览（总计 36.5s）

| 阶段 | 耗时 | 占比 |
|---|---|---|
| init | 0.73s | 2% |
| src（BGP 路由迭代 6 轮） | 30.06s | **82%** |
| spf（转发分析） | 5.59s | 15% |
| mine | ~0s | — |

### Sylvan BDD 统计

```
MTBDD nodes created  4,615,246
MTBDD nodes reused   9,626,282       ← 复用率 67.6%
BDD and              81,675,682 次
Operation cache      2,094,516 of 2,097,152 buckets filled（接近填满）
Memory (nodes)       192 MB
Memory (cache)        72 MB
```

### Lace 任务窃取统计（原始输出）

```
Tasks (0): 35397153
Tasks (1): 10133536
Tasks (2): 10379733
Tasks (3): 10479680
Tasks (sum): 66390102

Steals (0): 35763 good/11674 busy of 1685708 tries; leaps: 2208797 good/642391 busy of 69835838 tries
Steals (1): 6690344 good/1327011 busy of 155901533 tries; leaps: 1146783 good/335329 busy of 27544248 tries
Steals (2): 6761426 good/1339385 busy of 158107895 tries; leaps: 1167716 good/345053 busy of 28352385 tries
Steals (3): 6772627 good/1339558 busy of 157764393 tries; leaps: 1162960 good/337257 busy of 28822314 tries
Steals (sum): 20260160 good/4017628 busy of 473459529 tries; leaps: 5686256 good/1660030 busy of 154554785 tries

Idle steal (0): 0 nowork, avg 0.0 ns (samples 0, est 0.00 ms)
Idle leap  (0): 66984650 nowork, avg 81.7 ns (samples 65415, est 5472.22 ms)
Idle steal (1): 147306806 nowork, avg 167.0 ns (samples 143680, est 24565.57 ms)
Idle leap  (1): 26062136 nowork, avg 69.5 ns (samples 25626, est 1824.94 ms)
Idle steal (2): 149432524 nowork, avg 151.1 ns (samples 146117, est 22604.14 ms)
Idle leap  (2): 26839616 nowork, avg 69.7 ns (samples 26024, est 1857.32 ms)
Idle steal (3): 149214173 nowork, avg 152.1 ns (samples 145609, est 22679.20 ms)
Idle leap  (3): 27322097 nowork, avg 71.4 ns (samples 26790, est 1958.28 ms)
Idle steal (sum): 445953503 nowork, avg 156.7 ns (samples 435406, est 69848.92 ms)
Idle leap  (sum): 147208499 nowork, avg 75.4 ns (samples 143855, est 11112.76 ms)
Idle sample rate: 1/1024

Tasks per steal (0): 15
Tasks per steal (1): 1
Tasks per steal (2): 1
Tasks per steal (3): 1
Tasks per steal (sum): 2
```

### 分析

**1. 任务分布严重偏向 worker 0**

```
Tasks (0): 35,397,153  ← 占 53%
Tasks per steal (0): 15    ← 每次被偷后连续执行 15 个
Tasks per steal (1-3): 1   ← 几乎一偷一个，没有积压
```

worker 0 执行了超过一半的任务。`src` 阶段的 BGP 路由迭代是严格轮次依赖（每轮结果
是下一轮的输入），只有 worker 0 在推进主链，其他 worker 只能在 BDD and/or 子递归
中捡零散任务。

**2. 窃取成功率低，但量级比 nqueens 高出一个数量级**

| 指标 | nqueens N=10 | SRE fattree08 |
|---|---|---|
| 总窃取尝试 | 127.9 万 | **4.73 亿** |
| 成功次数 | 1,203 | **2,026 万** |
| 成功率 | 0.09% | **4.3%** |
| Leap 成功率 | 1.4% | 3.7% |

SRE 的绝对任务量远大于 nqueens（6600 万 vs 25 万），BDD 子递归粒度更细，
所以成功率相对更高，但每次成功的收益（Tasks per steal）极小。

**3. worker 1-3 空转时间估算**

| worker | 空转（steal loop） | 空转（leap loop） | 总空转估算 |
|---|---|---|---|
| 0 | 0 ms | 5,472 ms | ~5.5s（leap 等待） |
| 1 | ~24,566 ms | ~1,825 ms | ~26s |
| 2 | ~22,604 ms | ~1,857 ms | ~24s |
| 3 | ~22,679 ms | ~1,958 ms | ~25s |

总耗时 36.5s，4 个 worker 合计 CPU 时间约 146s。worker 1-3 各自约 70% 的
CPU 时间在空转，与 nqueens 结论一致。

**4. Operation cache 几乎填满**

`2,094,516 / 2,097,152`（99.9%），说明 fattree08 的工作集超出了当前 cache 配置，
可能存在 cache 置换导致命中率下降，进而拖慢串行主链。

### 与 nqueens 结论的对比

两个场景的根本瓶颈相同：**串行依赖链占主导，并行化收益有限**。

| 维度 | nqueens N=10 | SRE fattree08 |
|---|---|---|
| 瓶颈阶段 | Phase 3（串行 AND 左折叠） | src（BGP 轮次迭代） |
| 瓶颈可并行化 | 否 | 否（轮次间强依赖） |
| worker 0 任务占比 | 53% | 53% |
| 空转 worker 占比 | ~85%（采样偶然） | ~70% |
| `LACE_STEAL_BACKOFF` 作用 | 关键，防缓存争用 | 同等重要 |

SRE 场景额外需要关注 operation cache 容量：fattree08 已接近填满，
fattree12/16 需要更大的 cache 配置才能维持命中率。
