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
    pndd_config c = {(uint32_t)workers, (uint32_t)threshold, (uint32_t)chunk, (uint32_t)depth, flags & 1, flags & 2, flags & 4, (size_t)memory, (size_t)cache, (uint32_t)gc, flags & 8};
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
JNIEXPORT jlongArray JNICALL FN(prioritySelectNative)(JNIEnv *env, jclass cls, jlong ep, jlong budget,
        jlongArray input, jintArray groupEnds, jint start, jint ecmp) {
    (void)cls; jsize count; uint64_t *values = copy(env, input, &count); if (!values) return NULL;
    jsize groups = (*env)->GetArrayLength(env, groupEnds);
    if (start < 0 || start > count) { free(values); error(env, engine(ep)); return NULL; }
    uint32_t *ends = malloc(groups ? (size_t)groups * sizeof(uint32_t) : 1);
    size_t results = (size_t)(count - start);
    pndd_handle *handles = malloc(results ? results * sizeof(pndd_handle) : 1);
    if (!ends || !handles) { free(values); free(ends); free(handles); oom(env); return NULL; }
    (*env)->GetIntArrayRegion(env, groupEnds, 0, groups, (jint *)ends);
    if ((*env)->ExceptionCheck(env)) { free(values); free(ends); free(handles); return NULL; }
    int ok = pndd_priority_select(engine(ep), (pndd_handle)budget, values, count, ends, groups, start, ecmp, handles);
    free(values); free(ends);
    if (!ok) { free(handles); error(env, engine(ep)); return NULL; }
    jlongArray out = (*env)->NewLongArray(env, (jsize)results);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, (jsize)results, (jlong *)handles);
    if ((*env)->ExceptionCheck(env)) for (size_t i = 0; i < results; ++i) pndd_release(engine(ep), handles[i]);
    free(handles); return out;
}
JNIEXPORT void JNICALL FN(collectNative)(JNIEnv *env, jclass cls, jlong e) { (void)cls; if (!pndd_collect(engine(e))) error(env, engine(e)); }
JNIEXPORT jlongArray JNICALL FN(priorityBatchNative)(JNIEnv *env, jclass cls, jlong ep,
        jlongArray budgetsArray, jlongArray inputArray, jintArray inputOffsets,
        jintArray groupEnds, jintArray groupOffsets, jintArray starts, jintArray flags) {
    (void)cls; jsize n, inputs;
    uint64_t *budgets = copy(env, budgetsArray, &n); if (!budgets) return NULL;
    uint64_t *input = copy(env, inputArray, &inputs); if (!input) { free(budgets); return NULL; }
    jsize groups = (*env)->GetArrayLength(env, groupEnds);
    jlongArray result = NULL; pndd_handle *out = NULL; size_t results = 0;
    pndd_priority_request *requests = NULL;
    jint *data = NULL;
    if (n == INT32_MAX || (*env)->GetArrayLength(env, inputOffsets) != n + 1 || (*env)->GetArrayLength(env, groupOffsets) != n + 1 ||
        (*env)->GetArrayLength(env, starts) != n || (*env)->GetArrayLength(env, flags) != n) goto malformed;
    if ((size_t)n > (SIZE_MAX / sizeof(jint) - (size_t)groups - 2) / 4) { oom(env); goto done; }
    data = malloc(((size_t)n * 4 + groups + 2) * sizeof(jint));
    requests = malloc(n ? (size_t)n * sizeof(*requests) : 1);
    if (!data || !requests) { oom(env); goto done; }
    jint *offset = data, *goffset = offset + n + 1, *start = goffset + n + 1;
    jint *option = start + n, *ends = option + n;
    (*env)->GetIntArrayRegion(env, inputOffsets, 0, n + 1, offset);
    (*env)->GetIntArrayRegion(env, groupOffsets, 0, n + 1, goffset);
    (*env)->GetIntArrayRegion(env, starts, 0, n, start);
    (*env)->GetIntArrayRegion(env, flags, 0, n, option);
    (*env)->GetIntArrayRegion(env, groupEnds, 0, groups, ends);
    if ((*env)->ExceptionCheck(env)) goto done;
    if (offset[0] || goffset[0] || offset[n] != inputs || goffset[n] != groups) goto malformed;
    for (jsize i = 0; i < n; ++i) {
        if (offset[i] < 0 || offset[i + 1] < offset[i] || offset[i + 1] > inputs ||
            goffset[i] < 0 || goffset[i + 1] < goffset[i] || goffset[i + 1] > groups ||
            start[i] < 0 || start[i] > offset[i + 1] - offset[i]) goto malformed;
        results += (size_t)(offset[i + 1] - offset[i] - start[i]);
    }
    if (results > INT32_MAX) goto malformed;
    out = malloc(results ? results * sizeof(pndd_handle) : 1);
    if (!out) { oom(env); goto done; }
    size_t pos = 0;
    for (jsize i = 0; i < n; ++i) {
        requests[i] = (pndd_priority_request){budgets[i], input + offset[i], (size_t)(offset[i + 1] - offset[i]),
            (const uint32_t *)(ends + goffset[i]), (size_t)(goffset[i + 1] - goffset[i]), (size_t)start[i], option[i], out + pos};
        pos += requests[i].count - requests[i].start;
    }
    if (!pndd_priority_batch(engine(ep), requests, (size_t)n)) { error(env, engine(ep)); goto done; }
    result = (*env)->NewLongArray(env, (jsize)results);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, (jsize)results, (jlong *)out);
    if ((*env)->ExceptionCheck(env)) for (size_t i = 0; i < results; ++i) pndd_release(engine(ep), out[i]);
    goto done;
