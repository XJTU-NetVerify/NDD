# 完成度与本次完善（2026-10-13）

结论：**多终端核心可运行、能进行小域数值验证的研究实现**，尚不是完成了所有后端、资源管理和真实代数应用评测的长期运行引擎。

| 能力 | 当前状态 | 验证/边界 |
|---|---|---|
| Boolean AND/OR/NOT/DIFF/EXIST | 已实现 | N-Queens8、Java 基础调用及已有测试 |
| fraction/double 叶子 | 已实现 | fraction 约分；double 位模式 intern；有溢出、混合类型限制 |
| PLUS/TIMES | 已实现递归/行块并行 | 两字段16×16图，非终端 child、16条边，1/6 workers 枚举校验 |
| MINUS/DIVIDE | 已实现 | 同一枚举 oracle；并非所有算子都有等价的独立 SPAWN 路径 |
| ABSTRACT_PLUS | 已实现并修正 double 权重 | 常数、非终端、跳过字段、32-bit double 域及两个字段的消去 |
| 标签后端 | 仅 Sylvan BCDD | 没有 Boolean parallel-NDD 的 BDD/ZDD/BITSET/mixed 分派 |
| 规范表/节点池/缓存 | 已实现 | 分片锁、worker-local池；新增操作标签与原子版本发布 |
| JNI/Java API | 可用，全局 singleton | 手动 ref/deref；新增旧引擎代际检测；不是 NDDNode 的 owned AutoCloseable API |
| Batch | 已有接口 | JNI往返减少；C batch仍循环逐操作 external RUN，未复用 Boolean 核心的一次 RUN 宏观任务 |
| GC/资源管理 | 已有标签/节点/叶子回收 | 小测试含叶子GC；没有证明在途任务与回收/复用的所有并发边界、统一内存预算或多个隔离引擎 |
| 数值应用 benchmark | 代码存在，数据不足 | arith_benchmark有 PLUS/TIMES；没有同等完整的已发布数值扩展曲线/概率应用结果 |

## 修正了什么

1. OR/PLUS、AND/TIMES 仍共享物理表，但缓存键加入运算标签；不再把 Boolean OR 的结果当作整数 PLUS。回归：两位指示函数先 OR 后 PLUS，sum-out 分别必须为3与4。
2. 指针值的前后两次比较改为原子 payload、BUSY和单调版本发布，写入竞争时跳过缓存写入；版本耗尽禁用槽，避免回绕 ABA。它参考 `parallel` 的协议，但键/节点表示不同，未直接共用其缓存函数。32-byte entry 取代上游24-byte entry，本次没有评测其性能影响。
3. ABSTRACT_PLUS 的基数权重按子图的 fraction/double 类型构造；用 `ldexp` 代替可能超宽的移位。零函数可直接返回零。fraction 权重仍受32-bit表示限制，可能在最终结果可表示时因中间权重超界而拒绝。
4. Java 拒绝重复初始化、已关闭/前一代引擎的节点，包括 batch 输入；equals/hashCode纳入代际。仍没有检测同代节点在 deref/GC 后的所有失效方式，必须遵循显式引用协议。
5. 固定两个依赖提交；复用 `native/scripts/prepare_lace.py` 的 build-local适配；JNI只要求JVM组件，不要求AWT；注册CTest，Release测试及benchmark断言启用，修复原 arith benchmark 的Release未使用变量编译失败。
6. 新增缓存碰撞/多写者/版本耗尽测试、四则运算与两字段sum-out枚举oracle、Java代际及double宽域测试；N-Queens只作Boolean回归。

## 验证范围

Release CTest8项；Debug ASan/UBSan（leak detection与UB halt）相同8项；Java10项，零失败/零跳过。日志与摘要在 `../results/validation-20261013/`。并行数值oracle覆盖 fraction/double、256 个赋值、PLUS/MINUS/TIMES/DIVIDE以及两个字段消去，1与6 workers。

这不是完整的GC并发证明或所有输入穷举。上游 `test_parallel_plus` 的简单终端案例不会触发重递归行并行；新增两层16×16 oracle专门满足行任务的边数及非终端条件。未新增性能测量。

## 还需要完成

- 活动任务参数、中间值、缓存命中与节点/叶子回收复用的压力验证，必要时引入协作停顿/根保护协议。
- Java owned roots与AutoCloseable、完整节点存活检测；目前调用和手动ref必须作为受控事务使用，static synchronized不等于任意多线程持有borrowed结果都安全。
- 一次顶层RUN的批量数值apply/归约、融合TIMES+ABSTRACT_PLUS、反馈式GC与统一内存预算。
- 将“终端值域”和“标签后端”解耦，逐步接入四后端；这不是简单换标签指针。
- 加权模型计数、概率/可靠性或矩阵-向量乘法，配枚举/数组、Sylvan MTBDD对照；报告数值误差、内存、GC和1/p扩展。

上游审查记录保留在 [upstream-review-2026-10-10.md](upstream-review-2026-10-10.md)，其缺陷列表部分已由本次修正，不应当作本分支当前状态。
