# Parallel NDD benchmark

Suite `cache-large`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 13 | 6 | 0/0/None | 1/0 | 4/2/65536 | 5/5 | 7.8710 [7.8159–8.0013] | 856.8 |
| c | BCDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 7.5458 [7.5064–7.6926] | 863.1 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), [raw logs](raw_logs.tar.gz) (extracts to logs/).

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.
