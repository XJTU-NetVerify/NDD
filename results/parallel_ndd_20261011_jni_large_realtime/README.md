# Parallel NDD benchmark

Suite `jni-large`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 120.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| jni | BCDD | 13 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 27.8483 [27.3340–28.0891] | 956.4 |
| jni | BCDD | 13 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 30.1544 [29.6053–30.1910] | 926.3 |
| jni | BCDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 11.2589 [10.8848–11.4005] | 958.3 |
| jni | BCDD | 13 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 13.2626 [12.6695–13.5143] | 929.0 |
| jni | BDD | 13 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 27.5848 [27.1969–29.8267] | 893.5 |
| jni | BDD | 13 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 27.6741 [27.4862–28.8967] | 871.0 |
| jni | BDD | 13 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 11.8752 [11.5031–12.4631] | 873.0 |
| jni | BDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 11.1452 [10.6575–11.5841] | 895.7 |
| jni | ZDD | 13 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 30.4034 [29.7366–30.5800] | 905.9 |
| jni | ZDD | 13 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 27.7680 [27.2648–27.9659] | 941.4 |
| jni | ZDD | 13 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 11.8003 [11.6559–12.1722] | 943.5 |
| jni | ZDD | 13 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 14.0347 [13.5501–14.2230] | 908.0 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.


## Timing qualification

Preserved exploratory cohort: some GNU time elapsed values were shorter than monotonic in-process compute time. Its wall_s column is not used for end-to-end conclusions. Counts, compute_s and RSS remain recorded; final cohorts use an outer monotonic timer and separately record GNU time and realtime clocks.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
