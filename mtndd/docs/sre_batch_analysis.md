# SRE-NDD 批量操作热点分析

## 1. Profiling 数据（bgp_fattree08, mf=3, workers=1）

### 1.1 总体耗时

| 阶段 | 时间 |
|------|------|
| init | 0.59s |
| src (BGP) | 29.78s |
| spf (FWD+REACHABILITY) | 6.41s |
| mine | 0.002s |
| **total** | **36.90s** |

### 1.2 MTPNDD 操作耗时分解

| 操作 | 调用次数 | 时间 (s) | 占总时间% | 平均耗时 (µs) |
|------|---------|---------|----------|-------------|
| and | 3,473,991 | 12.51 | 33.9% | 3.6 |
| or | 741,828 | 10.75 | 29.1% | 14.5 |
| not | 621,608 | 4.36 | 11.8% | 7.0 |
| satCount | 970,682 | 5.53 | 15.0% | 5.7 |
| ref | 4,808,364 | 0.18 | 0.5% | 0.04 |
| deref | 1,807,838 | 0.07 | 0.2% | 0.04 |
| **TOTAL** | | **33.41s** | **90.5%** | |

**结论**：90.5% 的时间花在 MTPNDD native 操作上，Java 开销仅 ~3.5s（9.5%）。

### 1.3 关键观察

- **and** 是最高频操作（3.5M 次），但单次较快（3.6µs），总量占比最大
- **or** 单次较慢（14.5µs），可能因为 OR 未并行化（纯串行递归）
- **satCount** 出人意料地贵（5.5s），971K 次调用，主要来自 RIB 查询和路由比较
- **ref/deref** 可忽略（0.25s），JNI 开销不是瓶颈

---

## 2. 现有 batch 实现

### 2.1 Native 层

| 函数 | 实现方式 | 并行性 |
|------|---------|--------|
| `mtpndd_and_batch` | 顺序循环，每个 AND 内部用 Lace | **串行（各 AND 之间）** |
| `mtpndd_or_reduce` | tree-reduce，SPAWN/SYNC | **并行** |
| `mtpndd_and_reduce` | tree-reduce，SPAWN/SYNC | **并行** |

### 2.2 Java 层

| 函数 | 位置 | 用途 |
|------|------|------|
| `MTPNDD.andBatch(lefts[], rights[])` | MTPNDDEngine | 一次 JNI 调用完成多对 AND |
| `MTPNDD.orReduce(values[])` | MTPNDDEngine | 一次 JNI 调用完成多值 OR |
| `MTPNDD.andReduce(values[])` | MTPNDDEngine | 一次 JNI 调用完成多值 AND |
| `NDDUtil.andInBatch(nodes[])` | NDDUtil | Java 侧 pre-filter + threshold 分发 |
| `NDDUtil.orInBatch(nodes[])` | NDDUtil | Java 侧 pre-filter + threshold 分发 |

### 2.3 改进方向

`andBatch` 应改为 SPAWN/SYNC 并行，类似 `and_reduce`：

```c
TASK_DECL_4(void, and_batch_rec, mtpndd_t**, mtpndd_t**, mtpndd_t**, size_t);
TASK_IMPL_4(void, and_batch_rec, lefts, rights, results, count) {
    if (count <= 1) {
        results[0] = CALL(mtpndd_and_rec, lefts[0], rights[0]);
        return;
    }
    size_t mid = count / 2;
    SPAWN(and_batch_rec, lefts, rights, results, mid);
    CALL(and_batch_rec, lefts+mid, rights+mid, results+mid, count-mid);
    SYNC(and_batch_rec);
}
```

同理需要新增 `mtpndd_or_batch(lefts[], rights[], count) -> results[]`。

---

## 3. SRE 热点中的 batch 机会

### 3.1 已 batch 的地方

#### NDDBgpProcess.routeOut()
- **文件**：`src/main/java/controlplane/process/bgp/NDDBgpProcess.java:295-307`
- **模式**：收集 (route.listTC, edge.tc) 对，调用 `MTPNDD.andBatch(lefts, rights)`
- **阈值**：`sre.andBatch.threshold`，默认 8
- **状态**：✅ 已实现

### 3.2 可 batch 的热点（按优先级排序）

#### P0: NDDTuple.intersect() — 最高频，每次 2 个独立 AND

- **文件**：`src/main/java/bdd/NDDTuple.java:27-29`
- **当前代码**：
  ```java
  public static NDDTuple intersect(NDDTuple tuple, MTPNDD i, MTPNDD j) {
      return new NDDTuple(MTPNDD.ref(MTPNDD.and(tuple.i, i)),
                          MTPNDD.ref(MTPNDD.and(tuple.j, j)));
  }
  ```
- **调用点**：`NDDReachabilityDB.forward()` 连续调用 3 次（line 168-170），产生 6 个 AND
  ```java
  NDDTuple newTuple = NDDTuple.intersect(curNode.tuple, outPort.getPortFwdNDD(), MTPNDD.getTrue());
  newTuple = NDDTuple.intersect(newTuple, MTPNDD.getTrue(), outPort.getPortAclOutNDD());
  newTuple = NDDTuple.intersect(newTuple, MTPNDD.getTrue(), nxtPort.getPortAclInNDD());
  ```
- **分析**：
  - 每次 `intersect` 内的 2 个 AND 完全独立，可用 `andBatch`
  - 但 3 次 `intersect` 之间有数据依赖（前一次结果作为后一次输入）
  - 注意：当其中一个参数是 `MTPNDD.getTrue()` 时，AND 可以短路优化
  - Reachability 阶段花了 4.4s，此处是大头
- **batch 方案**：改为收集 forward() 中一个 router 所有 port 的 intersect 操作，批量提交

