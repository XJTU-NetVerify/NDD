# Parallel NDD benchmark

Suite `jni-final`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| jni | BCDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 4.1249 [3.9494–4.1591] | 251.3 |
| jni | BCDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 6.1513 [6.0344–6.3010] | 243.3 |
| jni | BCDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.7262 [1.6380–1.8066] | 253.7 |
| jni | BCDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 3.0973 [3.0131–3.1358] | 245.6 |
| jni | BDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 4.0109 [3.9057–4.1410] | 214.0 |
| jni | BDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 4.1798 [4.1106–4.4369] | 210.5 |
| jni | BDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 1.9489 [1.8878–2.0362] | 212.3 |
| jni | BDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.6737 [1.6310–1.7496] | 215.8 |
| jni | ZDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 6.2657 [6.2350–6.3267] | 231.0 |
| jni | ZDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 4.1762 [4.0577–4.3800] | 243.4 |
| jni | ZDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.8347 [1.7831–1.9470] | 245.8 |
| jni | ZDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 3.3494 [3.1765–3.4592] | 233.6 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Wall_s is measured by the outer monotonic clock, from spawn to dedicated wait-thread completion, including fork/wait scheduling overhead; resource_wall_s and realtime_wall_s are diagnostic clocks. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
