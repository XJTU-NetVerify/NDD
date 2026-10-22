# Design Notes

This page gives the implementation-level rationale behind the optimized branch.

It is meant for readers who already understand what NDD is and want to know how this repository's array-based implementation is organized internally.

## Overview

The optimized branch keeps the same conceptual model:

- one NDD node corresponds to one field
- each outgoing edge is guarded by a lower-level decision-diagram label
- logical operations recurse over fields and labels

What changes is the storage and execution strategy used to implement that model efficiently in Java.

## 1. Array-Based Node Table

The original implementation represented each node as an object with nested edge structures. In this branch, [`NodeTable.java`](../src/main/java/org/ants/jndd/nodetable/NodeTable.java) stores node state in parallel arrays.

That means:

- node metadata lives in dense primitive arrays
- edge payloads are stored in shared arrays
- canonical nodes reference edge blocks by index rather than owning separate edge containers

This is the main memory-layout change behind the current `NDD` implementation.

The design follows [JDD](https://bitbucket.org/vahidi/jdd), a Java version of [BuDDy](https://buddy.sourceforge.net/) and the BDD engine NDD uses for edge labels. JDD's [`NodeTable`](../src/main/java/jdd/bdd/NodeTable.java) uses `int` node ids, flat `int`/`short` arrays for node data and reference counts, and unique-table chains linked through an array. NDD keeps that scheme. Because NDD nodes can have any number of outgoing edges, it adds shared edge arrays and per-node edge blocks.

## 2. Global Edge Collection

During recursive logical operations, the implementation accumulates candidate edges in one shared stack-like structure instead of allocating temporary maps inside each call. Each recursive operation owns a stack frame `[frameStart, stackTop)` in that shared buffer.

Two helpers in [`NDD.java`](../src/main/java/org/ants/jndd/diagram/NDD.java) manage the frame:

- `edgeCollect(target, label)` appends one edge to the current frame in O(1). It does **not** deduplicate on the way in: duplicate targets are tolerated and merged later. The earlier implementation merged duplicates on every collect, which cost an O(D^2) linear scan per node at high fan-out D; deferring the merge keeps this hot path branch-free.
- `edgeFlush(field)` turns the frame into a canonical node. It (1) sorts the frame by target, (2) merges runs of equal targets with a consuming OR on their labels, then (3) creates or reuses the node through `NodeTable.mk`, which requires a sorted, deduplicated edge order.

The sort inside `edgeFlush` is size-adaptive:

- small/medium frames use an in-place quicksort that falls back to insertion sort for short runs. NDD fan-out is usually tiny, so this matches the cost of the previous insertion-sort path in the common case.
- large frames — at or above `ndd.radixThreshold` (default 64) — use an O(n) LSD radix sort over the packed `(target, label)` pairs, which avoids the comparison-sort blow-up at high fan-out.

Safe-point maintenance after the recursion unwinds keeps retired stack and edge slots reusable.

The point of this design is to keep the hot collect path allocation-free, then defer the single canonicalization step (sort + merge) to one place that can pick the right algorithm for the frame size.

## 3. Deferred Field Materialization

`declareField(...)` and `generateFields()` are deliberately separate.

Why:

- the implementation needs to know every field width before laying out shared BDD variables
- that global view is what makes right-aligned reuse possible within each label backend
- it also keeps the field model explicit for packet-processing applications

For mixed diagrams, fields are grouped by `LabelMode`. Each group computes its own maximum width
and creates one shared right-aligned variable layout, so BDD, BCDD, set-family ZDD, and BITSET fields do
not allocate duplicate engines or lose reuse among fields of the same type.

This is why the library expects users to commit to a field partition and its label modes early.

## 4. Safe-Point Recycling

The array-based layout introduces a constraint that the implementation must respect: recursive operations may still hold raw edge-array positions while the recursion stack is active.

Because of that, edge compaction and retired-slot reuse happen only at safe points after recursion unwinds. Relevant methods include:

- `NDD.runSafePointMaintenance()`
- `NodeTable.compactEdgesIfNeeded()`
- `NodeTable.compactEdgesAtSafePoint()`

## 5. Label Backend Abstraction

The low-level implementation supports multiple edge-label engines behind one internal backend interface:

- standard BDD labels
- complemented-edge BDD labels
- set-family ZDD labels
- canonical two-word BITSET truth tables for domains up to 128 values

`declareField(width, mode)` selects a backend per field. The overload without a mode uses the
initialization default. A small backend registry creates at most one engine for each active
`LabelMode`; unused modes allocate no engine.

An edge does not store a backend tag. Its parent NDD node already stores a field index, and that
field determines the backend used to interpret, reference, combine, count, or release the edge
label. Labels are combined only when recursive operations reach the same field, so handles from
different engines never participate in the same label operation even if their integer values
coincide.

Common label operations such as `ref`, `deref`, `and`, `or`, `diff`, `not`, `matches`, `satCount`,
node counting, and GC go through the same backend API. The edge collector carries the owning field
when it may release a label; `NodeTable.mk` and NDD GC similarly derive the backend from the node's
field. This avoids adding a backend array to the node or edge table.

Set-family ZDD counting uses an engine-level primitive `DoubleCache`, and the enclosing NDD count
separately memoizes macro-node states. The cache is cleared with the other ZDD caches after
node-table GC, so cached handles cannot outlive recycled nodes. This avoids both exponential
revisits in JDD's original `ZDD.count()` and per-call boxed maps.

Homogeneous diagrams retain a direct `labelBackend` fast path. Diagrams that actually mix modes use
materialized field-to-backend and field-to-mode arrays rather than list/interface lookup in hot
loops. Per-engine node creation counters are used for statistics
because the legacy JDD counter is process-global and cannot distinguish simultaneous BDD and ZDD
engines.

NDD difference is an ordered, memoized macro operation. It applies native backend `diff` to edge
residuals directly; `or`, `not`, and `diff` do not synthesize a full label complement followed by
intersection. This matters especially for sparse ZDD families, where a temporary universe-relative
complement can be much larger than the desired residual. ZDD also caches the frequent
`intersect(1, x)` empty-set-membership query.

Each label mode can be assigned an independent initial node-table/cache capacity with
`configureBackendCapacity(mode, tableSize, cacheSize)` after `initNDD` and before the first field of
that mode is declared. The default remains the common sizes passed to `initNDD`; independent sizing
is an optional, workload-measurement-based optimization for mixed deployments.

The complemented-BDD backend uses integer handles whose low bit is the complement attribute.
Internal nodes are stored in `int` arrays indexed by node handle (`vars`, `lows`, `highs`, `refs`); the high child is normalized to
a regular handle, so a function and its complement share the same regular node and `not` only flips
one bit. Primitive unique/apply/count/minimum-path caches avoid boxed keys. External label
references and permanently protected variable roots drive mark/sweep collection; GC rebuilds the
unique table and invalidates all handle caches. `andTo` and `orTo` follow the JDD ownership contract:
they reference the result and consume the owned left operand.

All three backends describe the same logical field domain. A width-`w` field denotes `2^w`
Boolean bit vectors. BDD and BCDD encode a set of assignments as a Boolean characteristic
function. ZDD encodes it as a family of subsets: a subset contains exactly the bit variables that
are true in that assignment.

This distinction requires an explicit universe for each ZDD field. ZDD terminal `1` is the family
containing only the empty set, not the family of all assignments. During `generateFields()`, NDD
therefore builds and retains the powerset of the field's right-aligned bit variables. Positive and
negative literal labels select, respectively, the members containing or omitting one variable;
intersection, union, and difference then implement Boolean AND, OR, and complement relative to
that field universe. The removed finite-domain ZDD backend instead treated a width as a one-of-`w`
domain and is no longer part of the API.

The current mixed-mode boundaries are deliberate:

- `substitute` requires source and target fields to use the same backend and width
- raw-label `refLabel` / `derefLabel` calls require a field in mixed mode
- `toBDD` and `toNDD` remain homogeneous standard-BDD conversion helpers

## 6. API Shape

### JNDD

The low-level API uses integer node IDs. This matches the array-based representation and avoids wrapper allocation on the hot path.

### JavaNDD

The `NDDFactory` layer keeps a `BDDFactory`-style API for compatibility with codebases built around `JavaBDD`.

That compatibility layer is especially relevant for the Batfish-style integration documented in [Network Verification Applications](Network-Verification-Applications.md).

## 7. Interpreting The Benchmark Variants

When reading the result pages:

- `NDD-Origin` is the baseline implementation
- `NDD-Reuse` isolates the effect of shared-BDD-variable reuse
- `NDD` adds the array-based node table and stack-based edge collection

That split is useful because it shows which gains come from better symbolic structure and which come from the new runtime representation.
