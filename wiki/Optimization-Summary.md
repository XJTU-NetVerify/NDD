# Optimization Summary

This page summarizes the changes that distinguish `NDD-SoA` from the upstream `NDD` baseline. It is written for reviewers who want to understand the optimization scope before reading the code.

## Goals

- Reduce overhead from the original nested `HashMap` representation.
- Cut transient allocation during logical operations.
- Reuse BDD variables across compatible fields so the BDD layer creates fewer nodes.
- Preserve the overall NDD semantics and application-facing workflow.

## Main Changes

### 1. Structure-of-Arrays Node Storage

The original implementation represented each NDD node as an object that owned a `HashMap<NDD, Integer>` edge map. `NDD-SoA` replaces that layout with array-backed storage in [`src/main/java/org/ants/jndd/nodetable/NodeTable.java`](../src/main/java/org/ants/jndd/nodetable/NodeTable.java):

- node metadata is stored in parallel arrays (`nodeField`, `nodeEdgeBlock`, `nodeEdgeCount`, `refCount`, ...)
- edge payload is stored in shared arrays (`edgeTarget`, `edgeLabel`)
- nodes reference stable edge blocks instead of allocating per-node edge containers

This cuts per-node object overhead and makes the hot path easier for the JVM to optimize.

### 2. Global Edge-Collection Stack

Logical operations in [`src/main/java/org/ants/jndd/diagram/NDD.java`](../src/main/java/org/ants/jndd/diagram/NDD.java) no longer build temporary `HashMap`s while combining child results. Instead, each recursive call reserves a slice of one shared stack:

- `edgeCollect(frameStart, target, label)` appends or merges edges for the current frame
- `edgeFlush(frameStart, field)` sorts the slice into canonical order and interns or creates the node

This removes a large amount of transient allocation from `and`, `or`, `not`, and related operations.

### 3. Right-Aligned Shared BDD Variables

Field declaration is now split into:

1. `declareField(bitNum)`
2. `generateFields()`

During `generateFields()`, fields are right-aligned against the maximum bit width, which means equal-width suffixes can share the same underlying BDD variables. This reduces duplicated BDD structure across domains and is the main reason the `ndd-reuse` and `ndd-soa` variants need far fewer BDD nodes than the baseline.

## Benchmark Takeaways

### NQueens

From [`results/nqueens_metrics.csv`](../results/nqueens_metrics.csv):

- size 10: `0.744s -> 0.221s` (`3.37x` faster), `314148KB -> 134680KB`
- size 11: `2.835s -> 0.792s` (`3.58x` faster), `603572KB -> 218416KB`
- size 12: `14.821s -> 4.353s` (`3.40x` faster), `2233308KB -> 579796KB`

### SRE

From [`results/SRE-results.md`](../results/SRE-results.md):

- `bgp_fattree08`, `MF=3`: `60.829s -> 25.602s`, BDD nodes `38199434 -> 3208829`
- `bgp_fattree12`, `MF=3`: `636.086s -> 230.906s`
- `bgp_fattree16`, `MF=2`: `1178.287s -> 472.056s`

## Files to Review First

- Core JNDD logic: [`src/main/java/org/ants/jndd/diagram/NDD.java`](../src/main/java/org/ants/jndd/diagram/NDD.java)
- Array-backed storage and GC: [`src/main/java/org/ants/jndd/nodetable/NodeTable.java`](../src/main/java/org/ants/jndd/nodetable/NodeTable.java)
- JNDD usage examples: [`src/main/java/application/nqueen/NDDSolution.java`](../src/main/java/application/nqueen/NDDSolution.java)
- JavaNDD facade: [`src/main/java/org/ants/javandd/NDDFactory.java`](../src/main/java/org/ants/javandd/NDDFactory.java)

## PR Notes

- This branch packages the optimized SoA snapshot in a git-tracked form suitable for upstream review.
- The repository-level metadata (`README.md`, `LICENSE`, `pom.xml`) has been restored here so the optimized tree is self-contained.
- The default Maven build excludes `AtomizedNDD`, the finite-domain ZDD NQueens demo, and the `application/wan/bdd/**` plus `application/wan/ndd/**` WAN experiment paths because those paths still depend on the pre-SoA object API and are not yet migrated.
