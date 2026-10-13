// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#ifndef MTPNDD_COMMON_H
#define MTPNDD_COMMON_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <stdio.h>

#ifndef MTPNDD_LOG_LEVEL
#define MTPNDD_LOG_LEVEL 1
#endif

#define MTPNDD_LOG_LEVEL_QUIET 0
#define MTPNDD_LOG_LEVEL_INFO 1
#define MTPNDD_LOG_LEVEL_DEBUG 2

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
#include <time.h>
#endif

#define MTPNDD_LOG_ERROR(...) do { fprintf(stderr, __VA_ARGS__); fflush(stderr); } while (0)

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_INFO
#define MTPNDD_LOG_INFO(...) do { fprintf(stdout, __VA_ARGS__); fflush(stdout); } while (0)
#else
#define MTPNDD_LOG_INFO(...) ((void)0)
#endif

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
#define MTPNDD_LOG_DEBUG(...) do { fprintf(stdout, __VA_ARGS__); fflush(stdout); } while (0)
#else
#define MTPNDD_LOG_DEBUG(...) ((void)0)
#endif

struct mtpndd_node_s;
struct mtpndd_edge_s;
struct mtpndd_nodetable_s;
struct mtpndd_op_cache_s;
struct mtpndd_leaf_table_s;

typedef uint64_t mtpndd_bdd_t;
typedef struct mtpndd_node_s mtpndd_node_t;
typedef mtpndd_node_t mtpndd_t;
typedef struct mtpndd_edge_s mtpndd_edge_t;
typedef struct mtpndd_nodetable_s mtpndd_nodetable_t;
typedef struct mtpndd_op_cache_s mtpndd_op_cache_t;
typedef struct mtpndd_leaf_table_s mtpndd_leaf_table_t;

/* mtpndd_hash_node_identity is defined inline in mtpndd_node.h (needs
 * the struct definition). Callers should include mtpndd_node.h. */

static inline size_t mtpndd_hash_u64(uint64_t key) {
    uint64_t value = key;
    value ^= value >> 33;
    value *= 0xff51afd7ed558ccdULL;
    value ^= value >> 33;
    value *= 0xc4ceb9fe1a85ec53ULL;
    value ^= value >> 33;
    return (size_t)value;
}

static inline size_t mtpndd_round_up_pow2(size_t v) {
    if (v == 0) return 1;
    v--;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
    if (sizeof(size_t) == 8) {
        v |= v >> 32;
    }
    v++;
    return v;
}

/********************************
 * Error handling system
 ********************************/
typedef enum mtpndd_error_e {
    MTPNDD_SUCCESS = 0,             // Success
    MTPNDD_ERROR_INVALID_PARAM,     // Invalid parameter
    MTPNDD_ERROR_INITIALIZE_FAILED, // Initialization failed
    MTPNDD_ERROR_NOT_INITIALIZED,   // Not initialized
    MTPNDD_ERROR_IS_INITIALIZED,    // Already initialized
    MTPNDD_ERROR_OUT_OF_MEMORY,     // Out of memory
    MTPNDD_ERROR_INVALID_FIELD,     // Invalid field
    MTPNDD_ERROR_NULL_POINTER,      // Null pointer
    MTPNDD_ERROR_PARALLEL_INIT,     // Parallel initialization failed
    MTPNDD_ERROR_BDD_OPERATION,     // BDD operation failed

    MTPNDD_ERROR_UNKNOWN            // Unknown error
} mtpndd_error_t;

extern const char* mtpndd_error_messages[];

typedef struct mtpndd_error_info_s {
    mtpndd_error_t code;
    const char *message;
    const char *function;
    int line;
} mtpndd_error_info_t;

const char* mtpndd_error_string(mtpndd_error_t error);
void mtpndd_set_error(mtpndd_error_t error, const char *function, int line);
mtpndd_error_info_t mtpndd_get_last_error();
void mtpndd_clear_error();

#define MTPNDD_SET_ERROR(error) mtpndd_set_error(error, __FUNCTION__, __LINE__)
#define MTPNDD_RETURN_ERROR(error) do { MTPNDD_SET_ERROR(error); return error; } while(0)

#define MTPNDD_CHECK_PARAM(condition, error) \
    do { if (!(condition)) { MTPNDD_RETURN_ERROR(error); } } while(0)
