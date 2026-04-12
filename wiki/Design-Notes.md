# Design Notes

## Overview

`NDD-SoA` keeps the semantics of NDD but changes the storage and execution model of the JNDD implementation to reduce allocation and improve reuse on realistic workloads.

## 1. Array-Backed Node Table

The original implementation stored each NDD node as a Java object with a nested edge `HashMap`. In this branch, [`NodeTable.java`](../src/main/java/org/ants/jndd/nodetable/NodeTable.java) stores node metadata and edge payload in parallel arrays.

Benefits:

- less object allocation
- tighter memory layout
- simpler deduplication with per-field unique tables
- more predictable GC behavior

## 2. Global Edge Stack

Recursive operations such as `and`, `or`, `not`, and existential projection now build their intermediate edge sets in one shared stack managed by `edgeCollect` and `edgeFlush`.

Why this matters:

- avoids allocating temporary `HashMap`s per recursive frame
- allows duplicate-target merging before node interning
- keeps canonical ordering explicit at `edgeFlush`

## 3. Right-Aligned Shared BDD Variables

The `declareField(...)` / `generateFields()` split exists so the implementation can wait until all field widths are known. It then right-aligns every field against the maximum width and reuses the same underlying BDD variables for compatible suffix positions.

This is what the benchmark name `ndd-reuse` isolates, and it is also part of `ndd-soa`.

## 4. Safe-Point Recycling

The SoA layout introduces a subtle constraint: recursive operations may hold physical edge-array positions on the call stack. Because of that, edge compaction and retired-slot recycling are deferred to safe points after recursion unwinds.

Relevant code:

- `NDD.runSafePointMaintenance()`
- `NodeTable.compactEdgesIfNeeded()`
- `NodeTable.compactEdgesAtSafePoint()`

## 5. API Shape

### JNDD

The low-level JNDD API now exposes integer node IDs. This matches the new storage model and avoids wrapper allocation on the hot path.

### JavaNDD

The `NDDFactory` path remains available for code that already uses `JavaBDD` and expects a factory/object style.

## 6. Benchmark Interpretation

When reading the benchmark tables:

- `ndd` shows the original baseline
- `ndd-reuse` isolates the variable-reuse optimization
- `ndd-soa` adds the SoA storage and stack-based execution changes

That split is useful in review because it shows which gains come from BDD reuse and which gains come from the new data layout.
