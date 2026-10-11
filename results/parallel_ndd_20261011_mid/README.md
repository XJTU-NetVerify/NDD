# Parallel NDD benchmark

Suite `mid`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 15.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 10 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.1102 [0.1058–0.1197] | 25.3 |
| c | BCDD | 10 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0989 [0.0949–0.1055] | 25.5 |
| c | BCDD | 10 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0848 [0.0819–0.0849] | 25.4 |
| c | BCDD | 10 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0847 [0.0818–0.0881] | 25.3 |
| java | BCDD | 10 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.2217 [0.1984–0.2371] | 83.2 |
| c | BDD | 10 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.1064 [0.1045–0.1147] | 19.0 |
| c | BDD | 10 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0937 [0.0913–0.0953] | 19.0 |
| c | BDD | 10 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0801 [0.0764–0.0833] | 19.0 |
| c | BDD | 10 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0818 [0.0804–0.0903] | 18.8 |
| java | BDD | 10 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.1982 [0.1963–0.2069] | 98.8 |
| c | ZDD | 10 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.1089 [0.1067–0.1170] | 25.3 |
| c | ZDD | 10 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0983 [0.0972–0.1033] | 25.3 |
| c | ZDD | 10 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0868 [0.0828–0.1024] | 25.3 |
| c | ZDD | 10 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0867 [0.0823–0.0881] | 25.1 |
| java | ZDD | 10 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.1952 [0.1816–0.2136] | 94.7 |
| c | BCDD | 11 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.5730 [0.5547–0.6029] | 53.3 |
| c | BCDD | 11 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.4108 [0.4089–0.4166] | 53.4 |
| c | BCDD | 11 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.3348 [0.3315–0.3380] | 53.5 |
| c | BCDD | 11 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.3240 [0.3195–0.3523] | 53.1 |
| java | BCDD | 11 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.4845 [0.4662–0.4931] | 172.3 |
| c | BDD | 11 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.5803 [0.5602–0.6064] | 45.9 |
| c | BDD | 11 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.4139 [0.4019–0.4403] | 46.1 |
| c | BDD | 11 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.3294 [0.3283–0.3392] | 45.9 |
| c | BDD | 11 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.3304 [0.3167–0.3406] | 45.9 |
| java | BDD | 11 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.8157 [0.7012–0.8319] | 181.7 |
| c | ZDD | 11 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.5730 [0.5479–0.5847] | 53.3 |
| c | ZDD | 11 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.4202 [0.4124–0.4558] | 53.4 |
| c | ZDD | 11 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.3455 [0.3371–0.3523] | 53.1 |
| c | ZDD | 11 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.3373 [0.3302–0.3657] | 53.1 |
| java | ZDD | 11 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 0.8577 [0.8376–0.9052] | 186.3 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