#define MTPNDD_CHECK_NULL(ptr, error) \
    do { if ((ptr) == NULL) { MTPNDD_RETURN_ERROR(error); } } while(0)
#define MTPNDD_CHECK_INIT() \
    do { if (!mtpndd_is_initialized()) { MTPNDD_RETURN_ERROR(MTPNDD_ERROR_NOT_INITIALIZED); } } while(0)

/********************************
 * Default sizing helpers
 ********************************/
#ifdef LARGE_NODETABLE
#define MTPNDD_DEFAULT_NODETABLE_BUCKET_COUNT 65537
#else
#define MTPNDD_DEFAULT_NODETABLE_BUCKET_COUNT 1024
#endif

#define MTPNDD_DEFAULT_EDGE_BUCKET_COUNT 8
#define MTPNDD_DEFAULT_NODE_SLAB_CAPACITY 1024
#define MTPNDD_DEFAULT_EDGE_ENTRY_SLAB_CAPACITY 4096
#define MTPNDD_DEFAULT_NODETABLE_ENTRY_SLAB_CAPACITY 2048
#define MTPNDD_DEFAULT_EDGE_MAP_SLAB_CAPACITY 2048

// Backpressure for batched `mtpndd_and_rec` SPAWN/SYNC: bounds the number of pending child tasks
// (and referenced edge-label BDDs) before forcing a drain. Override at compile time, e.g.:
//   cc ... -DMTPNDD_AND_PENDING_FLUSH_THRESHOLD=512
#ifndef MTPNDD_AND_PENDING_FLUSH_THRESHOLD
#define MTPNDD_AND_PENDING_FLUSH_THRESHOLD 256
#endif

// Spawn granularity: minimum product of edge counts (edges_a * edges_b)
// required before spawning a parallel sub-task.
// Larger values reduce task overhead but limit parallelism.
// Override at compile time, e.g.: cc ... -DMTPNDD_SPAWN_THRESHOLD=16
#ifndef MTPNDD_SPAWN_THRESHOLD
#define MTPNDD_SPAWN_THRESHOLD 2
#endif

/********************************
 * Global config definitions
 ********************************/
typedef struct mtpndd_pal_config_s {
    int32_t n_workers;
    size_t lace_dqsize;
    size_t bdd_nodetable_size;
    size_t mtpndd_nodetable_size;
    size_t op_cache_size;
    double quick_growth_threshold;
    size_t edge_bucket_count;
    size_t nodetable_bucket_count;
    size_t node_slab_capacity;
    size_t edge_entry_slab_capacity;
    size_t nodetable_entry_slab_capacity;
    size_t edge_map_slab_capacity;
} mtpndd_pal_config_t;

extern mtpndd_pal_config_t g_mtpndd_pal_config;

// Live node count — runtime state used for GC triggering, not a debug stat.
extern uint64_t g_mtpndd_node_count;

