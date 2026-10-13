# feature/serial 变更整理（自 7c1201c9e3e323438d933ec912b9f6edc5773614 起）

## 目的
为 `feature/c` 分支并行化逻辑运算做准备，整理 `feature/serial` 在 `7c1201c9e3e323438d933ec912b9f6edc5773614` 之后的所有变更，便于评估哪些改动可直接 cherry-pick、哪些需要手动合并。

## 变更时间线（按提交顺序）
1) **cf9ea39 / c17d335**：移除 MTPNDD 内部锁与相关锁逻辑，缩减 nodetable/op-cache 等路径上的锁开销，并清理不再使用的锁字段与调用。**并行分支不应引入**（移除锁会破坏并行正确性）。
2) **5599e2d**：修正 nodetable 哈希与比较接口（抽象出统一的 hash/compare 定义，避免误用），为后续缓存 hash 做准备。
3) **48939bc**：edge map 引入 cached hash（类似 Java HashMap），并在 nodetable/edge map 查找路径中复用；补充 `docs/lace_worker_wrapper_analysis.md`；更新 `docs/PERFORMANCE_ANALYSIS.md`；保持非 edge-reuse 行为。
4) **061f825**：收缩 `gc_protect` API，改进清理路径，减少 API 调用与数据结构开销；记录实验结果到性能分析文档。
5) **bbab060**：修复 `mtpndd_mk` 规约规则与引用计数释放逻辑。
6) **f45a95b**：优化 nodetable 与 edge map rehash 逻辑（包括 bucket 扩容、rehash 触发策略），并补充内存池初始化/容量逻辑。
7) **21dcf54**：新增 `mtpndd_nqueens_benchmark`；在 `mtpndd_node` 增加基础 stats 统计；更新 `docs/PERFORMANCE_ANALYSIS.md`。
8) **734cd1e**：升级 Sylvan 到 v1.9.1，并集成 bundled Lace；更新 Sylvan 构建、CI/示例文件，统一到新版 API/目录结构。
9) **c0af93a**：补充 AND/MK/GC protect 的细分统计；在 nodetable 内新增更细粒度耗时统计；benchmark 输出扩展。
10) **56e7a78**：移除 `gc_protect` 集合，改为临时节点 `ref/deref` 保护；同步 JNI 与测试路径；更新性能分析与架构图。
11) **f89acce**：细化 nodetable lookup 路径（预筛选、bucket 扫描统计）；精简/内联 op-cache hash；扩展 `nqueens` 与 `nqueens_benchmark` 输出；刷新性能分析基线与实验结论。

## 关键代码改动摘要
- **锁与并发（仅串行分支适用）**：移除 nodetable/op-cache 的锁与锁字段，简化热点路径；**并行分支不应应用**。
- **哈希与比较**：统一 nodetable/edge map hash 计算与比较入口，缓存 edge map hash，减少重复计算。
- **节点创建与规约**：修复 `mtpndd_mk` 规约与引用计数处理；强化重用/清理路径。
- **gc_protect 移除**：将临时保护从 `gc_protect` 集合迁移为显式 `ref/deref`，降低 lookup 和链表扫描成本。
- **统计与可视化**：新增 AND/MK 细分统计与 nodetable 细分统计，扩展 benchmark 输出，为跨语言对比提供数据。
- **Sylvan/Lace 版本**：升级至 Sylvan v1.9.1 并集成 bundled Lace，建立新的性能基线。

## 文档更新要点
- `docs/PERFORMANCE_ANALYSIS.md`：新增多轮实验结果、基线与对比表，包含 nodetable 细分、op-cache hash 实验与 bucket 实验等。
- `docs/lace_worker_wrapper_analysis.md`：保留 Lace worker wrapper 性能分析。
- 架构图更新：中文/英文 dot 图保持一致并更新。

## 建议的 cherry-pick 方向（进入 feature/c）
> 说明：`feature/c` 已包含自身的内存池与节点结构重构（`21a4f43`, `b8ae1d2`）及部分哈希修正（`e0f4489`），与 `feature/serial` 有重叠，需要手动处理冲突。

### 可直接 cherry-pick（优先级高）
- `734cd1e`：Sylvan v1.9.1 + bundled Lace。
- `21dcf54`：nqueens benchmark 与基础 stats。
- `c0af93a`：AND/MK/GC protect 细分统计（注意：若已移除 gc_protect，需调整输出）。
- `56e7a78`：移除 gc_protect（若当前分支仍使用该路径）。
- `f89acce`：nodetable lookup 细化统计与 op-cache hash 内联（性能分析依赖）。

### 可能冲突或需手动合并
- `cf9ea39`, `c17d335`：移除锁相关（**并行分支不应应用**，且与 `feature/c` 的内存/节点重构有冲突风险）。
- `5599e2d`, `48939bc`：hash/compare 修正与 cached hash（当前分支已做部分修正，需对齐实现细节）。
- `061f825`, `bbab060`, `f45a95b`：涉及 mk/rehash/内存池与结构体字段，需逐项核对。
- `7c1201c`：作为起点的内存池大重构，`feature/c` 已有对应版本，不建议直接 cherry-pick。

## 并行分支需避免的改动（重点标记）
- **移除锁/并发保护的提交（cf9ea39、c17d335）不能应用到 `feature/c`**，否则会破坏并行逻辑运算的正确性与可重入性。

## 下一步建议（针对 feature/c）
1) 先 cherry-pick `734cd1e`（Sylvan + bundled Lace）与统计相关提交，确保基线一致。
2) 再对照 `feature/serial` 的 nodetable/hash/lookup 优化，逐项手动合并或复用逻辑。
3) 保持“暂不启用 edge-BDD 复用”约束，避免引入实现差异。
