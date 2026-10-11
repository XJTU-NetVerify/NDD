# Parallel Boolean NDD: C core and Java API

The native engine supports true plain BDD, complemented BDD (BCDD), set-family
ZDD, BITSET, and mixed fields. Legacy Java NDD is unchanged. Boolean operations,
existential abstraction, counting, scalar evaluation, batch apply and AND/OR
reductions are available. Multi-terminal arithmetic and the legacy manipulation
API are outside this first version.

## Build and test

Linux requires GCC/Clang with C11, CMake 3.23+, Python 3, Git, pthreads, and a JDK.
Maven still builds the existing Java artifact without downloading native code.
The first native configure downloads pinned Sylvan and Lace sources.

```sh
export JAVA_HOME=/path/to/jdk
cmake -S native -B native/build-release -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-release -j
ctest --test-dir native/build-release --output-on-failure
mvn -Dpndd.tests=true -Dpndd.library="$PWD/native/build-release/libpnddjni.so" package
```

For an existing pinned checkout use
`-DPNDD_SYLVAN_SOURCE=/home/cheney/mtpndd/sylvan` and optionally
`-DFETCHCONTENT_SOURCE_DIR_LACE=/home/cheney/mtpndd/build/_deps/lace-src`.
The supplied Sylvan checkout must be at
`c8bf561ee64036375f4693eb389e57ec8ef4aa6d`; Lace is pinned to
`63d8a5ea79922a234e284ee4b264fa5e4e784939` (1.6.2).
The build adapts private copies of Sylvan and Lace; it does not edit MTPNDD or its submodule.
ZDD complement edges are disabled consistently in the dependency and caller.
When workers outnumber the CPUs available to the process, the Lace adapter adds
50 microsecond sleeps to GC barrier and `TOGETHER` completion waits. This avoids
waiting workers occupying CPUs needed by preempted workers. The normal worker
configuration retains the upstream busy waiting path. CI includes the full
6-worker correctness/GC suite restricted to two CPUs, with its 120 second timeout.

Native assertions are enabled in the test executable even in Release. Java native
tests are opt-in; normal Maven tests do not require a native library. Native CI
adds C/JNI differential tests and ASan/UBSan checks.
For a wider native scaling sweep use `benchmark.py --suite wide-scaling` with
`--workers 1,6,8,10,12,14,16,20,24,28` and an explicit `--cores` list covering the
largest worker count. Put distinct guest cores first, then their SMT siblings.
The suite compares N=12/13 with BDD, BCDD and ZDD using the original encoding and
fixed left-fold; each sample checks the known solution count. See the
[28-worker experiment](../results/parallel_ndd_20261011_wide_scaling/REPORT.md).

```sh
cmake -S native -B native/build-asan -DCMAKE_BUILD_TYPE=Debug \
  -DPNDD_SANITIZE=ON -DPNDD_BUILD_JNI=OFF
cmake --build native/build-asan -j
ASAN_OPTIONS=detect_leaks=1 native/build-asan/pndd_test 1
ASAN_OPTIONS=detect_leaks=1 native/build-asan/pndd_test 6
```

## Java API

Load with `-Djava.library.path=...` or the absolute
`-Dpndd.library=.../libpnddjni.so` property.

```java
import org.ants.jndd.parallel.*;

try (ParallelNDD e = new ParallelNDD(new ParallelNDDConfig(6),
         new ParallelNDD.Field(32, ParallelNDD.Backend.BDD),
         new ParallelNDD.Field(7, ParallelNDD.Backend.BITSET));
     NDDNode prefix = e.literal(0, 0, true);
     NDDNode port = e.value(1, 42);
     NDDNode valid = e.and(prefix, port)) {
    System.out.println(e.satCount(valid)); // 2^31
}
```

Fields and bits are zero-based; bit 0 is most significant. `value` uses unsigned
long values (all bit patterns allowed at width 64). Label variables are shared
and right-aligned within each backend. ZDD universe is an explicit powerset,
not its TRUE leaf. BITSET covers up to 128 assignments and divides out unused
prefix bits when narrower fields share its variable pool.

`NDDNode` owns one root reference. Operations borrow inputs and return an owned
root; use `retain()` for another independently closeable reference. Engine close
invalidates all its nodes. Closed nodes and nodes from another engine are rejected.
Canonical equality compares engine identity and native handle. The current handle
encodes a 32-bit node ID and a global 32-bit generation. Exhausting the generation
space (including reserved ID leases) safely rejects allocation; the process must
then be restarted. The generation counter is not reset by engine close. Handles are not
exposed and cannot be passed to the legacy int API. Both Java implementations can
coexist; only one native engine may be active per process.

