# Sylvan GC 导致 MTPNDD BDD 边标签被回收的 Bug 分析

## 问题现象

SRE-NDD 在 `SYLVAN_USE_MMAP=OFF` 且 Sylvan GC **启用**的情况下，
运行 fattree08/mf=3/workers=1 时发生 SIGSEGV（exit code 139），
且无 `hs_err_pid*.log` 文件生成。

崩溃前日志显示 MTPNDD GC 反复触发（reclaimed=0），
每次都伴随 Sylvan GC（refs 数量不变）：

```
[MTPNDD GC] start nodes=2097152 capacity=2097152
[MTPNDD GC] end nodes=1285629 reclaimed=0
[Sylvan GC] start refs=45854 capacity=8388608
[Sylvan GC] end refs=45854
... (重复 14 次)
[MTPNDD GC] start nodes=2160008 capacity=4194304
[MTPNDD GC] end nodes=2160008 reclaimed=0
[Sylvan GC] start refs=378478 capacity=8388608
[Sylvan GC] end refs=378478
[RUN.SH] java_exit_code=139
```

注意：workers=1 时不存在并发问题，之前 MMAP 相关的 SIGBUS 已通过
`SYLVAN_USE_MMAP=OFF` 解决。此崩溃是独立的新问题。

## 根因分析

### 调用链

```
mtpndd_and_rec (Lace TASK)
  → mtpndd_mk
    → gcOrGrow()              // mtpndd_nodetable.c:370
      → gc_internal()         // MTPNDD 自己的 GC
      → grow_internal()       // MTPNDD node table 扩容
      → sylvan_gc()           // mtpndd_nodetable.c:503  ← 触发 Sylvan GC
```

### Sylvan GC 流程

`sylvan_gc()` → `NEWFRAME(sylvan_gc_go)` 执行以下步骤：

1. **`llmsset_clear_data`** — 清除 BDD 节点表的 bitmap（所有节点标记为"未使用"）
2. **mark callbacks** — 遍历 `mark_list`，调用注册的 mark 回调重新标记活跃节点
3. **`llmsset_destroy_unmarked`** — 销毁未被标记的 BDD 节点
4. **`llmsset_clear_hashes`** — 清除哈希表
5. **`llmsset_rehash`** — 对标记的节点重建哈希

### Bug 所在

MTPNDD 在初始化时（`mtpndd_common.c:650-656`）：
- **注册了** `sylvan_gc_hook_pregc` / `sylvan_gc_hook_postgc`（仅用于日志）
- **没有注册** `sylvan_gc_add_mark` 回调

因此在步骤 2 中，只有通过 `sylvan_protect()` 保护的全局 BDD 变量
（`shared_bdd_vars`, `shared_bdd_not_vars`，约几百个）被标记。

而 MTPNDD 边标签（`edge_bucket_entry_t.label`）引用的 BDD 节点
**没有被标记**，在步骤 3 中被销毁。之后 MTPNDD 操作访问这些已销毁的
BDD 节点，导致 use-after-free → SIGSEGV。

### 数据结构参考

MTPNDD 的每个节点通过 edge map 存储子节点引用和 BDD 标签：

```c
// mtpndd_node.h:27-31
typedef struct edge_bucket_entry_s {
    struct edge_bucket_entry_s *next;
    mtpndd_node_t *child;
    _Atomic(mtpndd_bdd_t) label;    // ← BDD 节点引用，未被 Sylvan GC 保护
} edge_bucket_entry_t;
```

每个 MTPNDD node table（按 field 分）包含数十万到数百万个节点，
每个节点有多个 edge entry，每个 entry 的 `label` 都引用一个 BDD 节点。

### 为什么之前的测试没有触发

| 场景 | 结果 | 原因 |
|------|------|------|
| Sylvan GC 禁用 (`sylvan_gc_disable()`) | 通过 | Sylvan GC 从不执行，BDD 节点不被回收 |
| fattree12/mf=3/w=4 (GC 启用) | 通过 | `mtpnddNodeTableSize=33M` 足够大，`gcOrGrow` 从未触发 |
| fattree08/mf=3/w=1 (GC 启用) | 崩溃 | `mtpnddNodeTableSize=2M` 太小，频繁触发 `gcOrGrow` → `sylvan_gc()` |

## 解决方案

### 方案 C（推荐）：注册 `sylvan_gc_add_mark` 回调

