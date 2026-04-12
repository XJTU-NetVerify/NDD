# NDD-SoA Wiki

Welcome to the `NDD-SoA` wiki. This wiki is intended to hold the long-form material that does not belong on the repository front page.

## What This Branch Is

`NDD-SoA` is the optimized branch of `NDD` that combines:

- right-aligned shared BDD variables across compatible fields
- a global edge-collection stack for recursive operations
- a structure-of-arrays node and edge table instead of nested `HashMap` objects

The end result is lower allocation pressure, better cache behavior, and smaller BDDs on representative workloads.

## Start Here

- [Installation](Installation.md)
- [Usage](Usage.md)
- [Parameters](Parameters.md)
- [Optimization Summary](Optimization-Summary.md)
- [Benchmarks](Benchmarks.md)
- [Results: NQueens](Results-NQueens.md)
- [Results: SRE](Results-SRE.md)
- [Design Notes](Design-Notes.md)

## Repository Pointers

- Root overview: [`README.md`](../README.md)
- Optimization summary: [`Optimization-Summary.md`](Optimization-Summary.md)
- Benchmark artifacts: [`results/`](../results/)
- Regenerated core javadocs: [`doc/javadoc/`](../doc/javadoc/)
- Original code examples: [`src/main/java/application/`](../src/main/java/application/)
