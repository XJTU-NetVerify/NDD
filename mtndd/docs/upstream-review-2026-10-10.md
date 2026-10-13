MTPNDD 多终端与并行实现评估

研究日期：2026-10-10；代码：`a8649f5`。README 和已有报告作为材料，结论优先依据当前源码。检查了核心 C 引擎、JNI/Java API、N-Queens/代数 benchmark、四份 TSV 和性能记录。本次未修改引擎。当前环境没有 CMake、Java/Maven，Sylvan 子模块也未初始化，未重跑完整构建与性能实验。性能数字来自仓库历史记录，源码问题为静态审查结论。

后续实测补充（2026-10-11）：已补齐构建工具，完成 N=12、13 的同机普通 NDD 与 MTPNDD 对比，共 36 个样本，解数全部正确。6 workers 的操作内并行相对当前普通 NDD/BDD 加速约 2.67×、2.72×；相对 MTPNDD 自身 1 worker 加速约 4.42×、4.55×。外围构造并行在 N=13 的三次样本中没有稳定增益。详见 [完整实测报告](../../NDD/results/nqueens_comparison_20261011/README.md)。这次只验证 Boolean N-Queens，不改变下文代数路径的静态审查结论。

**总体判断**

这是已经实现多核执行的 C/Sylvan/Lace 引擎，有可借鉴的调度、规范表、分配器和两层节点引用管理。Boolean 大图运算的并行已有相对充分的历史数据。多终端数值能力也已经落地，但并行覆盖、代数语义、测试和应用性能证明还不完整。适合当作 NDD 并行的参考实现和 native 路线起点；需要修复与验证后再承担通用 MTNDD 代数引擎的角色。

**当前能力，以源码为准**

| 能力 | 当前实现 | 评价 |
| --- | --- | --- |
| Boolean AND/OR/NOT | Lace 子任务、pending 批量 SYNC、父任务合并 | 已实现操作内部并行 |
| Boolean DIFF/EXIST | 通过现有 Boolean 操作和结构遍历组合 | 可以间接利用内部并行；不能与专门融合内核等同 |
| 多终端 | fraction、double，两类叶子使用独立规范表 | 真正的数值叶子，并非只有名称上的 multi-terminal |
| PLUS/TIMES | 同字段按 A 的一行边拆 chunk，不同字段按子问题派发 | 已实现显式并行，且粒度比逐边对任务粗 |
| MINUS/DIVIDE | 当前外层主要递归 CALL | 不具备与 PLUS/TIMES 相同的显式并行覆盖；底层 BDD 仍可能并行 |
| ABSTRACT_PLUS | 按标签基数加权，顺序累积加法 | 有 sum-out 语义，显式并行归约不足，double 路径有缺口 |
| batch/reduce | OR/AND reduce 使用树形任务；AND batch 循环调用 AND | reduce 有宏观并行；batch 目前只是批量 JNI 接口 |
| Java 调用 | 核心 façade 方法是 static synchronized | 单次 native 操作可并行；Java 多调用不能同时进入 |
| 标签后端 | 绑定 Sylvan BDD 句柄/函数 | 没有当前 Java NDD 的 BDD/BCDD/ZDD/BITSET 可切换层 |

多终端叶子见 [mtpndd_leaf.h](../mtpndd/mtpndd_leaf.h)，PLUS/TIMES 分块见 [mtpndd_arith.c](../mtpndd/mtpndd_arith.c)。`mtpndd_arith.h` 仍说“不 SPAWN，Phase 1 顺序”，这是过时注释。PLUS 的同字段行任务在 `arith_should_spawn_row` 满足“child 非终端且 B 边数 ≥8”时派发；不同字段路径另有子节点边数乘积阈值 256。两种路径不能混为一个阈值。

Boolean AND 有两阶段 filter-then-recurse：先串行计算标签交集，过滤空边对，再派发存活子问题，最后合并。该方法减少无效递归，但标签筛选本身仍由一个 producer 做，标签时间占比高时会成为瓶颈。当前 `mtpndd_node.c` 默认 two-phase 阈值为 4；旧报告写 16，不能直接当作当前配置。

