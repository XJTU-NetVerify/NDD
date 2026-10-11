# Cache-line isolation experiment

Five shuffled paired rounds, one fresh process per sample, exclusive sequential execution. BCDD, original N-Queens encoding and left-fold, 3072 MiB diagram budget, label spawning off, threshold 4 / chunk 2 / cache 262144. Same CPU masks as the formal benchmark. Every count matched 14200 or 73712. Elapsed time uses Python monotonic perf_counter around subprocess completion; GNU time supplies peak RSS. This exploratory runner has a 120-second timeout but no live RSS-ceiling enforcement; observed RSS stayed below 1 GiB.

The candidate only aligns each SpinLock to 64 bytes and allocates the containing engine with C11 aligned_alloc followed by zero initialization. This also separates unique-table shards. It does not establish whether cache locks or unique-table locks account for more of the improvement; both changed together. Cache line contention is an inference from layout and the controlled change, not a hardware performance-counter measurement.

| N | Workers | Original compute s | Aligned compute s | Original/aligned | Original/aligned RSS MiB |
|---:|---:|---:|---:|---:|---:|
| 12 | 1 | 3.883 | 3.760 | 1.03 | 183.5 / 184.6 |
| 12 | 6 | 1.570 | 1.084 | 1.45 | 183.5 / 184.6 |
| 13 | 6 | 10.837 | 7.619 | 1.42 | 862.1 / 863.3 |

`metadata.json` records both binary hashes; `samples.json` contains commands, every repetition and counters; logs contain raw output. `aligned_source.tar.gz` preserves the exact candidate. `run.py` preserves the exploratory runner (paths are session-specific; adjust `root`, `bins`, and candidate source path for reproduction). For the original source, extract `../parallel_ndd_20261011_scaling_final/source_snapshot.tar.gz`. Build each tree separately with the pinned dependencies and Release flags documented in native/README.md. Neither original project nor MTPNDD was modified during this experiment.

The change was subsequently adopted. Final production correctness logs and the complete C/JNI/Java matrix are separate; these six exploratory medians are not a substitute for that matrix.
