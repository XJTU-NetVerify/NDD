# Parallel MTNDD

`parallel-MTNDD` 是 NDD 的 **C 核心＋JNI＋Java API 多终端分支**。在 `parallel` 的基础上保留 Boolean NDD 实现，并在 `mtndd/` 导入、完善 `~/mtpndd` 的多终端实现；原 `MTPNDD` C/Java 名称保留兼容。

目前已具备 fraction/double 叶子、四则运算、求和消去和部分算子的 Lace 并行，属于**可运行的研究原型**。本次修正缓存操作污染、double 消去权重和旧引擎句柄问题，补齐构建与数值测试；四后端 MT、完整 owned-root API、GC 并发边界和真实代数应用评测仍待完成。详见 [完成度报告](mtndd/docs/STATUS.md)。

## 与并行 Boolean NDD 的关系

用户所说的 parallel-NDD 对应远端 [`parallel`](https://github.com/XJTU-NetVerify/NDD/tree/parallel) 分支。

| 项目 | Boolean parallel-NDD | 本分支 MTNDD |
|---|---|---|
| 函数值域 | 0/1 | 0/1、32-bit有理数、IEEE double |
| 标签后端 | 无补边BDD、BCDD、普通ZDD、BITSET、mixed | 当前仅Sylvan BCDD |
| 主要运算 | AND/OR/NOT/DIFF、EXIST、计数 | 同类Boolean运算＋PLUS/MINUS/TIMES/DIVIDE、ABSTRACT_PLUS |
| 并行组织 | NDD内部、一次RUN的batch、可选标签任务 | 内部SPAWN/SYNC、PLUS/TIMES行块；已有batch仍逐操作RUN |
| Java | `org.ants.jndd.parallel`，实例引擎、owned `NDDNode`、AutoCloseable | `org.ants.mtpndd`，全局引擎、手动ref/deref、代际检查 |
| 字段编号 | 从0开始 | 从1开始 |
| 节点/句柄 | 紧凑边存储、不透明64-bit句柄 | 边桶/节点池、JNI封装原生指针 |

**实际共享**：相同的 Sylvan提交 `c8bf561e`、Lace提交 `63d8a5ea`，以及 `native/scripts/prepare_lace.py` 的构建适配和超额worker等待修补。节点规范化、两阶段AND、任务结果引用交接、分片规范表和版本缓存协议具有可复用设计。

**保持独立**：两套节点/缓存/根管理和Java契约，分别构建；没有让两引擎在同一JVM内共享一个worker pool，也没有将Boolean四后端存储自动变成多终端。使用两个引擎时分别放在独立进程。MTNDD未启用Boolean核心的全部性能开关，不能由这些共享项推定性能一致。

源码来源、原始提交和版权见 [UPSTREAM.json](mtndd/UPSTREAM.json)、[NOTICE](mtndd/NOTICE)。原有Boolean源码/API和 [native使用说明](native/README.md) 保留。

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

Linux、C11/C++编译器、CMake≥3.23、Python3、Git、JDK11+、Maven。依赖固定提交，由CMake下载；无需手工初始化嵌套子模块。

```sh
git clone --branch parallel-MTNDD https://github.com/XJTU-NetVerify/NDD.git
cd NDD
export JAVA_HOME=/path/to/jdk
cmake -S mtndd -B mtndd/build -DCMAKE_BUILD_TYPE=Release
cmake --build mtndd/build -j6
ctest --test-dir mtndd/build --output-on-failure
mvn -f mtndd/jni/pom.xml \
  -Dorg.ants.mtpndd.library.path="$PWD/mtndd/build/jni/libmtpnddjni.so" test package
```

输出：`mtndd/build/mtpndd/libmtpndd.a`、`mtndd/build/jni/libmtpnddjni.so`、`mtndd/jni/target/mtpndd-java-0.1.0-SNAPSHOT.jar`。离线构建可通过 `FETCHCONTENT_SOURCE_DIR_SYLVAN`、`FETCHCONTENT_SOURCE_DIR_LACE` 指向相同提交的本地源码。

Debug检查：

```sh
cmake -S mtndd -B mtndd/build-asan -DCMAKE_BUILD_TYPE=Debug \
  -DMTNDD_SANITIZE=ON -DMTPNDD_BUILD_JNI=OFF
cmake --build mtndd/build-asan -j6
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 \
  ctest --test-dir mtndd/build-asan --output-on-failure
```

本次Release与Debug ASan/UBSan各8项CTest、Java10项通过。包括N-Queens8、转换、有理数/Double、1/6 workers的256赋值四则运算及双字段消去、六线程缓存碰撞和版本耗尽、旧引擎节点拒绝。有限测试不代表所有并发GC边界已验证。[验证记录](mtndd/results/validation-20261013/README.md)。

## Java数值使用示例

```java
import org.ants.mtpndd.*;

MTPNDDEngine.init(MTPNDDConfig.builder().workers(6).build());
try {
    int field = MTPNDDEngine.declareField(2); // 四个取值，字段从1开始
    MTPNDDEngine.generateFields();
    MTPNDD value = MTPNDDEngine.makeFraction(1, 3).ref();
    try {
        MTPNDD sum = MTPNDDEngine.abstractPlus(value, field).ref();
        try {
            System.out.println(MTPNDDEngine.getNumer(sum) + "/"
                    + MTPNDDEngine.getDenom(sum)); // 4/3
            System.out.println(value.satCount()); // 4，非加权总和
        } finally { sum.deref(); }
    } finally { value.deref(); }
} finally { MTPNDDEngine.shutdown(); }
```

普通标量运算和reduce返回borrowed节点，持久保存必须ref，使用结束deref。`andBatch`的结果已增加引用；`andTo/orTo`会释放左输入的一份引用并返回已引用结果，不能再重复ref；不要将Java包装器留到节点被回收后再使用。当前代际检查仅阻止引擎关闭/重建后的旧句柄，不能检测同代所有失效节点。Boolean操作用于0/1谓词；数值图使用四则运算。fraction/double不自动混用，fraction不是任意精度；double的舍入、NaN和±0规范化按现有位模式约定处理。

## 后续方向

优先完成在途任务与GC根保护压力验证、Java owned roots、一次RUN的数值batch/融合乘法求和，再解耦值域与标签后端并扩展实际代数应用。更详细的能力边界、修正和差异见 [STATUS](mtndd/docs/STATUS.md)。

Apache-2.0；保留上游版权与许可证。Sylvan/Lace的授权和来源见 [native/NOTICE](native/NOTICE)。