**N-Queens 的结果能说明什么**

现有编码是 Boolean 约束图，只使用 TRUE/FALSE、AND/OR/NOT 和 satcount；没有用非 Boolean 叶子，也没有测 PLUS/TIMES/ABSTRACT_PLUS。N-Queens 是多终端引擎的合法特例和回归基准，可以证明 Boolean 操作正确性及该负载的并行扩展性，但不能证明数值代数、叶子规范化、数值误差、求和消去或真实代数应用的效果。

当前 `satcount` 统计的是非零赋值数；叶子为 1、2、1/3 等非零值时，对支持计数的贡献一样。它不是叶子值的加权总和。将非零叶子填入 N-Queens 图后仍然仅测 satcount，也不足以验证 MT 的核心能力。

仓库 `bench_results_serial_release_20260422_112658.tsv` 的重新计算结果：

| N | 1 worker | 6 workers | 加速 | 六 worker 效率 |
| --- | ---: | ---: | ---: | ---: |
| 10 | 0.237 s | 0.093 s | 2.55× | 42.5% |
| 11 | 1.171 s | 0.344 s | 3.40× | 56.7% |
| 12 | 6.021 s | 1.540 s | 3.91× | 65.2% |
| 13 | 35.491 s | 8.592 s | 4.13× | 68.8% |

“serial”指约束构造在应用层顺序执行，其每次 Boolean 操作内部仍可使用六 workers。`parallel` 版本增加 row/cell 约束构造的并发，最终 queen 累积仍是顺序折叠。相同 Release 配置下，六 workers 的 serial → parallel 为 N=10：0.093→0.081 s（时间减少约12.9%）；N=12：1.540→1.508 s（约2.1%）；N=13：8.592→8.526 s（约0.8%）。这说明大 N 的主要并行收益来自操作内部，外围构造在小 N 更有帮助。

README 的主表为单次 sweep，另有交错三次均值记录；大 N 的约四倍扩展性得到不同记录相近支持，但单次数据不能给出方差或置信区间。旧优化记录还存在 -O0 与 -O3 混淆，当前 README 的 Release 表和迁移后 A/B 应优先使用。PGO 与算法并行的收益必须分别计算。

不能拿这些秒数与 `/home/cheney/NDD/README.md` 的时间直接相除：机器、版本、语言、配置和计时范围不同。C N-Queens 在 init 之后开始计时，Java NDDSolution 从 init 之前开始计时，后续清理范围也不同。应在同一机器、相同 Boolean 输入与字段布局下复测，并分别报告初始化、构造、apply、count、清理。

**网络验证的效果，明显弱于 N-Queens**

[optimization-results-summary.md](optimization-results-summary.md) 的 SRE 记录说明：fattree12 MF=1 的 1→6 workers 为 19.915→21.579 s，反而增加约8.4%；MF=3 为 412.009→371.782 s，约1.11×。优化前后都有类似扩展性限制。这些属于文档中的历史实验，不是本次对当前 HEAD 的重跑。

更晚的 [open-addressing 报告](plans/2026-04-22-open-addressing-nodetable.md) 记录了迁移到 Lace 1.6 后，JNI 逐操作 external RUN 的 SRE 工作负载从约44 s 退化到85 s。报告中的解释是每次外部 RUN 的排队/唤醒成本，而 C N-Queens 把整个求解包进一个顶层 worker task，因此没有同样的调用模式。该问题没有在当前仓库的 wrapper/batch 实现中体现出完整解决方案。

Java façade 的 `and/or/plus/times` 等方法又是 static synchronized；因此简单从 Java 开更多线程不会形成多个外部操作并发。`andBatchNative` 直接调用 `mtpndd_and_batch`，而 C batch 里面顺序调用 `mtpndd_and`，没有围住整个循环的顶层 RUN，也没有分治任务。它减少 Java/native 往返，但并没有自动把多次外部 RUN 合成一次。修复应同时覆盖 JNI 批量入口、native worker 上下文、宏观任务拆分，而不是仅改 Java 线程池。

**代数 benchmark 已有，证明仍需补齐**

