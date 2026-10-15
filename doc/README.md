# NDD Documentation

This directory holds the long-form documentation for the `NDD` repository.

`NDD` is a field-aware decision diagram for packet-processing and network-verification workloads. Instead of branching on one bit at a time like a classical BDD, an NDD node represents a whole field such as destination IP, source port, protocol, or TCP flags. That field-oriented structure is what makes NDD a good fit for forwarding and reachability analysis.

This repository also includes the current optimized NDD implementation, which uses an array-backed node table. Historical branch notes may describe the same optimization line as the SoA implementation; these documents call the current implementation `NDD` and the original baseline `NDD-Origin`.

## What You Will Find Here

- library setup and build instructions
- how to use the low-level `JNDD` API and the `JavaNDD` compatibility layer
- field-level manipulation APIs: restriction, enumeration, quantification, and substitution
- parameter and field-modeling guidance
- detailed benchmark methodology and full result tables
- how NDD is used in network-verification experiments and Batfish-style integrations
- implementation notes for the optimized array-backed branch

## Recommended Reading Order

If you are new to the project:

1. [Installation](Installation.md)
2. [Usage](Usage.md)
3. [Manipulation APIs](Manipulation-APIs.md)
4. [Parameters](Parameters.md)
5. [Network Verification Applications](Network-Verification-Applications.md)
6. [Benchmarks](Benchmarks.md)

If you are reviewing the optimization work:

1. [Optimization Summary](Optimization-Summary.md)
2. [Design Notes](Design-Notes.md)
3. [Results: NQueens](Results-NQueens.md)
4. [Results: SRE](Results-SRE.md)

## Repository Pointers

- Front-page overview: [`README.md`](../README.md)
- Source tree: [`src/main/java/`](../src/main/java)
- JNDD implementation: [`org/ants/jndd`](../src/main/java/org/ants/jndd)
- JavaNDD facade: [`org/ants/javandd`](../src/main/java/org/ants/javandd)
- Example applications: [`application/`](../src/main/java/application)
- Benchmark artifacts: [`results/`](../results)
- Dedicated NQueens benchmark repository: <https://github.com/XJTU-NetVerify/nqueensBenchmarkDDs>
- API docs: [`javadoc/`](javadoc/)

## Beyond The README

The repository `README.md` gives a short overview. Long-form material lives here:

- quick-start and build details: [Installation](Installation.md)
- API snippets and example entry points: [Usage](Usage.md)
- variant naming, benchmark interpretation, and artifacts: [Benchmarks](Benchmarks.md)
- network-verification motivation and integrations: [Network Verification Applications](Network-Verification-Applications.md)

## Key Result Snapshot

- On the Java NQueens workload, `NDD` is about `3.4x` to `3.6x` faster than the `NDD-Origin` baseline at sizes `10` to `12`, while also using much less memory.
- On the WAN/SRE benchmark set, the optimized branch cuts both runtime and BDD-node count sharply on medium and large fattree cases, for example `bgp_fattree12`, `MF=3`: `636.086s -> 230.906s`.

## All Pages

- [MTNDD](MTNDD.md)
- [Installation](Installation.md)
- [Usage](Usage.md)
- [Manipulation APIs](Manipulation-APIs.md)
- [Parameters](Parameters.md)
- [Network Verification Applications](Network-Verification-Applications.md)
- [Benchmarks](Benchmarks.md)
- [Results: NQueens](Results-NQueens.md)
- [Results: SRE](Results-SRE.md)
- [Optimization Summary](Optimization-Summary.md)
- [Design Notes](Design-Notes.md)

