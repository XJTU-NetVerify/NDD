# SRE A/B/D 后端的并行对照（2026-10-13）

比较原 Java、当前 native 1 worker 和 6 workers。A = Header-BDD / Link-BDD，B = Header-BDD / Link-ZDD，D = Header-BCDD / Link-ZDD。fattree04/08/12 × MF1/2/3 × 3 profiles × 3 engines × 2 repeats，共 162 个正式独立进程。正式运行全部成功，组内与跨组输出检查全部通过，冻结的 2809 个归档文件未变化。

## 性能结果

MF3 两次独立进程均值，单位秒。自身加速 = native1/native6；Java 加速 = 同组原 Java/native6。

| 组别 / fattree | 原 Java | native 1 | native 6 | 自身加速 | Java 加速 |
|---|---:|---:|---:|---:|---:|
| A / 08 | 9.199 | 8.495 | 5.591 | 1.52× | 1.65× |
| B / 08 | 8.893 | 7.844 | 5.195 | 1.51× | 1.71× |
| D / 08 | 9.260 | 7.738 | 5.206 | 1.49× | 1.78× |
| A / 12 | 385.119 | 288.472 | 170.449 | 1.69× | 2.26× |
| B / 12 | 185.754 | 125.705 | 62.140 | 2.02× | 2.99× |
| D / 12 | 200.338 | 129.093 | 63.938 | 2.02× | 3.13× |

**混合后端可以取得并行收益。** 12/MF3 的 B、D 均约 2.02× 自身扩展，六 workers 相对同组原 Java 约 3×。两组 native 性能接近，这两个样本且按 profile/规模分块的实验不支持把约几个百分点差异解释为稳定的 Header 后端优势。A 的自身扩展约 1.69×，六 workers 仍快于原 Java。当前结果仍低于 N-Queens 约 3.5× 的自身扩展。

收益随负载增加：08/MF3 的自身扩展约 1.49–1.52×，12/MF2 约 1.47–1.50×；MF1 的自身扩展接近 1。04 六 workers 通常更慢，启动/同步成本无法被小任务摊薄。04/MF2、MF3 在原 trim 设置下为 0 BGP 路由，不能作为大图并行压力测试。

[全部 27 个负载的时间、RSS 与阶段表](matrix/tables.md)；[逐次数据与日志哈希](matrix/audited-runs.csv)；[全部均值与两次原始时间](matrix/summary.json)；[语义校验](matrix/checks-all.json)；[样本覆盖和二进制审计](audit.json)。

两次重复只提供描述性结果。A/12/MF3 的 Java 两次为 404.032、366.206 秒，native1 为 300.784、276.161 秒，native6 为 174.093、166.805 秒，有明显波动；没有只取较快一次。B 的 native6 为 62.369、61.911 秒，D 为 64.320、63.556 秒。

## 大图阶段与内存

12/MF3 的 SRC：A native1/6 为 176.000/75.085 秒，B 为 95.496/40.086 秒，D 为 97.472/40.929 秒，各约 2.34–2.38×。总时间的扩展被其他阶段限制。D 转发从 6.938 增至 7.712 秒，可达性从 22.920 降至 13.459 秒；B 转发接近持平，可达性有约 1.65× 扩展。

A 存在明显的数据平面退化：native6 转发 32.950 秒、可达性 60.592 秒，原 Java 分别为 9.848、9.593 秒。A 的 native1/6 都有 101 次 NDD GC；B/D 都只有 4 次 NDD GC 和 6 次标签 GC。A 的回收压力与更长的数据平面阶段同时出现，本轮未单独测量 GC wall，不能把时间差全部定量归因于 GC。统一 1228 MiB 水位来自 F 组，未针对 A 重新寻优，plain BDD 的 GC/资源策略仍有优化空间。

| 12 / MF3 | Java 峰值 RSS MiB | native1 MiB | native6 MiB |
|---|---:|---:|---:|
| A | 6497.6 | 3915.6 | 3951.7 |
| B | 4848.2 | 3163.0 | 3395.5 |
| D | 5489.3 | 3698.9 | 3369.4 |

RSS 表是两次进程高水位的平均。大图 native6 低于原 Java，但部分小负载的 native 内存更高；没有“所有配置都更省内存”的结论。逐次数据保留了每次峰值。

## 固定条件

CPU 为 i7-14700KF，在 CPU 0,2,4,6,8,10 六个物理核上运行，1/6 workers 使用相同 CPU mask。JDK17，GCC13.3，Release C11/LTO。Header-Link、plan=1、原始 trim 与输入。JVM Xmx8 GiB、native 图预算 3 GiB、进程 RSS 守卫 12 GiB、单进程超时 900 秒。

