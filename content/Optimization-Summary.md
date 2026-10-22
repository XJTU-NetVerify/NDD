# Optimization Summary

This page summarizes the changes that distinguish the current `NDD` implementation from the original `NDD-Origin` baseline.

If you want the short version first: the optimized branch keeps the same field-aware semantics, but reduces overhead in two places at once:

- fewer BDD label nodes through cross-field variable reuse
- fewer JVM allocations through an array-based node table and a shared edge-collection stack

## Goals

- reduce overhead from the original nested `HashMap` representation
- cut temporary allocation during logical operations
- reuse BDD variables across compatible fields
- preserve the external NDD workflow of declaring fields and then operating on them

## Main Changes

### 1. Array-Based Node Table

#### The original design: hash maps all the way down

In `NDD-Origin` (branch `ndd-original`), every NDD node is a Java object with a `HashMap` of edges, and the node table is built from more hash maps:

```java
// NDD.java (original)
class NDD {
    int field;
    HashMap<NDD, Integer> edges;          // child node -> BDD label handle
}

// NodeTable.java (original)
ArrayList<HashMap<HashMap<NDD, Integer>, NDD>> nodeTable;   // per field: edge map -> unique node
HashMap<NDD, Integer> referenceCount;                       // node -> ref count
```

This is easy to write, but it is slow on the operations that run most often:

- **A lot of objects per node.** A node with *k* edges needs the `NDD` object, its edge `HashMap` and bucket array, *k* `HashMap.Node` entries, and *k* boxed `Integer` labels. On top of that it needs one entry in the per-field unique table and one more entry, with another boxed `Integer`, in `referenceCount`. A node that holds about 8 bytes of real data per edge ends up spread across many separate heap objects.
- **Expensive unique-table lookups.** `mk(field, edges)` uses the whole edge map as the key. Hashing the key means walking every entry, and `equals` on a hash hit runs `AbstractMap.equals`, which does one hash lookup in the other map for each edge. All of this follows pointers to objects that may be far apart in memory.
- **Garbage even when the node already exists.** Each `and`/`or`/`not` step builds a fresh `HashMap` of edges before calling `mk`. When `mk` finds an existing node, which is the common case, that map is thrown away right away.
- **Reference counting goes through a map.** Every `ref`, `deref`, and child update in `mk` or `gc` is a `referenceCount.get` plus `put`, which means hashing and boxing an `Integer`. GC itself walks `referenceCount.entrySet()` and puts dead nodes on a `LinkedList`, which allocates even more.

Together this means heavy pointer chasing, poor cache locality, and constant pressure on the JVM garbage collector, all inside the inner loop of every logical operation.

#### The array-based design

