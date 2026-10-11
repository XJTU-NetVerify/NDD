# Final implementation validation

- Release CTest: 1/2/4/6 workers, 4/4 passed; test assertions enabled with `-UNDEBUG`.
- Debug CTest: 1/2/4/6 workers, 4/4 passed.
- Maven package: 29 tests, zero failures/errors/skips, including seven native integration tests.
- ASan + UBSan: `ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 native/build-asan/pndd_test 1` and `6` passed.
- JNI: `-Xcheck:jni`, N=7 BCDD, six workers, batch left-fold; 40 solutions, no JNI warnings.

Native fixtures require Sylvan GC to occur inside the first pressure-test batch. Other cases cover mixed fields, explicit enumeration, singleton lifecycle, stale handles, budget failure and recovery, and large-domain counting. Java fixtures include four caller threads sharing roots through collection and one top-level RUN per batch/reduce when every admission collects.

Logs and SHA-256 manifest accompany this report. The sanitizer script's final Java invocation initially used unsupported CLI backend `MIXED`; that command rejected its argument before opening an engine. It was rerun correctly as `BCDD`; the successful output is in `jni-check.log`. Mixed backend correctness is separately covered by native and Java integration fixtures. No ThreadSanitizer certification or full task-DAG serial-span measurement is claimed.

Additional explicit Sylvan exhaustion fixtures (BCDD/ZDD, 64-bit dense point values, 4 MiB diagram budget) verify failure occurs with spare C arena capacity, then release, collect and successfully reconstruct. Release/Debug 1/2/4/6 and ASan/UBSan 1/6 were rerun successfully; see `label-exhaustion-validation.log`. Only test code changed; measured core/JNI/driver hashes remain unchanged.

This validation predates shard cache-line isolation. It is preserved with its original manifest; current production verification is in [aligned validation](../parallel_ndd_20261011_aligned_validation/README.md).
