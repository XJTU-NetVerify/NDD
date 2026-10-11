# Parallel NDD benchmark

Suite `compact`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 10 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0984 [0.0958–0.1198] | 28.0 |
| c | BCDD | 10 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0678 [0.0662–0.0711] | 28.2 |
| java | BCDD | 10 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 0.2025 [0.1594–0.2077] | 85.1 |
| c | BDD | 10 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0938 [0.0877–0.0984] | 24.7 |
| c | BDD | 10 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0745 [0.0633–0.0894] | 24.5 |
| java | BDD | 10 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 0.2613 [0.1833–0.3107] | 90.1 |
| c | BITSET | 10 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0953 [0.0943–0.0973] | 24.5 |
| c | BITSET | 10 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0605 [0.0545–0.0674] | 24.4 |
| java | BITSET | 10 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 0.2120 [0.1398–0.2288] | 83.2 |
| c | MIXED | 10 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.1046 [0.1039–0.1066] | 29.3 |
| c | MIXED | 10 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0716 [0.0696–0.0758] | 29.5 |
| c | ZDD | 10 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0968 [0.0953–0.0991] | 28.0 |
| c | ZDD | 10 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0696 [0.0669–0.0737] | 28.0 |
| java | ZDD | 10 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 0.1693 [0.1515–0.2103] | 92.0 |
| c | BCDD | 12 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 3.8808 [3.7994–3.9814] | 336.8 |
| c | BCDD | 12 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.6733 [1.6287–1.7702] | 337.4 |
| java | BCDD | 12 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 2.1987 [2.1687–2.4114] | 457.9 |
| c | BDD | 12 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 3.8351 [3.7935–3.8444] | 329.9 |
| c | BDD | 12 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.6913 [1.6528–1.7591] | 330.5 |
| java | BDD | 12 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 2.3428 [2.3193–2.3740] | 479.6 |
| c | BITSET | 12 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 3.8284 [3.7860–3.9185] | 330.0 |
| c | BITSET | 12 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.3710 [1.3491–1.4071] | 330.5 |
| java | BITSET | 12 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 1.9247 [1.8310–2.1557] | 459.0 |
| c | MIXED | 12 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 3.8350 [3.7728–4.0785] | 338.2 |
| c | MIXED | 12 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.5968 [1.5650–1.6146] | 338.6 |
| c | ZDD | 12 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 3.8661 [3.8430–3.9290] | 336.9 |
| c | ZDD | 12 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.7738 [1.7391–1.8271] | 337.7 |
| java | ZDD | 12 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 2.6839 [2.4762–2.7438] | 482.7 |
| c | BCDD | 13 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 26.1006 [25.9405–26.2789] | 1719.4 |
| c | BCDD | 13 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 12.9015 [12.6550–13.0125] | 1720.0 |
| java | BCDD | 13 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 13.7388 [13.6770–13.9164] | 2601.1 |
| c | BDD | 13 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 26.0722 [25.9689–26.4311] | 1711.7 |
| c | BDD | 13 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 12.8985 [12.4507–13.0478] | 1712.6 |
| java | BDD | 13 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 13.7196 [13.5804–13.8149] | 2781.9 |
| c | BITSET | 13 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 26.1532 [25.9703–26.4590] | 1711.8 |
| c | BITSET | 13 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 11.1970 [11.0748–11.5436] | 1712.3 |
| java | BITSET | 13 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 12.6132 [12.3636–12.8824] | 2771.2 |
| c | MIXED | 13 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 26.4827 [26.4678–26.5405] | 1720.6 |
| c | MIXED | 13 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 12.3353 [12.2439–13.0991] | 1721.3 |
| c | ZDD | 13 | 1 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 26.4651 [25.9274–26.5600] | 1719.5 |
| c | ZDD | 13 | 6 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 13.2059 [13.0290–13.2840] | 1720.0 |
| java | ZDD | 13 | 1 | 1/0/None | None/None | None/None/None | 5/5 | 13.8409 [13.6849–13.9235] | 2782.0 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
