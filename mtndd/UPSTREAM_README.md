# MTPNDD

**Multi-Terminal Parallel Network Decision Diagrams** — a high-performance C library for parallel NDD operations, built on [Sylvan](https://github.com/trolando/sylvan) (BDD) and [Lace](https://github.com/trolando/lace) (work-stealing parallelism), with a Java API via JNI.

## Architecture

![architecture](docs/mtpndd_architecture.png)

MTPNDD extends Sylvan's BDD layer with multi-terminal NDD nodes. The stack is:

| Layer | Component | Role |
|-------|-----------|------|
| **Java API** | `MTPNDDEngine` / `MTPNDD` / `MTPNDDConfig` | High-level interface for Java applications |
| **JNI Bridge** | `mtpndd_jni.c` | Maps Java calls to native C API |
| **C API** | `mtpndd.h` | Single-include header exposing all operations |
| **Core** | `mtpndd_node`, `mtpndd_nodetable`, `mtpndd_operation_cache`, `mtpndd_memory_pool` | Node operations, canonicalization, caching, slab memory pools |
| **Runtime** | Sylvan (submodule) + Lace 1.6 | BDD storage/ops + work-stealing task scheduler |

Sylvan is tracked as a git submodule at `sylvan/`, pointing at a personal fork ([Augists/sylvan](https://github.com/Augists/sylvan)) that adds per-worker `mtbdd_refs` sharding, a coalescing ref cache, and a `sylvan_set_spawn_depth_cutoff` runtime knob on top of upstream `trolando/sylvan` 1.10.0.

## Key Features

- **Parallel boolean operations** — AND, OR, NOT, DIFF, EXIST with task-level parallelism via Lace SPAWN/SYNC
- **Two-phase AND** — filter-then-recurse strategy that prunes false edge pairs before spawning recursive tasks
- **Slab memory pools** — batch-allocated object pools for nodes, edges, and table entries; per-worker local caches to avoid cross-core cacheline traffic
- **Lock-free operation cache** — seqlock-style atomic reads, no mutex on the hot path
- **Per-worker refs sharding + coalescing cache** — Sylvan-side change: `mtbdd_ref`/`deref` write into a tiny thread-local cache first, cancelling ref/deref pairs locally; flushed to per-worker refs tables at GC time
- **Dynamic nodetable** — spinlock-sharded buckets with automatic rehash and GC-triggered growth
- **DOT visualization** — export any NDD subgraph to Graphviz DOT format
- **Java API** — full-featured JNI bridge with builder-pattern configuration

## Quick Start

```bash
# First-time clone
git clone <repo> mtpndd-c
cd mtpndd-c
git submodule update --init --recursive

# Build everything (C library + JNI) — defaults to Release -O3
cmake -B build
cmake --build build -j

# Produces:
#   build/sylvan/src/libsylvan.a
#   build/mtpndd/libmtpndd.a
#   build/mtpndd/mtpndd_nqueens_benchmark
#   build/mtpndd/mtpndd_nqueens_parallel_benchmark
#   build/jni/libmtpnddjni.so

# Run N-Queens smoke test
./build/mtpndd/mtpndd_nqueens_test 8
./build/mtpndd/mtpndd_nqueens_benchmark 12
```

For a production-optimized build (PGO + LTO), use:

```bash
./scripts/build-pgo.sh          # writes build-pgo/
./build-pgo/mtpndd/mtpndd_nqueens_benchmark 12
```

Java package and tests:

```bash
cd jni
mvn -DskipTests package
mvn test
```

See [CLAUDE.md](CLAUDE.md) for the full build and architecture reference.

## Performance

N-Queens benchmark, `MTPNDD_LOG_LEVEL=1`, default Release -O3, 6-core machine. Single-run figures from `./bench_sweep.sh`; interleaved multi-run means used for optimization decisions live in `docs/archived/perf/`.

### Release (default build)

| N | 1 worker | 4 workers | 6 workers | Speedup (6w/1w) | Solutions |
|---|---:|---:|---:|---:|---|
| 10 | 0.237 s | 0.113 s | 0.093 s | **2.55×** | 724 |
| 11 | 1.171 s | 0.446 s | 0.344 s | **3.40×** | 2,680 |
| 12 | 6.021 s | 2.132 s | 1.540 s | **3.91×** | 14,200 |
| 13 | 35.49 s | 12.07 s | 8.59 s | **4.13×** | 73,712 |

### PGO + LTO (via `scripts/build-pgo.sh`)

| N | 1 worker | 4 workers | 6 workers | Speedup (6w/1w) | vs Release (6w) |
|---|---:|---:|---:|---:|---:|
| 10 | 0.217 s | 0.104 s | 0.098 s | 2.21× | −5% |
| 11 | 1.082 s | 0.421 s | 0.311 s | 3.48× | **−10%** |
| 12 | 5.711 s | 2.033 s | 1.478 s | 3.86× | **−4%** |
| 13 | 34.19 s | 11.50 s | 8.67 s | 3.94× | −1% |

### Outer-operation parallel variant, PGO + LTO (`mtpndd_nqueens_parallel_benchmark`)

Same optimizations plus tree-parallel construction of the N-Queens formula. Most useful at small N where the inner AND/OR recursion runs out of parallelism.

| N | 1 worker | 6 workers | vs serial PGO (6w) |
|---|---:|---:|---:|
| 10 | 0.220 s | 0.080 s | **−18%** |
| 11 | 1.093 s | 0.295 s | **−5%** |
| 12 | 5.804 s | 1.473 s | 0% |
| 13 | 33.66 s | 8.23 s | **−5%** |

The parallel variant under plain Release (no PGO) is 1–8 % slower than the PGO numbers above (largest gap at 1 worker, shrinking to ~1–3 % at 6 workers). All figures from the 2026-04-22 sweep. Raw data: `bench_results_{serial,parallel}_{release,pgo}_*.tsv`.

### Session-over-session comparison

This codebase went through a large refactor in April 2026 (Sylvan + Lace upgrade from vendored 1.9.1 + 1.5.0 to upstream submodule 1.10.0 + 1.6.2, plus a round of targeted perf work). Pre-migration baseline vs current at N=12:

| W | pre-migration | current (Release) | current (PGO+LTO) |
|---|---:|---:|---:|
| 1 | 6.32 s | 6.02 s (−5%) | 5.71 s (**−10%**) |
| 2 | 5.14 s | 3.70 s (**−28%**) | 3.47 s (**−33%**) |
| 4 | 3.08 s | 2.13 s (**−31%**) | 2.03 s (**−34%**) |
| 6 | 2.06 s | 1.54 s (**−25%**) | 1.48 s (**−28%**) |

Details and the full A/B audit are in `docs/archived/perf/2026-04-21-upstream-migration-and-ab-audit.md`, `2026-04-21-bottleneck-analysis.md`, and the commit history on `feature/c`.

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — build/test/architecture reference
- [`docs/archived/perf/`](docs/archived/perf/) — performance notes and A/B results
- [`docs/plans/`](docs/plans/) — design notes for future work (e.g. open-addressing nodetable)
- [Wiki](../../wiki) — legacy design docs (pre-migration)

## License

Apache-2.0 — see [LICENSE](LICENSE).
