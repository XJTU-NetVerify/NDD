# 多后端并行 Boolean NDD 首版实现

实现位于 [native/](../native/README.md) 和
[Java 实例 API](../src/main/java/org/ants/jndd/parallel/ParallelNDD.java)。
六 worker 在 N=12/13 上达到 3.44～3.59 倍 C 自身加速；Java batch 端到端比旧 Java 快
1.85～2.48 倍，RSS 为旧 Java 的约 36～56%。

旧 Java 引擎和原有实验目录保留。首版支持 Boolean NDD；不包含多终端代数运算、
完整旧 API 的兼容迁移或网络验证应用的性能结论。

## 实现与计划的对应关系

| 阶段 | 已实现内容 | 边界 |
|---|---|---|
| 0：语义与基线 | 显式枚举 oracle、Java 差分、已知解数、固定原始编码与最终 left-fold、独立实验目录 | 紧凑编码和平衡树结果单独报告 |
| 1：C/JNI 骨架 | C11/CMake、固定 Sylvan/Lace 提交、BCDD、JNI 生命周期和错误转换 | 当前 Linux 构建；一个进程只允许一个 native 引擎 |
| 2：四后端 | 无补边 C BDD、Sylvan BCDD、普通 ZDD 及显式 universe、双字 BITSET、混合字段 | BITSET 宽度 ≤7；其他后端宽度 ≤64；字段数 ≤256 |
| 3：操作内部并行 | AND 存活交集分块，OR/DIFF 局部结果与覆盖归并，EXIST 子图并行及确定性 OR 归约 | 小任务直接调用；实际任务分裂才增加 fork depth |
| 4：GC 与资源 | 根引用、临时标签保护、任务结果交接、协作停顿、缓存失效、节点复用 cookie、预算错误与恢复 | NDD GC 在公开调用之间进行；Sylvan 标签 GC 可在操作内部进行；单次操作必须放得进预算 |
| 5：Java batch | applyBatch、AND/OR left-fold 与平衡树 reduce、N-Queens 约束按依赖层 batch 构造 | 一个 batch 一次顶层 RUN；JNI 数组复制和每个结果的 Java 包装仍有成本 |
| 6：调优与评测 | 粒度/缓存固定参数网格、1/2/4/6 workers、并行层开关、资源上限、分阶段计时与冷进程重复 | 3～4 倍自身加速是目标；是否达到由下面实测决定 |

### 借鉴和复用

复用 Lace 的同一个 worker pool，以及 Sylvan 的 BCDD/ZDD 规范存储、标签 GC 和引用栈。
参考 MTPNDD 的任务划分、规范化和引用交接思想。NDD 表、连续边存储、分片锁、缓存、
内存预算与 JNI API 是新增实现，没有修改 MTPNDD 工作树。

为避免依赖的耗尽路径直接退出 JVM，构建时只修改 Sylvan 的私有副本：两个节点分配原语
返回 INVALID，并统计创建节点。标签递归 apply 使用本项目的检查适配器；它能传播
INVALID，并可用同一 Lace pool 拆分。因此不直接调用假定分配始终成功的上游递归 apply。
依赖的提交、授权和修改范围见 [native/NOTICE](../native/NOTICE)。

Java 对外调用同步接入。内部节点一经发布不再移动；64 位句柄含 cookie，能够发现
已回收或旧引擎节点。运算借用输入并返回一个持有的根引用；`NDDNode.close()` 释放它。
关闭引擎会等待当前接入操作完成。没有增加 Java 线程池。
当前句柄采用 32 位节点 ID 和 32 位全局代际；包含预留 ID lease 在内，代际耗尽后
安全拒绝分配，需要重启进程。引擎 close 不重置代际，以防旧句柄跨引擎别名。
高节点周转的长期服务还需要扩大代际空间或调整句柄映射。

## 正确性和资源验证

原生测试用例覆盖四后端及混合字段的随机 Boolean 运算、显式枚举、EXIST、计数、
batch、归约、GC、非法输入、旧句柄、64 位字段、窄字段共享变量池及 BITSET 边界。
Java 测试将 native、旧 Java 与枚举 oracle 交叉比较。

