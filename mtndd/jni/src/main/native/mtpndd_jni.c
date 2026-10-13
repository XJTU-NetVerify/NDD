#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <limits.h>

#include "mtpndd.h"
#include "mtpndd_common.h"
#include "mtpndd_node.h"
#include "mtpndd_nodetable.h"
#include "mtpndd_leaf.h"
#include "mtpndd_leaf_table.h"
#include "mtpndd_arith.h"
#include "sylvan.h"
#include "sylvan_cache.h"
#include "sylvan_bdd.h"
#include "sylvan_mtbdd.h"

static void mtpndd_throw_exception(JNIEnv *env, const char *class_name, const char *message) {
    jclass ex_class = (*env)->FindClass(env, class_name);
    if (ex_class == NULL) {
        ex_class = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (ex_class == NULL) {
            (*env)->FatalError(env, "Unable to find exception class");
            return;
        }
    }
    (*env)->ThrowNew(env, ex_class, message ? message : "Unknown error");
}

static void mtpndd_throw_error(JNIEnv *env, mtpndd_error_t code) {
    const char *msg = mtpndd_error_string(code);
    mtpndd_throw_exception(env, "org/ants/mtpndd/MTPNDDException", msg);
}

static void mtpndd_throw_last_error(JNIEnv *env) {
    mtpndd_error_info_t info = mtpndd_get_last_error();
    const char *message = info.message ? info.message : mtpndd_error_string(info.code);
    char buffer[256];
    snprintf(buffer, sizeof(buffer), "%s (code=%d, at %s:%d)",
             message, (int)info.code,
             info.function ? info.function : "?",
             info.line);
    mtpndd_throw_exception(env, "org/ants/mtpndd/MTPNDDException", buffer);
}

static jlong mtpndd_ptr_to_jlong(const void *ptr) {
    return (jlong)(intptr_t)ptr;
}

static void *mtpndd_jlong_to_ptr(jlong value) {
    return (void *)(intptr_t)value;
}

static mtpndd_t *mtpndd_node_from_jlong(jlong value) {
    return (mtpndd_t *)mtpndd_jlong_to_ptr(value);
}

static void mtpndd_jni_log_init_request(
        jint nWorkers,
        jlong laceDQSize,
        jlong bddSize,
        jlong mtpnddSize,
        jlong opCacheSize,
        jdouble quickGrowth,
        jlong edgeBuckets,
        jlong nodeBuckets,
        jlong nodeSlabCap,
        jlong edgeEntrySlabCap,
        jlong nodetableEntrySlabCap,
        jlong edgeMapSlabCap)
{
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_INFO
    MTPNDD_LOG_INFO(
            "[MTPNDD JNI] init request workers=%d dq=%lld bdd_nodes=%lld mtpndd_nodes=%lld cache=%lld quick_growth=%.3f\n",
            (int)nWorkers,
            (long long)laceDQSize,
            (long long)bddSize,
            (long long)mtpnddSize,
            (long long)opCacheSize,
            (double)quickGrowth);
    MTPNDD_LOG_INFO(
            "[MTPNDD JNI] init request edge_buckets=%lld nodetable_buckets=%lld slabs(node/edge/nodetable/emap)=%lld/%lld/%lld/%lld\n",
            (long long)edgeBuckets,
            (long long)nodeBuckets,
            (long long)nodeSlabCap,
            (long long)edgeEntrySlabCap,
            (long long)nodetableEntrySlabCap,
            (long long)edgeMapSlabCap);
#endif
}

