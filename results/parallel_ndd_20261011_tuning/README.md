# Parallel NDD benchmark

Suite `tuning`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.6278 [1.5983–1.6980] | 183.5 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 8/4/65536 | 5/5 | 1.8730 [1.8023–1.9166] | 177.3 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 16/8/262144 | 5/5 | 3.5867 [3.5638–3.9034] | 183.1 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 16/8/65536 | 5/5 | 3.4629 [3.3667–3.6666] | 177.1 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 4/2/65536 | 5/5 | 1.7349 [1.6656–1.7485] | 177.4 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 8/4/262144 | 5/5 | 1.8179 [1.8039–1.9070] | 183.5 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
