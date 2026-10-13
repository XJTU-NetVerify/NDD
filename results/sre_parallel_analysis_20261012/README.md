# SRE 上并行 NDD 无收益的原因与改进实验

本次发现两个可修复的实现开销：Java native 根句柄的哈希冲突，以及每次 satCount 都创建整张节点表大小的临时数组。修复后 native 总耗时明显下降，但 **6 workers 仍慢于 1 worker，也没有超过 Java 基线**。进一步诊断显示，SRE 的多数 NDD 子问题分支很少，标签计算、任务准备、归并和 GC 占据大量时间；当前标量接入方式也没有把相互独立的顶层工作一起交给 Lace。N-Queens 的扩展性不能直接迁移到这个工作负载。

[原九组、54 次实验](../sre_parallel_20261011_final/README.md)保持不变。这里新增 89 次独立进程实验，以及 1 次单独的 JFR 诊断；重点复查 fattree08/MF2、08/MF3、12/MF2，**没有重新跑完整九组矩阵**。普通配置重复两次，profile 配置各一次，不纳入性能均值。

## 修复后的性能

所有时间单位为秒，是应用 `total` 的两次独立进程算术平均，包含初始化、SRC、RIB 输出和 SPF；进程启动、关闭、计时后校验另见各阶段 `runs.csv`。F 全 BCDD、Header-Link、原始 trim、plan=1、LSB-first 标签顺序保持一致。

### fattree08 / MF=3：同轮交错的修复前后对照

| 配置 | total | SRC 计算 | RIB 输出含计数 | SPF | 峰值 RSS MiB |
|---|---:|---:|---:|---:|---:|
| Java | 7.629 | 5.405 | 0.258 | 1.551 | 869.6 |
| 原 native 1 worker | 37.398 | 21.207 | 8.234 | 7.074 | 629.0 |
| 原 native 6 workers | 40.341 | 22.929 | 8.485 | 7.863 | 618.2 |
| 哈希修复＋节点计数缓存，1 worker | 23.379 | 16.833 | 0.147 | 6.017 | 551.7 |
| 哈希修复＋节点计数缓存，6 workers | 24.828 | 17.744 | 0.156 | 6.521 | 717.7 |

修复带来约 **1.60× / 1.62×** 的改善；修复后自身扩展性 `T(1)/T(6)=0.942×`，Java 比 native1 快约 3.06 倍。本阶段用同一 SRE adapter，原始 NDD JAR 与只覆盖哈希实现的 NDDNode.class 分离，避免把适配器版本变化混入对照。[原始 CSV](confirm/runs.csv)、[均值](confirm/summary.json)、[配置与二进制哈希](confirm/metadata.json)。

### fattree12 / MF=2：较大输入复查

| 配置 | total | SRC 计算 | RIB 输出含计数 | SPF | 峰值 RSS MiB |
|---|---:|---:|---:|---:|---:|
| 本轮 Java | 23.161 | 16.559 | 0.532 | 4.691 | 3056.2 |
| 本轮修复 native1 | 85.520 | 61.304 | 0.330 | 23.015 | 1261.4 |
| 本轮修复 native6 | 93.300 | 67.440 | 0.333 | 24.550 | 1493.5 |
| 历史原 native1（参考） | 160.989 | — | 56.269 | — | — |
| 历史原 native6（参考） | 170.593 | — | 57.600 | — | — |

相对历史数据改善约 1.88× / 1.83×；历史行来自原 54 次实验，**不是本轮成对的修复前控制组**。本轮自身扩展性仍只有 `0.917×`；Java 比 native1 快约 3.69 倍。native 仍有内存优势，native1/6 的 RSS 约为 Java 的 41%/49%，但本配置不是相同缓存字节预算的比较。[CSV](larger/runs.csv)、[均值](larger/summary.json)。

## 1. Java 接入层存在真实的哈希冲突