小容量测试覆盖并行标签分配、缓存和高频 GC；预算耗尽测试要求报错后释放/回收，
再成功构造和计数。极大定义域允许 double 溢出为 Infinity，但 FALSE 必须为零，
完全约束为一个赋值时必须计数为一。

最终版本通过 Release/Debug 的 1/2/4/6 worker 测试、29 个 Maven 测试，
以及 ASan/UBSan（含 leak detection）的 1/6 worker 检查。`-Xcheck:jni` 下 N=7 BCDD
batch 得到 40 个解，没有 JNI 警告。详情见
[验证日志与哈希](../results/parallel_ndd_20261011_aligned_validation/README.md)。
没有声称完成 ThreadSanitizer 对第三方调度器的验证。

## 性能评估方法

每个配置至少五次独立进程，按固定随机种子交错运行，计时任务互不重叠。
最终组的总耗时采用外层单调时钟：专门的 wait 线程记录子进程退出，避免 RSS 的 50ms
轮询粒度影响时间。实时钟和 GNU time 的 elapsed 同时记录作诊断；出现总耗时与单调计算耗时不一致的
探索组不参与端到端结论。计算阶段使用程序内的单调时钟。

绑定 CPU 0、2、4、6、8、10（来宾系统报告的六个不同 core）。C11 Release、GCC 13.3、
Java 17；机器拓扑、依赖版本、构建选项、源文件/二进制哈希及命令保存在每组 metadata 中。

主测试使用相同 4 GiB 进程树 RSS 上限，50ms 检查一次；native 图预算和旧 Java 堆上限
均为 3 GiB，JNI JVM 堆上限 512 MiB。图预算不包括 Lace/JVM/分配器全部开销。
主对比超时设为 120 秒；平衡树在多个后端触及此上限后，另开五轮、15 秒预算的失败
验证组。两组数据分开保留，不混算。超时、超 RSS、分配失败均保留，不作为成功样本求平均。

native 使用 262,144 项共享缓存；旧 Java 每类 NDD 运算与标签后端缓存为 65,536 项。
两者不是相同的缓存字节预算。冷 JVM 包含 JIT 和 GC。跨语言收益包含表示、分配、缓存
和 JVM 成本；自身并行扩展性单独使用 `T_native(1)/T_native(p)`。

原始编码固定 N 个 N 位字段、现有约束和最终 left-fold；BITSET 在 N>7 明确拒绝。
紧凑编码使用 `ceil(log2 N)` 位行变量并约束值在 0..N-1，四后端采用同一编码。
平衡树会改变中间图，不与 left-fold 混算。入口微基准使用热缓存 AND，单列报告。

## 实测结果

### 原始编码：C 核心自身扩展性

最终版本的五次中位计算耗时，单位秒；全部成功，N=12/13 解数为 14,200/73,712。
此表测 C 直接调用，不代表 Java JNI 的端到端耗时。

| N | 后端 | 1 worker | 2 workers | 4 workers | 6 workers | 1→6 倍率 |
|---:|---|---:|---:|---:|---:|---:|
| 12 | BDD | 3.839 | 2.074 | 1.288 | 1.115 | 3.44 |
| 12 | BCDD | 3.799 | 2.041 | 1.253 | 1.104 | 3.44 |
| 12 | ZDD | 3.808 | 2.076 | 1.268 | 1.105 | 3.45 |
| 13 | BDD | 27.174 | 14.781 | 9.167 | 7.575 | 3.59 |
| 13 | BCDD | 26.689 | 14.613 | 9.182 | 7.561 | 3.53 |
| 13 | ZDD | 26.699 | 14.926 | 9.276 | 7.552 | 3.54 |

最终版本达到约 3.44～3.59 倍自身扩展性。
单 worker 的 native 计算仍慢于旧 Java；串行成本与扩展性需要分别评估。
[最终交错矩阵](../results/parallel_ndd_20261011_aligned_final/README.md)。

### 原始编码：内存

| N | 后端 | C 6 workers RSS MiB | 旧 Java RSS MiB |
|---:|---|---:|---:|
| 12 | BDD | 177.2 | 474.6 |
| 12 | BCDD | 184.6 | 458.9 |
| 12 | ZDD | 184.4 | 475.7 |
| 13 | BDD | 856.2 | 2257.2 |
| 13 | BCDD | 863.4 | 2629.8 |
| 13 | ZDD | 862.9 | 2258.3 |

