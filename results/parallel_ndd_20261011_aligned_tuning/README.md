# Parallel NDD benchmark

Suite `tuning`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.0916 [1.0735–1.1092] | 184.6 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 8/4/65536 | 5/5 | 1.4844 [1.4700–1.5179] | 178.5 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 16/8/262144 | 5/5 | 3.7577 [3.6914–3.7808] | 184.3 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 16/8/65536 | 5/5 | 3.6083 [3.5644–3.7256] | 178.2 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 4/2/65536 | 5/5 | 1.0763 [1.0555–1.0828] | 178.7 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 8/4/262144 | 5/5 | 1.5099 [1.4777–1.5399] | 184.6 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), [raw logs](raw_logs.tar.gz) (extracts to logs/).

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.
