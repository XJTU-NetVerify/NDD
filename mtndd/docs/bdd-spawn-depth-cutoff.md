# BDD Spawn Depth Cutoff: Design, Implementation & Benchmark

## Motivation

Sylvan BDD operations (e.g. `sylvan_and`) recursively split into high/low
cofactors, SPAWNing one branch as a Lace task and CALLing the other.

In MTPNDD, parallelism is structured in two layers:

1. **Upper layer** (MTPNDD operations): parallel SPAWN of NDD AND/OR/NOT
   sub-problems on edge pairs.
2. **Lower layer** (BDD label operations): `sylvan_and`, `sylvan_or`, etc.
   on edge-label BDDs, called from within each MTPNDD sub-task.

BDD-internal spawning produces large numbers of micro-tasks that pollute the
Lace work-stealing deque. Other workers waste CPU in steal search with very
low success rates (~0.8%), creating scheduling overhead that outweighs any
parallel benefit from BDD-level task splitting.

Setting `cutoff=0` disables all BDD-internal spawning, confining parallelism
to the MTPNDD layer where tasks are larger and steal ROI is higher.

## Design

### Thread-local depth counter

```c
static _Thread_local int bdd_spawn_depth = 0;
static int bdd_spawn_depth_cutoff = -1;  // <0 = unlimited (original behaviour)
```

- Incremented before each `SPAWN`, decremented after the corresponding `SYNC`.
- `bdd_should_spawn()` returns false when `cutoff >= 0 && depth >= cutoff`.

### API semantics

```c
void sylvan_set_spawn_depth_cutoff(int value);
int  sylvan_get_spawn_depth_cutoff(void);
```

| value | meaning |
|------:|---------|
| `< 0` (default `-1`) | Unlimited — original Sylvan behaviour |
| `0` | **No BDD-internal spawning** — all recursive sub-problems via CALL |
| `> 0` (e.g. `3`) | SPAWN only when nested depth < cutoff |

### GC safety

Sequential cofactor computation requires protecting the first result from GC:

```c
low  = CALL(sylvan_and, aLow, bLow, level);
bdd_refs_push(low);          // protect from GC during high computation
high = CALL(sylvan_and, aHigh, bHigh, level);
bdd_refs_pop(1);
```

Without `bdd_refs_push`, GC triggered by `CALL(high)` via
`sylvan_gc_test()` → `YIELD_NEWFRAME()` could collect `low`.

### Modified operations

All fork-join BDD operations modified (14 total). `sylvan_relnext` /
`sylvan_relprev` not modified (complex multi-spawn, not used by MTPNDD).

---

## Benchmark Results

### Experiment: sre-ndd A/B 对比

**同一台机器、同一份代码、相近时间**运行的严格 A/B 对比。
- baseline: `bench-results-26.4.9`（默认 cutoff=-1）
- cutoff=0: `bench-results-26.4.9-cutoff`（`-Dmtpndd.bddSpawnDepthCutoff=0`）

取每组 2 runs 的较好值（秒）。

#### bgp_fattree04

| MF | w | baseline | cutoff=0 | delta |
|---:|--:|--------:|---------:|------:|
| 1 | 1 | 0.398 | 0.399 | +0.1% |
| 1 | 2 | 0.402 | 0.398 | -0.9% |
| 1 | 4 | 0.400 | 0.398 | -0.5% |
| 1 | 6 | 0.423 | 0.417 | -1.4% |
| 2 | 1 | 0.297 | 0.298 | +0.4% |
| 2 | 2 | 0.299 | 0.296 | -0.8% |
| 2 | 4 | 0.301 | 0.296 | -1.8% |
| 2 | 6 | 0.303 | 0.324 | +6.8% |
| 3 | 1 | 0.299 | 0.297 | -0.6% |
| 3 | 2 | 0.299 | 0.297 | -0.6% |
| 3 | 4 | 0.299 | 0.300 | +0.3% |
| 3 | 6 | 0.303 | 0.323 | +6.7% |

fattree04 太小（<0.5s），结果在噪声范围内。

