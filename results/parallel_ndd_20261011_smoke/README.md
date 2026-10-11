# Parallel NDD benchmark

Suite `smoke`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0083 [0.0077–0.0083] | 12.7 |
| c | BCDD | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0101 [0.0093–0.0105] | 12.6 |
| c | BCDD | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0107 [0.0102–0.0111] | 12.4 |
| c | BCDD | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0111 [0.0105–0.0115] | 12.4 |
| java | BCDD | 7 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.0278 [0.0270–0.0327] | 52.0 |
| c | BDD | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0071 [0.0069–0.0075] | 10.7 |
| c | BDD | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0089 [0.0080–0.0092] | 10.5 |
| c | BDD | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0089 [0.0088–0.0093] | 10.2 |
| c | BDD | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0091 [0.0084–0.0095] | 10.2 |
| java | BDD | 7 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.0296 [0.0275–0.0314] | 60.8 |
| c | BITSET | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0067 [0.0065–0.0074] | 10.7 |
| c | BITSET | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0091 [0.0085–0.0095] | 10.4 |
| c | BITSET | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0090 [0.0081–0.0098] | 10.2 |
| c | BITSET | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0089 [0.0081–0.0092] | 10.2 |
| java | BITSET | 7 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.0280 [0.0204–0.0330] | 51.5 |
| c | MIXED | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0093 [0.0091–0.0098] | 14.4 |
| c | MIXED | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0121 [0.0114–0.0128] | 14.4 |
| c | MIXED | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0132 [0.0122–0.0135] | 14.2 |
| c | MIXED | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0122 [0.0119–0.0128] | 14.0 |
| c | ZDD | 7 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0085 [0.0083–0.0088] | 13.1 |
| c | ZDD | 7 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0110 [0.0104–0.0117] | 13.1 |
| c | ZDD | 7 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0111 [0.0101–0.0123] | 13.1 |
| c | ZDD | 7 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0109 [0.0106–0.0115] | 12.8 |
| java | ZDD | 7 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.0354 [0.0292–0.0395] | 56.6 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
