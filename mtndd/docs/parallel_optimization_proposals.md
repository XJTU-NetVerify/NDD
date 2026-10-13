# MTPNDD 并行版本优化提案

本文档记录了在串行版本中验证有效、但在并行版本中需要特殊处理的优化方案。

---

## 1. Cached Hash 优化

### 1.1 背景与动机

在 MTPNDD 的 nodetable 查找中，需要计算 edge map 的 hash 值来定位 bucket。原始实现每次查找都需要遍历整个 edge map 来计算 hash：

```c
// 原始实现：O(n) 复杂度，n = edge_count
static inline size_t nodetable_hash_edges_with_bucket_count(const mtpndd_edge_t *key, size_t bucket_count) {
    uint64_t hash = 0;
    for (size_t i = 0; i < edge_bucket_cnt; i++) {
        edge_bucket_entry_t *entry = key->buckets[i];
        while (entry) {
            uint64_t entry_hash = mtpndd_hash_u64((uintptr_t)entry->child);
            mtpndd_bdd_t label = atomic_load_explicit(&entry->label, memory_order_relaxed);
            entry_hash ^= mtpndd_hash_u64((uint64_t)label);
            hash ^= entry_hash;
            entry = entry->next;
        }
    }
    // ... mix and return
}
```

### 1.2 串行版本的优化方案

在串行版本（feature/serial）中，我们通过缓存 hash 值实现了 O(1) 查找：

**数据结构修改**：
```c
struct mtpndd_edge_s {
    size_t edge_count;
    uint64_t cached_hash;  // 新增：缓存的 hash 值
    atomic_uint_fast64_t bucket_lock_word;
    edge_bucket_entry_t **buckets;
};
```

**增量更新 hash**（在 `mtpndd_add_edge` 中）：
```c
// XOR-based, order-independent hash update
// 移除旧的 (child, label) 贡献
if (old_label != sylvan_false) {
    uint64_t old_entry_hash = mtpndd_hash_u64((uintptr_t)descendant);
    old_entry_hash ^= mtpndd_hash_u64((uint64_t)old_label);
    edges->cached_hash ^= old_entry_hash;
}
// 添加新的 (child, label) 贡献
uint64_t new_entry_hash = mtpndd_hash_u64((uintptr_t)descendant);
new_entry_hash ^= mtpndd_hash_u64((uint64_t)new_label);
edges->cached_hash ^= new_entry_hash;
```

**O(1) 查找**：
```c
static inline size_t nodetable_hash_edges_with_bucket_count(const mtpndd_edge_t *key, size_t bucket_count) {
    uint64_t hash = key->cached_hash;  // O(1)
    // mix and return
}
```

**额外优化**：在 `nodetable_edges_equal` 中添加快速路径：
```c
static inline bool nodetable_edges_equal(const mtpndd_edge_t *a, const mtpndd_edge_t *b) {
    // ... 基本检查 ...
    // 快速路径：hash 不同则内容必不同
    if (a->cached_hash != b->cached_hash) return false;
    // 继续详细比较...
}
```

### 1.3 串行版本的性能提升

| N | 优化前 | 优化后 | 提升 |
|---|--------|--------|------|
| 10 | ~3.88s | ~0.9s | 4.3x |

### 1.4 并行版本的问题

在并行版本（feature/c）中直接应用此优化会导致**严重的性能退化**：

| N | 优化前 | 错误优化后 | 退化 |
|---|--------|------------|------|
| 11 | ~9s | 31s+ | 3.4x 更慢 |
| 12 | ~34s | 超长时间 | 严重退化 |

**问题根源**：竞态条件（Race Condition）

```c
// 多个线程可能同时执行：
edges->cached_hash ^= new_entry_hash;  // 非原子操作！
```

1. `cached_hash` 是普通 `uint64_t`，不是原子类型
2. 多个 worker 线程通过 `SPAWN` 并行执行任务时，同时调用 `mtpndd_add_edge`
3. XOR 操作 `edges->cached_hash ^= ...` 不是原子的
4. **后果**：
   - 错误的 hash 值导致 nodetable 查找失败
   - 相同内容的节点无法被正确识别和复用
   - 创建大量重复节点，内存和时间急剧增加

### 1.5 并行版本的实现方案

#### 方案 A：原子操作（推荐）

**修改数据结构**：
```c
struct mtpndd_edge_s {
    size_t edge_count;
    _Atomic(uint64_t) cached_hash;  // 改为原子类型
    atomic_uint_fast64_t bucket_lock_word;
    edge_bucket_entry_t **buckets;
};
```

**使用原子 XOR 操作**：
```c
// 在 mtpndd_add_edge 中
if (old_label != sylvan_false) {
    uint64_t old_entry_hash = mtpndd_hash_u64((uintptr_t)descendant);
    old_entry_hash ^= mtpndd_hash_u64((uint64_t)old_label);
    atomic_fetch_xor_explicit(&edges->cached_hash, old_entry_hash, memory_order_relaxed);
}
uint64_t new_entry_hash = mtpndd_hash_u64((uintptr_t)descendant);
new_entry_hash ^= mtpndd_hash_u64((uint64_t)new_label);
atomic_fetch_xor_explicit(&edges->cached_hash, new_entry_hash, memory_order_relaxed);
```

**读取时使用原子加载**：
```c
static inline size_t nodetable_hash_edges_with_bucket_count(const mtpndd_edge_t *key, size_t bucket_count) {
    uint64_t hash = atomic_load_explicit(&key->cached_hash, memory_order_acquire);
    // mix and return
}
```

**优点**：
- 保持 O(1) hash 查找复杂度
- 线程安全
- XOR 是可交换和可结合的，原子 XOR 操作顺序无关

