# SRE 跨优先级列表并行

本轮实现 C / JNI / Java 的 `prioritySelectBatch`，并接入 SRE 的 BGP 与全局 RIB 安装路径。多个独立优先级列表共用一次顶层 Lace RUN，每份列表的覆盖前缀构造和候选选择都是完整子任务；Java 在所有结果返回后沿原顺序写入条件和发送路由。

## 实现与语义

旧 `prioritySelect` 只能并行一份列表的候选选择，覆盖前缀仍串行。新接口将多份列表交给同一个 Lace pool，列表内部保留原分组、顺序和 dirty suffix 语义。每份请求可有自己的 budget、group ends、start 和 multipath / intersect-first flags。

- C [接口](../../native/include/pndd.h)与[任务](../../native/src/pndd.c)：先校验全部借用输入，再计算临时节点 ID；全部任务 SYNC 后由父任务获取输出引用。任务不并发修改根引用计数。整个 batch 失败时回滚已取得的输出引用。
- [JNI](../../native/src/jni.c) 一次复制扁平请求数组并调用 C 批处理；Java 输出数组分配失败时释放 C 输出引用。
- [Java API](../../src/main/java/org/ants/jndd/parallel/ParallelNDD.java)：`PriorityRequest` 对数组做快照，借用节点；`prioritySelectBatch` 返回拥有引用的二维 `NDDNode` 数组，调用者逐个关闭。
- [SRE adapter](/home/cheney/SRE/src/main/java/sre/ndd/NDD.java) 保护整批输入直到回收、重试和输出接收结束；重复输出各自拥有一个逻辑引用。
- [calTCBatch](/home/cheney/SRE/src/main/java/bdd/NDDTCWrapper.java) 按有界块提交，只重新计算 dirty suffix；单列表保留旧路径。Java / FILTER / 非失败预算路径沿用原计算。
- [BGP updateRIB](/home/cheney/SRE/src/main/java/controlplane/process/bgp/NDDBgpProcess.java) 在原来的全部插入结束后批量计算已就绪的 prefix；再按原 prefix 顺序设置路由、准备 out queue。[全局 RIB 安装](/home/cheney/SRE/src/main/java/controlplane/rib/NDDRib.java) 也批量重算受影响的列表。

本次没有并发修改 BGP actor、接收队列或 Java route table，也没有改变消息投递顺序。跨路由器完整转发任务、跨源完整 DFS 查询仍是后续工作。

NDD GC 仍只在操作边界运行；Sylvan label GC 可在任务中协作运行。NDD 临时图和 C 工作数组按图预算计费；JNI 数组等额外运行时开销体现在 RSS 中。块内工作必须能放入预算，内存不足明确失败，不能交出半批拥有引用的结果。SRE 默认每块最多 64 份列表，允许 1..1024；该上限控制请求数，并不保证任意输入的中间图一定可容纳。

## 测量方法

F 组全 BCDD、原来的字段/变量编码、Header-Link、plan=1、trim，固定 CPU 0,2,4,6,8,10。JDK 17，Release / LTO，标签任务关闭，NDD 内部并行和已有传播/转发/可达性 batch 保持开启。native 图预算 3072 MiB、JVM 8 GiB、RSS 守卫 12 GiB、超时 900 秒。缓存 08 为 4194304、12 为 16777216；本轮所有对照明确固定 `gcHighWaterMiB=1228`，生产默认 35% 没有修改。

每配置两次独立进程，阶段内部按已归档 schedule 交错；ON、OFF、OLD 分阶段运行，没有跨阶段完全随机交错。主表使用应用 `total` 的算术均值，包含初始化和 RIB 输出。进程 wall、每次范围、峰值 RSS、任务/缓存/GC 等保存在原始数据和 summary。两次样本适合确认较大收益，不能建立细小差异的统计显著性。

12/OFF 在第一轮完成后发生一次会话切换中断。已完成数据保留在 `large12-off`，中断的 native1/r2 日志与 `interruption.json` 也保留；缺少的完整第二轮放在 `large12-off-r2`，使用 `--repeat-start 2 --repeats 1`。两组 JNI/JAR 哈希一致，合并统计只包含完成的独立样本，未把中断尝试当作成功或偷偷删除。

三个对照分别是：

1. **ON**：新 core / JAR / adapter，启用跨列表批次。
2. **OFF**：同一个新构建，关闭跨列表批次，隔离新增宏观并行层的贡献。
3. **OLD**：修改前冻结的 core / JAR / SRE classes，与本轮相同 cache、GC 水位和资源设置重新运行。不是引用上一轮历史耗时。

Java 使用原 Java adapter 冻结 classes 和 canonical-terminal 修复后的 Java NDD JAR；没有把本轮 evolving native adapter 的 Java 分支当成原始控制。旧 native 的实际 classpath 由 `--native-classes`、`--native-jar` 指定，元数据中的 `native_control_*_sha256` 才是其实际控制 JAR / adapter 哈希。

