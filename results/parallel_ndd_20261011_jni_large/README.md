# Parallel NDD benchmark

Suite `jni-large`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| jni | BCDD | 13 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 27.4162 [27.1590–28.2371] | 956.4 |
| jni | BCDD | 13 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 30.2008 [29.4612–31.4497] | 926.3 |
| jni | BCDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 11.2230 [10.9855–11.5356] | 958.1 |
| jni | BCDD | 13 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 13.2394 [12.8290–13.6282] | 928.7 |
| jni | BDD | 13 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 28.0244 [26.7685–29.2379] | 893.5 |
| jni | BDD | 13 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 28.3206 [27.2300–28.8843] | 871.0 |
| jni | BDD | 13 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 11.8174 [11.5246–12.1302] | 873.4 |
| jni | BDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 11.1609 [10.9782–11.7270] | 895.6 |
| jni | ZDD | 13 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 30.8770 [29.6779–31.8057] | 905.8 |
| jni | ZDD | 13 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 27.3214 [27.0863–29.2112] | 941.4 |
| jni | ZDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 11.5629 [11.4228–12.3353] | 943.7 |
| jni | ZDD | 13 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 13.8725 [13.6192–14.5223] | 907.8 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