**缺点**：
- 原子操作有一定开销（但比遍历 edge map 小得多）
- 需要确保所有访问都使用原子操作

#### 方案 B：延迟计算 + 脏标记

```c
struct mtpndd_edge_s {
    size_t edge_count;
    uint64_t cached_hash;
    _Atomic(bool) hash_dirty;  // 脏标记
    atomic_uint_fast64_t bucket_lock_word;
    edge_bucket_entry_t **buckets;
};
```

**修改时标记为脏**：
```c
// 在 mtpndd_add_edge 中
atomic_store_explicit(&edges->hash_dirty, true, memory_order_release);
```

**查找时按需计算**：
```c
static inline size_t nodetable_hash_edges_with_bucket_count(const mtpndd_edge_t *key, size_t bucket_count) {
    if (atomic_load_explicit(&key->hash_dirty, memory_order_acquire)) {
        // 重新计算 hash（需要加锁）
        // 这里需要额外的同步机制
    }
    return key->cached_hash % bucket_count;
}
```

**缺点**：
- 实现复杂
- 需要额外的锁来保护 hash 重算
- 可能导致多个线程重复计算

#### 方案 C：只在 mtpndd_mk 时计算（不推荐）

在创建节点时一次性计算 hash，之后 edge map 不再修改。

**问题**：
- 当前架构中 edge map 在构建过程中会被多次修改
- 需要重构为先构建完整 edge map，再计算 hash 并创建节点

### 1.6 实现建议

1. **优先尝试方案 A**：原子操作是最简单和最可靠的方案
2. 在实现前进行基准测试，评估原子操作的实际开销
3. 如果原子操作开销过大，考虑方案 B 或重新评估架构

### 1.7 示例代码：方案 A 完整实现

```c
// mtpndd_node.h
struct mtpndd_edge_s {
    size_t edge_count;
    _Atomic(uint64_t) cached_hash;
    atomic_uint_fast64_t bucket_lock_word;
    edge_bucket_entry_t **buckets;
};

// mtpndd_node.c - mtpndd_edge_map_init
void mtpndd_edge_map_init(mtpndd_edge_t *edges) {
    edges->edge_count = 0;
    atomic_store_explicit(&edges->cached_hash, 0, memory_order_relaxed);
    edges->bucket_lock_word = 0;
}

// mtpndd_node.c - mtpndd_add_edge (hash 更新部分)
mtpndd_error_t mtpndd_add_edge(mtpndd_edge_t *edges, mtpndd_t *descendant, mtpndd_bdd_t label_bdd) {
    // ... 现有逻辑 ...

    // 原子更新 cached_hash
    if (old_label != sylvan_false) {
        uint64_t old_entry_hash = mtpndd_hash_u64((uintptr_t)descendant);
        old_entry_hash ^= mtpndd_hash_u64((uint64_t)old_label);
        atomic_fetch_xor_explicit(&edges->cached_hash, old_entry_hash, memory_order_relaxed);
    }
    uint64_t new_entry_hash = mtpndd_hash_u64((uintptr_t)descendant);
    new_entry_hash ^= mtpndd_hash_u64((uint64_t)new_label);
    atomic_fetch_xor_explicit(&edges->cached_hash, new_entry_hash, memory_order_relaxed);

    // ... 继续现有逻辑 ...
}

// mtpndd_node.c - mtpndd_edge_map_deep_clone
static mtpndd_error_t mtpndd_edge_map_deep_clone(const mtpndd_edge_t *source, mtpndd_edge_t *dest) {
    dest->edge_count = source->edge_count;
    uint64_t src_hash = atomic_load_explicit(&source->cached_hash, memory_order_relaxed);
    atomic_store_explicit(&dest->cached_hash, src_hash, memory_order_relaxed);
    // ... 继续现有逻辑 ...
}

// mtpndd_node.c - mtpndd_edge_map_reset
static void mtpndd_edge_map_reset(mtpndd_edge_t *edges) {
    // ... 现有逻辑 ...
    edges->bucket_lock_word = 0;
    atomic_store_explicit(&edges->cached_hash, 0, memory_order_relaxed);
    edges->edge_count = 0;
}

// mtpndd_nodetable.h - nodetable_hash_edges_with_bucket_count
static inline size_t nodetable_hash_edges_with_bucket_count(const mtpndd_edge_t *key, size_t bucket_count) {
    if (!key || bucket_count == 0) return 0;

    uint64_t hash = atomic_load_explicit(&key->cached_hash, memory_order_acquire);

    hash ^= (hash >> 33);
    hash *= 0xff51afd7ed558ccdULL;
    hash ^= (hash >> 33);

    return (size_t)(hash % bucket_count);
}

// mtpndd_nodetable.h - nodetable_edges_equal (快速路径)
static inline bool nodetable_edges_equal(const mtpndd_edge_t *a, const mtpndd_edge_t *b) {
    if (a == b) return true;
    if (!a || !b) return false;
    if (a->edge_count != b->edge_count) return false;
    if (a->edge_count == 0) return true;

    // 快速路径：原子读取并比较
    uint64_t hash_a = atomic_load_explicit(&a->cached_hash, memory_order_relaxed);
    uint64_t hash_b = atomic_load_explicit(&b->cached_hash, memory_order_relaxed);
    if (hash_a != hash_b) return false;

    // 继续详细比较...
}
```

---

## 2. 其他已应用的优化

### 2.1 memset 初始化优化

在 `mtpndd_edge_map_deep_clone` 中使用 `memset` 代替循环初始化：

```c
// 优化后
memset(dest->buckets, 0, sizeof(edge_bucket_entry_t *) * bucket_cnt);
```

**状态**：已在并行版本中应用，线程安全，无需额外处理。

---

## 更新记录

- 2024-12-27: 初始版本，记录 cached_hash 优化的分析和并行实现方案