08 第一组使用原型构建；随后只补充 JNI 极限长度校验和 SRE 诊断计数，再运行 12 的最终构建。两者的任务算法相同，二进制哈希不同，分别保留在各组元数据中。不要把所有样本说成同一个二进制。

## 结果与审计

本轮完成 **44 个完整进程样本**：08/MF3 的 ON / OFF / OLD 共 14 次，12/MF3 共 16 次，另外七组输入的六线程回归各 2 次。另保留一份中断尝试，不纳入成功计时均值。

完整表在 [tables.md](tables.md)，各阶段与范围在 [summary.json](summary.json)，合并明细在 [runs.csv](runs.csv)。[checks.json](checks.json) 的 `valid=true`：校验成功状态、重复次数、字段布局/链接顺序/极性、完整 RIB 文本和数值计数、条件指纹、reachability CSV 与此前冻结矩阵一致；ON/OFF 构建、输入、容量、其他开关、亲和性也匹配。结构条件指纹属于概率性回归检查，另由小域显式枚举及生命周期测试补充。

| 输入 | 原 Java s | 新 native1 s | 新 native2 s | 新 native6 s | 自身 1/6 | Java/新 6 |
|---|---:|---:|---:|---:|---:|---:|
| 08/MF3 | 8.088 | 7.412 | 未测 | 6.188 | 1.198× | 1.307× |
| 12/MF3 | 323.948 | 121.398 | 82.430 | 68.434 | 1.774× | 4.734× |

| 12/MF3 阶段均值 s | 原 Java | 新 native1 | 新 native2 | 新 native6 |
|---|---:|---:|---:|---:|
| SRC 计算，不含 RIB 输出 | 303.417 | 91.358 | 59.554 | 45.078 |
| 转发条件 | 5.693 | 6.305 | 6.902 | 7.112 |
| 可达性 | 12.160 | 21.620 | 13.929 | 14.135 |
| 进程 wall | 325.310 | 123.019 | 83.985 | 69.953 |
| 进程峰值 RSS MiB | 6163.0 | 3348.9 | 3185.8 | 3539.9 |

大输入的六线程 total 两次为 68.290 / 68.578 秒，SRC 计算自身加速 **2.027×**。每次有 946 次跨列表提交，覆盖 31715 份请求，平均 33.5 份/批。新增任务层覆盖之前串行的前缀准备，改善 SRC；整个进程六线程平均 CPU 使用约 2.98 个核，包含 JVM 和其他线程的 CPU 时间，并不是 Lace worker utilization 的专门计量。

新六线程峰值 RSS 均值为 Java 的约 57.4%；两次为 3346.9 / 3733.0 MiB，内存有明显波动。native1/2/6 的 adapter / label GC 都分别为 4 / 8 次，不能把这次并行收益解释为再次减少 GC 次数。

| 同构建对照，12/MF3 | native1 s | native6 s | 自身 1/6 |
|---|---:|---:|---:|
| OFF，关闭跨列表批次 | 119.645 | 117.405 | 1.019× |
| ON，开启跨列表批次 | 121.398 | 68.434 | 1.774× |

六线程 ON 比 OFF 快 **1.716×**；单线程 ON 并没有对应的改善，细小差异处于样本波动范围内。OFF 六线程 SRC 计算为 93.901 秒，ON 为 45.078 秒；adapter / label GC 仍各 4 / 8 次。外部 RUN 只从约 161.95 万降到 158.82 万，减少约 1.9%，所以不能把约 42% 的 total 时间下降仅解释为少了 JNI 入口。关键是把原本串行执行的列表前缀工作同时交给 worker。

| 修改前冻结 OLD，重新运行 | native1 s | native6 s | 旧 6 / 新 6 |
|---|---:|---:|---:|
| 08/MF3 | 7.721 | 7.882 | 1.274× |
| 12/MF3 | 113.812 | 113.107 | 1.653× |

12 的新单线程比 OLD 单线程均值慢约 6.7%，没有获得额外串行优化收益；宏观批次的实际价值取决于独立列表数和 worker 数。ON / OFF / OLD 分阶段运行，大输入 OFF 单线程两次为 124.774 / 114.516 秒，存在运行波动；不要给单线程这点差异指定未经测量的唯一原因。六线程 ON 两次约 68 秒，OFF / OLD 均超过 113 秒，收益远大于这些已观测的波动。

另外七组输入保持默认 35% 水位，六线程两次全部与冻结语义结果一致。其阶段、内存和 GC 也收录在 runs.csv；它们只作为本轮 native 回归，没有重新运行每组 Java，不能写成新的完整 Java/native 并行矩阵。

