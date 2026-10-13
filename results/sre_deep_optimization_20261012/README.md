# SRE 并行 NDD 深度优化

本目录保存 2026-10-12 的逐步实现、失败/负收益实验、冻结二进制和最终矩阵。历史实验没有覆盖或改写。当前代码同时改进 C 的串行成本与 SRE 的批量提交；并行收益必须分开看 `Java/native(p)` 和 `native(1)/native(p)`。

## 最终结果：大输入已超过原 Java

72 次运行全部成功，所有审计项通过。[审计](final-checks.json)、[合并 CSV](final-runs.csv)、[均值与样本范围](final-summary.json)。以下均为两次独立进程算术平均，时间单位秒；比值大于 1 表示加速。

| fattree / MF | Java s | native1 s | native2 s | native6 s | Java/native2 | native1/native2 |
|---|---:|---:|---:|---:|---:|---:|
| 04 / 1 | 0.268 | 0.296 | 0.310 | 0.322 | 0.865× | 0.954× |
| 04 / 2 | 0.199 | 0.222 | 0.233 | 0.248 | 0.855× | 0.955× |
| 04 / 3 | 0.200 | 0.227 | 0.226 | 0.241 | 0.887× | 1.006× |
| 08 / 1 | 1.171 | 1.268 | 1.333 | 1.380 | 0.879× | 0.952× |
| 08 / 2 | 2.532 | 2.741 | 2.686 | 2.895 | 0.943× | 1.020× |
| 08 / 3 | 7.730 | 7.444 | 7.108 | 7.516 | 1.087× | 1.047× |
| 12 / 1 | 6.170 | 6.836 | 6.286 | 7.365 | 0.981× | 1.087× |
| 12 / 2 | 24.068 | 26.003 | 23.275 | 25.391 | 1.034× | 1.117× |
| 12 / 3 | 320.645 | 193.145 | 172.431 | 179.131 | 1.860× | 1.120× |

08/MF3、12/MF2、12/MF3 的 native2 均快于原 Java，分别为 **1.087×、1.034×、1.860×**。三个案例中两次 native2 时间都低于两次同案例 Java 时间，但重复数仅为二。12/MF3 的六线程也达到 **1.790×** Java 加速；相对 native1 则约 **1.078×**，两线程自身扩展性为 **1.120×**。小输入仍未全面领先；12/MF1 两线程均值约慢 1.9%，六线程在不少案例更慢。不要将这些结果表述为六核接近线性的并行收益。

与历史原 native6 的 08/MF3=41.172、12/MF2=170.593、12/MF3=608.544 秒相比，本轮为 7.516、25.391、179.131 秒，约改善 **5.48×、6.72×、3.40×**。这三组历史参考不是本轮成对控制，且包含缓存容量调整的贡献。

### 内存

| fattree / MF | Java RSS MiB | native1 RSS MiB | native2 RSS MiB | native6 RSS MiB |
|---|---:|---:|---:|---:|
| 04 / 1 | 101.3 | 128.7 | 128.8 | 134.5 |
| 04 / 2 | 78.9 | 97.9 | 97.1 | 110.5 |
| 04 / 3 | 88.7 | 97.9 | 100.3 | 112.9 |
| 08 / 1 | 408.9 | 424.5 | 445.7 | 413.1 |
| 08 / 2 | 581.4 | 507.1 | 504.0 | 509.9 |
| 08 / 3 | 854.2 | 737.5 | 701.3 | 765.8 |
| 12 / 1 | 2523.8 | 1235.1 | 1216.9 | 1200.4 |
| 12 / 2 | 3030.3 | 1945.1 | 2284.0 | 1960.1 |
| 12 / 3 | 6167.1 | 3330.4 | 3735.3 | 3183.9 |


12/MF3 native2 的 RSS 均值为 Java 的 **60.6%**；两次范围为 3120.7–4349.9 MiB，Java 为 6163.7–6170.4 MiB。native6 均值约为 Java 的 51.6%。较初始小缓存 native6，内存用量上升：12/MF3 历史约 2233 MiB，本轮约 3184 MiB。较大缓存和节点存储是时间/内存权衡，不能同时宣称比旧 native 更省内存。表中 RSS 包括计时后校验；`timed_peak_rss_mib` 和过程计数另见 CSV。

