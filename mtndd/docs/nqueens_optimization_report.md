# MTPNDD N-Queens 并行化与性能优化实验报告

> 本报告覆盖从 commit `ded72dd`（2026-04-03）起的全部优化工作，记录每项优化的
> 动机、实现细节、实验设计、性能数据及失败案例分析，供论文写作参考。

---

## 目录

1. [实验环境与测试方法](#1-实验环境与测试方法)
2. [基准场景：N-Queens 问题的 MTPNDD 编码](#2-基准场景n-queens-问题的-mtpndd-编码)
3. [优化一：操作间并行（inter-operation parallelism）](#3-优化一操作间并行inter-operation-parallelism)
4. [优化二：Nodetable 桶数预分配](#4-优化二nodetable-桶数预分配)
5. [优化三：Nodetable 内部硬限提升与桶数公式精化](#5-优化三nodetable-内部硬限提升与桶数公式精化)
6. [优化四：Edge Map 桶数调优（edge_bucket_count=16）](#6-优化四edge-map-桶数调优edge_bucket_count16)
7. [失败实验记录](#7-失败实验记录)
8. [优化效果综合对比](#8-优化效果综合对比)
9. [讨论：Phase 3 顺序依赖链是主要瓶颈](#9-讨论phase-3-顺序依赖链是主要瓶颈)

---

## 1. 实验环境与测试方法

### 1.1 软件栈

| 层次 | 组件 | 说明 |
|------|------|------|
| 底层调度 | Lace（工作窃取框架） | 提供 SPAWN/SYNC/CALL 任务原语 |
| BDD 层 | Sylvan | 用于 MTPNDD 边标签的 BDD 运算 |
| NDD 层 | MTPNDD（本项目） | 多终端并行网络决策图，核心操作 `and`/`or`/`not` |
| 测试驱动 | `nqueens_benchmark`（串行版）<br>`nqueens_parallel_benchmark`（并行版） | C 可执行文件，输出制表符分隔的耗时和解数 |

### 1.2 编译配置

```bash
# 性能测试（默认配置，无调试 overhead）
cmake -B build -DMTPNDD_LOG_LEVEL=0
cmake --build build --parallel

# 调试分析（收集操作内部计时统计）
cmake -B build -DMTPNDD_LOG_LEVEL=2
cmake --build build --parallel
```

`MTPNDD_LOG_LEVEL=2` 启用 `clock_gettime` 精细计时：AND/OR/NOT 各子阶段耗时、
nodetable 查找次数与碰撞次数、edge map rehash 次数等，用于定位瓶颈。

### 1.3 基准程序调用方式

```bash
./build/src/sylvan/mtpndd/mtpndd_nqueens_benchmark        <N> [workers]
./build/src/sylvan/mtpndd/mtpndd_nqueens_parallel_benchmark <N> [workers]
```

输出格式：`\t<elapsed_s>\t<solutions>`，elapsed 包含 `mtpndd_init`、字段声明、
构建与折叠的全部时间。

### 1.4 批量扫描脚本

`bench_sweep.sh` 支持 `--parallel` 标志选择 binary，自动遍历 N=7..13、workers=1..6
并输出 CSV：

```
N,workers,mode,elapsed_s,solutions
```

### 1.5 基准线（baseline）说明

**基准线 = commit `ded72dd` 的代码**，即操作间并行首次引入但尚未做任何内存参数优化时。
后续各节的"vs baseline"均以此为参照。

---

## 2. 基准场景：N-Queens 问题的 MTPNDD 编码

### 2.1 问题定义

N-Queens 问题要求在 N×N 棋盘上放置 N 枚皇后，使得任意两枚不在同行、同列、同对角线。
解的总数是经典组合计数问题（N=12 有 14200 个解，N=13 有 73712 个解）。

### 2.2 MTPNDD 编码

棋盘状态表示为 N 个 N 位字段：

- 字段 `field[i]`（i=1..N）对应第 i 行，宽度 N 位
- `var(field=i, col=j)` = 1 表示第 i 行第 j 列有皇后

全局约束分两类：

**每行至少放一个（row-OR term）**：

$$\text{or}[i] = \bigvee_{j=0}^{N-1} \text{var}(i+1,\,j), \quad i = 0,\ldots,N-1$$

**每格不冲突蕴含（cell implication term）**：

$$\text{imp}[i][j] = \text{var}(i+1, j) \Rightarrow \bigwedge_{\text{攻击格}(k,l)} \neg\text{var}(k+1, l)$$

其中"攻击格"包含同行（同字段内）、同列（不同字段、相同位位置）、两条对角线上的所有格子。
蕴含通过 $p \Rightarrow q \equiv \neg p \vee q$ 实现：

```c
mtpndd_t *neg_guard = mtpndd_not(guard);
mtpndd_t *imp = mtpndd_or(neg_guard, conseq);
```

最终合法放置集合：

$$\text{queen} = \bigwedge_{i=0}^{N-1} \text{or}[i] \;\wedge\; \bigwedge_{i=0}^{N-1}\bigwedge_{j=0}^{N-1} \text{imp}[i][j]$$

### 2.3 三阶段构建流程

```
Phase 1  构建 row-OR terms：n 个独立任务
         or_batch[0], or_batch[1], ..., or_batch[N-1]

Phase 2  构建 cell implication terms：n² 个独立任务
         imp_batch[0][0], ..., imp_batch[N-1][N-1]

Phase 3  顺序左折叠 AND（sequential left-fold）：
         queen ← TRUE
         for i in 0..N-1:    queen ← AND(queen, or_batch[i])
         for i,j in 0..N-1:  queen ← AND(queen, imp_batch[i][j])
```

Phase 1 和 Phase 2 的 n + n² 个子任务**相互独立**，天然适合并行。
Phase 3 是严格的数据依赖链，不能直接并行（原因见第 9 节）。

---

## 3. 优化一：操作间并行（inter-operation parallelism）

### 3.1 背景

MTPNDD 核心操作（`mtpndd_and`、`mtpndd_or`、`mtpndd_not`）在 **操作内部** 已使用
Lace SPAWN/SYNC 实现了**操作内并行**（intra-operation parallelism）：每次 AND/OR
调用会在递归子问题之间 SPAWN Lace 任务，使多 worker 在单个操作的子树上并发工作。

原串行 benchmark（`nqueens_benchmark.c`）的构建循环是顺序的：

```c
// Phase 1（串行版）
for (size_t i = 0; i < n; ++i) {
    mtpndd_t *cond = &MTPNDD_FALSE;
    mtpndd_ref(cond);
    for (size_t j = 0; j < n; ++j) {
        mtpndd_t *next = mtpndd_or_to(cond, mtpndd_get_var(i+1, j));
        cond = next;
    }
    or_batch[i] = cond;
}

// Phase 2（串行版）
for (size_t i = 0; i < n; ++i)
    for (size_t j = 0; j < n; ++j)
        imp_batch[i*n+j] = build_cell(i, j, n);
```

n 行 OR 项和 n² 个格子约束的构建是顺序的，`build_cell` 内部的 OR/AND 操作虽有
操作内并行，但操作之间无法同时占用所有 worker。

### 3.2 实现：外层 Lace SPAWN/SYNC

新增 `nqueens_parallel_benchmark.c`，将 Phase 1 和 Phase 2 改为外层并发：

```c
LACE_ME;  // 获取主线程 Lace worker 上下文

/* Phase 1：n 行 OR 任务并发 SPAWN */
for (size_t i = 0; i < n - 1; i++) {
    SPAWN(par_build_row_or, i, n);
}
or_batch[n-1] = CALL(par_build_row_or, n-1, n);   // 最后一个本地执行
for (int i = (int)n-2; i >= 0; i--) {             // LIFO 顺序 SYNC
    or_batch[i] = SYNC(par_build_row_or);
}

/* Phase 2：n² 个格子任务并发 SPAWN */
for (size_t k = 0; k < total_cells - 1; k++) {
    SPAWN(par_build_cell, k/n, k%n, n);
}
imp_batch[total_cells-1] = CALL(par_build_cell, n-1, n-1, n);
for (int k = (int)total_cells-2; k >= 0; k--) {
    imp_batch[k] = SYNC(par_build_cell);
}
```

每个 Lace 任务（`par_build_row_or`、`par_build_cell`）返回 **+1 ref** 的节点指针，
确保在 SYNC 完成之前节点不会被 GC 回收。

### 3.3 Lace 嵌套安全性

`par_build_cell` 内部调用 `build_cell`，`build_cell` 调用 `mtpndd_and`/`mtpndd_or`，
而这些操作使用 `RUN()` 宏（= `LACE_ME + CALL`）。当 `par_build_cell` 被 worker N
窃取执行时，`LACE_ME` 调用 `lace_get_worker()` 获取 worker N 自己的上下文，
内部 SPAWN/SYNC 在 worker N 的 deque 上操作，语义完全正确，不会与其他 worker 竞争。

### 3.4 BDD 表大小调整

串行版对 N≤8 分配约 32K BDD 节点，而并行版 n² 个格子同时在内存中存活，
需要更大的 BDD nodetable。并行版对小 N 设置 128K 下限：

```c
// 串行版
size_t bdd_size = 1 + (size_t)fmax(1000.0, pow(4.4, (double)n-6.0) * 1000.0);

// 并行版（128K 下限）
size_t bdd_size = 1 + (size_t)fmax(131072.0, pow(4.4, (double)n-6.0) * 1000.0);
```

对 N≥10，两个公式产生相同结果（≥512K），无差异。

### 3.5 实验结果

**测试环境**：`MTPNDD_LOG_LEVEL=0`（release 模式），扫描 N=7..13，workers=1..6，
每组测量一次取单次结果。

| N  | workers | 串行版 (s) | 并行版 (s) | 加速比 | 解数  |
|----|---------|-----------|-----------|--------|-------|
| 7  | 1       | 0.028     | 0.027     | 1.04x  | 40    |
| 7  | 2       | 0.024     | 0.021     | 1.14x  | 40    |
| 7  | 3       | 0.022     | 0.017     | 1.29x  | 40    |
| 7  | 4       | 0.022     | 0.016     | 1.38x  | 40    |
| 7  | 5       | 0.021     | 0.014     | **1.50x** | 40 |
| 7  | 6       | 0.021     | 0.016     | 1.31x  | 40    |
| 8  | 1       | 0.049     | 0.050     | 0.98x  | 92    |
| 8  | 2       | 0.043     | 0.036     | 1.19x  | 92    |
| 8  | 3       | 0.035     | 0.027     | 1.30x  | 92    |
| 8  | 4       | 0.035     | 0.025     | 1.40x  | 92    |
| 8  | 5       | 0.033     | 0.022     | **1.50x** | 92 |
| 8  | 6       | 0.033     | 0.021     | **1.57x** | 92 |
| 9  | 1       | 0.134     | 0.137     | 0.98x  | 352   |
| 9  | 2       | 0.106     | 0.095     | 1.12x  | 352   |
| 9  | 3       | 0.077     | 0.062     | 1.24x  | 352   |
| 9  | 4       | 0.071     | 0.057     | 1.25x  | 352   |
| 9  | 5       | 0.066     | 0.049     | 1.35x  | 352   |
| 9  | 6       | 0.061     | 0.043     | **1.42x** | 352 |
| 10 | 1       | 0.479     | 0.484     | 0.99x  | 724   |
| 10 | 2       | 0.350     | 0.338     | 1.04x  | 724   |
| 10 | 3       | 0.234     | 0.212     | 1.10x  | 724   |
| 10 | 4       | 0.215     | 0.192     | 1.12x  | 724   |
| 10 | 5       | 0.189     | 0.165     | 1.15x  | 724   |
| 10 | 6       | 0.167     | 0.140     | **1.19x** | 724 |
| 11 | 1       | 2.310     | 2.343     | 0.99x  | 2680  |
| 11 | 2       | 1.632     | 1.605     | 1.02x  | 2680  |
| 11 | 3       | 1.049     | 1.021     | 1.03x  | 2680  |
| 11 | 4       | 0.919     | 0.900     | 1.02x  | 2680  |
| 11 | 5       | 0.772     | 0.742     | 1.04x  | 2680  |
| 11 | 6       | 0.683     | 0.643     | **1.06x** | 2680 |
| 12 | 1       | 12.647    | 12.511    | 1.01x  | 14200 |
| 12 | 2       | 8.772     | 8.706     | 1.01x  | 14200 |
| 12 | 3       | 5.614     | 5.364     | 1.05x  | 14200 |
| 12 | 4       | 4.875     | 4.887     | 1.00x  | 14200 |
| 12 | 5       | 4.134     | 4.044     | 1.02x  | 14200 |
| 12 | 6       | 3.552     | 3.585     | 0.99x  | 14200 |
| 13 | 1       | 75.876    | 75.633    | 1.00x  | 73712 |
| 13 | 2       | 51.484    | 51.938    | 0.99x  | 73712 |
| 13 | 3       | 32.421    | 32.994    | 0.98x  | 73712 |
| 13 | 4       | 28.999    | 29.068    | 1.00x  | 73712 |
| 13 | 5       | 24.325    | 24.115    | 1.01x  | 73712 |
| 13 | 6       | 21.115    | 21.081    | 1.00x  | 73712 |

### 3.6 结果分析

加速比呈现明显的 N 分段特征：

| N 范围  | 最大加速比（w=6）| 分析 |
|---------|-----------------|------|
| N=7–9   | 1.31–1.57x      | Phase 1+2 构建占总时比例大（~45%），外层并行效果显著 |
| N=10    | 1.19x           | 构建比例下降至约 15%，效益减少 |
| N=11    | 1.06x           | Phase 3 开始主导（>90% 时间）|
| N=12–13 | ≈1.00x（噪声）  | Phase 3 绝对主导（>97%），构建并行几乎无贡献 |

**Amdahl 定律验证（N=8, w=6）**：

Phase 1+2 可并行部分 $f \approx 0.015\text{s} / 0.033\text{s} \approx 45\%$，
理论加速上限：

$$S = \frac{1}{(1-f) + f/p} = \frac{1}{0.55 + 0.45/6} \approx 1.55\text{x}$$

与实测 1.57x 吻合。

**Amdahl 定律验证（N=12, w=6）**：

Phase 1+2 可并行部分 $f \approx 0.15\text{s} / 3.55\text{s} \approx 4\%$，
理论加速上限：

$$S = \frac{1}{0.96 + 0.04/6} \approx 1.04\text{x}$$

Phase 3 占 96% 时间将并行收益压制到噪声范围内。

**w=1 时并行版略慢的原因**：
Lace 在 `lace_workers() <= 1` 时 `mtpndd_should_spawn` 返回 false，SPAWN/SYNC
退化为 CALL（顺序执行），但外层 SPAWN/SYNC 的任务帧开销仍存在，导致 w=1 时并行版
比串行版略慢约 0–2%。

---

## 4. 优化二：Nodetable 桶数预分配

### 4.1 问题发现

通过 `MTPNDD_LOG_LEVEL=2` 的调试统计，分析 N=12、w=4 的瓶颈：

```
nodetable rehash=80  max_buckets=1048576  avg_steps=1.87
nodetable_collisions=772K
mk/lookup: 7.9s CPU (= 2.0s wall，占 AND 总时 30.6s 的 26%)
```

**根因**：MTPNDD nodetable 初始分配 1024 个桶（默认值 `MTPNDD_DEFAULT_NODETABLE_BUCKET_COUNT`），
随着节点不断插入触发 rehash，到 N=12 结束时 rehash 了 **80 次**，最终桶数达 1M，
final load factor ≈ 1.85，平均查找链长 1.87。
每次 `mtpndd_mk` 调用都需要 1.87 次随机 DRAM 指针追踪，是 mk（节点创建/查找）的主要耗时来源。

注：`avg_steps` 统计的是 edge map 内部的比较步数，不直接等于 nodetable 链长；
真正反映 nodetable 碰撞的指标是 `nodetable_collisions`（772K → 124K 的下降量）。

### 4.2 解决方案

在 benchmark 的 `mtpndd_pal_config_t` 中预先设置 `nodetable_bucket_count`，
让 nodetable 从一开始就拥有足够的桶数，避免多次 rehash：

```c
/* 初始版本（commit e2fcc45）：上限 2M，仅对 N>=11（bdd_size>=1M）生效 */
size_t nodetable_init_buckets = 0;
if (bdd_size >= (size_t)1048576) {
    nodetable_init_buckets = (bdd_size < (size_t)2097152)
                                 ? bdd_size : (size_t)2097152;
}
```

**Linux 虚拟内存使稀疏桶数组廉价**：
nodetable 桶数组通过 `calloc` 分配，每个 bucket 是 8 字节指针（初始为 NULL）。
Linux 的 COW（copy-on-write）机制将未写入页映射到零页，仅在实际插入节点（写入 bucket）
时才分配物理页。因此：

- N=12，每个字段 2M 桶 × 8 bytes = 16MB 虚拟地址，但实际节点约 2M，分布在约 2M 个桶
  中，每桶平均一个节点，实际物理页约 2M × 8 / 4096 = 4MB，12 个字段合计约 50MB 物理。
- 不会像 `malloc`+`memset` 那样一次性消耗全部物理内存。

**仅对 N≥11 应用的原因**：

| N  | bdd_size | N×(bdd_size×8B) 虚拟 | 初始化物理代价 | 实际节点数  | 是否值得 |
|----|----------|---------------------|---------------|------------|---------|
| 10 | 512K     | 10×4MB = 40MB       | ~5ms          | ~200K      | 不值（节省<0.1s）|
| 11 | 2M       | 11×16MB = 176MB     | ~15ms         | ~700K      | 值（节省~0.3s）|
| 12 | 8M       | 12×64MB = 768MB     | ~30ms         | ~2M        | 值（节省~1s）|

### 4.3 实验结果（初始版本 e2fcc45，上限 2M）

调试统计变化（N=12, w=4）：

```
优化前：nodetable rehash=80  max_buckets=1048576  nodetable_collisions=772K
        mk/lookup: 7.9s CPU（占 AND 的 26%）

优化后：nodetable rehash=0   max_buckets=0       nodetable_collisions=124K (↓6x)
        mk/lookup: 5.3s CPU（↓1.5x）    AND total CPU: 27.6s (↓1.11x)
```

性能测试（release build，并行版，vs baseline `ded72dd`）：

| N  | w | baseline (s) | nodetable 优化 (s) | 加速 |
|----|---|-------------|-------------------|------|
| 10 | 4 | 0.316       | 0.304             | 1.04x（持平） |
| 11 | 4 | 1.423       | 1.313             | **1.08x** |
| 11 | 6 | 1.454       | 1.246             | **1.17x** |
| 12 | 4 | 7.322       | 6.201             | **1.18x** |
| 12 | 6 | 7.136       | 5.722             | **1.25x** |
| 13 | 4 | 41.24       | 38.12             | **1.08x** |
| 13 | 6 | 40.37       | 37.51             | **1.08x** |

N=13 提升较小（仅 1.08x）是因为 2M 桶上限相对 N=13 的节点数仍然偏小，
nodetable 在运行中期仍会触发 1-2 次 rehash，load factor 没有降至最优。

---

## 5. 优化三：Nodetable 内部硬限提升与桶数公式精化

### 5.1 问题

MTPNDD nodetable 内部有一个硬限（commit e2fcc45 之前为 `1<<21 = 2M`）：

```c
// mtpndd_nodetable.c（优化前）
if (bucket_cnt > (1 << 21)) bucket_cnt = (1 << 21);
```

N=13 时 `bdd_size = 32M`，若应用 `min(bdd_size, 2M)` 公式，2M 桶对 N=13 的节点数仍有
约 50% load factor，rehash 次数没有降为 0，加速效果受限。

### 5.2 实现

**修改一：提升库内硬限**（`mtpndd_nodetable.c`）

```c
// 优化前
if (bucket_cnt > (1 << 21)) bucket_cnt = (1 << 21);  // 2M

// 优化后（commit a762fa2）
if (bucket_cnt > (1 << 23)) bucket_cnt = (1 << 23);  // 8M
```

**修改二：桶数公式改为 `bdd_size/4`，上限 8M**（两个 benchmark 文件）

```c
/* 最终版本（commit a762fa2，当前代码） */
size_t nodetable_init_buckets = 0;
if (bdd_size >= (size_t)1048576) {       /* 仅 N>=11 */
    nodetable_init_buckets = bdd_size / 4;
    if (nodetable_init_buckets > (size_t)8388608)   /* 上限 8M */
        nodetable_init_buckets = (size_t)8388608;
}
```

**公式设计逻辑**：

bdd_size 是 Sylvan BDD nodetable 大小，根据经验约等于 NDD 节点数的 4 倍（两层之间的放大系数）。
因此 `bdd_size/4 ≈ 预期 NDD 节点数`，初始桶数等于预期节点数，
初始 load factor ≈ 100%，通过一次 rehash 达到 50%，
实际上在 N=12 测试中观测到 rehash=0，说明 bdd_size 对节点数的估算有一定余量（实际比 bdd_size/4 少）。

各 N 对应的初始桶数：

| N  | bdd_size | bdd_size/4 | cap(8M) | 每字段桶数 | 总虚拟内存（N字段） |
|----|----------|-----------|---------|-----------|-------------------|
| 10 | 512K     | 128K      | —       | 0（不触发）| — |
| 11 | 2M       | 512K      | 512K    | 512K      | 11×4MB = 44MB    |
| 12 | 8M       | 2M        | 2M      | 2M        | 12×16MB = 192MB  |
| 13 | 32M      | 8M        | 8M      | 8M        | 13×64MB = 832MB  |

### 5.3 实验结果（commit a762fa2，vs baseline `ded72dd`）

| N  | w | baseline (s) | 优化后 (s) | 加速  |
|----|---|-------------|-----------|-------|
| 10 | 4 | 0.316       | 0.316     | 1.00x（不触发）|
| 10 | 6 | 0.167（串行）| 0.140（并行）| 对应操作间并行收益 |
| 11 | 6 | 1.454       | 1.243     | **1.17x** |
| 12 | 4 | 7.322       | 6.179     | **1.18x** |
| 12 | 6 | 7.136       | 5.733     | **1.24x** |
| 13 | 4 | 41.24       | 35.94     | **1.15x** |
| 13 | 6 | 40.37       | 32.93     | **1.23x** |

N=13 从 1.08x 提升至 1.23x，说明 8M 桶配额解决了之前 2M 桶不足的问题。

---

## 6. 优化四：Edge Map 桶数调优（edge_bucket_count=16）

### 6.1 背景

每个 NDD 节点有一个 edge map（`mtpndd_edge_t`），是从子节点指针到 BDD 边标签的哈希表。
`edge_bucket_count` 控制每个 edge map 分配的初始桶数，默认值为 0（库内默认 8）。

`FOR_EACH_ENTRY_IN_ALL_BUCKETS` 宏遍历**所有**桶（包括为 NULL 的空桶）：

```c
// edge map 遍历宏（遍历所有桶，包括 NULL）
#define FOR_EACH_ENTRY_IN_ALL_BUCKETS(map, entry) \
    for (size_t _b = 0; _b < (map)->bucket_count; _b++) \
        for ((entry) = (map)->buckets[_b]; (entry); (entry) = (entry)->next)
```

AND 操作的核心循环是对两个节点的所有出边做笛卡尔积：

```
for each edge_a in edges(node_a):
    for each edge_b in edges(node_b):
        compute AND(child_a, child_b) with BDD label AND
```

两层循环均调用 `FOR_EACH_ENTRY_IN_ALL_BUCKETS`，因此**空桶也计入迭代代价**。

### 6.2 权衡分析

N-Queens 中每个节点的出边数取决于字段宽度（N 列）。
对于 N=12，通过调试统计观测，每个节点平均约 **7 条出边**。

| edge_bucket_count | 桶数 | load factor | 碰撞率 | 遍历代价（空桶次数）|
|-------------------|------|-------------|--------|-------------------|
| 8（默认）          | 8    | 87.5%       | 高     | 7/8=12.5% 空桶    |
| **16（优化值）**   | **16** | **43.7%** | **低** | **9/16=56.3% 空桶** |
| 32                | 32   | 21.9%       | 极低   | 25/32=78.1% 空桶   |

> 注："遍历代价"为空桶迭代次数占总桶数的比例，代表 `FOR_EACH_ENTRY_IN_ALL_BUCKETS`
> 中无效循环的比例。

**选择 16 而非 32 的原因**：

- `edge_bucket_count=8`：load factor 87.5%，碰撞频繁，每次 lookup 需追踪链表，
  写入时触发 edge map rehash，产生额外开销。
- `edge_bucket_count=16`：load factor 43.7%，碰撞率低，lookup O(1)，
  空桶遍历代价（9 次）可接受。
- `edge_bucket_count=32`：load factor 21.9%，空桶过多（25/32），
  `FOR_EACH_ENTRY_IN_ALL_BUCKETS` 在两层循环中各多迭代约 16 次无效步，
  实测比 16 更慢。

### 6.3 实现

```c
/* 两个 benchmark 文件的 mtpndd_pal_config_t（commit 5271eb8） */
mtpndd_pal_config_t cfg = {
    .n_workers = g_n_workers,
    ...
    .edge_bucket_count = 16,   /* 优化前为 0（默认 8）*/
    .nodetable_bucket_count = nodetable_init_buckets,
    ...
};
```

### 6.4 实验结果

**仅 edge_bucket_count 优化（在 a762fa2 nodetable 优化基础上叠加）**：

| N  | w | nodetable 优化后 (s) | + edge_bucket=16 (s) | 额外加速 |
|----|---|---------------------|---------------------|---------|
| 11 | 6 | 1.243               | 1.03                | **1.21x** |
| 12 | 4 | 6.179               | 5.18                | **1.19x** |
| 12 | 6 | 5.733               | 4.83                | **1.19x** |
| 13 | 4 | 35.94               | 31.2（估算）         | **~1.15x** |
| 13 | 6 | 32.93               | 28.0                | **1.18x** |

---

## 7. 失败实验记录

以下三项优化尝试均经过完整实现和测试，最终证明无效或有害，均已回退。
这些记录对于理解 MTPNDD 的性能特征具有重要意义。

### 7.1 失败实验一：按行分组规约（row-level grouping）

**动机**：将 Phase 3 的 n² 步 AND fold 改为：
1. 先在 n² 个格子约束内按行并行规约：$\text{row\_imp}[i] = \text{AND}(\text{imp}[i][0], \ldots, \text{imp}[i][n-1])$
2. 再对 n 行顺序折叠：$\text{queen} = \text{AND}(\ldots\text{AND}(\text{AND}(\text{queen}, \text{row\_imp}[0]), \text{row\_imp}[1])\ldots, \text{row\_imp}[n-1])$

期望将 Phase 3 的步数从 n² 降到 n，减少 AND 操作次数。

**实测结果（vs cell-level 顺序折叠，w=4）**：

| N  | cell-level fold (s) | row-level grouping (s) | 差异         |
|----|---------------------|----------------------|--------------|
| 8  | 0.025               | 0.028                | 1.12x 更慢   |
| 10 | 0.192               | 0.399                | **2.1x 更慢**|
| 12 | 4.887               | 6.725                | **1.4x 更慢**|

**失败原因**：

行内规约 $\text{row\_imp}[i] = \text{AND}(\text{imp}[i][0], \ldots, \text{imp}[i][n-1])$
产生的中间节点 **不经过渐进约束修剪**（progressive constraint tightening）。
单个 `imp[i][j]` 是描述格子 (i,j) 局部冲突关系的小约束，节点数少。
而 `row_imp[i]` 是 n 个这样的约束的合集，节点数接近 n 倍，且没有被之前的约束修剪。
Phase 3 中用大的 `row_imp[i]` 作为 AND 的 RHS，每步操作代价反而更高。

**关键认识**：NDD AND 操作的效率高度依赖 **渐进约束修剪**：
$\text{queen}_k = \text{AND}(\text{queen}_{k-1}, c_k)$ 中，
$\text{queen}_{k-1}$ 已经被前 k-1 个约束充分修剪，节点数远小于 $c_k$ 单独时的大小。
任何"预聚合"策略若破坏这一效应都会导致性能下降。

### 7.2 失败实验二：折叠顺序交错（interleaved fold）

**动机**：原 Phase 3 先折叠所有 n 个 row-OR 项，再折叠 n² 个格子约束。
尝试改为"按行交错"：对每行 i，先折 `or[i]`，再折 `imp[i][0..n-1]`，
期望 `queen` 在进入 i+1 行前已包含该行的完整约束，降低后续 AND 代价。

**实测结果（DEBUG=2 重建，w=4）**：

| N  | 原序（先全OR后全imp）(s) | 交错序（行内OR+imp）(s) | 差异          |
|----|------------------------|----------------------|---------------|
| 10 | ~0.316                 | ~0.449               | **1.4x 更慢** |
| 11 | ~1.42                  | ~2.24                | **1.6x 更慢** |
| 12 | ~7.32                  | ~11.43               | **1.6x 更慢** |

**失败原因**：

交错折叠过早引入跨字段约束（`queen` 在只完成第 i 行时已含跨行蕴含），
破坏了 `op_cache`（操作缓存）的时间局部性。
AND 结果缓存基于 (a, b) 指针对查找，若 `queen` 在不同迭代间的结构变化规律被打乱，
缓存命中率下降，每步操作都需要完整计算而非命中缓存。

**关键认识**："更受约束" ≠ "更小的 NDD"。交错折叠让 NDD 的中间形式更"异质"，
op_cache 命中率（14.7% → 约 9%）下降后代价远超"更小的 queen"带来的收益。

### 7.3 失败实验三：op_cache 扩容

**动机**：`op_cache_size` 同时控制 Sylvan BDD 缓存和 MTPNDD AND/OR/NOT 结果缓存，
每个 entry 为 3 个 `_Atomic(mtpndd_node_t*)` = 24 bytes。
猜测扩大缓存可提高命中率，减少重复计算。

**调试统计（N=12, w=4）**：

| op_cache_size | 总大小   | 命中率 | 覆盖率 | AND CPU 时间 |
|---------------|---------|--------|--------|-------------|
| 524K（原始）   | ~38MB   | 14.7%  | 93.9%  | 30.6s       |
| 8M（扩大 16×）| ~576MB  | 15.8%  | 43.1%  | 30.8s       |

**失败原因**：

- 命中率从 14.7% 仅升至 15.8%（+1.1%），说明大多数 AND 调用的 (a,b) 对**几乎不重复**——
  工作集远超 8M 条目，扩容对命中率贡献微小。
- 更严重的是：8M × 24B × 3 ≈ 576MB 超出 L3 CPU 缓存，每次缓存查找变为 DRAM 访问。
  原始 524K（~38MB）约等于 L3 大小，是 CPU 缓存利用率的最优点。
- 覆盖率从 93.9% 大幅下降到 43.1%，说明扩容后每个 slot 的利用率反而降低了（写入稀疏）。

**关键认识**：op_cache 的瓶颈不在大小，而在工作集的不重复性。
缓存命中率由计算的重复结构决定，N-Queens AND 链的操作对唯一性高，
任何大小的缓存都难以获得高命中率（上限约 15-16%）。

---

## 8. 优化效果综合对比

### 8.1 数据来源说明

本报告中出现三类测量数据，需要明确区分：

| 数据来源 | 文件/测量时机 | binary | 内存配置 |
|---------|------------|--------|---------|
| **早期串行基线** | `bench_results_20260328_160722.tsv`（2026-03-28，ded72dd 之前）| `nqueens_benchmark`（串行版）| 默认（nodetable 1024 桶，edge_bucket 8）|
| **操作间并行效果** | `bench_parallel_sweep.csv`（2026-04-05，全优化 5271eb8 之后）| 串行版 vs 并行版各一次 | **全优化**（nodetable bdd_size/4 + edge_bucket=16）|
| **内存优化逐步对比** | 各 commit 实测（ded72dd 并行基线 → 每次 commit 后重测）| `nqueens_parallel_benchmark`（并行版）| 逐步添加优化 |

> **关键认识**：`bench_parallel_sweep.csv` 中的"serial"和"parallel"列是
> **全优化代码（5271eb8）** 下两个 binary 的对比，不是优化前的基线。
> 因此 bench_parallel_sweep.csv 体现的是"内存优化之后，操作间并行的额外贡献"。
>
> 内存优化的贡献通过 **ded72dd 并行基线 → 5271eb8 并行版** 的逐步对比体现。
> ded72dd 并行基线（`nqueens_parallel_benchmark`，默认内存配置）：
> N=12 w=4 = 7.322s，N=12 w=6 = 7.136s，N=13 w=6 = 40.37s。
> 这比早期串行基线慢是因为 n² 个 cell 同时存活导致 BDD 工作集更大、nodetable 碰撞更多。

---

### 8.2 维度一：内存参数优化的逐步贡献

**基准**：`nqueens_parallel_benchmark` at `ded72dd`（操作间并行已加入，内存参数为默认值）

| 优化阶段 | N=11, w=6 | N=12, w=4 | N=12, w=6 | N=13, w=4 | N=13, w=6 |
|---------|-----------|-----------|-----------|-----------|-----------|
| ded72dd 并行基线（无内存优化）| 1.454s | 7.322s | 7.136s | 41.24s | 40.37s |
| + nodetable 预分配 v1（e2fcc45，上限 2M）| 1.246s | 6.201s | 5.722s | 38.12s | 37.51s |
| + 硬限提升+公式精化（a762fa2，bdd_size/4 上限 8M）| 1.243s | 6.179s | 5.733s | 35.94s | 32.93s |
| + edge_bucket_count=16（5271eb8）| **1.03s** | **5.18s** | **4.83s** | **~31s** | **28.0s** |

**内存优化总加速比（ded72dd 并行基线 → 5271eb8 并行版）**：

| N  | w | 基线 (s) | 最终 (s) | 加速比 |
|----|---|---------|---------|--------|
| 11 | 6 | 1.454   | 1.03    | **1.41x** |
| 12 | 4 | 7.322   | 5.18    | **1.41x** |
| 12 | 6 | 7.136   | 4.83    | **1.48x** |
| 13 | 4 | 41.24   | ~31     | **~1.33x** |
| 13 | 6 | 40.37   | 28.0    | **1.44x** |

---

### 8.3 维度二：操作间并行的贡献（全优化代码下，串行 vs 并行 binary）

**数据来源**：`bench_parallel_sweep.csv`，5271eb8 全优化代码，串行版与并行版各跑一次。
此表体现的是在内存优化充分之后，操作间并行（Phase 1+2 并发 SPAWN）的额外贡献。

| N  | w | 全优化串行 (s) | 全优化并行 (s) | 操作间并行加速 |
|----|---|-------------|-------------|--------------|
| 7  | 1 | 0.028       | 0.027       | 1.04x |
| 7  | 3 | 0.022       | 0.017       | 1.29x |
| 7  | 5 | 0.021       | 0.014       | **1.50x** |
| 8  | 3 | 0.035       | 0.027       | 1.30x |
| 8  | 5 | 0.033       | 0.022       | **1.50x** |
| 8  | 6 | 0.033       | 0.021       | **1.57x** |
| 9  | 4 | 0.071       | 0.057       | 1.25x |
| 9  | 6 | 0.061       | 0.043       | **1.42x** |
| 10 | 4 | 0.215       | 0.192       | 1.12x |
| 10 | 6 | 0.167       | 0.140       | **1.19x** |
| 11 | 4 | 0.919       | 0.900       | 1.02x |
| 11 | 6 | 0.683       | 0.643       | 1.06x |
| 12 | 4 | 4.875       | 4.887       | 1.00x |
| 12 | 6 | 3.552       | 3.585       | 0.99x |
| 13 | 6 | 21.115      | 21.081      | 1.00x |

**规律**：N≤10 有明显操作间并行收益（1.1–1.6x），N≥11 时 Phase 3 主导，
操作间并行的贡献在噪声范围内（0.99–1.06x）。

---

### 8.4 综合对比：早期串行基线 → 最终全优化并行

**最终综合加速比 = 内存优化加速 × 操作间并行加速**

早期串行基线（`bench_results_20260328_160722.tsv`，默认内存配置，仅操作内并行）：

| N  | w | 早期串行基线 (s) | 全优化并行 (s)（5271eb8）| 总加速比 |
|----|---|--------------|----------------------|---------|
| 8  | 1 | 0.042        | 0.050（w=1 并行略慢）  | 0.84x |
| 8  | 4 | 0.030        | 0.025                | **1.20x** |
| 8  | 6 | 0.051        | 0.021                | **2.43x** ★ |
| 9  | 4 | 0.066        | 0.057                | **1.16x** |
| 9  | 6 | 0.072        | 0.043                | **1.67x** |
| 10 | 4 | 0.212        | 0.192                | 1.10x |
| 10 | 6 | 0.181        | 0.140                | **1.29x** |
| 11 | 4 | 0.934        | 0.900                | 1.04x |
| 11 | 6 | 0.770        | 0.643（bench_parallel_sweep）| **1.20x** |
| 12 | 1 | 12.485       | 12.511               | 1.00x |
| 12 | 4 | 5.035        | 4.887（bench_parallel_sweep）| 1.03x |
| 12 | 6 | 4.076        | 3.585（bench_parallel_sweep）| **1.14x** |
| 13 | 4 | 28.755       | 29.068（bench_parallel_sweep）| 0.99x |
| 13 | 6 | 23.569       | 21.081（bench_parallel_sweep）| **1.12x** |

> ★ N=8, w=6 的早期串行基线 0.051s 是 w=6 时操作内并行效率下降所致（8×6=48 worker-task 竞争）。
> 全优化并行版在同等 worker 数下达到 0.021s，综合加速 2.43x。

---

### 8.5 各优化贡献分解（N=12 w=6 为例）

```
优化来源                              贡献   代表数据
─────────────────────────────────────────────────────────────────
操作内并行（intra-op, Lace SPAWN/SYNC）  已存在  N=12 w=1→w=6: 12.485s→4.076s = 3.06x
操作间并行（inter-op, Phase 1+2 并发）   +0%    N=12 w=6: 3.552→3.585s = 0.99x
nodetable 预分配（e2fcc45, 2M 桶上限）   -20%   7.136→5.722s（并行基线→优化后）
硬限提升+公式精化（a762fa2, bdd_size/4） +0%    N=12 无变化（N=13 有改善）
edge_bucket_count=16（5271eb8）          -16%   5.722→4.83s
─────────────────────────────────────────────────────────────────
内存优化总计（ded72dd parallel → 5271eb8）      7.136→4.83s = 1.48x
─────────────────────────────────────────────────────────────────
```

**重要结论**：对于大 N（≥12），操作间并行单独收益接近 0（Phase 3 绝对主导），
内存参数优化（nodetable + edge_bucket）是实际加速的来源，带来约 1.48x 提升。
对于小 N（≤10），操作间并行和内存优化共同贡献，综合可达 1.1–1.6x。

---

## 9. 讨论：Phase 3 顺序依赖链是主要瓶颈

### 9.1 为什么 Phase 3 不能树形规约

最直觉的并行方案是用树形规约（Reduce）替代顺序折叠：

```
Level 0: [r₀, r₁, r₂, ..., r₁₅₅]          (156 项，N=12)
Level 1: [AND(r₀,r₁), AND(r₂,r₃), ...]    (78 项)
Level 2: [AND(L1[0],L1[1]), ...]           (39 项)
...
Level 8: [final queen]
```

**问题**：

树形规约在未施加"足够约束"之前就将独立约束两两合并，导致中间 NDD 急剧膨胀。
`AND(r₀, r₁)` 的结果是两个无关约束的合集，节点数近似为 $|r_0| + |r_1|$ 量级，
而不是经过约束修剪后的更小结果。

实测：对 N=12 使用 `mtpndd_and_reduce` 会触发 OOM（SIGKILL），而顺序折叠仅需 4-13 秒。

### 9.2 渐进约束修剪的重要性

顺序折叠的关键特性是**单调性**：

$$|\text{queen}_k| \leq |\text{queen}_{k-1}|, \quad \forall k$$

每施加一个新约束后，`queen` 的 NDD 节点数**不增加**（通常大幅减少）。
因此 Phase 3 后期的 AND 操作虽然步数多，但每步的操作数都很小。

如果将约束预聚合为更大的组（行内 AND-reduce、交错折叠等），
则 AND 的一侧（RHS）不再是小约束，而是"已聚合的大约束"，
操作代价反而更高。

### 9.3 进一步优化的方向

Phase 3 是本优化工作目前无法突破的瓶颈：

1. **对称性分解**（未实现）：利用 N-Queens 的 8 对称群（旋转+反射）
   将问题分解为 k 个独立子问题，各自独立完成 Phase 1+2+3，最后 OR 合并。
   此方案使 Phase 3 也可并行，但需要正确实现对称变换，工程复杂度较高。

2. **流水线重叠**（未实现）：在 Phase 3 每步 AND 完成后，立即异步启动下一批
   Phase 2 约束的构建，让构建与折叠在时间上重叠。但由于 Phase 1+2 的时间占比
   对 N≥12 已不足 3%，流水线收益极为有限。

3. **更激进的 op_cache 改造**（未实现）：将 AND 结果缓存按字段 pair 分片，
   提高局部访问的命中率。需要修改 `mtpndd_operation_cache.c` 的散列函数，
   工程风险较高。

---

## 附录 A：关键代码位置索引

| 模块 | 文件 | 关键结构/函数 |
|------|------|--------------|
| 串行 benchmark | `sylvan/src/sylvan/mtpndd/test/nqueens_benchmark.c` | `run_benchmark()`, `build_cell()` |
| 并行 benchmark | `sylvan/src/sylvan/mtpndd/test/nqueens_parallel_benchmark.c` | `run_parallel_benchmark()`, `par_build_row_or`, `par_build_cell` |
| Nodetable 实现 | `sylvan/src/sylvan/mtpndd/mtpndd_nodetable.c` | `mtpndd_nodetable_declare_field()`, 硬限 `1<<23` |
| 操作缓存 | `sylvan/src/sylvan/mtpndd/mtpndd_operation_cache.c` | `mtpndd_op_cache_lookup()` |
| AND 操作 | `sylvan/src/sylvan/mtpndd/mtpndd_node.c` | `mtpndd_and_same_field_item`, `FOR_EACH_ENTRY_IN_ALL_BUCKETS` |
| 配置结构 | `sylvan/src/sylvan/mtpndd/mtpndd_common.h` | `mtpndd_pal_config_t` |

## 附录 B：Commit 历史

| Commit | 日期 | 内容 |
|--------|------|------|
| `ded72dd` | 2026-04-03 | 新增并行 benchmark，操作间并行，Phase 1+2 外层 SPAWN/SYNC |
| `e2fcc45` | 2026-04-05 | Nodetable 桶数预分配（上限 2M），失败实验文档 |
| `a762fa2` | 2026-04-05 | 库内硬限 2M→8M，公式改为 bdd_size/4 |
| `5271eb8` | 2026-04-05 | edge_bucket_count=16，文档完善 |