typedef struct mtpndd_stats_s {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    uint64_t max_edges_per_node;
    uint64_t bdd_nodes_converted;
    uint64_t cache_lookup_hits;
    uint64_t cache_lookup_misses;
    uint64_t cache_store_overwrites;
    uint64_t cache_store_total;
    uint64_t gc_runs;
    uint64_t nodes_created_total;
    uint64_t nodes_reused_total;
    uint64_t nodes_collected_last;
    uint64_t edge_insert_total;
    uint64_t edge_collision_total;
    uint64_t nodetable_collision_total;
    uint64_t nodetable_lookup_hits;
    uint64_t nodetable_lookup_misses;
    uint64_t nodetable_hash_ns;
    uint64_t nodetable_bucket_scan_ns;
    uint64_t nodetable_edge_compare_ns;
    uint64_t nodetable_edge_compare_entries;
    uint64_t nodetable_edge_compare_steps_total;
    uint64_t nodetable_edge_compare_max_steps;
    uint64_t edge_lock_spin_total;
    uint64_t edge_lock_wait_time_ns;
    uint64_t gc_pause_time_ns;
    uint64_t edge_entry_total;
    uint64_t bdd_nodes_processed_total;
    uint64_t node_pool_acquire_total;
    uint64_t node_pool_release_total;
    uint64_t node_pool_slab_total;
    uint64_t edge_entry_pool_acquire_total;
    uint64_t edge_entry_pool_release_total;
    uint64_t edge_entry_pool_slab_total;
    uint64_t nodetable_entry_pool_acquire_total;
    uint64_t nodetable_entry_pool_release_total;
    uint64_t nodetable_entry_pool_slab_total;
    uint64_t edge_map_pool_acquire_total;
    uint64_t edge_map_pool_release_total;
    uint64_t edge_map_pool_slab_total;
    uint64_t edge_map_rehash_total;
    uint64_t edge_map_max_buckets;
    uint64_t nodetable_rehash_total;
    uint64_t nodetable_max_buckets;
    uint64_t and_time_ns;
    uint64_t and_call_total;
    uint64_t or_call_total;
    uint64_t not_call_total;
    uint64_t diff_call_total;
    uint64_t and_call_wall_ns;
    uint64_t or_call_wall_ns;
    uint64_t not_call_wall_ns;
    uint64_t diff_call_wall_ns;
    uint64_t and_spawn_total;
    uint64_t and_spawn_same_total;
    uint64_t and_spawn_diff_total;
    uint64_t and_pending_flush_total;
    uint64_t or_time_ns;
    uint64_t not_time_ns;
    uint64_t and_fastpath_ns;
    uint64_t and_cache_hit_ns;
    uint64_t and_build_edges_ns;
    uint64_t and_same_field_ns;
    uint64_t and_same_outer_loop_ns;
    uint64_t and_same_inner_loop_ns;
    uint64_t and_same_label_load_ns;
    uint64_t and_same_bdd_op_ns;
    uint64_t and_same_bdd_op_count;       // number of sylvan_and calls in same-field AND
    uint64_t and_same_bdd_op_false_count; // of those, how many returned sylvan_false
    uint64_t and_two_phase_trigger_count; // times two-phase path was taken
    uint64_t and_two_phase_total_pairs;   // total E_a * E_b entering Phase 1
    uint64_t and_two_phase_surviving;     // pairs surviving Phase 1 filter (label != false)
    uint64_t and_same_add_edge_ns;
    uint64_t and_diff_field_ns;
    uint64_t and_mk_ns;
    uint64_t and_mk_call_ns;
    uint64_t and_mk_cache_store_ns;
    uint64_t and_mk_other_ns;
    uint64_t mk_hash_ns;
    uint64_t mk_lookup_ns;
    uint64_t mk_fast_return_ns;
    uint64_t mk_reuse_cleanup_ns;
    uint64_t mk_ref_children_ns;
    uint64_t mk_gc_or_grow_ns;
    uint64_t mk_alloc_node_ns;
    uint64_t mk_alloc_entry_ns;
    uint64_t mk_bucket_scan_ns;
    uint64_t mk_link_ns;
    uint64_t mk_collision_cleanup_ns;
    uint64_t mk_other_ns;
    uint64_t mk_total_ns;
#endif
} mtpndd_stats_t;

extern mtpndd_stats_t g_mtpndd_stats;

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
static inline void mtpndd_stat_add(uint64_t *field, uint64_t value) {
    __atomic_add_fetch(field, value, __ATOMIC_RELAXED);
}

static inline void mtpndd_stat_set(uint64_t *field, uint64_t value) {
    __atomic_store_n(field, value, __ATOMIC_RELAXED);
}