原 `NDDNode.hashCode()` 把 64 位句柄的高低 32 位直接 XOR。高位 allocation cookie 和低位 node ID 常常同时递增，`id ^ (id+1)` 只能产生少量值。SRE 用 `HashMap<NDDNode,Integer>` 去重并映射 native 根，因此冲突直接进入热点路径。

在 fattree08/MF2，计时结束后的真实键审计得到：

| 哈希实现 | 键数 | 不同哈希数 | 最大同哈希键数 | 冲突键对数 |
|---|---:|---:|---:|---:|
| 原实现 | 72,388 | 28,246 | 4,890 | 16,930,534 |
| 修复 | 72,394 | 72,393 | 2 | 1 |

键数量轻微变化受运行时回收与句柄分配影响；不是改变业务结果。[原审计](hash/logs/base1-r1-hash.json)、[修复审计](hash/logs/hash1-r1-hash.json)。单独的 JFR 中，主线程的 181 个 **Java 执行采样**有 161 个包含 HashMap.TreeNode 栈帧。这只描述 Java 主线程采样，不能说 HashMap 占整个程序 89% 的 CPU；native workers 不在该分母里。[JFR 摘要](jfr/summary.json)、[录制文件](jfr/baseline.jfr)。

已将 64 位混合后再折叠的哈希写入源码，equals 与生命周期语义保持一致，并加入相关 id/cookie 序列的哈希分布回归测试。[NDDNode.java](../../src/main/java/org/ants/jndd/parallel/NDDNode.java)、[回归测试](../../src/test/java/org/ants/jndd/parallel/NDDNodeHashTest.java)。

## 2. RIB 输出慢的主因是重复 satCount，不是磁盘

`NDDBgpRoute.toString()` 对 arriveTC、listTC 各调用一次 satCount。原 C 实现每次调用都 calloc 一个 `node_high` 大小的 double 数组和一个同大小的标记数组，然后计算并释放；查询大小取决于整个节点表，而不是被查询的子图。12/MF2 的诊断中共有 170,784 次计数调用。

最终原型把 **相对基数存入不可变 NDD 节点**：首次递归计算，之后复用；跨缺失字段的因子仍按原语义处理。存活节点经过 GC 保留有效值，被释放后复用的节点由 calloc 清除有效标记。计数写入发生在串行准入的查询路径，或子任务完成后的 N-Queens 根计数路径。当前 64 位布局每节点增加 **16 字节**。

该原型用 `PNDD_EXPERIMENT_SATCOUNT_CACHE=ON` 显式启用，默认 OFF；与永久哈希修复分别管理。12/MF2 的 RIB 输出从历史约 56–58 秒降到本轮约 0.33 秒；完整输出文本、数值计数和条件校验均一致。

先前 v1 原型把计数存入有界操作缓存，在小例上也有效，但存在碰撞驱逐和重复遍历，且挤占操作缓存；最终采用每节点缓存。08/MF2 同轮实验中，哈希修复 native1 为 7.522 秒，加入最终节点计数缓存为 6.479 秒；native6 为 9.251 → 8.027 秒。[两种计数缓存对照](countnode/summary.json)。

## 3. 当前并行切分没有覆盖主要工作

固定哈希与计数后，对 12/MF2 做了一次 **单 worker、带计时器的诊断**：

| 诊断指标 | 观测值 |
|---|---:|
| total（受诊断计时器影响，不入均值） | 96.504 s |
| native apply 累计时间 | 72.212 s |
| 任务准备时间 | 41.532 s |
| 标签计算累计时间 | 37.549 s |
| 子结果归并时间 | 9.018 s |
| NDD 缓存未命中并展开的子问题数 | 22,529,529 |
| 存活子任务总数 | 37,409,483 |
| 标签根调用数 | 210,842,031 |
| 标签缓存命中 / 未命中 | 237,923,953 / 268,675,999 |

**这些时间不能全部相加**：标签时间包含在准备/归并等阶段内；apply 包含所有下层工作。准备＋归并约为 apply 的 70%，只能说明该单线程诊断中工作的位置，不能直接当作全程序串行比例或硬性 Amdahl 上限——不同递归任务的准备阶段也可能互相重叠。