这里报告进程 RSS，包含依赖和调度开销；不同缓存布局和表示均会影响内存。
Java API 的 RSS 在配对比较中另列。

### 最终版本：工作拆分与热点计数

原始编码 N=12、13 分别创建 1,848,512、9,355,911 个 NDD 节点，所有 worker 数一致。
6 workers 分别创建约 5,693、6,214 个 NDD 任务；N=13 记录约 2,400 次任务迁移。
主要工作位于最终 left-fold：N=13 的约 7.55～7.58 秒并行耗时中，构造约 0.02 秒、计数约 0.03 秒。
BDD/BCDD 共享缓存命中率约 57%，ZDD 约 66～67%；包含 NDD 和标签条目。

`merge_ns` 是 worker 归并时间之和：N=13/BCDD 单 worker 约 14.0 秒，6 workers 约 18.9 秒。
不能将 18.9 秒除以并行 wall time 得到串行占比。尚未得到完整任务 DAG 上的串行归并 span；
现有计数支持定位归并成本，仍需更细粒度 profiling 分离锁等待、重复计算和真正的串行关键路径。
迁移计数覆盖 NDD、标签和归约任务；batch 任务的迁移尚未单独计入，不能把零计数
解释为 batch 没有发生工作窃取。

### 冻结版本：分层并行与归约顺序

N=12 原始编码、6 workers、五次计算耗时中位数（秒）：

| 后端 | 两层关闭 | 仅标签 | 仅 NDD | 两层开启 |
|---|---:|---:|---:|---:|
| BDD | 3.788 | 3.783 | 1.630 | 1.554 |
| BCDD | 3.714 | 3.753 | 1.618 | 1.599 |
| ZDD | 3.754 | 3.692 | 1.703 | 1.647 |

收益主要来自 NDD 层。两层开启相对仅 NDD 约快 1～5%，但标签小任务明显增加；
默认保持参数网格中选出的标签拆分关闭配置，用户可通过开关测量具体负载。
这些是独立 ablation cohort 的结果，不与 scaling cohort 的单 worker 时间混算。

原始编码 N=10 平衡树：BDD/BCDD/ZDD 共 15 个样本均超过独立的 15 秒预算；
同 cohort 的 left-fold 约 0.08 秒。此前 120 秒探索也出现三个后端的超时。
小规模枚举、N=6 原生及 N=7 JNI 的平衡树结果正确；大规模耗时差不能解释为语义错误。
超时过程未完成，不能据此精确归因于内存或节点数；归约顺序改变的工作量需进一步 profiling。

### Java API：最终版本 N=12

五轮全部得到 14,200 个解。单调总耗时包含 JVM 启动、初始化和清理。

| 后端 | workers | 逐项 JNI 计算 / 总耗时 s | batch JNI 计算 / 总耗时 s | batch RSS MiB |
|---|---:|---:|---:|---:|
| BDD | 1 | 4.157 / 4.303 | 3.987 / 4.127 | 215.1 |
| BDD | 6 | 1.553 / 1.662 | 1.201 / 1.314 | 217.1 |
| BCDD | 1 | 6.136 / 6.283 | 4.088 / 4.225 | 252.4 |
| BCDD | 6 | 2.555 / 2.695 | 1.263 / 1.398 | 254.7 |
| ZDD | 1 | 6.232 / 6.376 | 4.064 / 4.207 | 244.6 |
| ZDD | 6 | 2.678 / 2.828 | 1.350 / 1.490 | 247.1 |

batch 保持相同约束和最终 left-fold，将构造按依赖层打包。顶层 RUN 由约 11,209 次
减少到 44 次，NDD GC 由 93 次减少到 7 次。这包含入口和 GC 频率变化，不能只解释为递归并行加速。
[最终 N=12 JNI 数据](../results/parallel_ndd_20261011_aligned_jni/README.md)。

### 缓存行隔离前：N=13 JNI 逐项与 batch

这是缓存行隔离前的参考矩阵，五轮全部得到 73,712 个解。最终 N=13 batch 性能见下面配对比较。

