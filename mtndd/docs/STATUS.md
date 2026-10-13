# 当前完成度（2026-10-13）

推荐路径已切换为 `native/` 的共享compact manager＋数值扩展，以及Java `ParallelMTNDD` / `MTNDDNode`。这是有四后端、显式owned roots、协作GC和小域/压力测试的并行多终端引擎。旧 `mtndd/mtpndd` 及其 borrowed JNI API保留兼容，不能把新路径的生命周期保证套到旧接口上。

| 能力 | 新路径状态 |
|---|---|
| BDD/BCDD/ZDD/BITSET与mixed字段 | 已实现，四后端及不同宽度混合数值图枚举一致 |
| Boolean apply、NOT、EXIST | 共用Boolean并行内核；非Boolean输入明确拒绝 |
| Rational/double、四则、evaluate/support count | 已实现；int64有理数与有限double，数值模式按引擎固定 |
| sumAbstract / multiplySumAbstract | 已实现，包含字段缺失/宽域、精确label基数及融合消去 |
| Dense import / batch / reduce | 已实现；各入口一次顶层RUN，batch内部CALL/SPAWN；LEFT_FOLD与BALANCED_TREE可选 |
| Java owned roots、AutoCloseable | 已实现：retain产生独立root，运算不消耗输入，节点/引擎close幂等，跨引擎/关闭后拒绝调用 |
| Native stale/reused IDs | ID＋allocation cookie验证，回收前清缓存，复用时更新cookie；引用溢出拒绝 |
| 活动任务与标签GC | 标签GC通过Lace停顿扫描已发布节点、worker局部label roots和任务结果；不在规范表锁内触发 |
| NDD/数值叶子/本地BDD/BITSET回收 | 操作边界等待任务结束后NEWFRAME标记清扫；Java roots、字段常驻roots递归保活 |
| 预算耗尽及失败清理 | 分配/标签表耗尽返回明确错误，不退出JVM；batch不交出部分owned结果；collect后可继续 |
| 长期应用/代数性能评测 | 按用户要求留待后续；没有新增应用性能数据或性能达标声明 |

## 生命周期与GC协议

1. 所有C入口由manager admission mutex串行接入；Java实例方法、节点close与引擎close使用同一实例锁。调用者仍可由多个外部线程使用owned节点，实际工作在共用Lace pool内展开。
2. 输出在释放admission前增加一份根引用。JNI包装/数组分配失败会释放尚未交给Java的roots。Java包装失败、batch失败也回滚；close引擎前等待已接入调用结束，之后关闭worker与存储。
3. NDD GC不与NDD递归并发回收：只在安全操作边界暂停所有workers，失效缓存，再扫描owned及字段roots，最后回收节点/数值叶子、原生label并重建规范表。活动任务中间NDD结果不会被清扫。
4. Sylvan可能在标签分配时GC。Lace frame暂停所有workers后，marker扫描所有已发布NDD标签（含未交接/缓存命中的节点），以及临时residual/jobs/edge向量；因此不要求每个中间NDD结果先加全局引用。每个向量必须在下一次可分配标签前登记到worker root frame。
5. 标签回收不复用NDD ID；NDD回收先清除所有shared/local操作缓存。数字叶子也在同一规范表/GC中，避免旧独立leaf-table失效策略。

这是一套协作停顿协议，不是无停顿的concurrent mark/sweep。单次大操作中达到预算时返回失败，不会为继续运算而冒险回收活动NDD节点。新Java节点必须close（推荐try-with-resources）；忘记close会持有root直到引擎close，没有自动Cleaner回收。

## 数值与资源边界

- 有理数不是任意精度：normalized int64分子/正int64分母，checked __int128中间运算；label multiplicity≤INT64_MAX。IEEE double拒绝非有限结果或中间基数权重；极宽域的中间权重可能先溢出，即使重排能得到有限最终值。浮点归约顺序可能造成舍入差异。
- `satCount`是非零支持赋值数（double）；叶子加权总和用sumAbstract逐字段消去。
- BDD/BCDD/ZDD宽度遵循共享manager上限4096；BITSET≤7。整数赋值接口零扩展uint64，宽域可用literal约束。dense导入需要每个赋值一项，Java总位数≤30且数组长度匹配。
- `memoryBytes`是共享manager与Sylvan的核心分配预算，不等于进程RSS上限；JVM、JNI输入/输出拷贝、固定manager和线程栈另计。
- C引擎指针关闭后不能再使用；Java对象层会阻止这种访问。旧静态JNI必须独立进程使用。

验证记录见 [validation](../results/validation-20261013-shared-core/README.md)，迁移来源和未迁移数值策略见 [MIGRATION](MIGRATION.md)。旧原型审查记录保留于 [status-legacy](status-legacy-20261013.md)。有限压力测试不等同于所有工作负载/调度的形式化证明。
