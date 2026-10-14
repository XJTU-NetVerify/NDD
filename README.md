# Parallel MTNDD

**Multi-Terminal Network Decision Diagram (MTNDD)** represents symbolic functions
whose values may be Boolean, rational, or floating point. Like NDD, each node
branches on a field, and each edge represents a set of field values rather than
one concrete value. Numeric terminals extend this structure to weighted counts,
probabilities, costs, and other algebraic computations.

The `parallel-MTNDD` branch provides a **C11 core + JNI + Java instance API**.
It supports BDD, BCDD, ZDD, BITSET, and mixed fields, with parallel arithmetic,
sum abstraction, fused multiplication and abstraction, and explicit root ownership.
The implementation shares the compact native manager and Lace scheduling
infrastructure with the Boolean [`parallel`](https://github.com/XJTU-NetVerify/NDD/tree/parallel)
branch.

## Build

Requirements:

- Linux, GCC or Clang with C11 and `__int128` support
- CMake 3.23 or newer, Python 3, and Git
- JDK 11 or newer and Maven 3.x

```bash
git clone --branch parallel-MTNDD https://github.com/XJTU-NetVerify/NDD.git
cd NDD
export JAVA_HOME=/path/to/jdk

cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build -j6
mvn -DskipTests package
```

The build produces `native/build/libpmtndd.a`,
`native/build/libpmtnddjni.so`, and `target/ndd-1.0.1.jar`.
CMake fetches pinned Sylvan and Lace dependencies. For an offline build,
`PNDD_SYLVAN_SOURCE` and `FETCHCONTENT_SOURCE_DIR_LACE` may point to local
checkouts of the pinned revisions.

Run the included example:

```bash
java -Dpmtndd.library="$PWD/native/build/libpmtnddjni.so" \
  -cp target/ndd-1.0.1.jar application.examples.ParallelMTNDDExample
```

## Minimal Example

```java
import org.ants.jndd.parallel.*;
import static org.ants.jndd.parallel.ParallelMTNDD.*;

try (ParallelMTNDD engine = new ParallelMTNDD(
        new ParallelNDDConfig(6), Domain.RATIONAL,
        new Field(2, Backend.BCDD), new Field(2, Backend.ZDD));
     MTNDDNode selected = engine.literal(0, 0, true);
     MTNDDNode weight = engine.fraction(1, 3);
     MTNDDNode weighted = engine.times(selected, weight);
     MTNDDNode sum = engine.sumAbstract(weighted, 0);
     MTNDDNode fused = engine.multiplySumAbstract(selected, weight, 0)) {

    System.out.println(engine.terminalValue(sum)); // 2/3
    System.out.println(sum.equals(fused));         // true
    System.out.println(engine.satCount(weighted)); // 8.0
}
```

Fields are numbered from zero, and bits are numbered from most to least
significant by default. Each field above has four possible assignments. Selecting
one bit leaves two assignments in the first field; summing a weight of `1/3` over
that field therefore gives `2/3`.

Every returned `MTNDDNode` owns one native root reference. Operations preserve
their inputs; `retain()` creates another owned reference. Use
try-with-resources or `close()` to release nodes and engines. Unclosed nodes
remain rooted until the engine closes; there is no automatic Cleaner.

`satCount` counts assignments with a **nonzero value**, not the sum of terminal
values. To compute a weighted total, use `sumAbstract` to eliminate every field.

## Label Backends

Backend selection changes the edge representation, while a width-`w` field
always denotes the same `2^w` bit-vector assignments. Fields using the same
backend share a variable pool and a right-aligned variable layout by default. Different fields
may select different backends within one diagram.

| Backend | Representation | Domain limit |
| --- | --- | --- |
| `BDD` | Reduced ordered BDD without complemented edges | 1–4096 bits |
| `BCDD` | Sylvan BDD with complemented edges | 1–4096 bits |
| `ZDD` | Set-family ZDD with an explicit field universe | 1–4096 bits |
| `BITSET` | Canonical truth table in two 64-bit words | 1–7 bits |

The numeric domain is selected per engine:

- `Domain.RATIONAL` uses normalized signed 64-bit numerators and positive
  64-bit denominators, with checked `__int128` intermediates.
- `Domain.DOUBLE` uses finite IEEE doubles without approximate clustering or
  near-zero pruning. Signed zero is canonicalized to zero.

Overflow, division by zero, and nonfinite values or intermediate weights fail
explicitly. Rational arithmetic is bounded rather than arbitrary precision;
floating-point reductions may differ with evaluation order. See
[capabilities and numerical limits](mtndd/docs/STATUS.md) for details.

## Core API

| Purpose | Main methods |
| --- | --- |
| Construction | `constant`, `literal`, `value`, `fraction`, `number` |
| Boolean operations | `apply`, `and`, `or`, `not`, `diff`, `exist` |
| Arithmetic | `plus`, `minus`, `times`, `divide` |
| Abstraction | `sumAbstract`, `multiplySumAbstract`, `sumAbstractBatch`, `multiplySumAbstractBatch` |
| Batch operations | `applyBatch`, `reduce` |
| Dense import | `fromArray`, `fromFractions` |
| Inspection | `evaluate`, `evaluateBits`, `terminalValue`, `maxTerminal`, `satCount`, `stats` |
| Lifetime | `retain`, `close`, `collect` |

Boolean operations and `exist` accept only diagrams with values `0` and `1`.
Dense import uses one array entry per assignment, with field zero varying
slowest and the last field varying fastest. `reduce` supports AND, OR, PLUS,
and TIMES with either a left fold or a balanced tree.

Runtime settings are supplied through `ParallelNDDConfig`, including worker
count, core memory budget, cache capacity, task thresholds, and independent
switches for NDD, label, and batch parallelism. The core budget does not include
the JVM, JNI array copies, or thread stacks.

## Parallel Implementation

Recursive arithmetic partitions work by intersections of edge labels and
processes independent child problems in Lace tasks. Boolean operations use the
shared optimized Boolean kernels. All task layers use the engine's worker pool.
Small problems execute directly to avoid task overhead.

`applyBatch` and `reduce` each enter one top-level computation RUN, with
CALL/SPAWN inside it. Boundary collections are counted separately in
`externalRuns`. Dense import also uses one RUN and creates terminals directly
in C, avoiding a Java wrapper for each input value. Fused
`multiplySumAbstract` aggregates weighted children at the eliminated field
without materializing that layer of the product diagram.

The shared manager uses contiguous edge storage, sharded canonical tables,
versioned caches, and allocation cookies to detect stale node handles.
Numeric leaves participate in the same canonical table and collection protocol.

NDD collection takes place at operation boundaries after tasks finish. Sylvan
label collection may occur during computation; Lace pauses workers while the
collector marks node labels and temporary worker roots. This is cooperative
stop-the-world collection. Failed batches do not return partially owned results,
and budget exhaustion produces an error rather than a corrupted diagram.

Java calls and close operations serialize admission; computation inside an
operation can run in parallel. `ParallelNDD` and `ParallelMTNDD` share an
admission gate, allowing one active native engine at a time. An engine can be
closed before switching to the other API.

### Relationship to Other Branches

| Branch | Core | Terminals | Java API |
| --- | --- | --- | --- |
| [`main`](https://github.com/XJTU-NetVerify/NDD/tree/main) | Java NDD | Boolean | Existing NDD API |
| [`parallel`](https://github.com/XJTU-NetVerify/NDD/tree/parallel) | C + JNI | Boolean | `ParallelNDD`, `NDDNode` |
| [`mtndd`](https://github.com/XJTU-NetVerify/NDD/tree/mtndd) | Java MTNDD | Numeric and Boolean | Historical `NDD` package |
| `parallel-MTNDD` | Shared C manager + numeric extension + JNI | Rational or double, including Boolean | `ParallelMTNDD`, `MTNDDNode` |

This branch draws backend dispatch, dense import, and fused abstraction designs
from `mtndd`. It reuses `native/src/pndd.c` and the production optimization
settings from `parallel`, adding the numeric algorithms in `pmtndd_impl.inc`.
Interval terminals, approximate clustering, and n-ary contraction have not been
ported. [Migration notes and provenance](mtndd/docs/MIGRATION.md) describe these
choices and semantic differences.

The earlier MTPNDD C/JNI prototype is retained under [`mtndd/`](mtndd/README.md).
Its borrowed-pointer Java API is deprecated and should run in a separate
process. The new API's ownership and GC guarantees do not apply to that prototype.

## LLVF integration additions

`sumAbstractBatch(nodes, field)` and
`multiplySumAbstractBatch(left, right, field)` return owned arrays in one
computation RUN, with no partially owned output on failure. `evaluateBits`
accepts one MSB-first Boolean array per field, including fields wider than 64
bits. `maxTerminal` returns the exact maximum over assignments, accounting for
implicit zero regions even when every stored terminal is negative.

Scalar arithmetic identities, terminal calculations and memo hits return from
native admission without a computation RUN; a boundary collection may still
add an external RUN. Division remains strict, including implicit-zero holes.
The companion LLVF `parallel-mtndd-integration` branch uses its active-group
mask to make ECMP denominators nonzero outside the selected group.

The new interfaces and admission path passed the seven native Release cases,
ASan/UBSan numeric rechecks, and 43 Java tests. These checks do not establish
application speedup; LLVF carries separate fixed-input, paired-process reports.

## Benchmark

The following results are **historical measurements of the MTPNDD prototype**,
retained from its original repository. They are not measurements of the current
shared core. Four sweeps from April 22, 2026 contain 168 observations, with one
sample per configuration and incomplete machine metadata.

### N-Queens

The serial/Release variant constructs constraints sequentially while allowing
parallel work inside operations. Timing includes field declaration, constraint
construction, combination, and counting; runtime initialization and shutdown
are excluded. Self-scaling is `T(1) / T(6)`.

| N | 1 worker s | 2 workers s | 4 workers s | 6 workers s | Self-scaling | Solutions |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 0.237 | 0.165 | 0.113 | 0.093 | 2.55× | 724 |
| 11 | 1.171 | 0.724 | 0.446 | 0.344 | 3.40× | 2,680 |
| 12 | 6.021 | 3.697 | 2.132 | 1.540 | **3.91×** | 14,200 |
| 13 | 35.491 | 21.361 | 12.071 | 8.592 | **4.13×** | 73,712 |

Six-worker results for the other build and construction variants:

| N | Serial Release s | Serial PGO+LTO s | Parallel Release s | Parallel PGO+LTO s |
| ---: | ---: | ---: | ---: | ---: |
| 10 | 0.093 | 0.098 | 0.081 | 0.080 |
| 11 | 0.344 | 0.311 | 0.299 | 0.295 |
| 12 | 1.540 | 1.478 | 1.508 | 1.473 |
| 13 | 8.592 | 8.674 | 8.526 | 8.225 |

The parallel variant adds tasks for constraint construction; final combination
remains a sequential fold. PGO is not faster in every case: the serial N=13
sample is about 1% slower than Release. Full N=7–13, workers=1–6 samples,
source hashes, and the summary script are linked in the
[historical benchmark report](mtndd/results/upstream-20260422/README.md).

N-Queens exercises the Boolean special case of MTNDD. It does not establish
arithmetic throughput or performance on algebraic applications. No new algebraic
application measurements have been added to this branch.

### Archived SRE Results

The prototype's earlier SRE report records limited native scaling:

| Workload | 1 worker s | 6 workers s | Self-scaling |
| --- | ---: | ---: | ---: |
| fattree12 / MF1 | 19.915 | 21.579 | 0.92× |
| fattree12 / MF3 | 412.009 | 371.782 | 1.11× |

See the [original SRE report](mtndd/docs/optimization-results-summary.md).
These measurements and the newer Boolean `parallel` results use different
versions and measurement cohorts; their times should not be divided to infer
a cross-branch speedup.

## Tests

```bash
ctest --test-dir native/build --output-on-failure
mvn -DskipTests=false -Dpndd.tests=true \
  -Dpndd.library="$PWD/native/build/libpnddjni.so" \
  -Dpmtndd.library="$PWD/native/build/libpmtnddjni.so" verify
```

For address and undefined-behavior checks:

```bash
cmake -S native -B native/build-asan -DCMAKE_BUILD_TYPE=Debug \
  -DPNDD_SANITIZE=ON -DPNDD_BUILD_JNI=OFF
cmake --build native/build-asan -j6
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 \
  ctest --test-dir native/build-asan --output-on-failure
```

The October 13, 2026 validation passed **7 CTest cases in both Release and
ASan/UBSan builds**, and **41 Java tests** with no failures or skips. Coverage
includes all four backends and mixed fields, rational/double enumeration,
fused abstraction, dense import, cache collisions, stale handles, budget
failure recovery, and label GC during 32,768-element numeric batches.
Six-worker GC tests also passed with affinity restricted to two CPUs.
[Validation records and reproduction commands](mtndd/results/validation-20261013-shared-core/README.md)
are retained in the repository.

## Repository Structure

```text
native/
├── include/pmtndd.h             C multi-terminal API
├── src/pndd.c                  Shared compact manager and Boolean kernels
├── src/pmtndd_impl.inc          Numeric algorithms and terminals
├── src/pmtndd_jni.c             JNI binding
└── tests/test_pmtndd.c          Numeric and resource-management tests

src/main/java/org/ants/jndd/parallel/
├── ParallelMTNDD.java           Java engine API
├── MTNDDNode.java               Owned native root
└── ParallelNDDConfig.java       Shared runtime configuration

mtndd/                          Compatibility prototype and historical results
```

## Resources

- [NDD wiki](https://github.com/XJTU-NetVerify/NDD/wiki)
- [NDD paper, NSDI 2025](https://www.usenix.org/system/files/nsdi25-li-zechun.pdf)
- [Native build and configuration reference](native/README.md)
- [Migration notes](mtndd/docs/MIGRATION.md) and [capabilities/GC contracts](mtndd/docs/STATUS.md) (Chinese)
- [Dependency and source notices](native/NOTICE)

## License

Apache-2.0. See [LICENSE](LICENSE). Upstream copyright and license notices
are retained with the compatibility prototype and fetched dependencies.