| 后端 | workers | 逐项 JNI 计算 / 总耗时 s | batch JNI 计算 / 总耗时 s | batch RSS MiB |
|---|---:|---:|---:|---:|
| BDD | 1 | 28.321 / 28.505 | 28.024 / 28.224 | 893.5 |
| BDD | 6 | 11.817 / 12.080 | 11.161 / 11.325 | 895.6 |
| BCDD | 1 | 30.201 / 30.400 | 27.416 / 27.613 | 956.4 |
| BCDD | 6 | 13.239 / 13.452 | 11.223 / 11.438 | 958.1 |
| ZDD | 1 | 30.877 / 31.330 | 27.321 / 27.532 | 941.4 |
| ZDD | 6 | 13.873 / 14.094 | 11.563 / 11.761 | 943.7 |

N=13 的最终组合占比更高，batch 对入口和构造的改善占总耗时比例较小。
[完整 N=13 JNI 数据](../results/parallel_ndd_20261011_jni_large/README.md)。

### Java API 与旧 Java 的配对比较

最终矩阵将 C 1/2/4/6 workers、旧 Java 和 6-worker JNI batch 放在同一交错 cohort；
每个配置五个独立进程。比较单调时钟端到端耗时，包含启动、构造、组合、计数和清理。

| N | 后端 | 旧 Java 总耗时 s | JNI batch 总耗时 s | 收益倍率 | Java / JNI RSS MiB |
|---:|---|---:|---:|---:|---:|
| 12 | BDD | 3.242 | 1.328 | 2.44 | 474.6 / 217.0 |
| 12 | BCDD | 2.726 | 1.476 | 1.85 | 458.9 / 254.7 |
| 12 | ZDD | 3.237 | 1.476 | 2.19 | 475.7 / 247.1 |
| 13 | BDD | 20.346 | 8.204 | 2.48 | 2257.2 / 896.7 |
| 13 | BCDD | 19.017 | 8.235 | 2.31 | 2629.8 / 959.6 |
| 13 | ZDD | 19.878 | 8.385 | 2.37 | 2258.3 / 944.6 |

Java API 端到端收益约 1.85～2.48 倍，RSS 约为旧 Java 的 36～56%。
这是产品路径的整体收益，不能视为单独的线程加速比。
[最终配对数据与源快照](../results/parallel_ndd_20261011_aligned_final/README.md)。

### 热缓存入口微基准

10,000 次缓存命中 AND，batch 大小 100，禁用 GC 和 batch 拆分。
计算时间包含释放引用，Java 还包含包装对象和数组复制；预热在计时外。微秒/操作，五次中位数：

| 入口 | workers | 逐项 | batch |
|---|---:|---:|---:|
| c | 1 | 10.658 | 0.130 |
| c | 6 | 0.623 | 0.086 |
| jni | 1 | 11.240 | 0.301 |
| jni | 6 | 0.761 | 0.225 |

单 worker 绑定一个 CPU，调用线程与 Lace worker 共享该 CPU，调度交接成本明显。
6 workers 允许调用方和 worker 在多个 CPU 上运行。这不是纯 JNI ABI 延迟，不能外推为复杂运算加速比。
[最终入口微基准](../results/parallel_ndd_20261011_aligned_entry/README.md)。

### 与 MTPNDD 的速度和内存取舍

最终核心与 MTPNDD 原驱动的 N=12/BCDD/6-worker 五轮交错对照：

| 实现 | 计算中位 s | 单调总耗时 s | RSS MiB |
|---|---:|---:|---:|
| 新 C 核心 | 1.105 | 1.221 | 184.6 |
| MTPNDD 原驱动 | 1.215 | 1.291 | 1585.7 |

新 C 核心计算约快 10%，总耗时约快 6%，RSS 约为 MTPNDD 的 1/8.6。
这是 C 驱动间的对比，不代表包含 JVM 启动的 Java API 也快于 MTPNDD C 驱动。
[最终配对数据](../results/parallel_ndd_20261011_aligned_mtp_pair/README.md)。

较早完整矩阵中，MTPNDD N=12 的 1/2/4/6 worker 计算中位数为
4.948 / 2.796 / 1.585 / 1.188 秒；N=13 的 20 个样本均触及共同 4 GiB RSS 上限。
不能从失败样本推断完成时间。原驱动固定表容量，不能设置相同 3 GiB 图预算，初始化计时范围也不同。
因此这些是共同进程上限下的实际可运行性和驱动速度对比，不能当作相同内部配置的算法比较。
[完整 MTPNDD 矩阵和源码/构建补充记录](../results/parallel_ndd_20261011_mtp/README.md)。

