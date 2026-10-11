# Parallel NDD

The `parallel` branch provides a **C11 core + JNI + Java instance API** for parallel
Boolean Network Decision Diagrams. It supports BDD, BCDD, ZDD, BITSET and mixed
fields, while retaining the existing Java implementation as a baseline.

On the measured N-Queens workloads (N=12/13), six workers deliver **3.44–3.59×
native scaling**. The Java batch API is **1.85–2.48× faster end to end** than the
same-backend Java baseline, with **36–56% of its peak RSS**. These are measured
results for this workload and configuration, rather than guarantees for other applications.

- [Native build, API and configuration reference](native/README.md)
- [Implementation report and complete experiment index (中文)](doc/parallel-implementation.md)
- [Final five-round C/JNI/Java benchmark](results/parallel_ndd_20261011_aligned_final/README.md)
- [Correctness and sanitizer validation](results/parallel_ndd_20261011_aligned_validation/README.md)

## What is parallelized?

NDD branches on a **field**, with symbolic sets of field values as edge labels.
The native engine primarily parallelizes recursive **NDD operations**, adds
explicit **batch parallelism**, and optionally parallelizes label operations.
All three layers share one Lace worker pool.

```mermaid
flowchart TD
    J[Java ParallelNDD / NDDNode] --> I[JNI: serialized admission]
    I --> C[C Boolean NDD core]
    C --> N[NDD recursive tasks]
    C --> B[Batch apply / reduction tasks]
    C --> L[Optional label tasks]
    N --> P[One shared Lace worker pool]
    B --> P
    L --> P
```

- **NDD operations:** AND filters empty label intersections and splits surviving
  child problems into chunks. OR/DIFF generate local child results and merge
  coverage/residuals in the parent. EXIST processes child subgraphs and combines
  them in a deterministic order. Small problems run directly.
- **Batch operations:** `applyBatch` submits independent applications together;
  AND/OR reductions support a fixed left-fold or balanced tree. Each batch enters
  one top-level Lace `RUN`, using `CALL`/`SPAWN` internally.
- **Label operations:** BDD, BCDD and ZDD have optional recursive task splitting.
  BITSET uses direct word operations. Label spawning is disabled by default:
  N-Queens benefited chiefly from the NDD layer.
- **Storage and synchronization:** nodes have sorted, contiguous edges; canonical
  tables grow on demand under shard locks. Cache keys include operation/context.
  Locks occupy separate 64-byte cache lines, and worker-local statistics and ID
  leases reduce shared updates. Cache-line isolation substantially improved scaling.

The engine reuses Lace scheduling and Sylvan BCDD/ZDD storage, GC and reference
infrastructure, and draws on MTPNDD's task partitioning/reference handoff design.
Checked label adapters propagate table exhaustion as errors. Dependency changes
are confined to a build-local copy; versions and licenses are in [NOTICE](native/NOTICE).

## Backends and supported operations

| Backend | Label representation | Field width |
|---|---|---:|
| BDD | Plain C BDD, without complemented edges | 1–64 bits |
| BCDD | Sylvan complemented-edge BDD | 1–64 bits |
| ZDD | Ordinary set-family ZDD with explicit powerset universe | 1–64 bits |
| BITSET | Two 64-bit words | 1–7 bits |

Each width-`w` field has a Boolean domain of `2^w` values. Fields can mix backends;
variables are shared and right-aligned within each backend. Field and bit indices
start at zero, with bit 0 most significant.

The new API supports constants, literals, exact field values, AND/OR/NOT/DIFF,
XOR/IMP/EQUIV/NAND/NOR, EXIST, `satCount`, evaluation, batch apply and AND/OR
reduction. `satCount` returns `double`, matching the legacy counting semantics.
Multi-terminal arithmetic and a complete migration of the legacy manipulation API
remain future work. N-Queens tests Boolean behavior, not multi-terminal algebra.

## Build and verify

Linux prerequisites: a C11 compiler, CMake 3.23+, Python 3, Git, pthreads, a JDK
and Maven. Initial configuration fetches pinned Sylvan and Lace sources.