代码先串行计算标签交集；OR/DIFF 还先算 coverage/residual，然后才 `CALL(process_jobs)`，并行的是过滤后的子图递归。该诊断展开的子问题中：

| 存活子任务数 | 子问题数 |
|---|---:|
| 0 | 1,704,060 |
| 1 | 9,541,693 |
| 2–3 | 10,537,348 |
| 4–15 | 737,303 |
| ≥16 | 9,125 |

**96.69% 只有 0～3 个子任务**，默认阈值为 4；08/MF2 原版诊断也有 96.27% 处在这一范围。即使满足阈值，子任务可能很便宜或命中缓存。12/MF2 修复后的 native6 平均派发约 104.8 万任务、被窃取约 12.1 万，存在任务活动，却没有转化为更短耗时。

此外，通用 apply 每次展开都 calloc `jobs` 与 `edges` 两个缓冲区，按上述计数对应约 **4,506 万次临时分配**。Java 基线有复用的边工作区，并对不同字段的操作保留快路径。标签缓存未命中约占全部 native 缓存未命中的 90.7%，标签内核和分配路径都值得优先处理。[诊断数据](largeprofile/runs.jsonl)、[日志](largeprofile/logs/hashprof1-r1.log)、[当前 apply 实现](../../native/src/pndd.c)。

## 4. 标量提交、GC 和缓存清空削弱扩展性

同一 12/MF2 单线程诊断有 **567 万次 adapter apply、433 万次 external Lace RUN**。adapter apply 为 73.160 秒，C API apply 为 72.212 秒，差约 0.95 秒，包含 JNI、校验、返回等，不能全部归给 JNI。单独的 JNI 转换不是主要耗时；**外部 RUN 的提交、唤醒、等待包含在 C API 时间内**，数百万个细碎根操作又没有共享批次可窃取。所有 public 调用串行准入，在 Java 外面简单开线程也不能得到顶层并行。

GC 方面，adapter 触发 67 次回收，adapter collect 累计 17.816 秒，其中 native GC 15.080 秒、reachable 标记 1.955 秒。这些包含关系也不能重复相加。回收会清空操作缓存，后续重新计算；Java adapter 还要扫描持有槽位。

修复后仅改变回收间隔，在 08/MF3 做两轮交错对照，缓存仍为 262,144：

| 配置 | collectEvery | total | adapter GC 次数 | RSS MiB |
|---|---:|---:|---:|---:|
| native1 | 16,384 | 23.041 | 24 | 709.6 |
| native1 | 65,536 | 19.779 | 6 | 703.1 |
| native6 | 16,384 | 24.773 | 24 | 568.2 |
| native6 | 65,536 | 21.898 | 6 | 675.7 |

耗时改善约 **16.5% / 13.1%**，但并行仍无正收益。native6 RSS 上升约 19%，且只验证了这一案例，**没有全局修改默认间隔**。下一步应按内存压力和回收产出来触发，而不是把 65,536 当成通用答案。[GC 对照数据](gc/summary.json)。

## 5. 已试过哪些并行开关，为什么暂不推荐继续盲调

修复前，在 08/MF2 的两轮对照：

| 配置 | total s |
|---|---:|
| native1 | 11.068 |
| native6 默认 NDD 并行 | 13.483 |
| native6，关闭 NDD 派发、保留池 | 14.113 |
| native6，NDD 阈值提高到 64 | 14.131 |
| native6，仅标签并行，depth=2 | 13.804 |
| native6，NDD＋标签并行，depth=2 | 13.947 |
| native1，BCDD AND 内核规范化原型 | 11.089 |
| native1，Sylvan 缓存原型 | 11.516 |

