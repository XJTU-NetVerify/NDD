# Parallel NDD benchmark

Suite `ablation`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 15.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 10 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0826 [0.0808–0.0851] | 25.1 |
| c | BCDD | 10 | 6 | 0/1/None | 1/0 | 4/2/262144 | 0/5 | - | - |
| c | BDD | 10 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0849 [0.0775–0.0854] | 18.8 |
| c | BDD | 10 | 6 | 0/1/None | 1/0 | 4/2/262144 | 0/5 | - | - |
| c | ZDD | 10 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0825 [0.0814–0.0850] | 25.1 |
| c | ZDD | 10 | 6 | 0/1/None | 1/0 | 4/2/262144 | 0/5 | - | - |
| c | BCDD | 12 | 6 | 0/0/None | 1/1 | 4/2/262144 | 5/5 | 1.5995 [1.5380–1.6097] | 183.2 |
| c | BCDD | 12 | 6 | 0/0/None | 0/0 | 4/2/262144 | 5/5 | 3.7140 [3.6981–3.7843] | 183.0 |
| c | BCDD | 12 | 6 | 0/0/None | 0/1 | 4/2/262144 | 5/5 | 3.7526 [3.7184–3.8689] | 183.1 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.6185 [1.6160–1.6809] | 183.4 |
| c | BDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.6300 [1.6057–1.6934] | 175.8 |
| c | BDD | 12 | 6 | 0/0/None | 1/1 | 4/2/262144 | 5/5 | 1.5535 [1.5415–1.6320] | 175.6 |
| c | BDD | 12 | 6 | 0/0/None | 0/1 | 4/2/262144 | 5/5 | 3.7829 [3.7216–3.7995] | 175.6 |
| c | BDD | 12 | 6 | 0/0/None | 0/0 | 4/2/262144 | 5/5 | 3.7883 [3.7313–3.8168] | 175.6 |
| c | ZDD | 12 | 6 | 0/0/None | 1/1 | 4/2/262144 | 5/5 | 1.6475 [1.6429–1.7012] | 183.3 |
| c | ZDD | 12 | 6 | 0/0/None | 0/1 | 4/2/262144 | 5/5 | 3.6924 [3.6841–3.7672] | 183.0 |
| c | ZDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.7034 [1.6692–1.7244] | 183.5 |
| c | ZDD | 12 | 6 | 0/0/None | 0/0 | 4/2/262144 | 5/5 | 3.7539 [3.6701–3.7618] | 183.0 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