malformed: {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (type) (*env)->ThrowNew(env, type, "Malformed priority batch");
}
done:
    free(out); free(requests); free(data); free(input); free(budgets); return result;
}
JNIEXPORT jlongArray JNICALL FN(statsNative)(JNIEnv *env, jclass cls, jlong e) {
    (void)cls; pndd_stats s = pndd_get_stats(engine(e));
    jlong values[] = {(jlong)s.nodes_live, (jlong)s.nodes_created, (jlong)s.labels_live, (jlong)s.labels_created, (jlong)s.tasks, (jlong)s.stolen_tasks, (jlong)s.cache_hits, (jlong)s.cache_misses, (jlong)s.collections, (jlong)s.external_runs, (jlong)s.merge_ns, (jlong)s.native_bytes, (jlong)s.peak_native_bytes, (jlong)s.label_collections, (jlong)s.build_ns, (jlong)s.reduce_ns, (jlong)s.count_ns};
    jlongArray array = (*env)->NewLongArray(env, 17); if (array) (*env)->SetLongArrayRegion(env, array, 0, 17, values); return array;
}
JNIEXPORT jlongArray JNICALL FN(snapshotNative)(JNIEnv *env, jclass cls, jlong ep, jlong a) {
    (void)cls; pndd_engine *e = engine(ep); uint32_t field; size_t count; pndd_handle *pairs;
    if (!pndd_snapshot(e, (uint64_t)a, &field, &count, &pairs)) { error(env, e); return NULL; }
    jlongArray out = (*env)->NewLongArray(env, (jsize)(count * 2 + 1));
    if (out) {
        jlong f = field; (*env)->SetLongArrayRegion(env, out, 0, 1, &f);
        if (count) (*env)->SetLongArrayRegion(env, out, 1, (jsize)(count * 2), (jlong *)pairs);
    }
    if ((*env)->ExceptionCheck(env)) for (size_t i = 0; i < count * 2; ++i) pndd_release(e, pairs[i]);
    free(pairs); return out;
}
JNIEXPORT jbooleanArray JNICALL FN(reachableNative)(JNIEnv *env, jclass cls, jlong ep, jlongArray rr, jlongArray cc) {
    (void)cls; jsize rn, cn; uint64_t *roots = copy(env, rr, &rn); if (!roots) return NULL;
    uint64_t *candidates = copy(env, cc, &cn); if (!candidates) { free(roots); return NULL; }
    unsigned char *mask = malloc(cn ? (size_t)cn : 1);
    if (!mask) { free(roots); free(candidates); oom(env); return NULL; }
    int ok = pndd_reachable(engine(ep), roots, rn, candidates, cn, mask);
    free(roots); free(candidates);
    jbooleanArray out = NULL;
    if (!ok) error(env, engine(ep));
    else { out = (*env)->NewBooleanArray(env, cn); if (out) (*env)->SetBooleanArrayRegion(env, out, 0, cn, (jboolean *)mask); }
    free(mask); return out;
}
JNIEXPORT jint JNICALL FN(minLabelCostNative)(JNIEnv *env, jclass cls, jlong ep, jlong a, jint field, jboolean costly) {
    (void)cls; int result = pndd_min_label_cost(engine(ep), (uint64_t)a, (uint32_t)field, costly);
    if (result < 0) error(env, engine(ep)); return result;
}
JNIEXPORT jobjectArray JNICALL FN(labelCubesNative)(JNIEnv *env, jclass cls, jlong ep, jlong a, jint field, jint width, jint limit) {
    (void)cls; size_t count; signed char *data;
    if (!pndd_label_cubes(engine(ep), (uint64_t)a, (uint32_t)field, (size_t)limit, &count, &data)) { error(env, engine(ep)); return NULL; }
    jclass bytes = (*env)->FindClass(env, "[B");
    jobjectArray out = bytes ? (*env)->NewObjectArray(env, (jsize)count, bytes, NULL) : NULL;
    if (out) for (size_t i = 0; i < count && !(*env)->ExceptionCheck(env); ++i) {
        jbyteArray cube = (*env)->NewByteArray(env, width);
        if (!cube) break;
        (*env)->SetByteArrayRegion(env, cube, 0, width, (jbyte *)(data + i * (size_t)width));
        if (!(*env)->ExceptionCheck(env)) (*env)->SetObjectArrayElement(env, out, (jsize)i, cube);
        (*env)->DeleteLocalRef(env, cube);
    }
    free(data); return out;
}
JNIEXPORT jlong JNICALL FN(fingerprintNative)(JNIEnv *env, jclass cls, jlong ep, jlong a) {
    (void)cls; uint64_t digest = 0;
    if (!pndd_fingerprint(engine(ep), (uint64_t)a, &digest)) error(env, engine(ep));
    return (jlong)digest;
}