标签并行不是完全没开启过；开启后仍没有收益。保留六线程池而关闭 NDD 派发也慢，说明不能只归咎于递归任务数。Sylvan 缓存原型 RSS 为 559.4 MiB，原 native1 为 413.6 MiB，未获得速度收益。两个原型继续默认关闭；这里保留安全检查和现有标签递归，仅试验 AND 归一化与缓存发布，**不是换成完整优化后的 Sylvan 内核**。[完整消融矩阵](matrix/summary.json)。

修复后，再在 08/MF3 做两轮：

| 配置 | total s |
|---|---:|
| native1 | 23.802 |
| native6 默认 threshold=4, chunk=2, depth=4 | 24.933 |
| native6 更细 threshold=2, chunk=1 | 24.474 |
| native6 仅标签并行，depth=2 | 24.717 |
| native6 NDD＋标签并行，depth=2 | 25.107 |

所有 native6 配置仍慢于 native1。小幅波动只有两次样本，不能据此证明某个线程参数稳定更优。更细任务的进程 CPU/wall 达约 2.48 核，默认约 2.27 核；**这包含 JVM、GC 和等待/自旋，不是有效计算利用率**。[修复后并行对照](postparallel/summary.json)。

## 后续解决方案与优先级

1. **SRE 传播层接入 batch，合并顶层独立工作。** 从同一轮传播中相互独立的“条件 AND 链路”入手，收集操作数后一次 applyBatch，一个批次仅一次 external RUN；结果返回后沿原有顺序更新 RIB、队列与引用，先保持控制平面依赖和结果顺序。不要并发改写共享路由表。分别比较 native1/6 的批量与标量路径、C 直接调用与 Java JNI，记录 RUN 数、构造/更新/查询时间和中间节点规模。若 RUN 数明显下降而时间不降，再定位标签/锁/内存带宽，避免把批量收益预设为多核收益。
2. **优化标签内核和并行位置。** 用安全适配层保留 OOM/GC/引用协议，借鉴 Sylvan 的余因子、补边归一化、剪枝、递归缓存与成本估计，而不是只增加 SPAWN。对耗时的标签交集/coverage 阶段分块，或在批次里并行独立标签操作；依据标签图复杂度启用任务，不只看 NDD 边数。维护显式参数根与待交接结果保护，重新做小容量 GC 压力验证。
3. **减少单线程冗余工作。** 为不同字段实现直接快路径；用 worker-local、支持递归与被窃取任务的 scratch arena 替代每子问题两次 calloc，处理好根帧寿命与内存预算；DIFF residual 可独立维护并提前剪枝的部分先优化。先确认单 worker 优于通用实现，再评估并行。
4. **GC 按压力和收益触发。** 使用当前内存、最近回收比例、存活根数和可增长预算决策，减少 adapter 全表扫描及不必要缓存失效。不能为保留命中而让缓存引用回收到的句柄；先验证 GC 安全，再探索分代或独立标签/NDD 缓存。

锁等待与内存带宽尚未取得直接硬件证据，当前不能把它们定为首要原因。已测的开关没有让 6 workers 超过 1 worker；上述 batch 和内核结构改造是后续候选，不是已实现的性能成果。SRE 当前建议继续使用 Java 基线；试用 native 可先选择 1 worker，并显式启用本报告的计数缓存。不能承诺 N-Queens 上的 3～4× 自身扩展性会出现在 SRE。

## 实现、验证与复现

永久修复：NDDNode 哈希；新增哈希回归。可选原型：节点计数缓存、BCDD AND 归一化、Sylvan 标签缓存；三个 `PNDD_EXPERIMENT_*` 默认关闭。新增 `PNDD_PROFILE` 和 SRE `sre.ndd.profile` 诊断，以及独立 `sre.ndd.nddParallel` 开关；普通运行关闭诊断，避免计时器干扰。