`applyBatch(op,left,right)` submits the entire batch in one top-level RUN.
`reduce(AND|OR,LEFT_FOLD|BALANCED_TREE,...)` returns one root. Empty AND is TRUE;
empty OR is FALSE. Java and C public admission are serialized; task layers share
one Lace pool. Java threads do not create another native worker pool.

The default is NDD and batch parallelism, label spawning disabled, threshold 4,
chunk 2, spawn depth 4, combined cache 262,144 entries, GC interval 128, and a
512 MiB diagram budget. A five-repeat N=12/BCDD grid selected threshold/chunk; a five-round N=13
cache comparison selected the larger cache for the default. At N=12, 65,536
entries can save about 6 MiB with similar time. These are N-Queens defaults,
not universal optima.
`new ParallelNDDConfig(workers, memoryBytes)` changes the budget; the full
constructor exposes independent NDD/label/batch switches and tuning settings.
Use a larger budget for large diagrams. Cache sizes must be powers of two.

Counting returns double, matching the existing interface. Integers above 2^53
can lose precision; large domains can overflow to positive infinity.

## Storage, parallelism and collection

- Canonical nodes store sorted contiguous `(label,target)` edges. Equal targets
  are merged, empty edges disappear, and a universe edge removes its field node.
  Shards grow on demand. Published objects never move; worker ID leases reduce
  allocator locking. Cookies reject reclaimed handles and prior-engine nodes.
- AND filters empty intersections before spawning child work. OR/DIFF compute
  local intersections and residual coverage, then normalize in the parent.
  Existential abstraction recurses and performs deterministic child OR reduction.
  Only actual task splits consume NDD fork depth: deep sequential paths can still
  expose parallel work. Bounded binary splits avoid unbounded pending tasks.
- Labels use a checked recursive apply adapter: plain C BDD nodes have no
  complement edges; BCDD/ZDD use Sylvan's canonical storage and cooperative GC;
  BITSET uses canonical pairs of words. Optional label tasks use the same Lace
  pool. The adapter propagates allocation failure rather than invoking upstream
  apply kernels that assume allocation always succeeds.
- Cache entries are protected by short shard locks and include operation and
  backend/counting context. Worker statistics and allocation credits avoid a
  shared atomic update on every cache access. Locks are isolated on 64-byte cache
  lines; the containing engine uses C11 aligned allocation. Cache entries are cleared before
  any node reuse and before Sylvan collection.
- NDD mark/sweep runs in a stopped Lace frame **between admitted operations**.
  Owned roots and cached literals are traced. Sylvan can collect **inside** a
  label operation: all allocated NDD labels, scoped temporary vectors and pending
  label-task results are protected. Explicit collection coordinates both layers.
  No GC is requested while a canonical-table lock is held.
- A single large operation or batch must fit the diagram budget. NDD collection
  does not interrupt its recursive work. Exhaustion returns an error/Java
  `OutOfMemoryError`; draining tasks leaves inputs intact. Release unwanted roots,
  collect, and retry with more memory or smaller batches. Recovery is tested.

20% of the native arena partition is reserved for collection scratch. For
BCDD/ZDD, one quarter of the configured budget is assigned to Sylvan's maximum
node/cache tables; the remainder bounds the C arena. Without them the full budget
is assigned to the arena. This is not a process RSS limit: Lace deques, thread
stacks, malloc overhead, dependency support structures and JVM memory are extra.
The benchmark harness applies an independent, common process-tree RSS ceiling.

`stats()` reports allocated canonical nodes, including unreachable nodes until
collection; created counts are cumulative. `nativeBytes`/peak cover C allocations
and reserved worker credits, excluding Sylvan and the worker/JVM overhead.
`labelsLive` also includes Sylvan table occupancy. Task steals count PNDD task
migration in NDD, label and reduction tasks, not every scheduler steal attempt.
Batch task migration is not separately counted; zero recorded migrations does
not prove that no batch work was stolen. Merge nanoseconds are summed worker
time, not the serial portion of wall time. `externalRuns` counts work/collection
entries, excluding diagnostic table queries.

## Benchmarks

```sh
# Original one-hot encoding, 12 fields of 12 bits, fixed final left-fold:
native/build-release/pndd_nqueens 12 BCDD 6
# Compact row-variable encoding; all backends can test larger N:
native/build-release/pndd_nqueens 13 BITSET 6 1 0
# Public Java API: all constraints batched by dependency layer:
java -Dpndd.library="$PWD/native/build-release/libpnddjni.so" \
  -cp target/ndd-1.0.1-jar-with-dependencies.jar \
  org.ants.jndd.parallel.ParallelNQueens 12 BCDD 6 true false
```