### 收益来自哪里，限制在哪里

| 12 / MF3，均值 s | Java | native1 | native2 | native6 |
|---|---:|---:|---:|---:|
| 初始化 | 1.202 | 1.172 | 1.119 | 1.194 |
| SRC 计算 | 300.303 | 93.087 | 88.596 | 94.460 |
| RIB 输出 | 1.367 | 0.794 | 0.881 | 0.817 |
| 转发条件 | 5.275 | 23.047 | 21.826 | 24.038 |
| 可达性 | 12.427 | 74.963 | 59.935 | 58.551 |
| 完整进程 wall | 321.947 | 194.532 | 173.827 | 180.553 |

12/MF3 native2 的主要优势是 SRC 从 Java 的 300.30 秒降至 88.60 秒。并行也将 native 的可达性由单 worker 的 74.96 秒降到 59.94 秒；六线程为 58.55 秒，继续加线程收益很小。native 的转发和可达性仍明显慢于 Java。总耗时的改善首先来自算法、缓存、分配和接入成本下降，多核本身只带来约 12% 的自身加速。

最大输入 native1/2/6 分别有 72/74/77 次 adapter collection，以及 74/76/79 次 label collection。固定 35% 水位与保留 slab 空间，在这个输入上仍导致频繁缓存失效；后续应根据 GC 回收收益和活跃空间调整水位/检查间隔，并分别计时标记、回收和缓存重建。尚未测试更高水位，不能直接推荐增大到某个值。shared cache/规范表访存、串行 prefix 准备、归并和入口依赖也限制扩展性。`native_stolen_tasks` 不包含完整 batch 调度窃取；进程 CPU/wall 包括 JVM、GC、调度等待，不能当作有效计算利用率。

### 推荐运行方式

本次 SRE 负载优先使用 **2 workers**、关闭标签任务、开启 NDD 和四类 batch，缓存按 04/08/12 固定为 262144/4194304/16777216。兼容性默认值仍为 workers=6、cache=262144；要复现新结果需显式设置推荐参数。示例：

```sh
cd /home/cheney/SRE
java -Xmx8g -Dsre.ndd.engine=native -Dsre.ndd.workers=2 \
  -Dsre.ndd.cache=16777216 -Dsre.ndd.memoryMiB=3072 \
  -Dsre.ndd.labels=false -Dsre.ndd.collectEvery=0 \
  -Dpndd.library=/home/cheney/NDD/native/build-deep/libpnddjni.so \
  -cp "target/classes:$(cat target/cp.txt)" \
  application.config2spec.NDDConfig2spec bgp_fattree12 3 \
  backend=f order=header-link plan=1
```

以上是已验证配置，不是对其他网络的普遍最优参数。建议先用同一输入/编码核对结果，再比较 1/2/6 workers。

## 实现

1. **减少 apply 自身工作。** 小缓冲区使用栈存储，小边表使用插入排序；不同字段的操作避免重复 universe 交集；相同子节点的单边图直接计算标签。OR/DIFF 沿有序边更新 residual，空 residual 提前退出，省去整图 coverage OR。在同一 08/MF3 输入中，标签创建从早期约 720 万降到约 213 万；native1 从阶段 4 的 15.626 秒降至阶段 7 的 12.142 秒。阶段之间同时有其他改动，这不是独立因素的严格消融。
2. **优化 BCDD 内核与存储。** 检查型 AND 直接读取余因子并剪掉终端递归，保留补边规范化、任务引用保护与分配失败协议。采用 64 字节对齐的单边节点 slab、worker-local 分配与计数，以及 Release LTO。BDD、ZDD、BITSET 没有被替换成 BCDD；四后端和混合字段仍由枚举 oracle 验证。
3. **缓存和 GC。** 配置的共享缓存均分给 NDD 与标签；worker front cache 和根注册表隔离减少共享访存。标签 GC 清空标签缓存，保留 NDD memo，因为所有已分配 NDD 的标签都会标记且 NDD ID 在标签 GC 中不复用。NDD GC 仍先清空全部缓存再回收。不可变节点缓存相对 satCount，跨缺失字段因子照旧计算。adapter 从固定每 16384 个新根清缓存改为按内存压力回收，发生分配失败时保护操作数并回收重试。
4. **把完整独立工作交给线程池。** BGP 传播 AND、路由优先级选择、转发条件选择和可达性 AND 使用批次；每个 native 批次只进入一次顶层 Lace RUN。新 `prioritySelect` 按顺序准备 prefix，再并行计算选择结果。可选择先相交再差集；两条路径语义相同，路由与转发分别使用适合其图形状的次序。路由更新和 DFS 仍沿原顺序执行。预算内条件带保守标记，修改条件即使标记失效，避免重复预算相交。
5. **避免派发缓存命中的工作。** 递归任务先处理终端并查 memo，剩余未解决任务达到阈值才拆分。小批次也过滤常量和相同输入；不足四个真实 AND 时直接执行。