04/08/12 的 native 缓存分别为 262144 / 4194304 / 16777216 项；同一规模所有 profile 与 worker 数保持一致。GC 水位统一为 1228 MiB；NDD 与 batch 并行开启，标签内部任务关闭，threshold=4/chunk=2/depth=4，priority list block=64。这些参数沿用上一轮，无逐组择优调参。

主时间为程序 `total`：包含初始化、SRC、RIB 输出和数据平面检查，不含 JVM 启动、计时结束后的校验与 native close。进程 wall、各阶段时间、峰值 RSS、CPU 时间、节点/缓存/GC/任务计数另行记录。RSS 是 `/proc` 中整个 JVM 的高水位，包含 Java heap、C/Sylvan/Lace 及其他进程内资源；3 GiB 图预算不等于进程 RSS 上限。

每个 profile/规模内，两个重复轮次各随机交错 Java/1/6 workers 和 MF；profile/规模块顺序也固定随机。每次均新进程，计时期间不编译、不并行运行其他 benchmark。

## 适配与比较边界

- native 的 plain BDD Header 跳过继承 API 的 Java raw-variable 初始化，继续使用已存在的 NDD prefix 构造接口。
- Java ZDD 保留原有直接集合族故障预算构造；native 标签是不透明条件根，改走现有 Boolean exact-failure 递推，保持 Link 的 `1=failure`、字段宽度与故障预算语义。A 的 Link 保持 `1=up`。
- Java JDD ZDD 的 raw 变量方向为降序，Sylvan ZDD 为升序。字段与 Link 身份映射相同，内部变量方向不同。跨 Java 的收益包含语言、核心算法、已有 batch 和表示差异；native 1/6 使用相同核心、编码、变量方向和参数，自身加速比是本轮最直接的并行扩展指标。没有增加优化 Java 控制组。
- 原 Java 使用此前冻结的 `/home/cheney/.cache/ndd-bench-tools/sre-java-baseline-20261012` classes 与已有 `fixed-ndd.jar`，没有改为最新 native adapter 的 Java 分支。仅扩展计时结束后的 ZDD digest，并让缺少 created-node 计数接口的旧 JDD 返回未知值 `-1`，计算路径不变。[精确差异](final/java-checksum.diff)。
- native 混合后端的标签统计只有总量，暂不能拆成 BDD/BCDD/ZDD 子计数；不再把总量重复填到每个后端，而是报告 `-1`。这些未知计数不用于性能结论。

Java 与 native ZDD 的标签摘要采用带权集合族求和：空集合族=0，只有空集的集合族=1，`F(node)=F(low)+weight(var)*F(high)`，在 64 位模运算下与变量遍历方向无关。该整数模摘要在含大量自由位的密集集合族上可能退化，尤其不适合作为未裁剪条件的唯一校验；本轮将它与小域显式赋值、完整 RIB 计数和可达性 CSV 联合使用，不能代替相等证明。BDD/BCDD 沿用结构摘要，NDD 边组合与边遍历顺序无关。

小域测试覆盖 A/B/D、Java/1/6 workers、0..3 个故障的计数、显式赋值、GC 后摘要一致；native 五项 CTest 和 SRE 六项适配测试通过。补充明确的 0/1/2/3/4 故障赋值后，混合适配测试再次通过。正式每次比较路由数、字段布局、Link 顺序/极性、条件摘要、完整 RIB 文本（含计数）和标准化可达性 CSV。跨 profile 额外核对 RIB 与 CSV，字段后端和条件摘要因表示不同不做跨 profile 相等要求。

## 归档与复现

[冻结 classes、JAR、JNI 和哈希](final/manifest.json)；[正式运行日志](matrix.log)；每个 profile/规模块保留 metadata、源码快照、完整命令和逐次 JSON/CSV。准备阶段的 `pilot`、`pilot2` 不混入正式均值；首轮 pilot 暴露的兼容错误已修复，第二轮 18 次全部通过。

```sh
# 从 NDD 根目录执行，output 必须是新目录；artifacts 使用本次冻结归档。
python3 native/scripts/benchmark_sre_mixed.py \
  --artifacts results/sre_mixed_parallel_20261013/final \
  --output results/sre_mixed_NEW --repeats 2
python3 native/scripts/summarize_sre_mixed.py results/sre_mixed_NEW
```
