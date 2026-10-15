# Benchmark Results

This directory holds the raw benchmark data. Explanations, highlights, and takeaways are in the
documentation pages linked below; see [Benchmarks](../documentation/Benchmarks.md)
page for methodology and caveats.

## N-Queens

Cross-library comparison on N-Queens for N = 8 to 13. Documentation:
[Results: NQueens](../documentation/Results-NQueens.md).

| File | Content |
| --- | --- |
| [`nqueens_metrics.csv`](nqueens_metrics.csv) | One row per implementation and board size |
| [`nqueens_time_sec.png`](nqueens_time_sec.png), [`nqueens_time_sec_log.png`](nqueens_time_sec_log.png) | Running time (linear and log scale) |
| [`nqueens_max_rss_kb.png`](nqueens_max_rss_kb.png), [`nqueens_max_rss_kb_log.png`](nqueens_max_rss_kb_log.png) | Peak memory (linear and log scale) |
| [`nqueens_nodes_created.png`](nqueens_nodes_created.png) | Total nodes created |
| [`nqueens_nodes_alive.png`](nqueens_nodes_alive.png) | Nodes alive |

CSV columns:

- `implementation`, `language`, `size` (N)
- `time_sec`: running time in seconds; BuDDy at N = 13 hit the 500 s limit
- `max_rss_kb`: peak resident memory in KB
- `nodes_created`, `nodes_alive`: total nodes; for NDD, the sum of NDD nodes and label BDD nodes
- `ndd_nodes_created`, `ndd_nodes_alive`, `bdd_nodes_created`, `bdd_nodes_alive`: the NDD and BDD parts
- `solutions`: number of solutions, identical across implementations (0 for runs that timed out)

Implementations: `NDD` is the current library on the `main` branch, and `NDD-Origin` is the original
NDD prototype.
The others are BDD libraries: BuDDy, CUDD, JDD, JavaBDD, JavaBDD-C, DD-BDD, DD-CBDD, and JSylvan.
JSylvan is a parallel library and was run with 48 worker threads; all other implementations are
single-threaded.

To rerun the NDD side, build the project and run
[`NDDSolution`](../src/main/java/application/nqueen/NDDSolution.java):

```bash
mvn -DskipTests package
java -cp target/ndd-1.0.1-jar-with-dependencies.jar application.nqueen.NDDSolution 8 9 10 11 12 13
```

The other libraries are benchmarked in the companion repository
[nqueensBenchmarkDDs](https://github.com/XJTU-NetVerify/nqueensBenchmarkDDs).

## SRE (network verification)

Network-verification workloads on BGP fattree networks (`bgp_fattree04` to `bgp_fattree20`), each
with `MF` = 1 to 3, using a 14,400 s timeout per variant. Documentation:
[Results: SRE](../documentation/Results-SRE.md).

| File | Content |
| --- | --- |
| [`SRE-results.md`](SRE-results.md) | One table per dataset and `MF` value |

Variant names in `SRE-results.md` differ from those used in the documentation:

| In `SRE-results.md` | In the documentation | Meaning |
| --- | --- | --- |
| `bdd` | `bdd` | Plain BDD baseline |
| `ndd` | `NDD-Origin` | Original NDD prototype |
| `ndd-reuse` | `NDD-Reuse` | NDD with label-variable reuse across fields |
| `ndd-array` | `NDD` | Current NDD with the array-based node table |

Columns: `src(s)` is the source-stage time, `total(s)` the total running time, `peak rss` the peak
resident memory, `bgp routes` the number of routes in the input, and `bdd nodes` the size of the
BDD label layer.

The SRE drivers depend on external datasets and are not part of the default Maven build.