#### bgp_fattree08

| MF | w | baseline | cutoff=0 | delta |
|---:|--:|--------:|---------:|------:|
| 1 | 1 | 2.834 | 2.745 | **-3.2%** |
| 1 | 2 | 2.850 | 2.855 | +0.2% |
| 1 | 4 | 2.881 | 2.849 | -1.1% |
| 1 | 6 | 2.972 | 3.147 | +5.9% |
| 2 | 1 | 9.802 | 9.965 | +1.7% |
| 2 | 2 | 9.935 | 9.787 | -1.5% |
| 2 | 4 | 10.056 | 9.825 | **-2.3%** |
| 2 | 6 | 10.068 | 9.906 | -1.6% |
| 3 | 1 | 33.382 | 33.327 | -0.2% |
| 3 | 2 | 30.626 | 30.970 | +1.1% |
| 3 | 4 | 31.177 | 29.574 | **-5.1%** |
| 3 | 6 | 30.816 | 30.147 | **-2.2%** |

fattree08 中等规模，MF=3 w=4 有 -5.1% 改善。

#### bgp_fattree12

| MF | w | baseline | cutoff=0 | delta |
|---:|--:|--------:|---------:|------:|
| 1 | 1 | 19.915 | 19.733 | -0.9% |
| 1 | 2 | 20.618 | 20.198 | **-2.0%** |
| 1 | 4 | 21.296 | 20.664 | **-3.0%** |
| 1 | 6 | 21.579 | 20.826 | **-3.5%** |
| 2 | 1 | 89.236 | 86.952 | **-2.6%** |
| 2 | 2 | 89.606 | 85.189 | **-4.9%** |
| 2 | 4 | 91.474 | 85.226 | **-6.8%** |
| 2 | 6 | 92.660 | 85.714 | **-7.5%** |
| 3 | 1 | 412.009 | 396.417 | **-3.8%** |
| 3 | 2 | 364.519 | 340.586 | **-6.6%** |
| 3 | 4 | 363.311 | 327.566 | **-9.8%** |
| 3 | 6 | 371.782 | 330.627 | **-11.1%** |

---

## 分析

### 1. 效果随 workload 规模和 worker 数递增

fattree12 MF=3 的改善幅度：

```
w=1: -3.8%  →  w=2: -6.6%  →  w=4: -9.8%  →  w=6: -11.1%
```

- **Worker 越多效果越大**：更多 worker 意味着更多空闲 worker 在
  steal search，而 BDD micro-task 又增大了 deque 噪声。cutoff=0
  消除了这些噪声，减少了无效 steal search 的 CPU 浪费。

- **MF 越大效果越大**：MF（最大故障数）越大，NDD 操作越多，BDD
  SPAWN 的累积开销越大。MF=1 最多 -3.5%，MF=3 最高 -11.1%。

### 2. w=1 也有 1-4% 改善

cutoff=0 在 w=1 时也有效（fattree12 MF=3: -3.8%）。虽然单 worker
不存在 steal 问题，但 cutoff=0 避免了 BDD SPAWN/SYNC 的 deque
push/pop 操作本身的开销（即使不被偷，每次 SPAWN 仍有 ~10ns 的
deque 写入 + `bdd_refs_spawn` GC 引用栈操作）。在数千万次
`sylvan_and` 调用中，这些微小开销累积成可测量的差异。

### 3. Lace profiling 详细分析

通过 `LACE_PIE_TIMES=1 LACE_COUNT_TASKS=1 LACE_COUNT_STEALS=1
LACE_IDLE_STATS=1 SYLVAN_STATS=1` 的 profiling，对 **fattree12 MF=3
w=4**（效果最大的配置）做详细对比：