主要源码：[C 核心](../../native/src/pndd.c)、[JNI](../../native/src/jni.c)、[实例 API](../../src/main/java/org/ants/jndd/parallel/ParallelNDD.java)、[SRE adapter](/home/cheney/SRE/src/main/java/sre/ndd/NDD.java)、[转发批处理](/home/cheney/SRE/src/main/java/bdd/NDDPredicateWrapper.java)。编译开关与引用语义见 [native README](../../native/README.md)。

## 测量约定

固定 F 全 BCDD、Header-Link、plan=1、原始 trim、LSB-first、同一字段与链路顺序。Java 控制使用优化前归档的 SRE 源码构建的独立 classes，以及前一轮冻结的 `fixed-ndd.jar`；不使用当前 adapter 来替代原 Java。基线保留必要的 BCDD 终端规范化修正，完整版本和哈希在各阶段 metadata 中。阶段 14 之前的 Java 控制是当轮 adapter，阶段 14 起使用冻结原 Java，不能把两类控制混为同一个基线。

每种配置两次独立 JVM，交错随机顺序，绑 CPU 0,2,4,6,8,10；JVM `-Xmx8g`，native diagram budget 3 GiB，总进程 RSS 上限 12 GiB，超时 900 秒。主要指标为应用 `total`，包括初始化、SRC、RIB 输出、SPF、清理阶段；不包括 JVM 启动、计时后语义校验与 native close。另存完整进程 wall 和 RSS；wall 包含最多 50 ms 的父进程轮询延迟。两次样本不能支撑置信区间或长期稳定性的结论。

最终 native 缓存按规模固定为 04:262144、08:4194304、12:16777216 总条目，约 8/128/512 MiB；每类分一半。三种 worker 数使用同一容量。原 Java 保留原容量配置，并不是相同缓存条目/字节的内核比较；统一进程内存上限。worker front cache 每个至多 512 KiB，总额至多 diagram budget 的 1/64。Sylvan 初始最大容量比率为 1/4，大预算下减少过早 label GC；小容量压力测试保持 1/16。nativeBytes 不包括完整 Sylvan/Lace 与系统分配器开销，应以 RSS 衡量总内存。slab 预留空间计入预算且在 GC 后保留以复用，直到引擎关闭。

04/MF2、MF3 经原始 trim 后没有 BGP 路由，主要反映启动/过滤成本。小输入可能比 Java 慢；不能把它解释为控制平面并行扩展性。大输入的六线程也可能受共享表、缓存与串行 prefix/归并限制。更多线程并不自动更快。

## 数据与复现

在最终矩阵前另有 22 阶段、162 次探索性独立进程运行，汇总为
[stage-summary.csv](stage-summary.csv) 和 [stage-summary.json](stage-summary.json)。
这些试验包含不同源码、容量、GC、线程和批处理设置，用于定位改动，不能合并成一个配置的统计样本。

`final04`、`final08`、`final12` 为最终矩阵，每目录 24 次独立进程，共 72 次，含 Java/native1/native2/native6。`runs.jsonl`、CSV、原始日志、metadata、调度顺序、源码 tar 和 diff 都保留。`checkpoints` 保存关键阶段 C 源码、JNI 动态库、JAR 和编译参数；每个阶段的 metadata 对实际运行二进制/JAR 给出 SHA-256。`pre-stage22-ndd.jar` 是优先级 JNI flags 扩展前的接口版本。