### 紧凑编码

紧凑行变量使 BITSET 能参与 N=10～13 的共同编码对比，但并不保证减少 NDD 图。
冻结版本 N=13 的 6 workers 计算中位数为 BDD 12.898、BCDD 12.902、ZDD 13.206、
BITSET 11.197、混合字段 12.335 秒，RSS 约 1,712～1,721 MiB。
同一版本原始编码的 RSS 约 855～862 MiB；编码变化在这个负载上增加了中间图成本。
旧 Java 紧凑编码 N=13 的计算耗时约 12.6～13.8 秒、RSS 约 2,601～2,782 MiB。
两条编码路径单独报告，不能把 BITSET 紧凑编码直接与 BCDD 原始编码计算倍数。
[1/6 workers](../results/parallel_ndd_20261011_compact/README.md) 和
[2/4 workers](../results/parallel_ndd_20261011_compact-middle/README.md) 共同构成完整矩阵。

### 数据版本和失败实验

修正大定义域 FALSE 计数、将 batch 自动 GC 合入同一次 RUN、将归并计时改成
worker 局部统计之前，已完成的矩阵作为冻结版本保留。
[冻结源快照和哈希](../results/parallel_ndd_20261011_frozen_sources/README.md) 用于追溯。
最终版本已重新测量；统计优化前的数据保留为独立参考。
每个后续 cohort 自带源快照、二进制哈希、所有成功和失败样本及命令。

最终版本的首次 JNI 探索中，GNU time elapsed 曾短于程序内单调计算耗时。
`jni_final_realtime`、`jni_large_realtime` 保留这一批原始数据，墙钟列不参与端到端结论；
`scaling_final_realtime` 是随后中断的探索组，不纳入最终五轮矩阵。
隔离前正式组 `jni_final`、`jni_large`、`scaling_final`，以及全部 `aligned_*` 组使用计时协议 version 2：
外层 wait 线程单调计时，同时记录实时钟、GNU time elapsed 和时钟差供检查。

早期 `ablation` 是保留的 120 秒探索；`ablation_bounded` 曾与中断遗留子进程重叠，
整组排除，不参与结论。清理后重跑的
[ablation_final](../results/parallel_ndd_20261011_ablation_final/README.md) 是正式消融组。
现在 SIGINT/SIGTERM 会终止并等待独立会话中的计时子进程，避免孤儿任务污染后续运行。

## 使用和后续改进

构建、Java 示例、所有参数及 benchmark 复现命令见 [native/README.md](../native/README.md)。
默认粒度来自 N=12/BCDD/6 workers 的六组参数网格，各组五次：threshold=4、chunk=2、
cache=262,144；默认启用 NDD 和 batch 并行，关闭标签任务拆分。

隔离后六组网格仍选择 threshold=4/chunk=2。N=12 下 cache=65,536/262,144
的中位数为 1.076/1.092 秒，小缓存约快 1.4%、少用 6 MiB；补测 N=13 则为
7.871/7.546 秒，大缓存约快 4%。默认面向较大目标保留 262,144，N=12 可选小缓存。
两组均五轮、容量参数固定；结果不宣称是其他应用的最优值。
[最终网格](../results/parallel_ndd_20261011_aligned_tuning/README.md)、
[N=13 缓存对照](../results/parallel_ndd_20261011_aligned_cache_large/README.md)。

后续优先优化归并与规范表争用、降低单 worker 成本，并增加网络验证实际负载。
若需要单次超大操作在预算内持续回收，应进一步实现操作中 NDD 协作 GC，扩展活动任务
参数和中间 NDD 结果的显式根保护；当前操作边界 GC 不能代替这项工作。
多终端代数能力需要单独的终端规范化和代数操作测试，N-Queens 无法验证它。

## 最终调优：分片锁的缓存行隔离

