# Parallel NDD benchmark

Suite `smoke`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0077 [0.0075–0.0080] | 13.8 |
| c | BCDD | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0101 [0.0093–0.0101] | 13.7 |
| c | BCDD | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0104 [0.0102–0.0106] | 13.8 |
| c | BCDD | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0109 [0.0106–0.0111] | 13.5 |
| java | BCDD | 7 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.0220 [0.0177–0.0265] | 52.0 |
| c | BDD | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0068 [0.0067–0.0071] | 11.8 |
| c | BDD | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0085 [0.0080–0.0091] | 11.6 |
| c | BDD | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0086 [0.0069–0.0094] | 11.5 |
| c | BDD | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0089 [0.0087–0.0093] | 11.5 |
| java | BDD | 7 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.0334 [0.0308–0.0339] | 58.2 |
| c | BITSET | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0068 [0.0066–0.0068] | 11.8 |
| c | BITSET | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0086 [0.0066–0.0090] | 11.8 |
| c | BITSET | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0087 [0.0081–0.0089] | 11.8 |
| c | BITSET | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0086 [0.0084–0.0090] | 11.5 |
| java | BITSET | 7 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.0271 [0.0176–0.0313] | 51.6 |
| c | MIXED | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0096 [0.0091–0.0106] | 15.7 |
| c | MIXED | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0119 [0.0118–0.0121] | 15.5 |
| c | MIXED | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0124 [0.0120–0.0127] | 15.5 |
| c | MIXED | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0125 [0.0123–0.0131] | 15.4 |
| c | ZDD | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0080 [0.0079–0.0083] | 14.4 |
| c | ZDD | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0099 [0.0095–0.0107] | 14.2 |
| c | ZDD | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0108 [0.0100–0.0111] | 14.4 |
| c | ZDD | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0109 [0.0106–0.0110] | 14.2 |
| java | ZDD | 7 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.0318 [0.0265–0.0338] | 57.9 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), [raw logs](raw_logs.tar.gz) (extracts to logs/).

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.