[artifacts/manifest.json](artifacts/manifest.json) 保存最终 JNI 库、运行 JAR、原 Java 控制 JAR、两套 SRE classes、最终源码及编译配置的 SHA-256。动态库/JAR 哈希已与三个最终阶段的 metadata 一致性审计通过；源码的 Git 状态和 diff 也已保存。本机 Intel i7-14700KF、WSL/Linux、GCC 13.3、JDK 17；依赖固定为 Sylvan `c8bf561ee64036375f4693eb389e57ec8ef4aa6d` 和 Lace `63d8a5ea79922a234e284ee4b264fa5e4e784939`。具体工具版本、CPU 拓扑和输入文件哈希见各阶段 metadata。

最终审计：

```sh
python3 native/scripts/summarize_sre_deep.py results/sre_deep_optimization_20261012
```

脚本生成最终 CSV、均值/范围、表格与审计 JSON，要求每组每引擎恰好两次成功运行、版本哈希一致、完整 RIB 文本和数值计数、RIB/FIB 条件、reachability CSV 均一致；另将条件、路由数、字段/链路顺序与历史 54 次结果对照。结构 fingerprint 是回归校验，不是形式证明；小域枚举补充完整真值表验证。

重新构建使用新目录，防止旧 CMakeCache 把新默认开关保留为 OFF：

```sh
export JAVA_HOME=/path/to/jdk17
cd /home/cheney/NDD
cmake -S native -B native/build-deep-new -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-deep-new -j6
mvn -Dpndd.tests=true -Dpndd.library="$PWD/native/build-deep-new/libpnddjni.so" install
cd /home/cheney/SRE
mvn -Dpndd.library=/home/cheney/NDD/native/build-deep-new/libpnddjni.so test
mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt
python3 experiments/parallel-ndd/benchmark.py \
  --library /home/cheney/NDD/native/build-deep-new/libpnddjni.so \
  --java-classes /path/to/frozen-original-sre/target/classes \
  --java-jar /path/to/fixed-ndd.jar \
  --sizes 8 --mfs 1,2,3 --cache 4194304 \
  --engines java,native1,native2,native6 --repeats 2 \
  --output /path/to/new-results/final08
```

分别用 `--sizes 4 --cache 262144`、`--sizes 12 --cache 16777216` 跑另外两组。控制 classes 由前一轮 `results/sre_parallel_analysis_20261012/sources/sre-final-source.tar.gz` 解压到独立目录后构建；控制 JAR 为前一轮同目录的 `fixed-ndd.jar`。运行中不重编译、不替换 classes、JAR 或动态库。

完整三组可用 [benchmark_sre_deep.sh](../../native/scripts/benchmark_sre_deep.sh)，设置
`JAVA_CONTROL_CLASSES`、`JAVA_CONTROL_JAR`、`NATIVE_LIBRARY` 与新的 `OUTPUT_ROOT`。
汇总脚本的 `--golden` 可指定历史 JSONL 的位置。该脚本验证旧结果，不修改它。

## 验证与负收益实验

最新实现已通过 CTest 1/2/4/6 workers、NDD JUnit 35 项、SRE JUnit 37 项；新增优先级选择覆盖独占/多路径、suffix、两种求值次序、显式枚举、跨 GC 根保护及非法分组。转发测试覆盖四条规则以实际进入批处理、预算耗尽、零条件、ECMP 与端口 key 保留。ASan/UBSan（Debug、断言开启、leak 检查）通过 1/6 workers，包含四后端、混合字段、label GC、表扩容、OOM 和复用。

最终 Java 测试日志为 [ndd-final-tests.log](ndd-final-tests.log)（35 项，0 skipped）和
[sre-final-tests.log](sre-final-tests.log)（37 项，0 skipped）；另在 `taskset -c 0,2`
下运行 6 workers 的完整 C 正确性/GC 压力测试，120 秒内成功，见
[oversubscribed-final.log](oversubscribed-final.log)。该检查验证过度订阅下的 Lace GC 协作。

没有采用负收益试验作为默认：路由逐项先相交再差集（stage3），仅禁用 NDD 子任务保留线程池（stage12），以及先前共享 AND-only 和 Sylvan 缓存原型。扩大缓存也并非每次获益：stage5 减少 miss 却没有改善耗时。早期 slab/L1/细粒度试验本身收益很小，真正的大幅改善来自减少 residual 工作、避免过频 GC 和提交完整批次。
