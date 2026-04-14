# Benchmarks

## Artifact Locations

- NQueens CSV: [`results/nqueens_metrics.csv`](../results/nqueens_metrics.csv)
- NQueens plots: [`results/`](../results/)
- Full SRE markdown table: [`results/SRE-results.md`](../results/SRE-results.md)

## Workloads

### NQueens

This repository keeps the traditional NQueens benchmark used in DD papers and library comparisons. The CSV in [`results/nqueens_metrics.csv`](../results/nqueens_metrics.csv) includes:

- runtime
- peak RSS
- total nodes created
- alive nodes
- NDD and BDD sub-counts
- solution count

The copied PNGs provide the plotting artifacts for time, memory, and node statistics.

> Note: in the NQueens results, `Sylvan` and `JSylvan` are parallel BDD libraries and were run with 48 worker threads. All other implementations (including `NDD`, `NDD-reuse`, and `NDD-Array`) are single-threaded.

### WAN / SRE

The WAN benchmark set exercises larger network-verification-style workloads and is summarized in [`results/SRE-results.md`](../results/SRE-results.md). The wiki version groups the same results by dataset and `MF`.

## Headline Results

### NQueens Highlights

| Size | NDD time (s) | NDD-Array time (s) | Speedup | NDD max RSS (KB) | NDD-Array max RSS (KB) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 8 | 0.092 | 0.053 | 1.74x | 129764 | 44032 |
| 9 | 0.238 | 0.092 | 2.58x | 254136 | 78768 |
| 10 | 0.732 | 0.214 | 3.42x | 316372 | 124168 |
| 11 | 2.750 | 0.762 | 3.61x | 568980 | 216364 |
| 12 | 14.605 | 4.101 | 3.56x | 2226480 | 537192 |

### WAN / SRE Highlights

| Dataset | Metric | NDD | NDD-Array | Improvement |
| --- | --- | ---: | ---: | ---: |
| `bgp_fattree08`, `MF=3` | total time (s) | 60.829 | 25.602 | 2.38x faster |
| `bgp_fattree08`, `MF=3` | peak RSS (MB) | 4220.4 | 2046.0 | 51.5% lower |
| `bgp_fattree08`, `MF=3` | BDD nodes | 38199434 | 3208829 | 91.6% fewer |
| `bgp_fattree12`, `MF=3` | total time (s) | 636.086 | 230.906 | 2.75x faster |
| `bgp_fattree12`, `MF=3` | BDD nodes | 645702063 | 61386922 | 90.5% fewer |
| `bgp_fattree16`, `MF=2` | total time (s) | 1178.287 | 472.056 | 2.50x faster |

## Detailed Pages

- [Results: NQueens](Results-NQueens.md)
- [Results: SRE](Results-SRE.md)
