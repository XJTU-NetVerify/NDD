# Parallel NDD benchmark

Suite `jni-final`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| jni | BCDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 3.9091 [3.8182–3.9922] | 251.4 |
| jni | BCDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 5.9724 [5.9567–6.1922] | 243.3 |
| jni | BCDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.7580 [1.7311–1.8891] | 253.5 |
| jni | BCDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 3.1468 [3.0081–3.2544] | 245.7 |
| jni | BDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 3.8453 [3.8174–4.0064] | 214.0 |
| jni | BDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 3.9845 [3.9620–4.1893] | 210.3 |
| jni | BDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 1.9988 [1.9441–2.0299] | 212.5 |
| jni | BDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.7059 [1.6672–1.7715] | 215.8 |
| jni | ZDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 6.1535 [6.0434–6.2987] | 231.2 |
| jni | ZDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 3.9762 [3.8811–4.0006] | 243.4 |
| jni | ZDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.8901 [1.8698–1.9664] | 245.6 |
| jni | ZDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 3.5034 [3.3009–3.7234] | 233.7 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.


## Timing qualification

Preserved exploratory cohort: some GNU time elapsed values were shorter than monotonic in-process compute time. Its wall_s column is not used for end-to-end conclusions. Counts, compute_s and RSS remain recorded; final cohorts use an outer monotonic timer and separately record GNU time and realtime clocks.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
