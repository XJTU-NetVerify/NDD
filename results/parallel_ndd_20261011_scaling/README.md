# Parallel NDD benchmark

Suite `scaling`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| c | BCDD | 12 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 4.1120 [3.7785–4.3048] | 183.5 |
| c | BCDD | 12 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 2.5254 [2.3135–2.9329] | 183.5 |
| c | BCDD | 12 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.8183 [1.7731–1.8546] | 183.2 |
| c | BCDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.7229 [1.6247–1.7609] | 183.5 |
| java | BCDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 2.4956 [2.3686–2.5943] | 458.9 |
| c | BDD | 12 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 4.2396 [3.9011–4.3372] | 175.9 |
| c | BDD | 12 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 2.5158 [2.4660–2.5751] | 176.0 |
| c | BDD | 12 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.7993 [1.7369–1.8999] | 175.8 |
| c | BDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.6778 [1.6515–1.7338] | 175.9 |
| java | BDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 3.0748 [2.8258–3.4553] | 475.6 |
| c | ZDD | 12 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 4.1593 [3.7992–4.5436] | 183.3 |
| c | ZDD | 12 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 2.5345 [2.4454–2.9031] | 183.3 |
| c | ZDD | 12 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.8298 [1.7771–1.8707] | 183.3 |
| c | ZDD | 12 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 1.7881 [1.7524–1.8189] | 183.3 |
| java | ZDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 3.0770 [2.9484–3.3481] | 475.9 |
| c | BCDD | 13 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 28.8557 [27.6886–29.5558] | 861.6 |
| c | BCDD | 13 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 17.7916 [16.8661–18.3924] | 861.8 |
| c | BCDD | 13 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 12.4518 [12.4099–12.6445] | 862.0 |
| c | BCDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 11.4391 [11.3511–11.8516] | 862.1 |
| java | BCDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 18.5225 [18.2868–18.9640] | 2629.8 |
| c | BDD | 13 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 29.3074 [27.0455–30.3332] | 854.7 |
| c | BDD | 13 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 18.0705 [17.8400–18.4509] | 854.9 |
| c | BDD | 13 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 12.6437 [12.4726–12.9039] | 854.8 |
| c | BDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 11.5931 [11.3541–11.9506] | 855.0 |
| java | BDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 19.7806 [19.6669–20.0412] | 2257.5 |
| c | ZDD | 13 | 1 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 28.1453 [26.7413–29.2059] | 861.5 |
| c | ZDD | 13 | 2 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 18.0158 [16.8858–18.6820] | 861.8 |
| c | ZDD | 13 | 4 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 12.9995 [12.5403–13.2708] | 861.9 |
| c | ZDD | 13 | 6 | 0/0/None | 1/0 | 4/2/262144 | 5/5 | 11.9447 [11.4753–11.9869] | 861.9 |
| java | ZDD | 13 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 19.9319 [18.8976–20.0836] | 2258.2 |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
