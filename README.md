# Parallel MTNDD

`parallel-MTNDD` 是 NDD 的 **C 核心＋JNI＋Java API 多终端分支**。推荐入口为 `org.ants.jndd.parallel.ParallelMTNDD`：使用与并行 Boolean NDD 共用的紧凑manager、四后端和GC基础设施，扩展数值叶子、四则运算、求和消去及融合乘法求和。

本次审查并迁移了 Java [`mtndd`](https://github.com/XJTU-NetVerify/NDD/tree/mtndd) 分支的后端分派、显式universe、数组化存储、BulkBuilder和融合消去设计。新路径已支持BDD/BCDD/ZDD/BITSET及mixed字段、owned roots、协作GC、一次RUN的batch/reduce/dense导入；原 `~/mtpndd` 的C原型位于 `mtndd/`，作为兼容实现和历史数据来源保留。[迁移说明](mtndd/docs/MIGRATION.md) / [能力与边界](mtndd/docs/STATUS.md)。

## 与 parallel-NDD 的共享与差异

parallel-NDD对应远端 [`parallel`](https://github.com/XJTU-NetVerify/NDD/tree/parallel) 分支。

| 项目 | Boolean parallel-NDD | 新 ParallelMTNDD | 旧 MTPNDD兼容路径 |
|---|---|---|---|
| 值域 | 0/1 | 精确int64有理数或有限double；0/1共用 | 32-bit fraction/double |
| 标签后端 | BDD/BCDD/ZDD/BITSET/mixed | 共用全部四后端；BITSET≤7位 | BCDD |
| 存储/缓存 | 分块ID表、连续边、64-byte单边slab、版本缓存 | 共用同一manager源文件和优化开关；数值叶子单独intern | 边桶/独立leaf table、原始指针 |
| 并行 | NDD递归、标签、batch共享Lace pool | 共用Boolean内核；数值交集子问题分块；batch/reduce一次RUN | 部分算子并行，batch仍逐操作RUN |
| Java根管理 | owned NDDNode、AutoCloseable | owned MTNDDNode、AutoCloseable；跨引擎及关闭后检查 | 手动ref/deref的borrowed API，已deprecated |
| GC | 操作边界NDD回收、运算中协作标签GC | 数值叶子加入同一规范表/GC；预算与失败协议共用 | 原型协议，不能套用新路径保证 |
| 字段编号 | 从0开始 | 从0开始 | 从1开始 |

**共用代码**包括 `native/src/pndd.c`、固定Sylvan/Lace提交、构建适配、四后端、分配预算、sharded unique tables、缓存发布、root frames和Lace调度。`pmtndd_impl.inc`仅扩展数值层；保持Boolean核心的任务粒度、缓存和单边存储优化，而不是把Java全局边栈放进多个workers。

Java的两种新式引擎共用准入门，一次只运行一个引擎/worker pool，关闭后可以切换。旧兼容JNI应在独立进程使用。[来源与授权](native/NOTICE)；旧C导入来源见 [UPSTREAM.json](mtndd/UPSTREAM.json)。

## 已有 benchmark 数据

这里**仅整理 `~/mtpndd` 原仓库已有数据**，没有新增性能评测。四个2026-04-22的TSV文件共168条历史记录，每配置一次；单次sweep不能给出方差/置信区间，也不是本分支修正后代码的性能测量。

N-Queens serial/Release：外围约束构造顺序执行，运算内部使用多个workers。C计时在运行时初始化之后、退出之前，包含字段/约束构造、组合和计数。

| N | 1 worker s | 2 workers s | 4 workers s | 6 workers s | 自身1/6 | 解数 |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 0.237 | 0.165 | 0.113 | 0.093 | 2.55× | 724 |
| 11 | 1.171 | 0.724 | 0.446 | 0.344 | 3.40× | 2,680 |
| 12 | 6.021 | 3.697 | 2.132 | 1.540 | **3.91×** | 14,200 |
| 13 | 35.491 | 21.361 | 12.071 | 8.592 | **4.13×** | 73,712 |

PGO和外围构造并行的六worker历史结果，单位秒：

| N | serial Release | serial PGO+LTO | parallel Release | parallel PGO+LTO |
|---:|---:|---:|---:|---:|
| 10 | 0.093 | 0.098 | 0.081 | 0.080 |
| 11 | 0.344 | 0.311 | 0.299 | 0.295 |
| 12 | 1.540 | 1.478 | 1.508 | 1.473 |
| 13 | 8.592 | 8.674 | 8.526 | 8.225 |

parallel变体增加外围约束构造任务，最终组合仍保持顺序折叠。PGO并非每项更快：N=13 serial PGO的8.674秒比Release的8.592秒约慢1.0%。[全部N=7–13、workers=1–6数据、来源SHA和汇总脚本](mtndd/results/upstream-20260422/README.md)。

原库文档还保留了旧SRE实验：fattree12/MF1的1/6 workers为19.915/21.579秒（0.92×），MF3为412.009/371.782秒（1.11×）；它们属于 [历史文档](mtndd/docs/optimization-results-summary.md)，不是当前分支重跑，也不能和 `parallel` 的最新SRE数字直接相除。

**N-Queens是Boolean特例，不是代数性能验证。** 当前 `satCount` 统计非零支持的赋值数，而不是叶子值总和。原库有 `arith_benchmark` 的PLUS/TIMES基准代码，但没有同等完整的已发布代数性能数据；本分支增加数值正确性oracle，不补造吞吐或加速数字。后续需要加权计数、概率或矩阵-向量负载及数组/MTBDD基线。

## 构建与验证

Linux、GCC/Clang（C11及`__int128`）、CMake≥3.23、Python3、Git、JDK11+、Maven。固定依赖提交与Boolean分支相同。

```sh
git clone --branch parallel-MTNDD https://github.com/XJTU-NetVerify/NDD.git
cd NDD
export JAVA_HOME=/path/to/jdk
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build -j6
ctest --test-dir native/build --output-on-failure
mvn -DskipTests=false -Dpndd.tests=true \
  -Dpndd.library="$PWD/native/build/libpnddjni.so" \
  -Dpmtndd.library="$PWD/native/build/libpmtnddjni.so" verify
```

输出包括 `native/build/libpmtndd.a`、`native/build/libpmtnddjni.so` 和 `target/ndd-1.0.1.jar`。离线可通过 `PNDD_SYLVAN_SOURCE` / `FETCHCONTENT_SOURCE_DIR_LACE` 指向固定提交。Boolean API仍用 `libpnddjni.so`；旧原型单独构建见 [mtndd模块](mtndd/README.md)。

```sh
cmake -S native -B native/build-asan -DCMAKE_BUILD_TYPE=Debug \
  -DPNDD_SANITIZE=ON -DPNDD_BUILD_JNI=OFF
cmake --build native/build-asan -j6
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 \
  ctest --test-dir native/build-asan --output-on-failure
```

测试包含四后端/mixed字段、单/六workers、数值枚举、融合/分离消去、dense导入、频繁GC、运行中Sylvan GC、缓存碰撞、stale ID、预算失败及JNI引用交接。记录见 [shared-core验证](mtndd/results/validation-20261013-shared-core/README.md)。代数应用评测留待后续，历史benchmark保持原样。

## Java使用

```java
import org.ants.jndd.parallel.*;
import static org.ants.jndd.parallel.ParallelMTNDD.*;

try (ParallelMTNDD e = new ParallelMTNDD(new ParallelNDDConfig(6), Domain.RATIONAL,
        new Field(2, Backend.BCDD), new Field(2, Backend.ZDD));
     MTNDDNode predicate = e.literal(0, 0, true);
     MTNDDNode weight = e.fraction(1, 3);
     MTNDDNode f = e.times(predicate, weight);
     MTNDDNode sum = e.sumAbstract(f, 0);
     MTNDDNode fused = e.multiplySumAbstract(predicate, weight, 0)) {
    System.out.println(e.terminalValue(sum)); // 2/3
    System.out.println(sum.equals(fused));   // true
    System.out.println(e.satCount(f));       // 8.0，非零支持赋值数
    e.collect();                            // 所有尚未close的owned节点保活
}
```

完整示例：[ParallelMTNDDExample](src/main/java/application/examples/ParallelMTNDDExample.java)。运行时指定 `-Dpmtndd.library="$PWD/native/build/libpmtnddjni.so"`。

- 每个返回节点持有一份root；运算不消耗输入。`retain()`产生独立root，节点和引擎都用try-with-resources/close释放。忘记close会保留root到引擎关闭，没有自动Cleaner。
- `applyBatch`一次计算RUN（边界GC另计入`externalRuns`）；`reduce`支持PLUS/TIMES/AND/OR及固定left-fold/平衡树。double归约可能随树形顺序产生舍入差异。
- `fromArray(double[])` / `fromFractions(long[],long[])`避免逐叶Java包装器，字段0变化最慢、最后字段最快；必须给出全域数组。
- 数值域按引擎固定。有理数分子/分母受int64限制；溢出、除零及非有限double结果明确失败。不是任意精度，也不包含Java MTNDD的interval或近似聚类策略。
- `satCount`统计非零支持；加权总和用`sumAbstract`消去所有字段。Boolean操作/EXIST只接受0/1图。

GC通过Lace协作停顿协调：NDD只在操作边界回收，运行中的标签GC扫描节点标签和临时root frames。它不是无停顿concurrent mark/sweep。新接口的生命周期与内存边界见 [STATUS](mtndd/docs/STATUS.md)。

Apache-2.0；保留上游版权与许可证。