```sh
export JAVA_HOME=/path/to/jdk
cmake -S native -B native/build-release -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-release -j
ctest --test-dir native/build-release --output-on-failure
mvn -Dpndd.tests=true -Dpndd.library="$PWD/native/build-release/libpnddjni.so" package
```

The legacy Java artifact can still be built with `mvn package` without a native
build. Existing pinned local dependency checkouts and sanitizer commands are
covered in [native/README.md](native/README.md).

Validation passed Release/Debug CTest at 1/2/4/6 workers, 29 Maven tests,
ASan/UBSan with leak detection at 1/6 workers, and `-Xcheck:jni`. Small-domain
enumeration covers all four backends and mixed fields, including GC, stale handles,
resource exhaustion and recovery. All 410 final cold-process benchmark samples
matched known solution counts; 20 warm entry samples are reported separately.

## Use the Java API

```java
import org.ants.jndd.parallel.*;

public class ParallelExample {
    public static void main(String[] args) {
        try (ParallelNDD engine = new ParallelNDD(new ParallelNDDConfig(6),
                 new ParallelNDD.Field(32, ParallelNDD.Backend.BCDD),
                 new ParallelNDD.Field(7, ParallelNDD.Backend.BITSET));
             NDDNode prefix = engine.literal(0, 0, true);
             NDDNode port = engine.value(1, 42);
             NDDNode allowed = engine.and(prefix, port)) {
            System.out.println(engine.satCount(allowed)); // 2^31
        }
    }
}
```

Save as `ParallelExample.java`, then compile and run from the repository root:

```sh
javac -cp target/ndd-1.0.1-jar-with-dependencies.jar ParallelExample.java
java -Dpndd.library="$PWD/native/build-release/libpnddjni.so" \
  -cp .:target/ndd-1.0.1-jar-with-dependencies.jar ParallelExample
```

Operations borrow their inputs and return an owned root. Both `ParallelNDD` and
`NDDNode` implement `AutoCloseable`; use try-with-resources or explicitly close
owned results. Closing a node does not consume other nodes. Use `engine.retain(node)`
when another independently closeable reference is needed.

For batch work, `engine.applyBatch(operation, left, right)` returns an array of
owned results; close each element. `engine.reduce(AND|OR, LEFT_FOLD|BALANCED_TREE,
...)` returns one owned result. Balanced-tree order can greatly increase
intermediate graphs: it is an explicit choice, not the N-Queens default.

Default API configuration: NDD/batch spawning enabled, label spawning disabled,
threshold 4, chunk 2, spawn depth 4, cache 262,144 entries, GC interval 128 and a
512 MiB diagram budget. Set `new ParallelNDDConfig(workers, memoryBytes)` for a
larger budget; the full constructor exposes all task-layer switches and capacities.

## Run N-Queens

```sh
# C driver: original encoding, six workers, fixed left-fold, 3 GiB graph budget.
native/build-release/pndd_nqueens 13 BCDD 6 0 0 3072

# Java API: batch construction, the same original encoding and left-fold.
java -Xmx512m -Dpndd.library="$PWD/native/build-release/libpnddjni.so" \
  -cp target/ndd-1.0.1-jar-with-dependencies.jar \
  org.ants.jndd.parallel.ParallelNQueens 13 BCDD 6 true false 3072

# Compact row encoding lets BITSET participate at larger N; report separately.
native/build-release/pndd_nqueens 13 BITSET 6 1 0 3072
```

N=12 has **14,200** solutions; N=13 has **73,712**. Original encoding uses N
fields of N bits and therefore rejects BITSET for N>7. Compact encoding uses
`ceil(log2 N)` bits per row and constrains valid values; it is a different
benchmark track. The Java CLI currently uses original encoding.

## Measured performance

The final matrix uses five independent processes per configuration, shuffled
interleaved rounds, CPU affinity 0,2,4,6,8,10 on the current Linux guest, GCC 13.3
Release and Java 17. Native diagram budget / legacy Java heap: 3 GiB; JNI JVM
heap: 512 MiB; common process-tree RSS ceiling: 4 GiB. CPU IDs represent distinct
cores reported by the guest, not a guarantee of physical host placement.
All rows below use original encoding, the same final left-fold and default task
parameters. Timings are medians. Native combined cache and legacy per-operation
caches have different layouts/capacities, recorded in metadata.