[arith_benchmark.c](../mtpndd/test/arith_benchmark.c) 构造两字段密集数值图，测 PLUS/TIMES，支持 fraction/double、worker sweep 和重复采样；每次改变叶子偏移以避免整次操作直接命中缓存，当前实际报告中位数，文件开头“取最小值”注释过时。

这个基准能测高扇出行分块的效果，但没有随仓库发布与 N-Queens 同等清晰的数值结果表。它只检查输出非空，没有逐赋值核对数值；不测 MINUS/DIVIDE/ABSTRACT_PLUS、融合乘法求和，也不代表真实概率或矩阵应用。测试反复保留越来越多的 intern 值，且不是完全独立的冷启动样本，需要记录增长和 GC。

该 benchmark 从非 worker 主线程分别调用构造、PLUS 和 TIMES，调用模式还包含 external RUN 成本；可补一版在单个顶层 Lace task 内做整个采样，以区分引擎内核与应用入口成本。两版本都值得测，不应混报。

密集唯一叶子矩阵还应与平坦数组 baseline 对照。若每字段有 m 条互斥具体值边，同层标签比较仍可能遍历 m² 对，而只有 m 个匹配；低一层再重复该代价。并行加速不能说明相对 O(m²) 数组逐元素运算就有效。应同时构造稀疏、块常量、有共享子图的输入，观察 NDD 的压缩优势与运算成本。

**复用前需要处理的具体问题**

1. PLUS 与 Boolean OR 缓存没有操作类型隔离。`mtpndd_plus_rec` 在 846–851 行使用 `g_mtpndd_config.or_cache`，`mtpndd_or_rec` 使用同一个缓存；缓存 key 只有左右节点，public wrappers 没有在切换操作时清缓存。可从源码推出顺序反例：a 是字段中 bit0 的指示函数，b 是 bit1 的指示函数；先 `OR(a,b)`，再 `PLUS(a,b)`，后者会命中同一 key，返回 OR 图。在两位都为1处，期望值2，却得到1。修复应使用 `(op,a,b)` key 或独立运算缓存。TIMES 与 AND 共用缓存也不适合作为通用 MT 运算设计。此结论为静态反例，未运行完整引擎验证。
2. DOUBLE 的 ABSTRACT_PLUS 路径不完整。`abstract_scale_factor` 和边基数 weight 总是构造 fraction，而叶子乘法拒绝 double/fraction 混用。对一个1位字段的常数 double(0.5)，sum-out 期望 double(1.0)，当前路径会将 double(0.5) 与 fraction(2,1) 相乘并报类型错误。需要按值域创建权重或明确类型提升规则。fraction 权重还限制了大域求和；它不是任意精度有理数系统。
3. `test_parallel_plus` 没有实际覆盖 row-SPAWN。当前用同一个 terminal 覆盖两个 cube，规范化后可合并为一条边；即使有多条边，child 仍是 terminal，`arith_should_spawn_row` 会返回 false。应构造两层图、非终端 child 和至少8条 B 边，记录 task/steal 数确认执行路径。
4. 缓存“lock-free”用语需要谨慎。写入端用 result 哨兵做 CAS 自旋锁，读端以 result-before/result-after 验证，没有单调版本号。不能把它直接当作已证明的无锁 seqlock；重复 result 指针的 ABA 和混合 key 读取需要专门审计、压力测试，或者改用真正版本化发布。这里是待验证的并发风险，区别于上面可直接推出的运算类型污染。
5. C 正确性测试大量用 assert；默认 Release/PGO 会定义 NDEBUG，许多数值校验以及写在 assert 内的函数调用不执行。CMake 目前只生成测试 executable，没有 CTest 注册。生产复用前需要 assertion-enabled 测试、缓存混用测试、线程压力测试、强制 GC/扩容/回收测试和跨字段宽度的数值对照。

MTPNDD 已做节点 atomic ref、任务中间结果 +1 ref 交接、Sylvan BDD label GC 标记等管理，但这些设施存在不等于两层 GC 的所有并发边界都已验证。普通 nodetable GC 主要通过锁住规范表排除修改；操作缓存读取和返回值交接并不都在这个锁范围内，需要测试活动任务、缓存命中、回收复用之间的完整保护协议。

