# Parallel NDD benchmark

Suite `scaling`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 12 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 3.6565 [3.5615–3.7091] | 183.5 |
| c | BCDD | 12 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 2.2287 [2.2080–2.3067] | 183.3 |
| c | BCDD | 12 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.7037 [1.6336–1.7502] | 183.5 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.5966 [1.5679–1.6874] | 183.2 |
| java | BCDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.1694 [2.1206–2.3048] | 459.0 |
| c | BDD | 12 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 3.7093 [3.6650–3.8797] | 175.8 |
| c | BDD | 12 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 2.2652 [2.2397–2.3121] | 176.0 |
| c | BDD | 12 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.6884 [1.6345–1.7351] | 175.8 |
| c | BDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.5868 [1.5575–1.6376] | 175.8 |
| java | BDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.6976 [2.4950–2.8242] | 477.4 |
| c | ZDD | 12 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 3.7542 [3.6335–4.1417] | 183.3 |
| c | ZDD | 12 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 2.2767 [2.2145–2.2835] | 183.3 |
| c | ZDD | 12 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.7201 [1.6831–1.7787] | 183.5 |
| c | ZDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.6852 [1.6572–1.7374] | 183.3 |
| java | ZDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.7136 [2.4374–2.8456] | 473.5 |
| c | BCDD | 13 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 25.8433 [25.6894–26.2003] | 861.7 |
| c | BCDD | 13 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 15.9382 [15.7172–16.1335] | 861.8 |
| c | BCDD | 13 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 11.7234 [11.2690–11.8669] | 861.9 |
| c | BCDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 10.6989 [10.6075–11.0703] | 861.9 |
| java | BCDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 16.5507 [16.4059–16.6985] | 2630.2 |
| c | BDD | 13 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 25.9712 [25.9426–27.2088] | 854.7 |
| c | BDD | 13 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 16.1834 [15.9477–16.7658] | 854.8 |
| c | BDD | 13 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 11.7888 [11.6050–12.4664] | 855.1 |
| c | BDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 10.7518 [10.5769–11.1956] | 854.9 |
| java | BDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 18.0566 [17.6356–18.5279] | 2256.8 |
| c | ZDD | 13 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 26.1003 [25.7592–26.7120] | 861.5 |
| c | ZDD | 13 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 16.4028 [16.1931–16.7534] | 861.7 |
| c | ZDD | 13 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 12.1710 [11.7961–12.4045] | 861.8 |
| c | ZDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 11.1383 [11.0426–11.3436] | 861.9 |
| java | ZDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 17.5381 [17.1522–17.9718] | 2258.1 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
