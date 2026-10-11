# Parallel NDD benchmark

Suite `jni`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 15.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| jni | BCDD | 7 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 0.3804 [0.3773–0.3825] | 49.3 |
| jni | BCDD | 7 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 0.0158 [0.0156–0.0160] | 50.0 |
| jni | BCDD | 7 | 1 | 0/True/True | None/None | None/None/None | 5/5 | 0.1272 [0.1209–0.1341] | 78.0 |
| jni | BCDD | 7 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 0.2631 [0.2438–0.3007] | 51.0 |
| jni | BCDD | 7 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 0.0288 [0.0228–0.0501] | 51.7 |
| jni | BCDD | 7 | 6 | 0/True/True | None/None | None/None/None | 5/5 | 0.1363 [0.1255–0.1586] | 80.4 |
| jni | BDD | 7 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 0.0092 [0.0086–0.0107] | 48.0 |
| jni | BDD | 7 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 0.0369 [0.0360–0.0372] | 47.6 |
| jni | BDD | 7 | 1 | 0/True/True | None/None | None/None/None | 5/5 | 0.1010 [0.0966–0.1063] | 54.4 |
| jni | BDD | 7 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 0.0258 [0.0105–0.0390] | 49.1 |
| jni | BDD | 7 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 0.0782 [0.0509–0.0947] | 48.9 |
| jni | BDD | 7 | 6 | 0/True/True | None/None | None/None/None | 5/5 | 0.1220 [0.1105–0.1312] | 56.8 |
| jni | ZDD | 7 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 0.0162 [0.0162–0.0180] | 50.7 |
| jni | ZDD | 7 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 0.3888 [0.3836–0.3903] | 49.7 |
| jni | ZDD | 7 | 1 | 0/True/True | None/None | None/None/None | 5/5 | 0.1248 [0.1196–0.1288] | 77.3 |
| jni | ZDD | 7 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 0.3298 [0.2706–0.3588] | 51.4 |
| jni | ZDD | 7 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 0.0244 [0.0230–0.0370] | 52.3 |
| jni | ZDD | 7 | 6 | 0/True/True | None/None | None/None/None | 5/5 | 0.1297 [0.1245–0.1369] | 79.9 |
| jni | BCDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 4.0646 [4.0293–4.0977] | 251.4 |
| jni | BCDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 6.1153 [6.0891–6.1611] | 243.3 |
| jni | BCDD | 12 | 1 | 0/1/1 | None/None | None/None/None | 0/5 | - | - |
| jni | BCDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 3.0845 [2.9770–3.1527] | 246.1 |
| jni | BCDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.7545 [1.7347–1.8584] | 253.5 |
| jni | BCDD | 12 | 6 | 0/1/1 | None/None | None/None/None | 0/5 | - | - |
| jni | BDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 4.1260 [4.1107–4.1421] | 210.5 |
| jni | BDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 3.9401 [3.9167–3.9667] | 214.0 |
| jni | BDD | 12 | 1 | 0/1/1 | None/None | None/None/None | 0/5 | - | - |
| jni | BDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.7121 [1.7041–1.7423] | 216.0 |
| jni | BDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 1.9727 [1.9371–2.0183] | 212.5 |
| jni | BDD | 12 | 6 | 0/1/1 | None/None | None/None/None | 0/5 | - | - |
| jni | ZDD | 12 | 1 | 0/False/False | None/None | None/None/None | 5/5 | 6.2432 [6.1670–6.3698] | 231.2 |
| jni | ZDD | 12 | 1 | 0/False/True | None/None | None/None/None | 5/5 | 4.0646 [4.0411–4.1328] | 243.4 |
| jni | ZDD | 12 | 1 | 0/1/1 | None/None | None/None/None | 0/5 | - | - |
| jni | ZDD | 12 | 6 | 0/False/False | None/None | None/None/None | 5/5 | 3.3355 [3.2850–3.3674] | 233.6 |
| jni | ZDD | 12 | 6 | 0/False/True | None/None | None/None/None | 5/5 | 1.8914 [1.8582–1.9107] | 246.0 |
| jni | ZDD | 12 | 6 | 0/1/1 | None/None | None/None/None | 0/5 | - | - |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
