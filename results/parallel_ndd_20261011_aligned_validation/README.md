# Validation after shard cache-line isolation

The final aligned production core passed Release and Debug CTest with 1/2/4/6 workers (4/4 each), ASan + UBSan with leak detection and halt-on-UB with 1/6 workers, Maven package (29 tests, zero failures/errors/skips), and -Xcheck:jni N=7 BCDD six-worker batch (40 solutions, no JNI warnings). Assertions remain enabled in native Release fixtures. Tests include explicit Sylvan exhaustion with spare C arena capacity and post-failure recovery, GC inside active parallel label batches, enumeration across all backends/mixed fields, stale handles, and double counting boundaries.

`validation.log` contains configuration, compilation and all verification output before timing began. `sha256.json` identifies measured source/binaries. Earlier unaligned validation is preserved in `../parallel_ndd_20261011_validation`. No ThreadSanitizer certification or operation-internal NDD GC is claimed.