#### P1: NDDPredicateWrapper.getPortPredicates() — FWD 阶段核心

- **文件**：`src/main/java/bdd/NDDPredicateWrapper.java:36-56`（ECMP 版）和 `78-103`（非 ECMP 版）
- **当前代码（ECMP 版）**：
  ```java
  for (NDDSortedMultiListItem item : list) {
      MTPNDD care = MTPNDD.ref(MTPNDD.and(fwd, dstPrefix));      // AND 1：独立
      MTPNDD ruleBDD = MTPNDD.ref(MTPNDD.and(care, tc));          // AND 2：依赖 AND 1
      MTPNDD toAdd = MTPNDD.ref(MTPNDD.and(ruleBDD, notAlready)); // AND 3：依赖 AND 2
      inner = MTPNDD.orTo(inner, ruleBDD);                         // OR：累积依赖
  }
  ```
- **分析**：
  - 不同 rule 间的 `and(fwd, dstPrefix)` 是独立的，可以先 batch 算出所有 care
  - 后续的 `and(care, tc)` 依赖 care 结果但各 rule 间独立，可以第二轮 batch
  - `orTo` 是累积依赖，不可 batch（但可用 orReduce 替代）
- **batch 方案**：两轮 batch — 第一轮 `andBatch(fwd[], dstPrefix[])`，第二轮 `andBatch(care[], tc[])`

#### P2: NDDBgpProcess.combineIgp() — BGP 路由解析

- **文件**：`src/main/java/controlplane/process/bgp/NDDBgpProcess.java:236-247`
- **当前代码**：
  ```java
  tbl.cellSet().forEach(cell -> {
      .setArriveTC(MTPNDD.ref(MTPNDD.and(bgpRoute.getArriveTC(), cell.getValue())))
  });
  ```
- **分析**：多个 cell 的 AND 操作完全独立（同一个 bgpRoute.arriveTC 与不同 igp TC）
- **batch 方案**：收集所有 cell.getValue()，用 `andBatch(bgpRoute.arriveTC 重复, cellValues[])`

#### P3: NDDTuple.unionTo() / minus() — 2 个独立 OR/DIFF

- **文件**：`src/main/java/bdd/NDDTuple.java:23-25, 35-37`
- **当前代码**：
  ```java
  public static NDDTuple unionTo(NDDTuple t1, NDDTuple t2) {
      return new NDDTuple(MTPNDD.orTo(t1.i, t2.i), MTPNDD.orTo(t1.j, t2.j));
  }
  public static NDDTuple minus(NDDTuple t1, NDDTuple t2) {
      return new NDDTuple(MTPNDD.ref(MTPNDD.diff(t1.i, t2.i)), MTPNDD.ref(MTPNDD.diff(t1.j, t2.j)));
  }
  ```
- **分析**：每次调用内的 2 个操作完全独立，可用 `orBatch`/`diffBatch`
- **batch 方案**：需要新增 `orBatch` 和 `diffBatch` native API

#### P4: NDDRib.query() — 路由查询

- **文件**：`src/main/java/controlplane/rib/NDDRib.java:194-211`
- **当前代码**：
  ```java
  for (NDDSortedMultiListItem item : entry.getValue().flat()) {
      NDDRoute route = (NDDRoute) item;
      MTPNDD delta = MTPNDD.ref(MTPNDD.and(route.getListTC(), nAlMatched));
      // ...
      alMatched = MTPNDD.orTo(alMatched, route.getListTC());
  }
  ```
- **分析**：`and(route.getListTC(), nAlMatched)` 在同一 prefix 组内独立，但 `alMatched` 累积更新导致 `nAlMatched` 在不同 prefix 组间变化
- **batch 方案**：同一 prefix 组内的 AND 可以 batch

#### P5: NDDACLWrapper.ConvertACLRule() — ACL 规则编码

- **文件**：`src/main/java/bdd/NDDACLWrapper.java:372-452`
- **当前代码**：条件式串联 `ret = MTPNDD.andTo(ret, ConvertXxx())`（最多 ~5 次 AND）
- **分析**：各字段编码独立，但数量少（≤5），batch 收益有限
- **batch 方案**：先生成所有非空子条件，然后用 `andReduce`

---

## 4. 需要新增的 Native API

| API | 签名 | 用途 |
|-----|------|------|
| `mtpndd_and_batch`（改为并行） | `mtpndd_t** (mtpndd_t**, mtpndd_t**, size_t)` | 已有，需改为 SPAWN/SYNC |
| `mtpndd_or_batch`（新增） | `mtpndd_t** (mtpndd_t**, mtpndd_t**, size_t)` | 多对 OR 并行 |
| `mtpndd_diff_batch`（新增，低优） | `mtpndd_t** (mtpndd_t**, mtpndd_t**, size_t)` | 多对 DIFF 并行 |

---

## 5. 实施建议

### 第一步：改进 andBatch 为并行
- 修改 `mtpndd_and_batch` 使用 SPAWN/SYNC 分治
- 保持 JNI/Java 层 API 不变
- 验证：routeOut() 已有 batch 路径，可直接对比

### 第二步：新增 orBatch
- Native + JNI + Java 全栈
- 应用到 NDDTuple.unionTo()、getPortPredicates() 的 inner OR 累积

### 第三步：优化 NDDTuple.intersect
- 识别 TRUE 参数短路
- 收集同一 forward() 调用中的多次 intersect，合并为一次 batch

### 第四步：优化 getPortPredicates
- 两轮 batch：先 andBatch(fwd, dstPrefix)，再 andBatch(care, tc)
- 最后 orReduce 替代 orTo 累积

---

## 更新记录

- 2026-03-14: 初始版本。基于 MTPNDDEngine timing instrumentation 的 profiling 数据和代码分析