static inline void mtpndd_stat_max(uint64_t *field, uint64_t value) {
    uint64_t current = __atomic_load_n(field, __ATOMIC_RELAXED);
    while (value > current && !__atomic_compare_exchange_n(field, &current, value, false, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
        /* retry with updated current */
    }
}

static inline uint64_t mtpndd_timespec_diff_ns(const struct timespec *start_ts, const struct timespec *end_ts) {
    uint64_t sec = (uint64_t)end_ts->tv_sec - (uint64_t)start_ts->tv_sec;
    int64_t nsec = end_ts->tv_nsec - start_ts->tv_nsec;
    return sec * 1000000000ull + (uint64_t)nsec;
}

#define MTPNDD_STAT_ADD(field, value) mtpndd_stat_add(&g_mtpndd_stats.field, (uint64_t)(value))
#define MTPNDD_STAT_SET(field, value) mtpndd_stat_set(&g_mtpndd_stats.field, (uint64_t)(value))
#define MTPNDD_STAT_MAX(field, value) mtpndd_stat_max(&g_mtpndd_stats.field, (uint64_t)(value))
#define MTPNDD_RECORD_TIME_START(var) struct timespec var = {0}; clock_gettime(CLOCK_MONOTONIC, &(var))
#define MTPNDD_RECORD_TIME_END(field, start_var) \
    do { \
        struct timespec _mtpndd_time_end = {0}; \
        clock_gettime(CLOCK_MONOTONIC, &_mtpndd_time_end); \
        MTPNDD_STAT_ADD(field, mtpndd_timespec_diff_ns(&(start_var), &_mtpndd_time_end)); \
    } while(0)
#else
#define MTPNDD_STAT_ADD(field, value) ((void)0)
#define MTPNDD_STAT_SET(field, value) ((void)0)
#define MTPNDD_STAT_MAX(field, value) ((void)0)
#define MTPNDD_RECORD_TIME_START(var) ((void)0)
#define MTPNDD_RECORD_TIME_END(field, start_var) ((void)0)
#endif

typedef struct mtpndd_field_info_s {
    uint32_t field_id;
    uint32_t bit_width;
    uint32_t start_var;

    // For internal use
    mtpndd_bdd_t *bdd_vars;
    mtpndd_bdd_t *bdd_not_vars;
    mtpndd_node_t **mtpndd_vars;
    mtpndd_node_t **mtpndd_not_vars;
} mtpndd_field_info_t;

extern mtpndd_field_info_t MTPNDD_TERMINAL_FIELD;

typedef struct mtpndd_config_s {
    uint32_t field_count;
    uint32_t field_capacity;
    mtpndd_field_info_t **field_info;

    mtpndd_nodetable_t **node_tables_by_field;

    // and_cache/or_cache are reused for times/plus respectively in MT mode;
    // Operation tags isolate Boolean and arithmetic results in shared slots.
    // minus/divide are not cached.
    mtpndd_op_cache_t *and_cache;
    mtpndd_op_cache_t *or_cache;
    mtpndd_op_cache_t *not_cache;

    // Canonical tables for terminal (leaf) nodes.  Separate tables per type
    // keep the hash keys simple (no type tag in the key).
    mtpndd_leaf_table_t *leaf_table;           // fractions (phase 1)
    mtpndd_leaf_table_t *double_leaf_table;    // IEEE doubles (phase 2)

    uint32_t pending_field_count;
    uint32_t pending_field_capacity;
    uint32_t *pending_field_bit_widths;
    uint32_t max_bit_width;
    bool fields_generated;
    mtpndd_bdd_t *shared_bdd_vars;
    mtpndd_bdd_t *shared_bdd_not_vars;
} mtpndd_config_t;

extern mtpndd_config_t g_mtpndd_config;

/********************************
 * MTPNDD field management
 ********************************/
// only append field
mtpndd_error_t mtpndd_declare_field(uint32_t bit_width);
mtpndd_error_t mtpndd_generate_fields(void);
mtpndd_field_info_t* mtpndd_get_field_info(uint32_t field_id);

/********************************
 * MTPNDD get node
 ********************************/
mtpndd_t *mtpndd_get_var(uint32_t field, uint32_t var_index);
mtpndd_t *mtpndd_get_not_var(uint32_t field, uint32_t var_index);
mtpndd_bdd_t mtpndd_get_bdd_var(uint32_t field, uint32_t var_index);
mtpndd_bdd_t mtpndd_get_bdd_not_var(uint32_t field, uint32_t var_index);

/********************************
 * Global status control
 ********************************/
mtpndd_stats_t *mtpndd_get_stats();

bool mtpndd_is_initialized();

mtpndd_error_t mtpndd_init(mtpndd_pal_config_t *config);
mtpndd_error_t mtpndd_quit();

/********************************
 * GC hooks
 ********************************/
// GC hook signature: pointer to void hook(void)
typedef void (*mtpndd_gc_hook_t)(void);
void mtpndd_gc_hook_pregc(mtpndd_gc_hook_t hook);
void mtpndd_gc_hook_postgc(mtpndd_gc_hook_t hook);

#endif // MTPNDD_COMMON_H