最初的 cache_locks 数组把多个 atomic_flag 放在同一缓存行，Unique 分片也相邻共享缓存行。
最终将 SpinLock 对齐到 64 字节，并用 aligned_alloc 分配包含它的引擎。
独立五轮交错 BCDD 对照中，N=12/13 六 worker 从 1.570/10.837 秒降到 1.084/7.619 秒；
N=12 单 worker 从 3.883 到 3.760 秒，RSS 增加约 1 MiB。
这支持缓存行争用是原瓶颈之一，但没有硬件计数器证据区分缓存锁和规范表锁的贡献。
[隔离实验](../results/parallel_ndd_20261011_lock_alignment/README.md) 保留原/候选二进制哈希和候选源码。
正式最终矩阵和测试均在采用修改后重新运行；较早的完整矩阵继续作为隔离前参考。

## 结果索引

| 实验 | 内容 |
|---|---|
| [aligned_final](../results/parallel_ndd_20261011_aligned_final/README.md) | 最终 C 扩展性、JNI batch 和旧 Java，同批五轮 |
| [aligned_jni](../results/parallel_ndd_20261011_aligned_jni/README.md) | 最终 N=12 逐项/batch JNI |
| [aligned_smoke](../results/parallel_ndd_20261011_aligned_smoke/README.md) | 最终 N=7 四后端、混合字段和完整 worker 矩阵 |
| [aligned_tuning](../results/parallel_ndd_20261011_aligned_tuning/README.md) | 最终六组参数网格 |
| [aligned_cache_large](../results/parallel_ndd_20261011_aligned_cache_large/README.md) | N=13 的两组缓存容量复测 |
| [aligned_mtp_pair](../results/parallel_ndd_20261011_aligned_mtp_pair/README.md) | 最终 C/MTPNDD N=12 六 worker 五轮交错对照 |
| [aligned_entry](../results/parallel_ndd_20261011_aligned_entry/README.md) | 最终 C/JNI 热入口微基准 |
| [aligned_validation](../results/parallel_ndd_20261011_aligned_validation/README.md) | 最终测试与 sanitizer 日志 |
| [lock_alignment](../results/parallel_ndd_20261011_lock_alignment/README.md) | 缓存行隔离的五轮对照 |
| [smoke](../results/parallel_ndd_20261011_smoke/README.md) | 原始 N=7，四后端与混合字段，1/2/4/6 workers，以及旧 Java |
| [mid](../results/parallel_ndd_20261011_mid/README.md) | 原始 N=10、11 的完整 worker 矩阵 |
| [scaling 冻结版本](../results/parallel_ndd_20261011_scaling/README.md) | 原始 N=12、13，C 与旧 Java |
| [tuning](../results/parallel_ndd_20261011_tuning/README.md) | 粒度与缓存的固定参数网格 |
| [compact](../results/parallel_ndd_20261011_compact/README.md) | 紧凑 N=10、12、13，1/6 workers，四后端、混合字段与旧 Java |
| [compact-middle](../results/parallel_ndd_20261011_compact-middle/README.md) | 同一冻结二进制的 2/4 worker 补全组；续跑信息独立记录 |
| [ablation_final](../results/parallel_ndd_20261011_ablation_final/README.md) | 分层并行消融、left-fold 与带独立预算的平衡树实验 |
| [jni 冻结版本](../results/parallel_ndd_20261011_jni/README.md) | N=7、12，逐项、batch left-fold 与平衡树 |
| [jni_final](../results/parallel_ndd_20261011_jni_final/README.md) | 隔离前 N=12 的逐项与 batch left-fold |
| [jni_large](../results/parallel_ndd_20261011_jni_large/README.md) | 隔离前 N=13 的逐项与 batch left-fold |
| [scaling_final](../results/parallel_ndd_20261011_scaling_final/README.md) | 隔离前 C 1/2/4/6 workers 与旧 Java |
| [api_comparison](../results/parallel_ndd_20261011_api_comparison/README.md) | 旧 Java 与 JNI batch 的同批配对端到端比较 |
| [entry](../results/parallel_ndd_20261011_entry/README.md) | C、JNI 的热缓存入口微基准 |
| [mtp](../results/parallel_ndd_20261011_mtp/README.md) | MTPNDD 原驱动在共同 RSS 上限下的速度与可运行性 |
| [validation](../results/parallel_ndd_20261011_validation/README.md) | 测试、sanitizer、JNI 检查日志及哈希 |

所有统计以各 cohort 的 samples.csv 为准；summary.json 和 README 报告成功样本的
中位数及范围，同时保留失败数。早期探索和被排除的组不参与上述正式结论。