### C core scaling: computation time in seconds

| N | Backend | 1 worker | 2 workers | 4 workers | 6 workers | 1→6 speedup |
|---:|---|---:|---:|---:|---:|---:|
| 12 | BDD | 3.839 | 2.074 | 1.288 | 1.115 | 3.44× |
| 12 | BCDD | 3.799 | 2.041 | 1.253 | 1.104 | 3.44× |
| 12 | ZDD | 3.808 | 2.076 | 1.268 | 1.105 | 3.45× |
| 13 | BDD | 27.174 | 14.781 | 9.167 | 7.575 | 3.59× |
| 13 | BCDD | 26.689 | 14.613 | 9.182 | 7.561 | 3.53× |
| 13 | ZDD | 26.699 | 14.926 | 9.276 | 7.552 | 3.54× |

This isolates scaling within the native engine. Its single-worker path is still
slower than the corresponding legacy Java baseline; further serial optimization
is needed.

### Wider worker sweep: up to 28 workers

A separate [five-round experiment](results/parallel_ndd_20261011_wide_scaling/REPORT.md)
tests 1/6/8/10/12/14/16/20/24/28 workers with the same original encoding,
left-fold and capacity settings. Each speedup uses the same backend's one-worker
median **within this new cohort**. All 300 samples returned the correct counts.

| N | Highest compute speedup | Configuration | Highest C process speedup | Configuration |
|---:|---:|---|---:|---|
| 12 | 3.92× | ZDD, 28 workers | 3.71× | BDD, 16 workers |
| 13 | 4.01× | BCDD, 16 workers | 3.91× | BCDD, 16 workers |

Scaling approaches a plateau: N=13/BCDD reaches 3.98× with 8 workers and 4.01×
with 16 workers, a difference of less than 1%. Nearby configurations have
overlapping timing ranges. [Full curves and raw data](results/parallel_ndd_20261011_wide_scaling/REPORT.md)
include every backend/configuration and initialization/cleanup costs.

### Java API: end-to-end time and peak RSS

Native Java API rows use six workers and batch construction. Legacy Java rows
use the same label backend. Total times include JVM startup, initialization,
construction, combination, counting and cleanup, measured with an outer
**monotonic clock**.

| N | Backend | Legacy Java total s | Parallel Java API total s | Speedup | Legacy / parallel RSS MiB |
|---:|---|---:|---:|---:|---:|
| 12 | BDD | 3.242 | 1.328 | 2.44× | 474.6 / 217.0 |
| 12 | BCDD | 2.726 | 1.476 | 1.85× | 458.9 / 254.7 |
| 12 | ZDD | 3.237 | 1.476 | 2.19× | 475.7 / 247.1 |
| 13 | BDD | 20.346 | 8.204 | 2.48× | 2257.2 / 896.7 |
| 13 | BCDD | 19.017 | 8.235 | 2.31× | 2629.8 / 959.6 |
| 13 | ZDD | 19.878 | 8.385 | 2.37× | 2258.3 / 944.6 |

The product comparison includes representation, allocation, cache, JIT and GC
costs; it is distinct from native thread scaling. The complete final data is in
[samples.csv](results/parallel_ndd_20261011_aligned_final/samples.csv), with commands,
source/binary hashes, source snapshots and archived raw logs alongside it.

In a separate five-round paired N=12/BCDD/six-worker C-driver experiment, the new
core took **1.105 s / 184.6 MiB**, versus MTPNDD's **1.215 s / 1585.7 MiB**:
about 10% faster computation and 8.6× lower RSS. MTPNDD uses fixed tables and a
different initialization boundary; this is a driver/configuration comparison.
In its full matrix, all N=13 samples exceeded the common 4 GiB RSS ceiling.
See [paired data](results/parallel_ndd_20261011_aligned_mtp_pair/README.md) and the
[full report](doc/parallel-implementation.md) for limitations and failed experiments.

