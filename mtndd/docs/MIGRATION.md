# Java MTNDD → 并行 C 核心迁移

来源：NDD `origin/mtndd` 的 `8d343b3`（2026-10-13审查）。这是Java实现，不是 `~/mtpndd` 的C实现；二者没有可以直接合并的节点ABI。

| 来源设计 | 新路径的实现 | 迁移取舍 |
|---|---|---|
| primitive数组节点/连续物理边、按ID缓存 | 复用 `native/src/pndd.c` 的分块ID表、连续边、单边/叶子64-byte slab和版本缓存 | 共用已有并行存储，不维护第二套Java全局边栈；多终端信息利用原有header padding和叶子的边空间 |
| 按字段后端、同后端共享右对齐变量布局 | C的BDD/BCDD/ZDD/BITSET分派及mixed字段直接用于MT | BDD无补边，BCDD补边，普通ZDD显式universe；BITSET≤7位 |
| Rational/double终端canonicalization | 同一分片规范表intern叶子；0/1和Boolean共用；精确有理数或double引擎 | 有理数提升为int64，采用checked __int128中间值；不是Java BigInteger的任意精度 |
| sumAbstract：叶子/跳过字段乘基数，边按label multiplicity求和 | `pmt_sum_abstract`；整数标签计数避免2^53以上double舍入 | 有理数权重和最终分子/分母必须可由正int64基数表示；过大中间权重明确拒绝 |
| multiplySumAbstract融合递归 | `pmt_multiply_sum`在目标字段直接加权归约，不创建该层乘积节点 | 保持先前字段，递归消去；浮点求和顺序可能区别于先乘后求和，按误差比较 |
| BulkBuilder批量导入 | `pmt_from_array` / Java `fromArray`、`fromFractions` | 一次RUN、C直接创建叶子和边，不为每个数值分配Java包装器；导入构造目前顺序递归 |
| 数值操作/消去缓存和统计 | 共用operation-tagged shared/local cache及节点/GC/task指标 | 数值与Boolean cache keys隔离，标签GC仅清理label相关缓存，NDD回收前全部失效 |

没有迁移其全局临时边栈；它不适合作为多个Lace任务的共享可变工作区。这里用任务局部向量和worker root frame。interval、近零剪枝、mantissa/grid聚类、n-ary contraction和误差包络也没有迁移：这些是可选数值/表示策略，当前接口先给出精确有理数和无近似剪枝的有限double运算。

新路径除以零明确失败（Java `ArithmeticException`），包括implicit-zero区域；Java `mtndd` 的部分除法路径将零除数结果置零，因此不能对这类输入声称API完全等价。double拒绝NaN/Inf、±0统一为零；没有继承旧C叶子的所有位模式政策。

## 代码入口

- `native/src/pndd.c`：Boolean与MT共用的分片表、ID/cookie、引用、四后端、分配预算、GC及Lace调度。
- `native/src/pmtndd_impl.inc`：编译进MT版本的数值叶子、四则、消去、融合、导入、batch/reduce。
- `native/include/pmtndd.h`：C接口；结果owned，输入borrowed。生命周期/构造使用`pndd_close/constant/literal/value/retain/release/collect/get_stats`；数值运算/检查使用`pmt_*`，不要对数值图调用Boolean-only检查/操作函数。
- `ParallelMTNDD` / `MTNDDNode`：新实例Java API。与`ParallelNDD`共享配置和Java引擎准入门，任一时刻只允许一个新式native引擎。
- `mtndd/mtpndd`及旧JNI：兼容研究路径，保留原有数据和旧调用，不等于新核心。旧 borrowed API已deprecated；新代码应迁往owned接口。

两套新式核心分别编译成`libpnddjni.so`和`libpmtnddjni.so`，不同时运行两个worker pool；顺序关闭再切换引擎已测试。旧兼容JNI不参与新式准入门，不应混用在同一进程。