This design comes from [JDD](https://bitbucket.org/vahidi/jdd), the Java BDD library that NDD already uses for its edge labels and that is itself a Java version of the C library [BuDDy](https://buddy.sourceforge.net/). JDD's own node table ([`jdd/bdd/NodeTable.java`](../src/main/java/jdd/bdd/NodeTable.java)) never creates BDD node objects. A BDD node is an `int` index into flat arrays: `t_nodes` holds each node's `(var, low, high)` triple, `t_ref` holds reference counts, and `t_list` links the unique-table hash chains. NDD applies the same idea to its own nodes. NDD nodes differ from BDD nodes in one way: a node can have any number of outgoing edges, not exactly two children. So NDD adds shared edge arrays and edge blocks on top of the JDD scheme.

The current [`NodeTable.java`](../src/main/java/org/ants/jndd/nodetable/NodeTable.java) drops node objects completely. A node is just an `int` id (0 = FALSE, 1 = TRUE, 2+ = internal nodes), and each property of a node lives in its own primitive array at that index:

| Array | Indexed by | Meaning |
| --- | --- | --- |
| `nodeField[id]` | node | field this node tests |
| `nodeEdgeCount[id]` | node | number of outgoing edges |
| `nodeEdgeBlock[id]` | node | id of the node's edge block |
| `refCount[id]` | node | reference count, as a plain `int` |
| `nodeHash[id]`, `nodeNext[id]` | node | cached hash and the next node in the same unique-table bucket |
| `blockStart[b]` | edge block | where block `b` starts in the edge arrays |
| `edgeTarget[e]`, `edgeLabel[e]` | edge | child node id and BDD label handle |

A node's edges sit next to each other in two shared arrays, `edgeTarget` and `edgeLabel`, sorted by target.

**Example.** Take this small NDD with two fields. `L_a`, `L_b` and `L_c` are BDD labels:

```text
node 3 (field 0) ──L_a──► node 2 (field 1) ──L_c──► TRUE
       │
       └────────L_b─────► TRUE
```

Nodes are built bottom-up, so the child gets id 2 and the root gets id 3. Ids 0 and 1 are the FALSE and TRUE terminals. The table stores these two nodes like this:

*Node arrays: one row per node id*

| id | `nodeField` | `nodeEdgeCount` | `nodeEdgeBlock` |
| --- | --- | --- | --- |
| 2 | 1 | 1 | 1 |
| 3 | 0 | 2 | 2 |

*Block array: one row per edge block*

| block | `blockStart` |
| --- | --- |
| 1 | 0 |
| 2 | 1 |

*Edge arrays: shared by all nodes*

| e | `edgeTarget` | `edgeLabel` | belongs to |
| --- | --- | --- | --- |
| 0 | 1 (TRUE) | `L_c` | node 2 |
| 1 | 1 (TRUE) | `L_b` | node 3, first edge |
| 2 | 2 | `L_a` | node 3, second edge |

To read the edges of node 3, follow three array lookups:

```text
nodeEdgeBlock[3] = 2   →   blockStart[2] = 1   →   edges are e = 1 .. 1 + nodeEdgeCount[3] - 1 = 1 .. 2
```

So node 3 has two edges: `L_b → TRUE` and `L_a → node 2`. Edges are stored sorted by target id (1 before 2), so two nodes with the same edges always have identical slices, which makes the unique-table comparison a simple loop.

In this example, block ids happen to match the order in which edges were written. They stop matching once dead nodes are collected and the edge arrays are compacted; the extra `blockStart` step is what lets compaction move edges without changing any node.

The operations that were slow before now work like this:

- **Following an edge is a few array reads.** `edgeTarget[blockStart[nodeEdgeBlock[id]] + i]` gives the *i*-th child. No objects are involved, and the edges of one node are adjacent in memory, so a loop over them stays in cache.
- **Unique-table lookups compare ints.** Each field has a `UniqueTable` made of an `int[] buckets`, and its chains are linked through `nodeNext`, so the table needs no entry objects. `mk` hashes the sorted `(target, label)` pairs from the caller's arrays. It then checks `nodeHash` first and only compares the contiguous edge slice when the hashes match. A node is written into the edge arrays only on a miss.
- **Reference counts are plain ints.** `ref` and `deref` become `refCount[id]++` and `refCount[id]--`, with no hashing or boxing.
- **No per-node allocation.** The arrays grow by doubling (`ensureNodeCapacity`, `ensureEdgeCapacity`). Freed node ids and edge blocks are put on free lists and reused. As a result, the steady state creates almost no garbage for the JVM to collect.
- **Edge blocks allow compaction.** A node refers to its edges through a stable block id, not a raw offset. That lets `compactEdges()` close the gaps left by dead nodes by rewriting only `blockStart`, without renumbering any nodes. Compaction and slot reuse wait until a safe point, because a recursive operation that is still running may hold raw edge offsets (see [Design Notes](Design-Notes.md#4-safe-point-recycling)).

In short, the table's memory now grows with the number of nodes and edges rather than the number of Java objects. The hot paths (`mk`, edge traversal, and reference counting) become tight loops over primitive arrays.

### 2. Global Edge-Collection Stack

#### What a logical operation has to do

Operations such as `and` and `or` are recursive. A call on two nodes of the same field looks at each pair of edges, one from each node. For each pair it computes a new label (for `and`, the two labels ANDed together) and a new child (a recursive call on the two children). The result is a list of candidate edges for the new node.

Two candidate edges can point to the same child. A canonical NDD node may have only one edge per child, so such edges must be merged by ORing their labels. Only then can `mk` find or create the node.

#### The original design: a new hash map in every call

In `NDD-Origin`, every recursive call allocates a fresh `HashMap<NDD, Integer>` to hold its candidate edges. Each new edge is merged into the map right away: look up the child, OR the labels, and put the result back, boxing the label as an `Integer`. The finished map is passed to `mk`.

One `and` on large diagrams makes many thousands of recursive calls, so this allocates many thousands of short-lived maps, entry objects and boxed integers. Most of them are thrown away right after `mk` returns, because the node usually exists already. That is a lot of garbage-collection work in the hottest loop of the library.

#### The stack-based design

The current code keeps all candidate edges in one shared stack, made of two `int` arrays (`stackTargets` and `stackLabels`) that live for the whole program. Each recursive call uses a *frame*, which is a slice of the stack that starts wherever the stack top was when the call began:

1. **Start:** remember `frameStart` = the current stack top.
2. **Collect:** append each candidate edge `(child, label)` to the top of the stack (`edgeCollect`). This is a plain append, with no lookup and no merging yet.
3. **Flush:** when all pairs are done, `edgeFlush` sorts the frame by child id, merges neighbouring edges with the same child by ORing their labels, and passes the slice straight to `mk`. Then it resets the stack top to `frameStart`, which frees the frame.

A recursive call made in the middle of step 2 simply builds its frame on top of the caller's frame and removes it before returning, just like a call stack. **Example:** a call `P` collects edges, and one of its recursive calls `C` produces a result node `c`:

```text
(1) P has collected one edge                      stack: [ (x, L1) ]
                                                           └─ P ─┘

(2) P recurses into C; C collects two edges       stack: [ (x, L1) | (y, K1) | (y, K2) ]
    on top of P's frame                                    └─ P ─┘   └────── C ──────┘

(3) C flushes: merges the two y-edges into        stack: [ (x, L1) ]
    (y, K1 ∨ K2), calls mk → node c,                       └─ P ─┘
    and pops its frame

(4) P appends the edge that leads to c            stack: [ (x, L1) | (c, L2) ]
                                                           └────── P ──────┘

(5) P flushes the same way and pops its frame     stack: [ ]
```

The arrays only grow (by doubling) and are never freed. After a short warm-up, collecting and merging edges allocates nothing.

Merging at flush time, not on every append, is also cheaper. Finding an earlier edge with the same child in a plain array would mean scanning the frame on every append. Sorting once at the end costs less, and it leaves the edges in the sorted order that `mk` needs anyway. Small frames use quicksort with insertion sort for short runs. Frames of 64 or more edges use a linear-time radix sort. See [Design Notes](Design-Notes.md#2-global-edge-collection) for the details.

### 3. Right-Aligned Shared BDD Variables

#### The original design: one set of BDD variables per field

In `NDD-Origin`, `declareField(bitNum)` immediately creates `bitNum` new BDD variables for the field. So every field gets its own variables, and the BDD engine has as many variables as all fields together. This design has two costs:

- **The same constraint becomes different BDDs.** "srcIP in 10.0.0.0/8" and "dstIP in 10.0.0.0/8" describe the same bit pattern, but they are built over different variables. So the BDD engine stores two separate BDDs, and an operation computed for one field is never reused from the cache for the other.
- **More variables than needed.** A header with fields of 32 + 32 + 16 bits needs 80 BDD variables, even though no label ever mentions more than 32 of them.

#### The shared-variable design

The current code lets all fields use the *same* BDD variables. It creates one shared pool of variables, sized to the widest field, and maps every field onto it.

The mapping is **right-aligned**: a field of width `w` uses the *last* `w` variables of the pool. Bit 0 of a field is its most significant bit, so this lines up the low-order bits of all fields, the same way a 16-bit number is zero-extended to 32 bits.

**Example.** Three fields: `dstIP` (32 bits), `srcIP` (32 bits) and `dstPort` (16 bits). The shared pool has 32 variables, `v0` … `v31`:

| Field | Width | `NDD-Origin` variables | Shared variables (right-aligned) |
| --- | --- | --- | --- |
| `dstIP` | 32 | `x0` … `x31` | `v0` … `v31` |
| `srcIP` | 32 | `x32` … `x63` | `v0` … `v31` |
| `dstPort` | 16 | `x64` … `x79` | `v16` … `v31` |
| **Total variables** | | **80** | **32** |

```text
shared pool   v0 ................ v15 v16 ............... v31
dstIP   (32)  [#############################################]
srcIP   (32)  [#############################################]
dstPort (16)                          [#####################]
```

Now "srcIP in 10.0.0.0/8" and "dstIP in 10.0.0.0/8" are both the BDD over `v0` … `v7`. They are the *same* BDD node, stored once, and an operation on one field can be answered from the cache when the same operation appears on another field. Workloads with many fields of the same width benefit most, for example the N-Queens rows or the IP fields in packet headers.

#### Why declaration is split into two steps

Right-alignment needs the widest field's width before any variables are created. So declaration is now split into two calls:

1. `declareField(bitNum)` only records the field's width and returns its index.
2. `generateFields()` runs once all fields are known. It creates the shared pool, then gives each field its slice of the pool (`offset = maxWidth - bitNum`).

Fields that use different label backends (BDD, complemented BDD, ZDD) each get their own pool, because their variables live in different engines. The `NDD-Reuse` benchmark variant measures this optimization on its own.

## Why The Benchmarks Improve

### NQueens

On N-Queens, `NDD` is 2.0x to 3.7x faster than `NDD-Origin` for sizes 8 to 13 and uses less memory;
see the [highlight table](Results-NQueens.md#highlight-table).

### SRE

On the WAN/SRE workloads, `NDD` is 2.4x to 2.75x faster than `NDD-Origin` on the medium and large
fattree cases and uses about 90% fewer BDD nodes; see the
[selected highlights](Results-SRE.md#selected-highlights).

These WAN/SRE results matter because they are much closer to the packet-header workloads NDD was originally designed for.

## Files To Review First

- Core JNDD logic: [`src/main/java/org/ants/jndd/diagram/NDD.java`](../src/main/java/org/ants/jndd/diagram/NDD.java)
- Array-based node table and GC: [`src/main/java/org/ants/jndd/nodetable/NodeTable.java`](../src/main/java/org/ants/jndd/nodetable/NodeTable.java)
- Low-level example usage: [`src/main/java/application/nqueen/NDDSolution.java`](../src/main/java/application/nqueen/NDDSolution.java)
- JavaNDD facade: [`src/main/java/org/ants/javandd/NDDFactory.java`](../src/main/java/org/ants/javandd/NDDFactory.java)

## Important Build Caveat

The optimized core library is maintained and buildable. The WAN/SRE experiment paths are still stored in the repository for reference, but they remain excluded from the default Maven build because they depend on older APIs or external data/setup.