static void mtpndd_jni_log_init_effective(void) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_INFO
    MTPNDD_LOG_INFO(
            "[MTPNDD JNI] init effective workers=%d dq=%zu bdd_nodes=%zu mtpndd_nodes=%zu cache=%zu quick_growth=%.3f\n",
            (int)lace_workers(),
            g_mtpndd_pal_config.lace_dqsize,
            g_mtpndd_pal_config.bdd_nodetable_size,
            g_mtpndd_pal_config.mtpndd_nodetable_size,
            g_mtpndd_pal_config.op_cache_size,
            g_mtpndd_pal_config.quick_growth_threshold);
    MTPNDD_LOG_INFO(
            "[MTPNDD JNI] init effective edge_buckets=%zu nodetable_buckets=%zu slabs(node/edge/nodetable/emap)=%zu/%zu/%zu/%zu\n",
            g_mtpndd_pal_config.edge_bucket_count,
            g_mtpndd_pal_config.nodetable_bucket_count,
            g_mtpndd_pal_config.node_slab_capacity,
            g_mtpndd_pal_config.edge_entry_slab_capacity,
            g_mtpndd_pal_config.nodetable_entry_slab_capacity,
            g_mtpndd_pal_config.edge_map_slab_capacity);
#endif
}

static int mtpndd_min_zeros_rec(BDD node) {
    static const uint64_t cache_id = 0x4d54504e44445a30ULL;  // "MTPNDDZ0"
    if (node == sylvan_true) {
        return 0;
    }
    if (node == sylvan_false) {
        return INT_MAX / 4;
    }

    uint64_t cached = 0;
    if (cache_get3(cache_id, (uint64_t)node, 0, 0, &cached)) {
        return (int)cached;
    }

    BDD low = sylvan_low(node);
    BDD high = sylvan_high(node);
    int low_cost = mtpndd_min_zeros_rec(low);
    int high_cost = mtpndd_min_zeros_rec(high);
    int result = high_cost;
    if (low_cost < INT_MAX / 4 && (low_cost + 1) < result) {
        result = low_cost + 1;
    }

    cache_put3(cache_id, (uint64_t)node, 0, 0, (uint64_t)result);
    return result;
}

static bool mtpndd_require_fields_generated(JNIEnv *env) {
    if (g_mtpndd_config.fields_generated) {
        return true;
    }
    if (g_mtpndd_config.pending_field_count > 0) {
        mtpndd_throw_exception(
                env,
                "org/ants/mtpndd/MTPNDDException",
                "Fields declared but not generated. Call MTPNDDEngine.generateFields() after declareField().");
        return false;
    }
    mtpndd_throw_exception(
            env,
            "org/ants/mtpndd/MTPNDDException",
            "No fields declared. Call MTPNDDEngine.declareField() first.");
    return false;
}

JNIEXPORT void JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_initNative(JNIEnv *env, jclass clazz,
                                             jint nWorkers,
                                             jlong laceDQSize,
                                             jlong bddSize,
                                             jlong mtpnddSize,
                                             jlong opCacheSize,
                                             jdouble quickGrowth,
                                             jlong edgeBuckets,
                                             jlong nodeBuckets,
                                             jlong nodeSlabCap,
                                             jlong edgeEntrySlabCap,
                                             jlong nodetableEntrySlabCap,
                                             jlong edgeMapSlabCap)
{
    (void)clazz;
    mtpndd_jni_log_init_request(
            nWorkers, laceDQSize, bddSize, mtpnddSize, opCacheSize, quickGrowth,
            edgeBuckets, nodeBuckets, nodeSlabCap, edgeEntrySlabCap, nodetableEntrySlabCap, edgeMapSlabCap);
    mtpndd_pal_config_t config = {0};
    // -1 means "use native default" for quick growth threshold.
    config.quick_growth_threshold = -1.0;
    config.n_workers = (int32_t)nWorkers;
    config.lace_dqsize = (size_t)laceDQSize;
    config.bdd_nodetable_size = (size_t)bddSize;
    config.mtpndd_nodetable_size = (size_t)mtpnddSize;
    config.op_cache_size = (size_t)opCacheSize;
    if (quickGrowth >= 0.0) {
        config.quick_growth_threshold = quickGrowth;
    }
    if (edgeBuckets > 0) {
        config.edge_bucket_count = (size_t)edgeBuckets;
    }
    if (nodeBuckets > 0) {
        config.nodetable_bucket_count = (size_t)nodeBuckets;
    }
    if (nodeSlabCap > 0) {
        config.node_slab_capacity = (size_t)nodeSlabCap;
    }
    if (edgeEntrySlabCap > 0) {
        config.edge_entry_slab_capacity = (size_t)edgeEntrySlabCap;
    }
    if (nodetableEntrySlabCap > 0) {
        config.nodetable_entry_slab_capacity = (size_t)nodetableEntrySlabCap;
    }
    if (edgeMapSlabCap > 0) {
        config.edge_map_slab_capacity = (size_t)edgeMapSlabCap;
    }

    mtpndd_error_t err = mtpndd_init(&config);
    if (err != MTPNDD_SUCCESS) {
        mtpndd_throw_error(env, err);
        return;
    }
    mtpndd_jni_log_init_effective();
}

