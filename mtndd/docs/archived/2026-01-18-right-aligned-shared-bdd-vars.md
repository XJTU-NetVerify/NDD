# 右对齐 shared BDD variables：复用边标签变量，降低内存/保护开销

**对应提交:** `a96389c` (2026-01-18) `feat(mtpndd): implement right-aligned shared BDD variables for edge labels`
**目标:** 减少 BDD 变量的重复创建/保护（protect/unprotect）与字段间拷贝开销，同时让不同 bit-width 字段共享统一的变量布局，便于编码边标签。

## 背景：每个 field 单独维护 bdd_vars 的成本

在多 field 场景下，每个 field 都需要 `bit_width` 个 BDD 变量用于构造 label。

如果每个 field 都创建/保护自己的 bdd_vars：
- `sylvan_ithvar` / `sylvan_not` 会被重复调用（或反复引用/保护）
- `sylvan_protect`/`sylvan_unprotect` 次数增加
- 内存占用与初始化成本随 field_count * bit_width 增长

## 方案：按 max_bit_width 一次性创建 shared_bdd_vars，然后右对齐切片复用

核心设计（在 field 生成阶段一次性完成）：
1) 统计所有字段的最大 bit width：`max_width`
2) 分配并初始化：
   - `g_mtpndd_config.shared_bdd_vars[max_width]`
   - `g_mtpndd_config.shared_bdd_not_vars[max_width]`
3) 对每个 field（bit_width = w）：
   - 计算偏移：`offset = max_width - w`
   - 该 field 的 `bdd_vars[i]` 直接引用 `shared_bdd_vars[offset + i]`
   - `bdd_not_vars[i]` 同理引用 `shared_bdd_not_vars[offset + i]`

之所以称为“右对齐”，是因为所有字段都映射到 shared 数组的尾部：
- 宽字段覆盖更多变量
- 窄字段使用尾部子区间
- 使得“低位/末端变量”在不同字段间的位置保持一致，便于统一编码策略

实现位置：
- `sylvan/src/sylvan/mtpndd/mtpndd_common.c`
  - `g_mtpndd_config.shared_bdd_vars` / `shared_bdd_not_vars`
  - `offset = max_width - bit_width`
  - 注释：field 的 `bdd_vars`/`bdd_not_vars` 只是 shared 的引用，不应重复 unprotect

## 为什么这是“优化点”

直接收益：
- `sylvan_ithvar` / `sylvan_not` 初始化只做 `max_width` 次，而不是 sum(bit_width) 次
- protect/unprotect 也只做 `max_width` 次（每个 shared slot 一次）
- field 初始化时更多是指针赋值而不是创建/保护

间接收益：
- 更好的数据局部性（shared 数组连续）
- 多 field 共用变量时更容易做进一步的 label 编码优化（例如批量构造/缓存）

## 复现实验建议（论文复现）

建议记录两套实现的差异：
1) per-field 独立 bdd_vars（或重复 protect/unprotect）
2) shared_bdd_vars + right-aligned slicing

观测项：
- 初始化阶段时间（field 生成 + bdd vars 初始化）
- Sylvan refs / protect 数量变化（如有可观测接口）
- NQueens N=12 的总体时间变化（该优化主要影响初始化与 label 构造的常数项）