```sh
export JAVA_HOME=/path/to/jdk17
cd /home/cheney/NDD
cmake -S native -B native/build-sre-count -DCMAKE_BUILD_TYPE=Release \
  -DPNDD_EXPERIMENT_SATCOUNT_CACHE=ON
cmake --build native/build-sre-count -j6
mvn -Dpndd.tests=true -Dpndd.library="$PWD/native/build-sre-count/libpnddjni.so" install
cd /home/cheney/SRE
mvn -Dpndd.library=/home/cheney/NDD/native/build-sre-count/libpnddjni.so test
mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -Xmx8g -Dsre.ndd.engine=native -Dsre.ndd.workers=1 \
  -Dsre.ndd.labels=false -Dsre.ndd.nddParallel=true -Dsre.ndd.checksum=true \
  -Dpndd.library=/home/cheney/NDD/native/build-sre-count/libpnddjni.so \
  -cp "target/classes:$(cat target/cp.txt)" \
  application.config2spec.NDDConfig2spec bgp_fattree08 3 backend=f order=header-link plan=1
```

当前机器 GCC 13.3、JDK17（Java target11），CPU 绑定 `0,2,4,6,8,10`，不等同于物理核保证；JVM Xmx8g、native 3 GiB、进程 RSS 上限 12 GiB。原标签及 NDD 共用缓存 262,144 项，与 Java 分操作缓存的容量不同；每次使用冷 JVM；应用初始化及其期间的 JIT/Java GC 包含在应用计时内，main 之前的 JVM 启动另计入进程 wall。普通阶段超时 180 秒，大输入阶段 600 秒。每次新进程，顺序执行、每轮固定种子打乱配置，未同时跑性能任务。

89 次运行均成功，路线数量、字段布局、链路顺序/极性、RIB/FIB 条件摘要与原正式实验一致，每阶段排序后的完整 RIB 文本（含数值计数）也一致。[总检查](checks.json)。大型条件摘要属于概率性结构校验，由小域枚举测试补充，不能等同于完全枚举这些大图。

新增修复验证：NDD **34** 项测试、SRE **35** 项测试全部通过；计数缓存 CTest 在 1/2/4/6 workers 全部通过；ASan/UBSan、泄漏检查在 1/6 workers 通过。profile 和全部原型组合另各跑四个 CTest 配置；最终对齐后的 profile＋节点计数版本也通过全部四个配置。[验证日志](validation/ndd-hash-count-tests.log)、[SRE 日志](validation/sre-final-tests.log)、[CTest](validation/ctest-count-node.log)、[最终 profile CTest](validation/ctest-final-profile.log)、[ASan1](validation/asan-1.log)、[ASan6](validation/asan-6.log)。

原始证据按阶段存放：`profile`、`matrix`、`hash`、`countnode`、`confirm`、`largeprofile`、`larger`、`gc`、`postparallel`。每个目录保留 schedule、完整命令、runs.jsonl/csv、均值、校验、日志和元数据；JFR 单独存放。`HashAuditAgent` 只在 hash 阶段 JVM 关闭时统计，不计入应用 total。

[消融脚本](../../native/scripts/analyze_sre.py)使用输出根中的 `libs/*.so`、`baseline-ndd.jar` 和 `hash-classes`。为了避免 Maven install 改写控制组，它现在显式读取归档原始 JAR；历史命令使用当时的 m2 路径，原 JAR 字节和元数据 SHA 已留存。以 `--out NEW` 复查时，先复制所需库、原始 JAR 与 hash-classes，再选 `--phase confirm|larger|gc|postparallel`，新阶段目录必须不存在。脚本内 Java 位置是本机工具路径，换机器需调整；不得在实验期间重新安装 JAR 或覆盖库。

```sh
python3 native/scripts/analyze_sre.py --out results/sre_parallel_ANALYSIS_NEW \
  --phase confirm --repeats 2 --sre /home/cheney/SRE
# 大例：--phase larger --timeout 600
```

计数原型经历 v1（有界缓存）、v2（最终节点缓存）、v3（增加标签命中统计），不要用当前源码重建 v1 后声称得到同一实验。源码版本、库/开关映射、最终源码归档、提交与差异见 [provenance.json](provenance.json)，制品校验见 [SHA256SUMS](SHA256SUMS)。本次仅更新本地源码与报告，未提交或推送。
