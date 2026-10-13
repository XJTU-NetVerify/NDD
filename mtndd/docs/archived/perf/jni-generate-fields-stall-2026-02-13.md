# JNI `generate_fields` Stall Investigation (2026-02-13)

## 1) Symptom
- JNI N-Queens (`org.ants.mtpndd.NQueensMTPNDD`) could hang even at `N=4`.
- Native benchmark (`sylvan/.../mtpndd_nqueens_test`) remained fast and correct.

## 2) Initial hypothesis and checks
- We aligned JNI flow to explicit field generation:
  - add `MTPNDDEngine.generateFields()`
  - call it right after all `declareField(...)`.
- Hang still reproduced, so the issue was not lazy-vs-eager generation strategy.

## 3) Narrowing down with runtime evidence
- Java thread dump (`kill -3`) repeatedly showed:
  - main thread RUNNABLE in `MTPNDDEngine.generateFieldsNative(...)`
  - native side stuck in `mtpndd_generate_fields()`.
- Isolation runner (`ManualNativeCheck declare-only`) reproduced:
  - `declareField` x4 completed
  - blocked at `generateFields`.

## 4) Root cause
- JNI links static libraries from `sylvan/build`:
  - `libmtpndd.a`, `libsylvan.a`.
- During prior iterations, JNI Java/test code was updated, but linked `sylvan/build` artifacts were stale relative to current branch state.
- Rebuilding only JNI shim was insufficient; rebuilding `sylvan/build` fixed the stall.

## 5) Fix and verification
- Rebuild core libs:
  - `cd sylvan && cmake -B build -DMTPNDD_LOG_LEVEL=2 && cmake --build build`
- Rebuild JNI shim + Java classes:
  - `cd jni && cmake -B build-log0 -DMTPNDD_LOG_LEVEL=0 && cmake --build build-log0`
  - `cd jni && mvn -DskipTests clean package`
- Post-fix results:
  - `NQueensMTPNDD 4 1`: solutions=`2`, total `0.017s`
  - `NQueensMTPNDD 12 1`: solutions=`14200`, total `20.497s`
  - `NQueensMTPNDD 12 4`: solutions=`14200`, total `9.534s`

## 6) Practical guidance
1. When debugging JNI runtime behavior, always rebuild `sylvan/build` first, then JNI.
2. Keep explicit `declare -> generateFields` in JNI path to match native benchmark semantics.
3. Treat inconsistent JNI behavior after core changes as potential link-artifact skew before deeper algorithmic debugging.
