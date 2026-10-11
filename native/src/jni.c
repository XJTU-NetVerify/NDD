#include "pndd.h"
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#define FN(name) Java_org_ants_jndd_parallel_ParallelNDD_##name
static pndd_engine *engine(jlong pointer) { return (pndd_engine *)(uintptr_t)pointer; }
static void error(JNIEnv *env, pndd_engine *e) {
    const char *message = pndd_error(e);
    const char *type = strstr(message, "memory") || strstr(message, "exhausted") ? "java/lang/OutOfMemoryError" : "java/lang/IllegalStateException";
    jclass cls = (*env)->FindClass(env, type); if (cls) (*env)->ThrowNew(env, cls, message);
}
static void oom(JNIEnv *env) { jclass cls = (*env)->FindClass(env, "java/lang/OutOfMemoryError"); if (cls) (*env)->ThrowNew(env, cls, "JNI array allocation failed"); }
static jlong checked(JNIEnv *env, pndd_engine *e, pndd_handle h) { if (h == PNDD_INVALID) error(env, e); return (jlong)h; }
JNIEXPORT jlong JNICALL FN(openNative)(JNIEnv *env, jclass cls, jintArray widths, jintArray backends, jint workers, jlong memory, jint cache, jint threshold, jint chunk, jint depth, jint flags, jint gc) {
    (void)cls; jsize count = (*env)->GetArrayLength(env, widths);
    if (count < 1 || count > 256 || (*env)->GetArrayLength(env, backends) != count) return 0;
    jint w[256], b[256]; pndd_field fields[256];
    (*env)->GetIntArrayRegion(env, widths, 0, count, w); (*env)->GetIntArrayRegion(env, backends, 0, count, b);
    if ((*env)->ExceptionCheck(env)) return 0;
    for (jsize i = 0; i < count; ++i) fields[i] = (pndd_field){(uint32_t)w[i], (pndd_backend)b[i]};
    pndd_config c = {(uint32_t)workers, (uint32_t)threshold, (uint32_t)chunk, (uint32_t)depth, flags & 1, flags & 2, flags & 4, (size_t)memory, (size_t)cache, (uint32_t)gc};
    pndd_engine *e = pndd_open(&c, fields, (size_t)count); if (!e) error(env, NULL); return (jlong)(uintptr_t)e;
}
JNIEXPORT void JNICALL FN(closeNative)(JNIEnv *env, jclass cls, jlong e) { (void)env; (void)cls; pndd_close(engine(e)); }
JNIEXPORT jlong JNICALL FN(constantNative)(JNIEnv *env, jclass cls, jlong e, jboolean value) { (void)cls; return checked(env, engine(e), pndd_constant(engine(e), value)); }
JNIEXPORT jlong JNICALL FN(literalNative)(JNIEnv *env, jclass cls, jlong e, jint f, jint bit, jboolean positive) { (void)cls; return checked(env, engine(e), pndd_literal(engine(e), f, bit, positive)); }
JNIEXPORT jlong JNICALL FN(valueNative)(JNIEnv *env, jclass cls, jlong e, jint f, jlong value) { (void)cls; return checked(env, engine(e), pndd_value(engine(e), f, (uint64_t)value)); }
JNIEXPORT jlong JNICALL FN(applyNative)(JNIEnv *env, jclass cls, jlong e, jint op, jlong a, jlong b) { (void)cls; return checked(env, engine(e), pndd_apply(engine(e), op, (uint64_t)a, (uint64_t)b)); }
JNIEXPORT jlong JNICALL FN(notNative)(JNIEnv *env, jclass cls, jlong e, jlong a) { (void)cls; return checked(env, engine(e), pndd_not(engine(e), (uint64_t)a)); }
JNIEXPORT jlong JNICALL FN(existNative)(JNIEnv *env, jclass cls, jlong e, jlong a, jint f) { (void)cls; return checked(env, engine(e), pndd_exist(engine(e), (uint64_t)a, f)); }
JNIEXPORT jlong JNICALL FN(retainNative)(JNIEnv *env, jclass cls, jlong e, jlong a) { (void)cls; return checked(env, engine(e), pndd_retain(engine(e), (uint64_t)a)); }
JNIEXPORT void JNICALL FN(releaseNative)(JNIEnv *env, jclass cls, jlong e, jlong a) { (void)cls; if (!pndd_release(engine(e), (uint64_t)a)) error(env, engine(e)); }
JNIEXPORT jdouble JNICALL FN(countNative)(JNIEnv *env, jclass cls, jlong e, jlong a) { (void)cls; double result = pndd_satcount(engine(e), (uint64_t)a); if (isnan(result)) error(env, engine(e)); return result; }
static uint64_t *copy(JNIEnv *env, jlongArray array, jsize *count) {
    *count = (*env)->GetArrayLength(env, array);
    uint64_t *values = malloc(*count ? (size_t)*count * sizeof(uint64_t) : 1);
    if (!values) { oom(env); return NULL; }
    (*env)->GetLongArrayRegion(env, array, 0, *count, (jlong *)values);
    if ((*env)->ExceptionCheck(env)) { free(values); return NULL; } return values;
}
JNIEXPORT jboolean JNICALL FN(evaluateNative)(JNIEnv *env, jclass cls, jlong e, jlong a, jlongArray array) {
    (void)cls; jsize count; uint64_t *values = copy(env, array, &count); if (!values) return 0;
    int result = pndd_evaluate(engine(e), (uint64_t)a, values, (size_t)count); free(values);
    if (result < 0) error(env, engine(e)); return result > 0;
}
JNIEXPORT jlongArray JNICALL FN(batchNative)(JNIEnv *env, jclass cls, jlong e, jint op, jlongArray aa, jlongArray bb) {
    (void)cls; jsize count, other; uint64_t *a = copy(env, aa, &count);
    if (!a) return NULL;
    uint64_t *b = copy(env, bb, &other);
    if (!b) { free(a); return NULL; }
    uint64_t *out = malloc(count ? (size_t)count * sizeof(uint64_t) : 1);
    if (!out) { free(a); free(b); oom(env); return NULL; }
    if (count != other || !pndd_apply_batch(engine(e), op, a, b, count, out)) { free(a); free(b); free(out); error(env, engine(e)); return NULL; }
    free(a); free(b); jlongArray result = (*env)->NewLongArray(env, count);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, count, (jlong *)out);
    if ((*env)->ExceptionCheck(env)) for (jsize i = 0; i < count; ++i) pndd_release(engine(e), out[i]);
    free(out); return result;
}
JNIEXPORT jlong JNICALL FN(reduceNative)(JNIEnv *env, jclass cls, jlong e, jint op, jlongArray array, jboolean tree) {
    (void)cls; jsize count; uint64_t *values = copy(env, array, &count); if (!values) return -1;
    pndd_handle result = pndd_reduce(engine(e), op, values, count, tree); free(values); return checked(env, engine(e), result);
}
JNIEXPORT void JNICALL FN(collectNative)(JNIEnv *env, jclass cls, jlong e) { (void)cls; if (!pndd_collect(engine(e))) error(env, engine(e)); }
JNIEXPORT jlongArray JNICALL FN(statsNative)(JNIEnv *env, jclass cls, jlong e) {
    (void)cls; pndd_stats s = pndd_get_stats(engine(e));
    jlong values[] = {(jlong)s.nodes_live, (jlong)s.nodes_created, (jlong)s.labels_live, (jlong)s.labels_created, (jlong)s.tasks, (jlong)s.stolen_tasks, (jlong)s.cache_hits, (jlong)s.cache_misses, (jlong)s.collections, (jlong)s.external_runs, (jlong)s.merge_ns, (jlong)s.native_bytes, (jlong)s.peak_native_bytes, (jlong)s.label_collections, (jlong)s.build_ns, (jlong)s.reduce_ns, (jlong)s.count_ns};
    jlongArray array = (*env)->NewLongArray(env, 17); if (array) (*env)->SetLongArrayRegion(env, array, 0, 17, values); return array;
}