在 MTPNDD 初始化时注册一个 mark 回调，在 Sylvan GC 期间遍历所有
活跃 MTPNDD 节点的边标签，调用 `mtbdd_gc_mark_rec()` 标记对应的 BDD 节点。

#### 实现要点

1. **新增 mark 回调函数**（建议放在 `mtpndd_nodetable.c`）：
   - 遍历所有 field 的 node table
   - 对每个活跃节点（refcount > 0 或其他存活判定）的每条边
   - 用 `mtbdd_gc_mark_rec(label)` 标记 BDD 节点（递归标记其子节点）

2. **注册回调**（在 `mtpndd_common.c` 初始化中）：
   ```c
   sylvan_gc_add_mark(mtpndd_gc_mark_bdd_labels);
   ```

3. **Sylvan 提供的标记 API**：
   ```c
   // sylvan_mtbdd.h:1018-1019
   VOID_TASK_DECL_1(mtbdd_gc_mark_rec, MTBDD);
   #define mtbdd_gc_mark_rec(mtbdd) RUN(mtbdd_gc_mark_rec, mtbdd)
   ```
   mark 回调在 Lace worker 上下文中执行（通过 `WRAP(e->cb)` 调用），
   可以直接使用 `CALL(mtbdd_gc_mark_rec, label)` 或宏 `mtbdd_gc_mark_rec(label)`。

4. **底层标记机制**（`sylvan_mtbdd.c:113-124`）：
   ```c
   VOID_TASK_IMPL_1(mtbdd_gc_mark_rec, MDD, mtbdd)
   {
       if (mtbdd <= mtbdd_true) return;
       if (llmsset_mark(nodes, MTBDD_STRIPMARK(mtbdd))) {
           mtbddnode_t n = MTBDD_GETNODE(mtbdd);
           if (!mtbddnode_isleaf(n)) {
               SPAWN(mtbdd_gc_mark_rec, mtbddnode_getlow(n));
               CALL(mtbdd_gc_mark_rec, mtbddnode_gethigh(n));
               SYNC(mtbdd_gc_mark_rec);
           }
       }
   }
   ```

#### 注意事项

- mark 回调必须遍历 **所有活跃 MTPNDD 节点**（不仅是有外部引用的根节点），
  因为 Sylvan GC 会清除整个 BDD 节点表
- `mtbdd_gc_mark_rec` 内部使用 `llmsset_mark` 做幂等标记（已标记的节点不会重复遍历），
  所以多个 MTPNDD 节点引用同一个 BDD 不会重复计算
- mark 回调在 NEWFRAME 内执行，此时所有 Lace worker 已同步，
  不存在并发的 MTPNDD 操作，无需额外加锁
- 需要同时标记 MTPNDD operation cache 中缓存的 BDD 节点（如有）

### 其他方案（对比）

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| A | 注释掉 `gcOrGrow` 中的 `sylvan_gc()` | 简单 | Sylvan 自身 `llmsset_lookup` 满时仍可能触发 GC |
| B | `sylvan_gc_disable()` 禁用所有 Sylvan GC | 简单，已验证可行 | 需要足够大的 BDD 表，内存浪费 |
| **C** | **注册 mark 回调正确标记 BDD 标签** | **正确解决根因，允许 BDD 节点回收** | **需要遍历所有 MTPNDD 节点，GC 暂停时间可能增加** |

## 相关文件

| 文件 | 关键位置 |
|------|----------|
| `mtpndd_node.h:27-31` | `edge_bucket_entry_t` 结构（BDD label 字段） |
| `mtpndd_node.c:991-996` | `mtpndd_edge_label_load` — 原子加载 BDD label |
| `mtpndd_nodetable.c:503` | `gcOrGrow` 中触发 `sylvan_gc()` |
| `mtpndd_nodetable.c:574-587` | `mtpndd_release_node` — 释放节点时未处理 BDD label |
| `mtpndd_common.c:309-314` | `sylvan_protect` 保护全局 BDD 变量 |
| `mtpndd_common.c:650-656` | GC hooks 注册（缺少 `sylvan_gc_add_mark`） |
| `sylvan_common.c:88-95` | `sylvan_gc_add_mark` API |
| `sylvan_common.c:118-127` | `sylvan_clear_and_mark` — GC 标记流程 |
| `sylvan_mtbdd.c:113-124` | `mtbdd_gc_mark_rec` — 递归标记 BDD 节点 |
