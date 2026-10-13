# SRE migration to current Java / parallel native NDD

The F-profile (`backend=f`) Config2Spec pipeline can select current Java NDD or
C/JNI parallel NDD. The historical embedded `org/ants/jndd` sources remain in the
repository but Maven excludes them and depends on `org.ants:ndd:1.0.1`, installed
from the local NDD checkout. The `jdd` sources still used by SRE are retained.

## Build

```sh
export JAVA_HOME=/path/to/jdk17
cd /home/cheney/NDD
cmake -S native -B native/build-sre -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-sre -j6
mvn -Dpndd.tests=true -Dpndd.library="$PWD/native/build-sre/libpnddjni.so" install
cd /home/cheney/SRE
mvn -Dpndd.library=/home/cheney/NDD/native/build-sre/libpnddjni.so test
mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt
```

CMake downloads pinned Sylvan and Lace; local pinned checkouts can be supplied
as described in NDD's `native/README.md`. Do not rebuild either repository or
replace the installed JAR during a benchmark run.

## Run

```sh
java -Xmx8g -Dsre.ndd.engine=native -Dsre.ndd.workers=6 \
  -Dsre.ndd.labels=false -Dsre.ndd.checksum=true \
  -Dpndd.library=/home/cheney/NDD/native/build-sre/libpnddjni.so \
  -cp "target/classes:$(cat target/cp.txt)" \
  application.config2spec.NDDConfig2spec bgp_fattree08 2 \
  backend=f order=header-link plan=1
```

Use `-Dsre.ndd.engine=java` for the migrated Java baseline. Native defaults are
6 workers, 3072 MiB diagram budget, 262144 cache entries, threshold 4, chunk 2,
spawn depth 4, NDD parallelism enabled, label parallelism disabled. Explicit
properties are `sre.ndd.memoryMiB`, `cache`, `threshold`, `chunk`, `depth`,
`labels`, and `collectEvery` (default 16384 newly retained adapter entries).
The native diagram budget is additional to Java heap; RSS covers both.

The adapter `sre.ndd.NDD` preserves SRE's integer IDs, borrowed results and
logical `ref/deref`. It owns native roots until adapter collection determines
that they are unreachable from external roots, permanent literals/label tokens,
and active operands. Borrowed child IDs survive while their parent remains
reachable. Snapshot labels are opaque condition roots, not Sylvan pointers.
Header conversion and minimum-failure queries use backend-neutral inspection
instead of direct access to Java BDD internals. Both engines use legacy
LSB-first BCDD order and the same field sharing/polarity for this comparison.

This migration covers the F-profile Config2Spec path. It is not a complete
replacement for all inherited legacy static APIs: direct label-engine access,
ZDD-specific fast construction and native mixed-backend statistics need further
adaptation before benchmarking other profiles. Label tokens referenced by SRE
memo keys remain protected until engine close. SRE still submits scalar JNI
operations; the core's batch API is not yet used by control-plane propagation.

## Reproduce the requested matrix

```sh
python3 experiments/parallel-ndd/benchmark.py \
  --ndd /home/cheney/NDD \
  --output /home/cheney/NDD/results/sre_parallel_NEW \
  --cpus 0,2,4,6,8,10 --repeats 2
```

The script runs fattree04/08/12 × MF 1/2/3 × Java/native1/native6 in two
interleaved rounds (54 independent processes). It fixes F, Header-Link, plan 1,
original trimming and link order, 8 GiB JVM heap, 3 GiB native diagram budget,
12 GiB process RSS guard and 900 second timeout. It records source archives,
commits and diffs, binary/input hashes, commands, raw logs, JSONL and CSV.
It validates route counts, field layout, link order/polarity, RIB/FIB condition
checksums and normalized reachability CSVs across engines and repetitions.
Condition checks combine 64-bit structural DAG hashes with a SHA-256 digest of
sorted route descriptors; they are probabilistic regression checks, supplemented
by small-domain native tests and migration lifecycle/header tests.

Primary time is the application's `total` (including initialization and RIB
output). `src_compute` excludes RIB serialization. Process wall time separately
includes JVM startup, post-timing semantic verification and native shutdown.
Process wall time includes up to 50 ms of parent polling delay.
Peak RSS is sampled every 50 ms from process VmHWM; very short processes have
coarser memory measurement. Native memory counters omit some external/native
allocator and Sylvan costs, so compare RSS rather than those counters alone.
All run failures and resource limits remain in the results.

With original trimming, fattree04/MF=2 and MF=3 produce zero BGP routes and therefore
mainly measures initialization/filter cost. Do not interpret it as BGP scaling.
Results: `/home/cheney/NDD/results/sre_parallel_20261011_final/README.md`.