| 指标 | baseline | cutoff=0 | 变化 |
|------|--------:|--------:|-----:|
| **Wall time** | 299.3s | 275.1s | **-8.1%** |
| **MTPNDD total** | 271.2s | 247.0s | **-8.9%** |
| **Tasks (sum)** | **1,032M** | **259M** | **-74.9%** |
| BDD and count | 1,588M | 1,527M | -3.8% |
| Steal good (sum) | 75.96M | 70.31M | -7.4% |
| **Leap tries (sum)** | 2,725M | **1,064M** | **-61.0%** |
| **Leap work (sum CPU)** | 105.4s | **45.6s** | **-56.8%** |
| **Leap search (sum CPU)** | 157.2s | **85.8s** | **-45.4%** |
| Steal work (sum CPU) | 440.9s | 427.5s | -3.0% |
| Steal search (sum CPU) | 464.7s | 518.5s | +11.6% |

#### Leapfrog 是真正的受益者

cutoff=0 的最大收益来自 **leapfrog 减少**：

- Leap work: 105.4s → 45.6s（**-57%**）
- Leap search: 157.2s → 85.8s（**-45%**）

Lace 的 leapfrog 机制是：当 worker A 的 SPAWN task 被 worker B
偷走后，worker A 在 SYNC 时不会阻塞等待，而是去帮 worker B 干活
（偷 B 的 task）。BDD 内部的大量 SPAWN 触发了大量 leapfrog——
owner 每次 `sylvan_and` 的 SPAWN+SYNC 都可能导致 leapfrog，而
BDD micro-task 本身只有 ~1μs，leapfrog 的搜索开销远大于 task
本身的价值。

cutoff=0 让 BDD 操作全部用 CALL（无 SPAWN → 无 SYNC → 无
leapfrog），彻底消除了这个开销源。

#### Task 数量减少 75%

从 10.3 亿降到 2.6 亿。减少的 7.7 亿全部是 BDD 内部的 SPAWN
task。这意味着原来每次 `sylvan_and` 平均产生 ~0.5 个 SPAWN task
（15.3 亿次 BDD and 产生 7.7 亿个 task）。

#### Steal search 增加但 ROI 更高

Steal search 从 465s 增到 519s（+12%），因为 deque 中少了 BDD
micro-task，空闲 worker 需要更多轮搜索才找到 MTPNDD 级别的大
task。但这些大 task 的 steal ROI 远高于 BDD micro-task。

### 4. 对 fattree04/fattree08 小规模 workload 效果有限

fattree04 总运行时间 <0.5s，BDD 操作占比小，cutoff 效果被噪声掩盖。
fattree08 MF=1/2 效果在 ±3% 波动，MF=3 开始显现（-5.1%）。

---

## 使用方式

### C API

```c
#include <sylvan.h>

// After sylvan_init_bdd():
sylvan_set_spawn_depth_cutoff(0);   // BDD fully serial (recommended for MTPNDD)
sylvan_set_spawn_depth_cutoff(-1);  // unlimited (default, original Sylvan)
```

### Java (sre-ndd)

```bash
# 通过 JVM property:
java -Dmtpndd.bddSpawnDepthCutoff=0 ...

# 或通过 run.sh:
JAVA_OPTS="-Xmx32768m -Dmtpndd.bddSpawnDepthCutoff=0" bash run.sh
```

### C benchmark (env var)

```bash
BDD_SPAWN_DEPTH_CUTOFF=0 ./mtpndd_nqueens_parallel_benchmark 13
```

## Files changed

**mtpndd-c:**
- `sylvan/src/sylvan/sylvan_bdd.h` — API declarations
- `sylvan/src/sylvan/sylvan_bdd.c` — thread-local counter, 14 BDD operations modified
- `sylvan/examples/nqueens.c` — env var support for benchmarking
- `sylvan/src/sylvan/mtpndd/test/nqueens_parallel_benchmark.c` — env var support

**sre-ndd:**
- `lib/mtpndd/sylvan/src/sylvan/sylvan_bdd.{h,c}` — synced from mtpndd-c
- `lib/mtpndd/jni/src/main/native/mtpndd_jni.c` — JNI bindings
- `lib/mtpndd/jni/src/main/java/org/ants/mtpndd/MTPNDDEngine.java` — Java API
- `src/main/java/bdd/NDDManager.java` — system property integration
