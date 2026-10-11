# Parallel NDD benchmark

Suite `api-comparison`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| java | BCDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.1482 [2.1245–2.4635] | 459.0 |
| jni | BCDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.7521 [1.7080–1.8005] | 253.4 |
| java | BDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.8280 [2.4709–2.8945] | 474.9 |
| jni | BDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.7433 [1.6743–1.7828] | 215.8 |
| java | ZDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.7884 [2.5527–2.9372] | 475.8 |
| jni | ZDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.8717 [1.8162–1.9116] | 245.8 |
| java | BCDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 16.5195 [16.3590–16.8806] | 2629.8 |
| jni | BCDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 11.0987 [10.9794–11.2001] | 958.2 |
| java | BDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 17.8173 [17.5493–18.0075] | 2257.7 |
| jni | BDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 11.1866 [10.7685–11.8437] | 895.6 |
| java | ZDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 17.1009 [16.9757–17.5186] | 2258.7 |
| jni | ZDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 11.8461 [11.2498–12.1448] | 943.4 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
