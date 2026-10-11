# Parallel NDD benchmark

Suite `jni-final`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| jni | BCDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 4.0877 [4.0515–4.1283] | 252.4 |
| jni | BCDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 6.1361 [6.0929–6.1685] | 244.5 |
| jni | BCDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.2634 [1.2432–1.2848] | 254.7 |
| jni | BCDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 2.5551 [2.5046–2.8092] | 247.0 |
| jni | BDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 3.9868 [3.9687–4.0442] | 215.1 |
| jni | BDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 4.1565 [4.0937–4.2030] | 211.6 |
| jni | BDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 1.5529 [1.4113–1.5977] | 213.6 |
| jni | BDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.2013 [1.1930–1.2440] | 217.1 |
| jni | ZDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 6.2321 [6.1506–6.3167] | 232.1 |
| jni | ZDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 4.0645 [4.0076–4.1576] | 244.6 |
| jni | ZDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.3503 [1.2578–1.3850] | 247.1 |
| jni | ZDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 2.6783 [2.6488–2.8123] | 234.7 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), [raw logs](raw_logs.tar.gz) (extracts to logs/).

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.
