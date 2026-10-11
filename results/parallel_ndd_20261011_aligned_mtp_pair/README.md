# Parallel NDD benchmark

Suite `mtp-pair`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.1049 [1.0730–1.1155] | 184.6 |
| mtp | BCDD | 12 | 6 | 0/0/None | None/None | None/None/None | 5/5 | 1.2150 [1.1980–1.3100] | 1585.7 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), [raw logs](raw_logs.tar.gz) (extracts to logs/).

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.