**哪些值得借鉴或直接复用**

| 内容 | 复用方式 | 限制 |
| --- | --- | --- |
| 两阶段 AND、pending 有界 SYNC | 移植算法结构到 Java 四后端 | producer 筛选与父节点合并仍串行；阈值按负载测 |
| PLUS/TIMES 行分块 | 借鉴到高扇出 AND/OR/DIFF 的任务划分 | Boolean 空交集分布和共享结构不同，不能固定照搬阈值 |
| hash 分片 unique table、二次查重 | 借鉴原子 get-or-create 设计 | Java 数组/变长边布局不同，不能直接复制 C 桶链 |
| worker-local slab 缓存、引用合并 | native 路线直接复用，Java 借鉴分块分配/减少共享写入 | Sylvan refs 修改在个人 fork 中，当前子模块未拉取，未独立审计 fork 源码 |
| 返回 item/chunk 携带 child/label 引用所有权 | 复用协议 | 错误路径、取消、被窃取任务、GC 间隙需完整覆盖 |
| MT 叶子规范化、sum-out | 借鉴值域和归约语义 | 修复运算缓存与 double 权重后再复用；考虑更宽/任意精度值域 |
| C 核心 + JNI | 经修复后作为 native NDD/MTNDD 备选实现 | 暴露 long pointer、全局引擎，现有 Java int 句柄不能直接替换 |

MTPNDD 只使用 Sylvan BDD 标签，并没有四后端接口。保留 Java NDD 的 mixed BDD/BCDD/ZDD/BITSET 功能时，需要重新抽象标签操作、字段 universe、计数、引用和 GC。应把“终端值域”与“标签表示”分成两个独立维度：Boolean/fraction/double 是值域，BDD/BCDD/ZDD/BITSET 是标签表示，不能把多终端默认等同于必须用某一种标签后端。

当前引擎也是全局状态，不是可同时创建的多个隔离 manager。作为新增 native 实现较可行；作为当前 Java 四后端 NDD 的透明替换，需要处理字段编号从1开始、句柄宽度、初始化生命周期、API差异和缓存/规范根身份。

**更适合 MTNDD 的评测方向**

N-Queens 保留为 Boolean 回归，同时补以下数值负载：

- 点运算与 sum-out：PLUS/TIMES/MINUS/DIVIDE、多个字段 ABSTRACT_PLUS；显式数组或枚举真值表对照，验证叶子值而非只验证非零支持。
- 加权模型计数、网络可靠性/概率：`sum_x indicator(valid(x)) * weight(x)`，真实使用多个权重和求和消去。
- 结构化矩阵-向量乘法：`y(x) = sum_z A(x,z) * v(z)`。当前 TIMES 是逐点乘法，只有配合 sum-out 才成为矩阵-向量计算。
- 概率状态转移、Markov 迭代或变量消去：包含重复共享、长期缓存、数值误差和 GC 压力，能够体现 MT 压缩与真实端到端收益。

基线至少包括 Sylvan MTBDD、当前 MTPNDD 的1 worker/多 workers 和适合输入的 dense/sparse array；使用相同数值类型、字段顺序、输入结构与内存预算。记录算子耗时、完整应用耗时、节点/叶子/标签数、峰值内存、GC、并行效率和误差。float/double 的归约重排会改变舍入，不能把有理数精确结果与 double 耗时当作同一语义比较。

对需要连续 TIMES + ABSTRACT_PLUS 的应用，可以研发融合 multiply-sum 内核，避免先物化大乘积再消去；这比只把四则运算全部加 SPAWN 更可能产生实际收益。是否支持 min-plus/max-plus 等值域要按具体应用扩展，当前仓库不应被称为通用 semiring 引擎。

建议路线：先修复缓存操作隔离、double sum-out、有效并行测试和 JNI batch 顶层 worker 入口；然后在同机复测 Java NDD、MTPNDD 和 Sylvan MTBDD；再决定直接采用 C 引擎，还是仅移植任务结构和并发规范表。已有 N-Queens 数据证明共享内存多核方向可行，也说明真实网络/代数负载的粒度和调用模式决定最终收益。
