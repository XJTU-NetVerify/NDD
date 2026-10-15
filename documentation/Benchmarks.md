# Benchmarks

This page explains what the benchmark artifacts in the repository measure and how to navigate the detailed result pages.

## Artifact Locations

- NQueens metrics CSV: [`results/nqueens_metrics.csv`](../results/nqueens_metrics.csv)
- NQueens plots: [`results/`](../results)
- Cross-library NQueens benchmark repository: <https://github.com/XJTU-NetVerify/nqueensBenchmarkDDs>
- WAN/SRE full markdown table: [`results/SRE-results.md`](../results/SRE-results.md)
- NQueens result page: [Results: NQueens](Results-NQueens.md)
- WAN/SRE result page: [Results: SRE](Results-SRE.md)

## Workloads In This Repository

### NQueens

NQueens is the maintained, easy-to-run example workload in this repository. It is useful because:

- it exercises the core logical operators repeatedly
- it provides a standard DD-style comparison point
- it can be rerun directly from the built project

The maintained Java-side entry points are under [`src/main/java/application/nqueen/`](../src/main/java/application/nqueen).

For the broader cross-library NQueens benchmarking workflow and companion implementations, see the dedicated repository: <https://github.com/XJTU-NetVerify/nqueensBenchmarkDDs>.

### WAN / SRE

The WAN/SRE benchmark set is the network-verification-oriented evaluation path used to study larger, field-structured workloads such as fattree-derived BGP cases. Those results are preserved in this repository, but the corresponding drivers are research artifacts and are not part of the default Maven build.

## Variant Names

The benchmark tables use the following names:

| Variant | Meaning |
| --- | --- |
| `bdd` / `JDD` / other library names | Plain BDD baselines or third-party DD libraries |
| `NDD-Origin` | Original NDD baseline |
| `NDD-Reuse` | NDD with shared-BDD-variable reuse enabled |
| `NDD` | Current optimized array-backed implementation documented here |

The most important comparison in this repository is usually `NDD-Origin` versus `NDD`, because that captures the combined benefit of reuse plus the structure-of-arrays execution model.

## How To Read The Results

### NQueens Columns

Typical NQueens metrics:

- runtime in seconds
- peak RSS
- total nodes created
- total nodes alive
- NDD node counts
- BDD label-node counts
- solution counts

Those numbers are useful for seeing whether an optimization reduced only time, only memory, or the underlying symbolic structure itself.

### WAN / SRE Columns

Typical WAN/SRE metrics:

- `src(s)`: preprocessing or source-stage time reported by the experiment harness
- `total(s)`: total run time
- `peak rss`: peak resident memory
- `bgp routes`: route scale in the input case
- `bdd nodes`: size of the BDD label layer during the run

`MF` is an experiment parameter emitted by the original harness and kept here as part of the dataset naming.

## Methodology Notes And Caveats

- `Sylvan` and `JSylvan` are parallel BDD libraries in the NQueens comparison; their runs used 48 worker threads, while the NDD variants in this repository are single-threaded.
- The NQueens data and plots are tracked directly in this repository and are the easiest results to reproduce.
- The wider NQueens benchmark project, including the companion benchmark repository used for multi-library comparisons, is documented at <https://github.com/XJTU-NetVerify/nqueensBenchmarkDDs>.
- The WAN/SRE result tables are preserved from the original research workflow; the corresponding code paths remain in the tree, but they currently depend on external datasets and are excluded from the default build.
- A timeout of `14400s` was used for the WAN/SRE tables on the result page.

## Headline Results

### NQueens Highlights

| Size | NDD-Origin time (s) | NDD time (s) | Speedup | NDD-Origin max RSS (KB) | NDD max RSS (KB) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 8 | 0.092 | 0.053 | 1.74x | 129764 | 44032 |
| 9 | 0.238 | 0.092 | 2.58x | 254136 | 78768 |
| 10 | 0.732 | 0.214 | 3.42x | 316372 | 124168 |
| 11 | 2.750 | 0.762 | 3.61x | 568980 | 216364 |
| 12 | 14.605 | 4.101 | 3.56x | 2226480 | 537192 |

### WAN / SRE Highlights

| Dataset | Metric | NDD-Origin | NDD | Improvement |
| --- | --- | ---: | ---: | ---: |
| `bgp_fattree08`, `MF=3` | total time (s) | 60.829 | 25.602 | 2.38x faster |
| `bgp_fattree08`, `MF=3` | peak RSS (MB) | 4220.4 | 2046.0 | 51.5% lower |
| `bgp_fattree08`, `MF=3` | BDD nodes | 38199434 | 3208829 | 91.6% fewer |
| `bgp_fattree12`, `MF=3` | total time (s) | 636.086 | 230.906 | 2.75x faster |
| `bgp_fattree12`, `MF=3` | BDD nodes | 645702063 | 61386922 | 90.5% fewer |
| `bgp_fattree16`, `MF=2` | total time (s) | 1178.287 | 472.056 | 2.50x faster |

## Where To Go Next

- [Results: NQueens](Results-NQueens.md) for the full cross-library table and plots
- [Results: SRE](Results-SRE.md) for the preserved WAN/SRE tables
- [Optimization Summary](Optimization-Summary.md) for the implementation reasons behind the gains