本轮最终 JNI、NDD JAR、SRE 编译类、编译 flags、源码快照和差异保存在 `final/`，文件哈希与依赖提交见 [manifest.json](final/manifest.json)；修改前的控制在 `baseline/`。性能比较使用的 source/binary 版本已冻结。

## 收益边界与后续空间

这次证实了 SRE 可以从更粗的应用任务获得收益：相对 Java 的 4.73× 同时包含 C 表示/算法优化；纯粹增加 workers 的提升是 1.77×，仍小于 N-Queens 的 3.1–3.2×。两者必须分开报告。

一份列表内部的前缀 OR 仍顺序执行。BGP process 与消息传播也保留原顺序；多个 prefix 共享的 cache / canonical table 仍有同步和访存开销。当前数据没有专门测锁等待、空闲 worker 或内核采样，不能把余下时间全部归因于某把锁。

下一步应先采样跨列表批次的成本/等待，并检查列表之间相同前缀是否重复计算；再把跨路由器转发和固定拓扑下的完整跨源查询接入有界 native 批次。当前转发和可达性在六线程下仍比 Java 慢，且当前两线程/六线程可达性基本相当；这些阶段不能靠本次 SRC 批处理自动改善。应继续保留路径/forest/PFEC 语义和确定性归并，并验证 GC 与批次大小的预算边界。

## 使用和复现

编译并安装本轮 NDD JAR / JNI 后重新编译 SRE，二者必须来自匹配版本。native 默认启用跨列表批次，可明确指定：

```sh
-Dsre.ndd.priorityBatch=true
-Dsre.ndd.priorityLists=true
-Dsre.ndd.priorityListsPerBatch=64
```

关闭这一层做对照用 `-Dsre.ndd.priorityLists=false`。它不关闭原来的单列表候选并行或 NDD 内部并行。SRE 输出 `native_priority_list_batches` / `native_priority_list_requests`（OOM 重试也计作一次提交）。

例如从 SRE 目录复现大输入新版本：

```sh
python3 experiments/parallel-ndd/benchmark.py \
  --ndd /home/cheney/NDD --java /path/to/jdk17/bin/java \
  --java-classes /path/to/frozen-java/classes --java-jar /path/to/frozen-java/ndd.jar \
  --library /home/cheney/NDD/native/build-deep/libpnddjni.so \
  --output /home/cheney/NDD/results/sre_priority_NEW \
  --sizes 12 --mfs 3 --engines java,native1,native2,native6 \
  --cache 16777216 --gc-high-water-mib 1228 \
  --priority-lists true --priority-list-block 64 \
  --cpus 0,2,4,6,8,10 --repeats 2
```

OFF 使用同构建、`--engines native1,native6 --priority-lists false`、新输出目录。OLD 再指定本目录 `baseline/libpnddjni.so`、`--native-classes baseline/sre-classes --native-jar baseline/ndd.jar` 的绝对路径。08 把 sizes 改为 8、cache 改为 4194304。各次精确命令、输入哈希、编译器/CPU/提交、source archives 与差异在对应组 metadata 和 runs 中；含未提交源码的快照比单独 commit ID 更完整。

生成最终审计与表：

```sh
python3 native/scripts/summarize_sre_priority.py results/sre_priority_batch_20261012
```

完整控制实验脚本为 [benchmark_sre_priority.sh](../../native/scripts/benchmark_sre_priority.sh)，显式指定当前 JNI、旧 JNI/classes/JAR、原 Java classes/JAR 和新输出目录；它依次运行 ON / OFF / OLD，再验证剩余七组输入。它不会在计时中重新编译或覆盖旧结果。

## 验证

- Release CTest 在 1/2/4/6 workers 全部通过：四后端及混合字段枚举、不同 budget / flags / suffix、空请求、无效后续请求、GC 后结果、整批预算不足与回收后继续使用输入。
- NDD JUnit 36 项全部执行通过，包括批次与单列表等价、请求快照、关闭/跨引擎拒绝和引用生命周期。
- SRE JUnit 38 项全部执行通过，新集成测试覆盖实际优先级列表、ECMP / 非 ECMP、dirty flag / budget tag、分块、无变化 suffix 和每次强制回收。
- Debug ASan / UBSan 在 1 和 6 workers 通过，启用 leak detection 与 UB halt。日志在本目录 `asan-1.log`、`asan-6.log`；其他检查日志也一并归档。
- 六 workers 固定到两个 CPU 的测试也通过，覆盖新增宏观任务与协作 GC：[日志](oversubscribed-6.log)。这项压力检查在全部计时结束后运行。

此前的 GC / 标签任务探针见[上一轮报告](../sre_parallel_potential_20261012/README.md)，原全矩阵与深度优化见[冻结控制报告](../sre_deep_optimization_20261012/README.md)。