### Reproduce the experiments

```sh
python3 native/scripts/benchmark.py --suite final --repeats 5 --timeout 120 \
  --out results/my_parallel_run
python3 native/scripts/benchmark.py --suite smoke --out results/my_all_backend_run
python3 native/scripts/benchmark.py --suite compact --out results/my_compact_run
```

Check CPU topology and supply `--cores` for your machine. Use a fresh output
directory. The harness records failures, compiler versions, capacities, commits,
source snapshots, phase timings, RSS, node/cache/task/GC statistics and full
commands. Additional suites cover JNI scalar/batch, layer ablation, tuning and
MTPNDD; see the [benchmark reference](native/README.md#benchmarks).

## Lifecycle and current limits

- One native engine can be active per JVM/process. External calls serialize
  admission; operation-internal and explicit batch tasks execute in parallel.
- Prefer a worker count within the process's available CPUs. A build-local Lace
  adapter respects Linux affinity masks and adds short sleeps to GC synchronization
  waits when workers outnumber CPUs. CI also tests six workers on two CPUs.
- NDD mark/sweep runs **between operations** in a stopped Lace frame. Sylvan
  label GC can run **inside** label operations, with temporary roots and pending
  results protected. A single operation or batch must fit its graph budget.
- Budget/table exhaustion returns an error or Java `OutOfMemoryError`. Release
  unwanted roots, collect and retry; graph budget excludes JVM, Lace and allocator
  overhead, so it is not a process RSS limit.
- Native handles detect released and cross-engine nodes. The current global
  32-bit generation space eventually exhausts safely and requires process restart.
- Fields: at most 256. Counting uses `double`, with possible precision loss above
  `2^53` and overflow to infinity. Multi-terminal algebra and operation-internal
  NDD GC remain future work. Application gains still need network-verification
  workloads in addition to N-Queens.

## About NDD and the other branches

**Network Decision Diagram (NDD)** is a field-aware decision diagram originally
proposed for network verification. Each edge represents a symbolic subset of a
field's values, encoded with a label backend, instead of branching on one bit or
one concrete field value. NDD can also be understood as a **Nested Decision Diagram**.

- `parallel`: C core, JNI, Java instance API and shared-pool parallel operations.
- `main`: existing Java implementation and backend APIs.
- `Reuse`: label-variable reuse design.
- `Original`: prototype for the NSDI '25 paper.

The existing Java [API guide](https://github.com/XJTU-NetVerify/NDD/wiki/Manipulation-APIs)
and [usage guide](https://github.com/XJTU-NetVerify/NDD/wiki/Usage) remain available.
Historical library comparisons are in the
[N-Queens wiki](https://github.com/XJTU-NetVerify/NDD/wiki/Results-NQueens); those
measurements are separate from this branch's native matrix. Retired finite-domain
ZDD measurements are identified in [the archived backend report](results/nqueens_backend_results.md).

## Resources

- [wiki](https://github.com/XJTU-NetVerify/NDD/wiki)
- [NSDI Paper](https://www.usenix.org/system/files/nsdi25-li-zechun.pdf)
- [NSDI talk slides](https://xjtu-netverify.github.io/papers/NDD/NDD-A-Decision-Diagram-for-Network-Verification.pdf)
- [NSDI talk video](https://www.youtube.com/watch?v=9Ni6Z7qKGV4)

## Bibtex

```bibtex
@inproceedings{NDD,
  title={NDD: A Decision Diagram for Network Verification},
  author={Li, Zechun and Zhang, Peng and Zhang, Yichi and Yang, Hongkun},
  booktitle={22nd USENIX Symposium on Networked Systems Design and Implementation (NSDI 25)},
  pages={237--258},
  year={2025}
}
```

### Contact

- Peng Zhang (p-zhang@xjtu.edu.cn)
- Yichi Zhang (augists@outlook.com)
- Zechun Li (1467874668@qq.com)

## License

Apache-2.0. See [`LICENSE`](LICENSE).