JNIEXPORT void JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_quitNative(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    mtpndd_error_t err = mtpndd_quit();
    if (err != MTPNDD_SUCCESS) {
        mtpndd_throw_error(env, err);
    }
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_isInitializedNative(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return mtpndd_is_initialized() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_declareFieldNative(JNIEnv *env, jclass clazz, jint bitWidth)
{
    (void)env;
    (void)clazz;
    mtpndd_error_t err = mtpndd_declare_field((uint32_t)bitWidth);
    if (err != MTPNDD_SUCCESS) {
        mtpndd_throw_last_error(env);
        return 0;
    }
    return (jint)g_mtpndd_config.pending_field_count;
}

JNIEXPORT void JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_generateFieldsNative(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    mtpndd_error_t err = mtpndd_generate_fields();
    if (err != MTPNDD_SUCCESS) {
        mtpndd_throw_last_error(env);
    }
}

JNIEXPORT jobject JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getFieldInfoNative(JNIEnv *env, jclass clazz, jint fieldId)
{
    (void)clazz;
    if (!mtpndd_require_fields_generated(env)) {
        return NULL;
    }
    mtpndd_field_info_t *info = mtpndd_get_field_info((uint32_t)fieldId);
    if (!info) {
        mtpndd_throw_last_error(env);
        return NULL;
    }
    jclass infoClass = (*env)->FindClass(env, "org/ants/mtpndd/MTPNDDFieldInfo");
    if (infoClass == NULL) {
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, infoClass, "<init>", "(III)V");
    if (ctor == NULL) {
        return NULL;
    }
    return (*env)->NewObject(env, infoClass, ctor,
            (jint)info->field_id,
            (jint)info->bit_width,
            (jint)info->start_var);
}

static jlong mtpndd_wrap_node(JNIEnv *env, mtpndd_t *node) {
    if (!node) {
        mtpndd_throw_last_error(env);
        return 0;
    }
    return mtpndd_ptr_to_jlong(node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getVarNative(JNIEnv *env, jclass clazz, jint fieldId, jint index)
{
    (void)clazz;
    if (!mtpndd_require_fields_generated(env)) {
        return 0;
    }
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_get_var((uint32_t)fieldId, (uint32_t)index);
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getNotVarNative(JNIEnv *env, jclass clazz, jint fieldId, jint index)
{
    (void)clazz;
    if (!mtpndd_require_fields_generated(env)) {
        return 0;
    }
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_get_not_var((uint32_t)fieldId, (uint32_t)index);
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jint JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getFieldIdNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    mtpndd_t *node = mtpndd_node_from_jlong(handle);
    if (!node) {
        return 0;
    }
    return (jint)node->field_id;
}

JNIEXPORT jlongArray JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getEdgesNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    mtpndd_t *node = mtpndd_node_from_jlong(handle);
    if (!node || mtpndd_is_terminal(node) || node->edges == NULL || node->edges->edge_count == 0) {
        return (*env)->NewLongArray(env, 0);
    }

    size_t edge_count = node->edges->edge_count;
    jsize length = (jsize)(edge_count * 2);
    jlongArray result = (*env)->NewLongArray(env, length);
    if (result == NULL) {
        return NULL;
    }

    jlong *buffer = (jlong *)malloc(sizeof(jlong) * (size_t)length);
    if (!buffer) {
        mtpndd_throw_exception(env, "java/lang/OutOfMemoryError", "Unable to allocate edge buffer");
        return NULL;
    }

    size_t idx = 0;
    edge_bucket_entry_t *entry = NULL;
    FOR_EACH_ENTRY_IN_ALL_BUCKETS(node->edges, entry) {
        if (idx + 1 >= (size_t)length) {
            break;
        }
        mtpndd_bdd_t label = atomic_load_explicit(&entry->label, memory_order_relaxed);
        buffer[idx++] = mtpndd_ptr_to_jlong(entry->child);
        buffer[idx++] = (jlong)label;
    }

    (*env)->SetLongArrayRegion(env, result, 0, (jsize)idx, buffer);
    free(buffer);
    return result;
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_andNative(JNIEnv *env, jclass clazz, jlong left, jlong right)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_and(mtpndd_node_from_jlong(left), mtpndd_node_from_jlong(right));
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_orNative(JNIEnv *env, jclass clazz, jlong left, jlong right)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_or(mtpndd_node_from_jlong(left), mtpndd_node_from_jlong(right));
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_notNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_not(mtpndd_node_from_jlong(handle));
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_diffNative(JNIEnv *env, jclass clazz, jlong left, jlong right)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_diff(mtpndd_node_from_jlong(left), mtpndd_node_from_jlong(right));
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT void JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_refNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    if (handle == 0) {
        mtpndd_throw_exception(env, "org/ants/mtpndd/MTPNDDException", "Cannot ref null handle");
        return;
    }
    mtpndd_error_t err = mtpndd_ref(mtpndd_node_from_jlong(handle));
    if (err != MTPNDD_SUCCESS) {
        mtpndd_throw_error(env, err);
    }
}

JNIEXPORT void JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_derefNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    if (handle == 0) {
        mtpndd_throw_exception(env, "org/ants/mtpndd/MTPNDDException", "Cannot deref null handle");
        return;
    }
    mtpndd_error_t err = mtpndd_deref(mtpndd_node_from_jlong(handle));
    if (err != MTPNDD_SUCCESS) {
        mtpndd_throw_error(env, err);
    }
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_existNative(JNIEnv *env, jclass clazz, jlong handle, jint fieldId)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_exist(mtpndd_node_from_jlong(handle), (uint32_t)fieldId);
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jdouble JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_satCountNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    mtpndd_clear_error();
    double result = mtpndd_satcount(mtpndd_node_from_jlong(handle));
    mtpndd_error_info_t info = mtpndd_get_last_error();
    if (info.code != MTPNDD_SUCCESS) {
        mtpndd_throw_last_error(env);
        return 0.0;
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_minZerosNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    mtpndd_t *node = mtpndd_node_from_jlong(handle);
    if (!node) {
        return 0;
    }
    mtpndd_bdd_t bdd = sylvan_false;
    mtpndd_error_t err = mtpndd_to_mtbdd(node, &bdd);
    if (err != MTPNDD_SUCCESS) {
        mtpndd_throw_error(env, err);
        return 0;
    }
    int result = mtpndd_min_zeros_rec((BDD)bdd);
    sylvan_deref((BDD)bdd);
    return (jint)result;
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getBddVarNative(JNIEnv *env, jclass clazz, jint fieldId, jint index)
{
    (void)clazz;
    if (!mtpndd_require_fields_generated(env)) {
        return 0;
    }
    mtpndd_clear_error();
    mtpndd_bdd_t value = mtpndd_get_bdd_var((uint32_t)fieldId, (uint32_t)index);
    mtpndd_error_info_t info = mtpndd_get_last_error();
    if (info.code != MTPNDD_SUCCESS) {
        mtpndd_throw_last_error(env);
        return 0;
    }
    sylvan_ref(value);
    return (jlong)value;
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getBddNotVarNative(JNIEnv *env, jclass clazz, jint fieldId, jint index)
{
    (void)clazz;
    if (!mtpndd_require_fields_generated(env)) {
        return 0;
    }
    mtpndd_clear_error();
    mtpndd_bdd_t value = mtpndd_get_bdd_not_var((uint32_t)fieldId, (uint32_t)index);
    mtpndd_error_info_t info = mtpndd_get_last_error();
    if (info.code != MTPNDD_SUCCESS) {
        mtpndd_throw_last_error(env);
        return 0;
    }
    sylvan_ref(value);
    return (jlong)value;
}

JNIEXPORT jlong JNICALL Java_org_ants_mtpndd_MTPNDDEngine_bddTrueNative(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    sylvan_ref(sylvan_true);
    return (jlong)sylvan_true;
}

JNIEXPORT jlong JNICALL Java_org_ants_mtpndd_MTPNDDEngine_bddFalseNative(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    sylvan_ref(sylvan_false);
    return (jlong)sylvan_false;
}

JNIEXPORT jlong JNICALL Java_org_ants_mtpndd_MTPNDDEngine_bddRefNative(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    if (handle != 0) {
        sylvan_ref((BDD)handle);
    }
    return handle;
}

JNIEXPORT void JNICALL Java_org_ants_mtpndd_MTPNDDEngine_bddDerefNative(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    if (handle != 0) {
        sylvan_deref((BDD)handle);
    }
}

JNIEXPORT jlong JNICALL Java_org_ants_mtpndd_MTPNDDEngine_bddAndNative(JNIEnv *env, jclass clazz, jlong left, jlong right) {
    (void)env;
    (void)clazz;
    BDD result = sylvan_and((BDD)left, (BDD)right);
    sylvan_ref(result);
    return (jlong)result;
}

JNIEXPORT jlong JNICALL Java_org_ants_mtpndd_MTPNDDEngine_bddOrNative(JNIEnv *env, jclass clazz, jlong left, jlong right) {
    (void)env;
    (void)clazz;
    BDD result = sylvan_or((BDD)left, (BDD)right);
    sylvan_ref(result);
    return (jlong)result;
}

JNIEXPORT jlong JNICALL Java_org_ants_mtpndd_MTPNDDEngine_bddNotNative(JNIEnv *env, jclass clazz, jlong value) {
    (void)env;
    (void)clazz;
    BDD result = sylvan_not((BDD)value);
    sylvan_ref(result);
    return (jlong)result;
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_bddIsTrueNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    return ((BDD)handle == sylvan_true) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_bddIsFalseNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    return ((BDD)handle == sylvan_false) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_bddVarNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    if (handle == 0) {
        mtpndd_throw_exception(env, "org/ants/mtpndd/MTPNDDException", "Null BDD handle");
        return -1;
    }
    BDD bdd = (BDD)handle;
    if (bdd == sylvan_true || bdd == sylvan_false) {
        return -1;
    }
    return (jint)sylvan_var(bdd);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_bddLowNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    if (handle == 0) {
        mtpndd_throw_exception(env, "org/ants/mtpndd/MTPNDDException", "Null BDD handle");
        return 0;
    }
    BDD bdd = (BDD)handle;
    if (bdd == sylvan_true || bdd == sylvan_false) {
        mtpndd_throw_exception(env, "org/ants/mtpndd/MTPNDDException", "bddLow is undefined on terminal BDD");
        return 0;
    }
    return (jlong)sylvan_low(bdd);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_bddHighNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    if (handle == 0) {
        mtpndd_throw_exception(env, "org/ants/mtpndd/MTPNDDException", "Null BDD handle");
        return 0;
    }
    BDD bdd = (BDD)handle;
    if (bdd == sylvan_true || bdd == sylvan_false) {
        mtpndd_throw_exception(env, "org/ants/mtpndd/MTPNDDException", "bddHigh is undefined on terminal BDD");
        return 0;
    }
    return (jlong)sylvan_high(bdd);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_fromMtbddNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    mtpndd_t *node = NULL;
    mtpndd_error_t err = mtbdd_to_mtpndd((mtpndd_bdd_t)handle, &node);
    if (err != MTPNDD_SUCCESS) {
        mtpndd_throw_last_error(env);
        return 0;
    }
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_terminalTrueNative(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return mtpndd_ptr_to_jlong(&MTPNDD_TRUE);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_terminalFalseNative(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return mtpndd_ptr_to_jlong(&MTPNDD_FALSE);
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_isTrueNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    return mtpndd_is_true(mtpndd_node_from_jlong(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_isFalseNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    return mtpndd_is_false(mtpndd_node_from_jlong(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_isTerminalNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    return mtpndd_is_terminal(mtpndd_node_from_jlong(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_andBatchNative(JNIEnv *env, jclass clazz,
    jlongArray lefts, jlongArray rights)
{
    (void)clazz;
    jsize len = (*env)->GetArrayLength(env, lefts);
    jsize rlen = (*env)->GetArrayLength(env, rights);
    if (len != rlen) {
        mtpndd_throw_exception(env, "java/lang/IllegalArgumentException",
                "andBatch: lefts and rights arrays must have same length");
        return NULL;
    }
    if (len == 0) {
        return (*env)->NewLongArray(env, 0);
    }

    jlong *left_ptrs = (*env)->GetLongArrayElements(env, lefts, NULL);
    jlong *right_ptrs = (*env)->GetLongArrayElements(env, rights, NULL);
    if (!left_ptrs || !right_ptrs) {
        if (left_ptrs) (*env)->ReleaseLongArrayElements(env, lefts, left_ptrs, JNI_ABORT);
        if (right_ptrs) (*env)->ReleaseLongArrayElements(env, rights, right_ptrs, JNI_ABORT);
        mtpndd_throw_exception(env, "java/lang/OutOfMemoryError", "andBatch: failed to get array elements");
        return NULL;
    }

    // Convert jlong arrays to mtpndd_t** arrays
    mtpndd_t **left_nodes = (mtpndd_t **)malloc((size_t)len * sizeof(mtpndd_t *));
    mtpndd_t **right_nodes = (mtpndd_t **)malloc((size_t)len * sizeof(mtpndd_t *));
    if (!left_nodes || !right_nodes) {
        free(left_nodes);
        free(right_nodes);
        (*env)->ReleaseLongArrayElements(env, lefts, left_ptrs, JNI_ABORT);
        (*env)->ReleaseLongArrayElements(env, rights, right_ptrs, JNI_ABORT);
        mtpndd_throw_exception(env, "java/lang/OutOfMemoryError", "andBatch: allocation failed");
        return NULL;
    }

    for (jsize i = 0; i < len; i++) {
        left_nodes[i] = mtpndd_node_from_jlong(left_ptrs[i]);
        right_nodes[i] = mtpndd_node_from_jlong(right_ptrs[i]);
    }

    (*env)->ReleaseLongArrayElements(env, lefts, left_ptrs, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, rights, right_ptrs, JNI_ABORT);

    mtpndd_clear_error();
    mtpndd_t **results = mtpndd_and_batch(left_nodes, right_nodes, (size_t)len);
    free(left_nodes);
    free(right_nodes);

    if (!results) {
        mtpndd_throw_last_error(env);
        return NULL;
    }

    // Convert results back to jlong array (results already have +1 refcount)
    jlongArray result_arr = (*env)->NewLongArray(env, len);
    if (!result_arr) {
        // Free results and deref
        for (jsize i = 0; i < len; i++) {
            if (results[i]) mtpndd_deref(results[i]);
        }
        free(results);
        return NULL;
    }

    jlong *result_buf = (jlong *)malloc((size_t)len * sizeof(jlong));
    if (!result_buf) {
        for (jsize i = 0; i < len; i++) {
            if (results[i]) mtpndd_deref(results[i]);
        }
        free(results);
        mtpndd_throw_exception(env, "java/lang/OutOfMemoryError", "andBatch: result allocation failed");
        return NULL;
    }

    for (jsize i = 0; i < len; i++) {
        result_buf[i] = mtpndd_ptr_to_jlong(results[i]);
    }
    (*env)->SetLongArrayRegion(env, result_arr, 0, len, result_buf);
    free(result_buf);
    free(results);
    return result_arr;
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_orReduceNative(JNIEnv *env, jclass clazz,
    jlongArray values)
{
    (void)clazz;
    jsize len = (*env)->GetArrayLength(env, values);
    if (len == 0) {
        return mtpndd_ptr_to_jlong(&MTPNDD_FALSE);
    }

    jlong *ptrs = (*env)->GetLongArrayElements(env, values, NULL);
    if (!ptrs) {
        mtpndd_throw_exception(env, "java/lang/OutOfMemoryError", "orReduce: failed to get array elements");
        return 0;
    }

    mtpndd_t **nodes = (mtpndd_t **)malloc((size_t)len * sizeof(mtpndd_t *));
    if (!nodes) {
        (*env)->ReleaseLongArrayElements(env, values, ptrs, JNI_ABORT);
        mtpndd_throw_exception(env, "java/lang/OutOfMemoryError", "orReduce: allocation failed");
        return 0;
    }

    for (jsize i = 0; i < len; i++) {
        nodes[i] = mtpndd_node_from_jlong(ptrs[i]);
    }
    (*env)->ReleaseLongArrayElements(env, values, ptrs, JNI_ABORT);

    mtpndd_clear_error();
    mtpndd_t *result = mtpndd_or_reduce(nodes, (size_t)len);
    free(nodes);

    return mtpndd_wrap_node(env, result);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_andReduceNative(JNIEnv *env, jclass clazz,
    jlongArray values)
{
    (void)clazz;
    jsize len = (*env)->GetArrayLength(env, values);
    if (len == 0) {
        return mtpndd_ptr_to_jlong(&MTPNDD_TRUE);
    }

    jlong *ptrs = (*env)->GetLongArrayElements(env, values, NULL);
    if (!ptrs) {
        mtpndd_throw_exception(env, "java/lang/OutOfMemoryError", "andReduce: failed to get array elements");
        return 0;
    }

    mtpndd_t **nodes = (mtpndd_t **)malloc((size_t)len * sizeof(mtpndd_t *));
    if (!nodes) {
        (*env)->ReleaseLongArrayElements(env, values, ptrs, JNI_ABORT);
        mtpndd_throw_exception(env, "java/lang/OutOfMemoryError", "andReduce: allocation failed");
        return 0;
    }

    for (jsize i = 0; i < len; i++) {
        nodes[i] = mtpndd_node_from_jlong(ptrs[i]);
    }
    (*env)->ReleaseLongArrayElements(env, values, ptrs, JNI_ABORT);

    mtpndd_clear_error();
    mtpndd_t *result = mtpndd_and_reduce(nodes, (size_t)len);
    free(nodes);

    return mtpndd_wrap_node(env, result);
}

JNIEXPORT jobject JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getStatsNative(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    mtpndd_stats_t *stats = mtpndd_get_stats();
    if (!stats) {
        return NULL;
    }
    jclass statsClass = (*env)->FindClass(env, "org/ants/mtpndd/MTPNDDStats");
    if (statsClass == NULL) {
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, statsClass, "<init>", "(JZ[J)V");
    if (ctor == NULL) {
        return NULL;
    }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    jlong values[] = {
        (jlong)stats->max_edges_per_node,
        (jlong)stats->bdd_nodes_converted,
        (jlong)stats->cache_lookup_hits,
        (jlong)stats->cache_lookup_misses,
        (jlong)stats->gc_runs,
        (jlong)stats->nodes_created_total,
        (jlong)stats->nodes_reused_total,
        (jlong)stats->nodes_collected_last,
        (jlong)stats->edge_insert_total,
        (jlong)stats->edge_collision_total,
        (jlong)stats->nodetable_collision_total,
        (jlong)stats->edge_lock_spin_total,
        (jlong)stats->edge_lock_wait_time_ns,
        (jlong)stats->gc_pause_time_ns,
        (jlong)stats->edge_entry_total,
        (jlong)stats->bdd_nodes_processed_total,
        (jlong)stats->node_pool_acquire_total,
        (jlong)stats->node_pool_release_total,
        (jlong)stats->node_pool_slab_total,
        (jlong)stats->edge_entry_pool_acquire_total,
        (jlong)stats->edge_entry_pool_release_total,
        (jlong)stats->edge_entry_pool_slab_total,
        (jlong)stats->nodetable_entry_pool_acquire_total,
        (jlong)stats->nodetable_entry_pool_release_total,
        (jlong)stats->nodetable_entry_pool_slab_total,
        (jlong)stats->edge_map_pool_acquire_total,
        (jlong)stats->edge_map_pool_release_total,
        (jlong)stats->edge_map_pool_slab_total,
        (jlong)stats->and_call_total,
        (jlong)stats->or_call_total,
        (jlong)stats->not_call_total,
        (jlong)stats->diff_call_total,
        (jlong)stats->and_call_wall_ns,
        (jlong)stats->or_call_wall_ns,
        (jlong)stats->not_call_wall_ns,
        (jlong)stats->diff_call_wall_ns,
        (jlong)stats->and_time_ns,
        (jlong)stats->or_time_ns,
        (jlong)stats->not_time_ns,
        (jlong)stats->and_spawn_total,
        (jlong)stats->and_pending_flush_total
    };
    jsize len = (jsize)(sizeof(values) / sizeof(values[0]));
#else
    jsize len = 0;
#endif
    jlongArray arr = (*env)->NewLongArray(env, len);
    if (arr == NULL) {
        return NULL;
    }
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    (*env)->SetLongArrayRegion(env, arr, 0, len, values);
#endif
    jobject result = (*env)->NewObject(env, statsClass, ctor,
            (jlong)__atomic_load_n(&g_mtpndd_node_count, __ATOMIC_RELAXED),
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
            JNI_TRUE,
#else
            JNI_FALSE,
#endif
            arr);
    return result;
}

/********************************
 * MT (fraction / double leaf) bindings
 ********************************/

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_makeFractionNative(JNIEnv *env, jclass clazz, jint numer, jint denom)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_make_fraction((int32_t)numer, (int32_t)denom);
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_makeDoubleNative(JNIEnv *env, jclass clazz, jdouble value)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_make_double((double)value);
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_isLeafNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    return mtpndd_is_leaf(mtpndd_node_from_jlong(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_isFractionLeafNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    return mtpndd_is_fraction_leaf(mtpndd_node_from_jlong(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_isDoubleLeafNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    return mtpndd_is_double_leaf(mtpndd_node_from_jlong(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getNumerNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    return (jint)mtpndd_get_numer(mtpndd_node_from_jlong(handle));
}

JNIEXPORT jint JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getDenomNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    return (jint)mtpndd_get_denom(mtpndd_node_from_jlong(handle));
}

JNIEXPORT jdouble JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_getDoubleLeafNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    return (jdouble)mtpndd_get_double(mtpndd_node_from_jlong(handle));
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_plusNative(JNIEnv *env, jclass clazz, jlong left, jlong right)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_plus(mtpndd_node_from_jlong(left), mtpndd_node_from_jlong(right));
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_minusNative(JNIEnv *env, jclass clazz, jlong left, jlong right)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_minus(mtpndd_node_from_jlong(left), mtpndd_node_from_jlong(right));
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_timesNative(JNIEnv *env, jclass clazz, jlong left, jlong right)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_times(mtpndd_node_from_jlong(left), mtpndd_node_from_jlong(right));
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_divideNative(JNIEnv *env, jclass clazz, jlong left, jlong right)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_divide(mtpndd_node_from_jlong(left), mtpndd_node_from_jlong(right));
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_leafCountNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    return (jlong)mtpndd_leafcount(mtpndd_node_from_jlong(handle));
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_abstractPlusNative(JNIEnv *env, jclass clazz, jlong handle, jint fieldId)
{
    (void)clazz;
    mtpndd_clear_error();
    mtpndd_t *node = mtpndd_abstract_plus(mtpndd_node_from_jlong(handle), (uint32_t)fieldId);
    return mtpndd_wrap_node(env, node);
}

JNIEXPORT jboolean JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_abstractPlusValidateNative(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    mtpndd_clear_error();
    return mtpndd_abstract_plus_validate(mtpndd_node_from_jlong(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_org_ants_mtpndd_MTPNDDEngine_leafGcNative(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jlong)mtpndd_leaf_gc();
}
