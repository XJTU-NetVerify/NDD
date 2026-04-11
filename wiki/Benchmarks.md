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

### WAN / SRE

The WAN benchmark set exercises larger network-verification-style workloads and is summarized in [`results/SRE-results.md`](../results/SRE-results.md). The wiki version groups the same results by dataset and `MF`.

## Headline Results

### NQueens Highlights

| Size | NDD time (s) | NDD-SoA time (s) | Speedup | NDD max RSS (KB) | NDD-SoA max RSS (KB) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 8 | 0.091 | 0.055 | 1.67x | 146220 | 48216 |
| 9 | 0.236 | 0.100 | 2.36x | 252820 | 78304 |
| 10 | 0.744 | 0.221 | 3.37x | 314148 | 134680 |
| 11 | 2.835 | 0.792 | 3.58x | 603572 | 218416 |
| 12 | 14.821 | 4.353 | 3.40x | 2233308 | 579796 |

### WAN / SRE Highlights

| Dataset | Metric | NDD | NDD-SoA | Improvement |
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
