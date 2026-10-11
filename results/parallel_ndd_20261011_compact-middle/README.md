# Parallel NDD benchmark

Suite `compact-middle`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 15.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 10 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0842 [0.0798–0.0879] | 28.0 |
| c | BCDD | 10 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0727 [0.0668–0.0814] | 28.2 |
| c | BDD | 10 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0785 [0.0754–0.0868] | 24.7 |
| c | BDD | 10 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0684 [0.0676–0.0764] | 24.5 |
| c | BITSET | 10 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0715 [0.0680–0.0752] | 24.7 |
| c | BITSET | 10 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0624 [0.0584–0.0728] | 24.4 |
| c | MIXED | 10 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0839 [0.0831–0.0893] | 29.3 |
| c | MIXED | 10 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0721 [0.0670–0.0743] | 29.5 |
| c | ZDD | 10 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0804 [0.0780–0.0841] | 28.0 |
| c | ZDD | 10 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 0.0732 [0.0702–0.0743] | 28.0 |
| c | BCDD | 12 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 2.4343 [2.4038–2.4666] | 337.0 |
| c | BCDD | 12 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.7903 [1.7693–1.8706] | 337.5 |
| c | BDD | 12 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 2.4250 [2.3828–2.4723] | 330.3 |
| c | BDD | 12 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.8046 [1.7850–1.8143] | 330.3 |
| c | BITSET | 12 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 2.3421 [2.3185–2.3450] | 330.2 |
| c | BITSET | 12 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.5487 [1.5213–1.6203] | 330.2 |
| c | MIXED | 12 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 2.4195 [2.2014–2.4733] | 338.6 |
| c | MIXED | 12 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.7236 [1.7032–1.7891] | 338.6 |
| c | ZDD | 12 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 2.4752 [2.4496–2.7699] | 337.0 |
| c | ZDD | 12 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 1.8791 [1.7466–1.9181] | 337.0 |
| c | BCDD | 13 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 17.8614 [17.7620–18.0210] | 1719.8 |
| c | BCDD | 13 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 13.7712 [13.4699–13.8811] | 1719.8 |
| c | BDD | 13 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 17.9817 [17.4382–18.1141] | 1712.1 |
| c | BDD | 13 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 13.8660 [13.7217–14.0537] | 1712.2 |
| c | BITSET | 13 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 17.5445 [17.4043–18.7582] | 1711.9 |
| c | BITSET | 13 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 12.6272 [12.5735–12.7592] | 1712.2 |
| c | MIXED | 13 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 18.0530 [17.4696–18.2603] | 1721.0 |
| c | MIXED | 13 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 13.1626 [13.0664–13.6377] | 1720.9 |
| c | ZDD | 13 | 2 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 18.2717 [17.9113–18.3601] | 1719.7 |
| c | ZDD | 13 | 4 | 1/0/None | 1/0 | 4/2/262144 | 5/5 | 13.9570 [13.8999–14.1509] | 1719.8 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
