# Parallel NDD benchmark

Suite `final`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 12 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 3.7986 [3.7466–3.8701] | 184.6 |
| c | BCDD | 12 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 2.0412 [1.9603–2.4074] | 184.6 |
| c | BCDD | 12 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.2532 [1.2289–1.2856] | 184.6 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.1041 [1.0800–1.1345] | 184.6 |
| java | BCDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.2084 [2.1734–2.2783] | 458.9 |
| jni | BCDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.3425 [1.2717–1.3849] | 254.7 |
| c | BDD | 12 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 3.8394 [3.7555–3.8861] | 176.9 |
| c | BDD | 12 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 2.0738 [2.0018–2.2472] | 177.0 |
| c | BDD | 12 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.2876 [1.2680–1.3154] | 177.2 |
| c | BDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.1145 [1.0738–1.1234] | 177.2 |
| java | BDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.7507 [2.7307–3.0037] | 474.6 |
| jni | BDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.2179 [1.1691–1.2870] | 217.0 |
| c | ZDD | 12 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 3.8076 [3.7297–3.9008] | 184.4 |
| c | ZDD | 12 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 2.0762 [1.9721–2.1452] | 184.4 |
| c | ZDD | 12 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.2681 [1.2363–1.3177] | 184.6 |
| c | ZDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.1049 [1.0938–1.1120] | 184.4 |
| java | ZDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.7359 [2.5935–2.9303] | 475.7 |
| jni | ZDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.3179 [1.2209–1.3421] | 247.1 |
| c | BCDD | 13 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 26.6887 [26.4527–26.7556] | 863.0 |
| c | BCDD | 13 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 14.6125 [14.4155–15.0132] | 862.9 |
| c | BCDD | 13 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 9.1820 [9.0152–9.3101] | 862.9 |
| c | BCDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 7.5609 [7.4519–7.6336] | 863.4 |
| java | BCDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 16.7667 [16.6680–17.2484] | 2629.8 |
| jni | BCDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 8.0425 [7.8828–8.3836] | 959.6 |
| c | BDD | 13 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 27.1738 [26.6044–27.8850] | 855.7 |
| c | BDD | 13 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 14.7812 [14.7249–15.1143] | 856.1 |
| c | BDD | 13 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 9.1672 [9.0924–9.4837] | 856.1 |
| c | BDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 7.5751 [7.4188–7.6754] | 856.2 |
| java | BDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 18.2958 [17.7488–18.5266] | 2257.2 |
| jni | BDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 8.0397 [7.8965–8.1954] | 896.7 |
| c | ZDD | 13 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 26.6993 [26.4470–26.7752] | 862.8 |
| c | ZDD | 13 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 14.9261 [14.7074–15.4746] | 862.8 |
| c | ZDD | 13 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 9.2757 [9.1016–9.3411] | 862.8 |
| c | ZDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 7.5517 [7.3865–7.6644] | 862.9 |
| java | ZDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 17.8393 [17.4632–18.1405] | 2258.3 |
| jni | ZDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 8.1703 [8.0039–8.4384] | 944.6 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extracts to logs/. Archive added after completion; samples, metadata and source snapshot are unchanged.
