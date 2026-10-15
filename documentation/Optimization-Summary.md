# Optimization Summary

This page summarizes the changes that distinguish the current `NDD` implementation from the original `NDD-Origin` baseline.

If you want the short version first: the optimized branch keeps the same field-aware semantics, but reduces overhead in two places at once:

- fewer BDD label nodes through cross-field variable reuse
- fewer JVM allocations through array-backed storage and a shared edge-collection stack

## Goals

- reduce overhead from the original nested `HashMap` representation
- cut temporary allocation during logical operations
- reuse BDD variables across compatible fields
- preserve the external NDD workflow of declaring fields and then operating on them

## Main Changes

### 1. Structure-of-Arrays Node Storage

The original implementation stored each NDD node as a Java object with a nested edge map. The optimized path in [`NodeTable.java`](../src/main/java/org/ants/jndd/nodetable/NodeTable.java) replaces that with parallel arrays for node metadata and edge payloads.

Why it helps:

- less object overhead
- better cache locality
- simpler deduplication and compaction
- more predictable GC behavior

### 2. Global Edge-Collection Stack

Logical operations in [`NDD.java`](../src/main/java/org/ants/jndd/diagram/NDD.java) no longer allocate a temporary map per recursive frame. Instead, they collect candidate edges into one shared stack and flush that stack into canonical nodes after merging duplicates.

This is what removes a large amount of transient allocation from hot operators such as `and`, `or`, and `not`.

Collection itself is a plain O(1) append: duplicate targets are deduplicated only at flush time, where the frame is sorted (insertion/quicksort for small frames, O(n) radix sort for large ones) and equal targets are merged. This removes the per-collect linear scan the original code paid at every node. See [Design Notes](Design-Notes.md) for the algorithm-level detail.

### 3. Right-Aligned Shared BDD Variables

Field declaration is split into:

1. `declareField(bitNum)`
2. `generateFields()`

After all field widths are known, `generateFields()` right-aligns fields against the maximum width and reuses the same underlying BDD variables for compatible suffix positions. This is what the `ndd-reuse` benchmark variant isolates.

## Why The Benchmarks Improve

### NQueens

From [`results/nqueens_metrics.csv`](../results/nqueens_metrics.csv):

- size 10: `0.732s -> 0.214s`, `316372KB -> 124168KB`
- size 11: `2.750s -> 0.762s`, `568980KB -> 216364KB`
- size 12: `14.605s -> 4.101s`, `2226480KB -> 537192KB`

The NQueens tables also show that `NDD-Reuse` already reduces BDD-node counts sharply. `NDD` then improves further by making the NDD-side representation cheaper.

### SRE

From [`results/SRE-results.md`](../results/SRE-results.md):

- `bgp_fattree08`, `MF=3`: `60.829s -> 25.602s`, BDD nodes `38199434 -> 3208829`
- `bgp_fattree12`, `MF=3`: `636.086s -> 230.906s`
- `bgp_fattree16`, `MF=2`: `1178.287s -> 472.056s`

These WAN/SRE results matter because they are much closer to the packet-header workloads NDD was originally designed for.

## Files To Review First

- Core JNDD logic: [`src/main/java/org/ants/jndd/diagram/NDD.java`](../src/main/java/org/ants/jndd/diagram/NDD.java)
- Array-backed storage and GC: [`src/main/java/org/ants/jndd/nodetable/NodeTable.java`](../src/main/java/org/ants/jndd/nodetable/NodeTable.java)
- Low-level example usage: [`src/main/java/application/nqueen/NDDSolution.java`](../src/main/java/application/nqueen/NDDSolution.java)
- JavaNDD facade: [`src/main/java/org/ants/javandd/NDDFactory.java`](../src/main/java/org/ants/javandd/NDDFactory.java)

## Important Build Caveat

The optimized core library is maintained and buildable. The WAN/SRE experiment paths are still stored in the repository for reference, but they remain excluded from the default Maven build because they depend on older APIs or external data/setup.
