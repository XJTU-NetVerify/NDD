# Parallel NDD benchmark

Suite `mtp`, 5 fresh-process repetitions per configuration. RSS ceiling 4096 MiB; native budget / legacy heap 3072 MiB. Timeout 120.0s; balanced-tree timeout 15.0s.

| Engine | Backend | N | Workers | Compact/tree/batch | NDD/labels | Threshold/chunk/cache | OK | Compute median [min–max] s | RSS median MiB |
|---|---|---:|---:|---|---|---|---:|---|---:|
| mtp | BCDD | 12 | 1 | 0/0/None | None/None | None/None/None | 5/5 | 4.9480 [4.9110–5.1510] | 1585.2 |
| mtp | BCDD | 12 | 2 | 0/0/None | None/None | None/None/None | 5/5 | 2.7960 [2.7410–2.9700] | 1586.1 |
| mtp | BCDD | 12 | 4 | 0/0/None | None/None | None/None/None | 5/5 | 1.5850 [1.5780–1.7580] | 1587.0 |
| mtp | BCDD | 12 | 6 | 0/0/None | None/None | None/None/None | 5/5 | 1.1880 [1.1510–1.2060] | 1586.8 |
| mtp | BCDD | 13 | 1 | 0/0/None | None/None | None/None/None | 0/5 | - | - |
| mtp | BCDD | 13 | 2 | 0/0/None | None/None | None/None/None | 0/5 | - | - |
| mtp | BCDD | 13 | 4 | 0/0/None | None/None | None/None/None | 0/5 | - | - |
| mtp | BCDD | 13 | 6 | 0/0/None | None/None | None/None/None | 0/5 | - | - |

All samples, failures and commands: [samples.csv](samples.csv), [summary.json](summary.json), [metadata.json](metadata.json), [source snapshot](source_snapshot.tar.gz), logs/.

Compact and tree runs are separate algorithm/encoding tracks. Cross-language ratios include representation, caches, allocation and JIT costs. Compare native 1→p for scaling. Native_bytes excludes Sylvan, Lace, allocator overhead and the JVM; RSS includes them. Merge time is summed worker time, not serial wall time.

Read-only supplemental MTPNDD source snapshot and build configuration are in `mtp_source_snapshot.tar.gz` and `mtp_build_supplement.json`. The measured driver binary hash was verified unchanged; the original worktree was not edited.

Portable raw commands/stdout/stderr: [raw_logs.tar.gz](raw_logs.tar.gz), extract to `logs/`.