Native CLI trailing options: compact, tree, diagram MiB, NDD spawning, label
spawning, batch spawning, threshold, chunk, cache entries. Java CLI supports batch,
tree, diagram MiB, NDD spawning, label spawning, threshold, chunk, cache entries.
The original encoding rejects BITSET when N>7. Compact encoding uses
`ceil(log2 N)` bits per row and constrains values to 0..N-1; report separately.
Balanced-tree order can change intermediate graph size even when results agree.

```sh
python3 native/scripts/benchmark.py --suite smoke
python3 native/scripts/benchmark.py --suite mid      # N=10,11
python3 native/scripts/benchmark.py --suite scaling  # N=12,13
python3 native/scripts/benchmark.py --suite final    # C scaling + paired JNI batch / legacy Java
python3 native/scripts/benchmark.py --suite compact
python3 native/scripts/benchmark.py --suite compact-middle # complete 2/4-worker compact matrix
python3 native/scripts/benchmark.py --suite ablation
python3 native/scripts/benchmark.py --suite jni
python3 native/scripts/benchmark.py --suite jni-final # final N=12 scalar/batch left-fold
python3 native/scripts/benchmark.py --suite jni-large # N=13 scalar/batch left-fold
python3 native/scripts/benchmark.py --suite api-comparison # JNI batch versus legacy Java, paired
python3 native/scripts/benchmark.py --suite tuning
python3 native/scripts/benchmark.py --suite cache-large # N=13 cache comparison
python3 native/scripts/benchmark.py --suite mtp --mtp-root /home/cheney/mtpndd
python3 native/scripts/benchmark.py --suite mtp-pair # paired N=12 C/MTPNDD, six workers
```

Runs use five independent processes per configuration, shuffled paired rounds,
exclusive timing, CPU binding and known solution counts. A new output directory
is required; previous results are never overwritten. `--resume --out existing_directory`
can append missing paired-round samples after interruption, checking identical binaries,
budgets, timeouts, timer version, CSV schema and topology; previous metadata is preserved and resume metadata
is recorded separately. Default RSS ceiling is
4 GiB, C diagram budget and legacy Java heap 3 GiB, JNI Java heap 512 MiB, and
timeout 300 s. `--cores` must match the inspected machine topology; the default
0,2,4,6,8,10 is for the current guest, not a physical-core guarantee.
`--tree-timeout 15` sets a separately recorded balanced-tree latency budget while
other paths keep `--timeout`. The initial 120-second tree failures and the bounded
five-repeat tree experiment are separate tracks; do not average their failures.
Stopping the harness with SIGINT/SIGTERM kills the independently sessioned timed
child and waits for it, preventing orphan work from contaminating a later run.

Samples retain resource failures and incorrect counts. Reports record compiler,
commits, dirty diff, CMake configuration, source/binary hashes, timers, peak RSS,
allocation, cache/task/GC counters and full commands. Final runs measure elapsed
time with an outer monotonic clock and a dedicated wait thread; realtime and
GNU time elapsed are separate diagnostics because wall-clock adjustments can
make them shorter than the program's monotonic compute time. Legacy Java caches are
65,536 entries per NDD operation and backend, native cache is 262,144 combined
entries; these are explicit configurations, not identical cache byte budgets.
MTPNDD retains its existing fixed-capacity driver, so it can exceed the common
RSS ceiling even when C NDD fits. Its init timing differs; cross-project speeds
must include that qualification.

Compare native T(1)/T(p) for parallel scaling, same-backend Java versus JNI for
product performance, and process wall for complete application latency. Cold JVM
JIT and GC are included. The warm entry microbenchmark is a separate track. `pndd_entry [workers]
[operations=10000] [batchSize=100]` and `native/bench/EntryBenchmark.java` execute
the same warmed cached AND with GC and batch spawning disabled. Scalar and batch
timings include reference release; the Java version also includes wrappers and
JNI array copies. Compile the Java driver against the Maven jar. They isolate
entry costs more closely than comparing different N-Queens construction orders.
`python3 native/scripts/benchmark_entry.py --java "$JAVA_HOME/bin/java" --out results/new_entry_run`
runs five shuffled fresh processes per C/JNI and 1/6-worker configuration.
N-Queens exercises Boolean NDD; it does not validate MT algebra or establish
network-verification performance.

Implementation status, measured tradeoffs and result links: [Chinese report](../doc/parallel-implementation.md).
