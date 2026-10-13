// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Copyright (C) Augists
// Copyright (C) XJTU ANTS Netverify Lab

#include "mtpndd_common.h"
#include "mtpndd_node.h"
#include "mtpndd_nodetable.h"
#include "mtpndd_operation_cache.h"
#include "mtpndd_memory_pool.h"
#include "mtpndd_leaf_table.h"
#include "mtpndd_leaf.h"

#include <stdlib.h>
#include <string.h>
#include <lace.h>
#include "sylvan.h"
#include "sylvan_table.h"
#include <stdatomic.h>

/********************************
 * Global singletons and defaults
 ********************************/
mtpndd_pal_config_t g_mtpndd_pal_config = {0};
uint64_t g_mtpndd_node_count = 0;
mtpndd_stats_t g_mtpndd_stats;
mtpndd_field_info_t MTPNDD_TERMINAL_FIELD = {0, 0, 0, NULL, NULL, NULL, NULL};
mtpndd_config_t g_mtpndd_config = {
    .field_count = 0,
    .field_capacity = 0,
    .field_info = NULL,
    .node_tables_by_field = NULL,
    .and_cache = NULL,
    .or_cache = NULL,
    .not_cache = NULL,
    .leaf_table = NULL,
    .double_leaf_table = NULL,
};

mtpndd_leaf_table_t *mtpndd_get_leaf_table(void) {
    return g_mtpndd_config.leaf_table;
}

mtpndd_leaf_table_t *mtpndd_get_double_leaf_table(void) {
    return g_mtpndd_config.double_leaf_table;
}

#define MTPNDD_GC_HOOK_CAPACITY 16
static mtpndd_gc_hook_t g_mtpndd_gc_prehooks[MTPNDD_GC_HOOK_CAPACITY] = {0};
static mtpndd_gc_hook_t g_mtpndd_gc_posthooks[MTPNDD_GC_HOOK_CAPACITY] = {0};
static _Atomic size_t g_mtpndd_gc_prehook_count = 0;
static _Atomic size_t g_mtpndd_gc_posthook_count = 0;

#define DEFAULT_QUICK_GROWTH_THRESHOLD 0.1
#define DEFAULT_FIELD_CAPACITY 8

/********************************
 * Internal helpers
 ********************************/
static bool mtpndd_lace_init(void);
static void mtpndd_gc_run_hooks(mtpndd_gc_hook_t *hooks, size_t count);

mtpndd_error_t mtpndd_temp_refs_runtime_init(void);
void mtpndd_temp_refs_runtime_shutdown(void);
static void mtpndd_gc_hook_sylvan_pre(WorkerP *worker, Task *task);
static void mtpndd_gc_hook_sylvan_post(WorkerP *worker, Task *task);
static void mtpndd_gc_hook_mtpndd_pre(void);
static void mtpndd_gc_hook_mtpndd_post(void);
static void mtpndd_log_init_config(const mtpndd_pal_config_t *requested);

static void mtpndd_field_info_free(mtpndd_field_info_t *field) {
    if (!field) {
        return;
    }
    uint32_t width = field->bit_width;
    if (field->mtpndd_vars) {
        for (uint32_t i = 0; i < width; ++i) {
            mtpndd_node_t *node = field->mtpndd_vars[i];
            if (node) {
                if (node->edges) {
                    mtpndd_edge_map_free(node->edges);
                    node->edges = NULL;
                }
                mtpndd_memory_release_node(node);
                field->mtpndd_vars[i] = NULL;
            }
        }
        free(field->mtpndd_vars);
        field->mtpndd_vars = NULL;
    }
    if (field->mtpndd_not_vars) {
        for (uint32_t i = 0; i < width; ++i) {
            mtpndd_node_t *node = field->mtpndd_not_vars[i];
            if (node) {
                if (node->edges) {
                    mtpndd_edge_map_free(node->edges);
                    node->edges = NULL;
                }
                mtpndd_memory_release_node(node);
                field->mtpndd_not_vars[i] = NULL;
            }
        }
        free(field->mtpndd_not_vars);
        field->mtpndd_not_vars = NULL;
    }
    // bdd_vars and bdd_not_vars are references to shared_bdd_vars, don't unprotect them here
    if (field->bdd_vars) {
        free(field->bdd_vars);
        field->bdd_vars = NULL;
    }
    if (field->bdd_not_vars) {
        free(field->bdd_not_vars);
        field->bdd_not_vars = NULL;
    }
}

/********************************
 * Error handling system
 ********************************/
static __thread mtpndd_error_info_t g_last_error = {MTPNDD_SUCCESS, NULL, NULL, 0};

const char* mtpndd_error_messages[] = {
    "Success",
    "Invalid parameter",
    "NDD system initialization failed",
    "NDD system not initialized",
    "NDD system already initialized",
    "Out of memory",
    "Invalid field ID",
    "Null pointer",
    "Parallel initialization failed",
    "BDD operation failed",

    "Unknown error"
};

const char* mtpndd_error_string(mtpndd_error_t error) {
    if (error >= 0 && error <= MTPNDD_ERROR_UNKNOWN) {
        return mtpndd_error_messages[error];
    }
    return "Invalid error code";
}

void mtpndd_set_error(mtpndd_error_t error, const char *function, int line) {
    g_last_error.code = error;
    g_last_error.message = mtpndd_error_string(error);
    g_last_error.function = function;
    g_last_error.line = line;
}

mtpndd_error_info_t mtpndd_get_last_error() {
    return g_last_error;
}

void mtpndd_clear_error() {
    g_last_error.code = MTPNDD_SUCCESS;
    g_last_error.message = NULL;
    g_last_error.function = NULL;
    g_last_error.line = 0;
}

/********************************
 * GC hooks
 ********************************/
void mtpndd_gc_hook_pregc(mtpndd_gc_hook_t hook) {
    if (!hook) {
        return;
    }
    size_t idx = __atomic_load_n(&g_mtpndd_gc_prehook_count, __ATOMIC_RELAXED);
    if (idx >= MTPNDD_GC_HOOK_CAPACITY) {
        return;
    }
    g_mtpndd_gc_prehooks[idx] = hook;
    __atomic_store_n(&g_mtpndd_gc_prehook_count, idx + 1, __ATOMIC_RELAXED);
}

void mtpndd_gc_hook_postgc(mtpndd_gc_hook_t hook) {
    if (!hook) {
        return;
    }
    size_t idx = __atomic_load_n(&g_mtpndd_gc_posthook_count, __ATOMIC_RELAXED);
    if (idx >= MTPNDD_GC_HOOK_CAPACITY) {
        return;
    }
    g_mtpndd_gc_posthooks[idx] = hook;
    __atomic_store_n(&g_mtpndd_gc_posthook_count, idx + 1, __ATOMIC_RELAXED);
}

static void mtpndd_gc_run_hooks(mtpndd_gc_hook_t *hooks, size_t count) {
    if (!hooks) {
        return;
    }
    for (size_t i = 0; i < count; ++i) {
        mtpndd_gc_hook_t hook = hooks[i];
        if (hook) {
            hook();
        }
    }
}

void mtpndd_gc_run_prehooks(void) {
    size_t count = __atomic_load_n(&g_mtpndd_gc_prehook_count, __ATOMIC_RELAXED);
    mtpndd_gc_run_hooks(g_mtpndd_gc_prehooks, count);
}

void mtpndd_gc_run_posthooks(void) {
    size_t count = __atomic_load_n(&g_mtpndd_gc_posthook_count, __ATOMIC_RELAXED);
    mtpndd_gc_run_hooks(g_mtpndd_gc_posthooks, count);
}

/********************************
 * MTPNDD field management
 ********************************/
mtpndd_field_info_t* mtpndd_get_field_info(uint32_t field_id) {
    if (!mtpndd_is_initialized()) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED);
        return NULL;
    }
    if (field_id == 0 || field_id > g_mtpndd_config.field_count) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_FIELD);
        return NULL;
    }

    return g_mtpndd_config.field_info[field_id];
}

// only append field
mtpndd_error_t mtpndd_declare_field(uint32_t bit_width) {
    MTPNDD_CHECK_INIT();
    MTPNDD_CHECK_PARAM(bit_width > 0, MTPNDD_ERROR_INVALID_PARAM);
    if (g_mtpndd_config.fields_generated) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_INVALID_PARAM);
    }

    g_mtpndd_config.pending_field_count++;

    if (g_mtpndd_config.pending_field_count >= g_mtpndd_config.pending_field_capacity) {
        uint32_t old_capacity = g_mtpndd_config.pending_field_capacity;
        uint32_t new_capacity = old_capacity ? old_capacity * 2 : DEFAULT_FIELD_CAPACITY;
        if (new_capacity <= g_mtpndd_config.pending_field_count) {
            new_capacity = g_mtpndd_config.pending_field_count + 1;
        }
        size_t new_bytes = sizeof(uint32_t) * new_capacity;
        uint32_t *new_pending = (uint32_t *)realloc(
                g_mtpndd_config.pending_field_bit_widths, new_bytes);
        if (!new_pending) {
            MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        }
        if (new_capacity > old_capacity) {
            memset(new_pending + old_capacity, 0,
                    sizeof(uint32_t) * (new_capacity - old_capacity));
        }
        g_mtpndd_config.pending_field_bit_widths = new_pending;
        g_mtpndd_config.pending_field_capacity = new_capacity;
    }

    g_mtpndd_config.pending_field_bit_widths[g_mtpndd_config.pending_field_count - 1] = bit_width;
    return MTPNDD_SUCCESS;
}

mtpndd_error_t mtpndd_generate_fields(void) {
    MTPNDD_CHECK_INIT();
    if (g_mtpndd_config.fields_generated) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_INVALID_PARAM);
    }
    if (g_mtpndd_config.pending_field_count == 0) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_INVALID_PARAM);
    }

    // One-time resize of field_info and node_tables_by_field based on pending_field_count
    uint32_t required_capacity = g_mtpndd_config.pending_field_count + 1; // +1 for field_id starting from 1
    if (required_capacity > g_mtpndd_config.field_capacity) {
        uint32_t old_capacity = g_mtpndd_config.field_capacity;
        uint32_t new_capacity = required_capacity;

        size_t fi_size = sizeof(mtpndd_field_info_t*) * new_capacity;
        mtpndd_field_info_t **new_field_info =
                (mtpndd_field_info_t **)realloc(g_mtpndd_config.field_info, fi_size);
        if (!new_field_info) {
            MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        }
        if (new_capacity > old_capacity) {
            size_t fi_bytes = sizeof(mtpndd_field_info_t*) * (new_capacity - old_capacity);
            memset(new_field_info + old_capacity, 0, fi_bytes);
        }
        g_mtpndd_config.field_info = new_field_info;

        size_t nt_size = sizeof(mtpndd_nodetable_t*) * new_capacity;
        void *aligned_nt = NULL;
        if (posix_memalign(&aligned_nt, 64, nt_size) != 0) {
            MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        }
        mtpndd_nodetable_t **new_node_tables = (mtpndd_nodetable_t **)aligned_nt;
        if (g_mtpndd_config.node_tables_by_field && old_capacity > 0) {
            size_t old_nt_bytes = sizeof(mtpndd_nodetable_t*) * old_capacity;
            memcpy(new_node_tables, g_mtpndd_config.node_tables_by_field, old_nt_bytes);
        }
        if (new_capacity > old_capacity) {
            size_t nt_bytes = sizeof(mtpndd_nodetable_t*) * (new_capacity - old_capacity);
            memset(new_node_tables + old_capacity, 0, nt_bytes);
        }
        free(g_mtpndd_config.node_tables_by_field);
        g_mtpndd_config.node_tables_by_field = new_node_tables;
        g_mtpndd_config.field_capacity = new_capacity;
    }

    uint32_t max_width = 0;
    for (uint32_t i = 0; i < g_mtpndd_config.pending_field_count; ++i) {
        uint32_t width = g_mtpndd_config.pending_field_bit_widths[i];
        if (width > max_width) {
            max_width = width;
        }
    }

    g_mtpndd_config.max_bit_width = max_width;
    g_mtpndd_config.shared_bdd_vars = (mtpndd_bdd_t *)malloc(sizeof(mtpndd_bdd_t) * max_width);
    g_mtpndd_config.shared_bdd_not_vars = (mtpndd_bdd_t *)malloc(sizeof(mtpndd_bdd_t) * max_width);
    if (!g_mtpndd_config.shared_bdd_vars || !g_mtpndd_config.shared_bdd_not_vars) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
    }

    for (uint32_t i = 0; i < max_width; ++i) {
        g_mtpndd_config.shared_bdd_vars[i] = sylvan_ithvar(i);
        sylvan_protect(g_mtpndd_config.shared_bdd_vars + i);
        g_mtpndd_config.shared_bdd_not_vars[i] = sylvan_not(g_mtpndd_config.shared_bdd_vars[i]);
        sylvan_protect(g_mtpndd_config.shared_bdd_not_vars + i);
    }

    g_mtpndd_config.field_count = g_mtpndd_config.pending_field_count;

    for (uint32_t f = 0; f < g_mtpndd_config.pending_field_count; ++f) {
        uint32_t bit_width = g_mtpndd_config.pending_field_bit_widths[f];
        uint32_t field_id = f + 1;
        uint32_t offset = max_width - bit_width;

        mtpndd_field_info_t *new_field = (mtpndd_field_info_t *)malloc(sizeof(mtpndd_field_info_t));
        if (!new_field) {
            MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        }
        memset(new_field, 0, sizeof(mtpndd_field_info_t));
        new_field->field_id = field_id;
        new_field->bit_width = bit_width;
        new_field->start_var = offset;

        mtpndd_nodetable_t *nodetable = mtpndd_nodetable_declare_field();
        if (!nodetable) {
            free(new_field);
            MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        }
        g_mtpndd_config.node_tables_by_field[field_id] = nodetable;

#define FREE_MTPNDD_VARS_WITH_NODES(i) do { \
        if ((i) > 0) { \
            for (uint32_t j = 0; j < (i); j++) { \
                if (new_field->mtpndd_vars && new_field->mtpndd_vars[j]) { \
                    mtpndd_edge_map_free(new_field->mtpndd_vars[j]->edges); \
                    new_field->mtpndd_vars[j] = NULL; \
                } \
                if (new_field->mtpndd_not_vars && new_field->mtpndd_not_vars[j]) { \
                    mtpndd_edge_map_free(new_field->mtpndd_not_vars[j]->edges); \
                    new_field->mtpndd_not_vars[j] = NULL; \
                } \
            } \
        } \
        free(new_field->mtpndd_vars); \
        free(new_field->mtpndd_not_vars); \
        free(new_field->bdd_vars); \
        free(new_field->bdd_not_vars); \
        free(new_field); \
    } while(0)

        new_field->bdd_vars = (mtpndd_bdd_t *)malloc(sizeof(mtpndd_bdd_t) * bit_width);
        new_field->bdd_not_vars = (mtpndd_bdd_t *)malloc(sizeof(mtpndd_bdd_t) * bit_width);
        if (!new_field->bdd_vars || !new_field->bdd_not_vars) {
            free(new_field->bdd_vars);
            free(new_field->bdd_not_vars);
            free(new_field);
            MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        }
        for (uint32_t i = 0; i < bit_width; ++i) {
            new_field->bdd_vars[i] = g_mtpndd_config.shared_bdd_vars[offset + i];
            new_field->bdd_not_vars[i] = g_mtpndd_config.shared_bdd_not_vars[offset + i];
        }

        new_field->mtpndd_vars = (mtpndd_node_t **)malloc(sizeof(mtpndd_node_t *) * bit_width);
        new_field->mtpndd_not_vars = (mtpndd_node_t **)malloc(sizeof(mtpndd_node_t *) * bit_width);
        if (!new_field->mtpndd_vars || !new_field->mtpndd_not_vars) {
            free(new_field->mtpndd_vars);
            free(new_field->mtpndd_not_vars);
            free(new_field->bdd_vars);
            free(new_field->bdd_not_vars);
            free(new_field);
            MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
        }
        memset(new_field->mtpndd_vars, 0, sizeof(mtpndd_node_t *) * bit_width);
        memset(new_field->mtpndd_not_vars, 0, sizeof(mtpndd_node_t *) * bit_width);

        mtpndd_error_t err = MTPNDD_SUCCESS;
        for (uint32_t i = 0; i < bit_width; i++) {
            mtpndd_edge_t *edges_var = mtpndd_memory_acquire_edge_map();
            if (!edges_var) {
                FREE_MTPNDD_VARS_WITH_NODES(bit_width);
                MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
            }
            mtpndd_edge_map_init(edges_var);
            err = mtpndd_add_edge(edges_var, &MTPNDD_TRUE,
                    sylvan_ref(new_field->bdd_vars[i]));
            if (err != MTPNDD_SUCCESS) {
                mtpndd_edge_map_free(edges_var);
                FREE_MTPNDD_VARS_WITH_NODES(bit_width);
                MTPNDD_RETURN_ERROR(err);
            }
            mtpndd_node_t *node_var = NULL;
            mtpndd_mk(field_id, edges_var, &node_var);
            if (!node_var) {
                mtpndd_edge_map_free(edges_var);
                FREE_MTPNDD_VARS_WITH_NODES(bit_width);
                MTPNDD_RETURN_ERROR(mtpndd_get_last_error().code);
            }
            err = mtpndd_protect(node_var);
            if (err != MTPNDD_SUCCESS) {
                FREE_MTPNDD_VARS_WITH_NODES(bit_width);
                MTPNDD_RETURN_ERROR(err);
            }
            new_field->mtpndd_vars[i] = node_var;

            mtpndd_edge_t *edges_not = mtpndd_memory_acquire_edge_map();
            if (!edges_not) {
                FREE_MTPNDD_VARS_WITH_NODES(bit_width);
                MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
            }
            mtpndd_edge_map_init(edges_not);
            err = mtpndd_add_edge(edges_not, &MTPNDD_TRUE,
                    sylvan_ref(new_field->bdd_not_vars[i]));
            if (err != MTPNDD_SUCCESS) {
                mtpndd_edge_map_free(edges_not);
                FREE_MTPNDD_VARS_WITH_NODES(bit_width);
                MTPNDD_RETURN_ERROR(err);
            }
            mtpndd_node_t *node_not = NULL;
            mtpndd_mk(field_id, edges_not, &node_not);
            if (!node_not) {
                mtpndd_edge_map_free(edges_not);
                FREE_MTPNDD_VARS_WITH_NODES(bit_width);
                MTPNDD_RETURN_ERROR(mtpndd_get_last_error().code);
            }
            err = mtpndd_protect(node_not);
            if (err != MTPNDD_SUCCESS) {
                FREE_MTPNDD_VARS_WITH_NODES(bit_width);
                MTPNDD_RETURN_ERROR(err);
            }
            new_field->mtpndd_not_vars[i] = node_not;
        }

        g_mtpndd_config.field_info[field_id] = new_field;
    }

    g_mtpndd_config.fields_generated = true;
    return MTPNDD_SUCCESS;
}


/********************************
 * MTPNDD get node
 ********************************/
mtpndd_t *mtpndd_get_var(uint32_t field, uint32_t var_index) {
    if (!mtpndd_is_initialized()) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED);
        return NULL;
    }
    if (field == 0 || field > g_mtpndd_config.field_count) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_FIELD);
        return NULL;
    }
    mtpndd_field_info_t *field_info = g_mtpndd_config.field_info[field];
    if (!field_info || var_index >= field_info->bit_width) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_PARAM);
        return NULL;
    }

    return field_info->mtpndd_vars[var_index];
}

mtpndd_t *mtpndd_get_not_var(uint32_t field, uint32_t var_index) {
    if (!mtpndd_is_initialized()) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED);
        return NULL;
    }
    if (field == 0 || field > g_mtpndd_config.field_count) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_FIELD);
        return NULL;
    }
    mtpndd_field_info_t *field_info = g_mtpndd_config.field_info[field];
    if (!field_info || var_index >= field_info->bit_width) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_PARAM);
        return NULL;
    }

    return field_info->mtpndd_not_vars[var_index];
}

mtpndd_bdd_t mtpndd_get_bdd_var(uint32_t field, uint32_t var_index) {
    if (!mtpndd_is_initialized()) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED);
        return 0;
    }
    if (field == 0 || field > g_mtpndd_config.field_count) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_FIELD);
        return 0;
    }
    mtpndd_field_info_t *field_info = g_mtpndd_config.field_info[field];
    if (!field_info || var_index >= field_info->bit_width) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_PARAM);
        return 0;
    }

    return field_info->bdd_vars[var_index];
}

mtpndd_bdd_t mtpndd_get_bdd_not_var(uint32_t field, uint32_t var_index) {
    if (!mtpndd_is_initialized()) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_NOT_INITIALIZED);
        return 0;
    }
    if (field == 0 || field > g_mtpndd_config.field_count) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_FIELD);
        return 0;
    }
    mtpndd_field_info_t *field_info = g_mtpndd_config.field_info[field];
    if (!field_info || var_index >= field_info->bit_width) {
        MTPNDD_SET_ERROR(MTPNDD_ERROR_INVALID_PARAM);
        return 0;
    }

    return field_info->bdd_not_vars[var_index];
}

/********************************
 * Global status control
 ********************************/
mtpndd_stats_t *mtpndd_get_stats() {
    return &g_mtpndd_stats;
}

bool mtpndd_is_initialized() {
    return g_mtpndd_pal_config.lace_dqsize > 0
            || g_mtpndd_pal_config.bdd_nodetable_size > 0
            || g_mtpndd_pal_config.mtpndd_nodetable_size > 0
            || g_mtpndd_pal_config.op_cache_size > 0
            || g_mtpndd_config.field_count > 0;
}

static void mtpndd_apply_pal_config_defaults(void) {
    if (g_mtpndd_pal_config.edge_bucket_count == 0) {
        g_mtpndd_pal_config.edge_bucket_count = MTPNDD_DEFAULT_EDGE_BUCKET_COUNT;
    }
    if (g_mtpndd_pal_config.nodetable_bucket_count == 0) {
        g_mtpndd_pal_config.nodetable_bucket_count = MTPNDD_DEFAULT_NODETABLE_BUCKET_COUNT;
    }
    if (g_mtpndd_pal_config.node_slab_capacity == 0) {
        g_mtpndd_pal_config.node_slab_capacity = MTPNDD_DEFAULT_NODE_SLAB_CAPACITY;
    }
    if (g_mtpndd_pal_config.edge_entry_slab_capacity == 0) {
        g_mtpndd_pal_config.edge_entry_slab_capacity = MTPNDD_DEFAULT_EDGE_ENTRY_SLAB_CAPACITY;
    }
    if (g_mtpndd_pal_config.nodetable_entry_slab_capacity == 0) {
        g_mtpndd_pal_config.nodetable_entry_slab_capacity = MTPNDD_DEFAULT_NODETABLE_ENTRY_SLAB_CAPACITY;
    }
    if (g_mtpndd_pal_config.edge_map_slab_capacity == 0) {
        g_mtpndd_pal_config.edge_map_slab_capacity = MTPNDD_DEFAULT_EDGE_MAP_SLAB_CAPACITY;
    }
}

mtpndd_error_t mtpndd_init(mtpndd_pal_config_t *config) {
    if (mtpndd_is_initialized()) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_IS_INITIALIZED);
    }

    if (config == NULL || !(
            config->n_workers >= 0
            && config->lace_dqsize > 0
            && config->bdd_nodetable_size > 0
            && config->mtpndd_nodetable_size > 0
            && config->op_cache_size > 0)) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_INVALID_PARAM);
    }

    memset(&g_mtpndd_pal_config, 0, sizeof(g_mtpndd_pal_config));
    g_mtpndd_pal_config.n_workers = config->n_workers;
    g_mtpndd_pal_config.lace_dqsize = config->lace_dqsize;
    g_mtpndd_pal_config.bdd_nodetable_size = config->bdd_nodetable_size;
    g_mtpndd_pal_config.mtpndd_nodetable_size = config->mtpndd_nodetable_size;
    g_mtpndd_pal_config.op_cache_size = config->op_cache_size;
    g_mtpndd_pal_config.quick_growth_threshold =
            (config->quick_growth_threshold >= 0.0)
                    ? config->quick_growth_threshold
                    : DEFAULT_QUICK_GROWTH_THRESHOLD;
    g_mtpndd_pal_config.edge_bucket_count = config->edge_bucket_count;
    g_mtpndd_pal_config.nodetable_bucket_count = config->nodetable_bucket_count;
    g_mtpndd_pal_config.node_slab_capacity = config->node_slab_capacity;
    g_mtpndd_pal_config.edge_entry_slab_capacity = config->edge_entry_slab_capacity;
    g_mtpndd_pal_config.nodetable_entry_slab_capacity = config->nodetable_entry_slab_capacity;
    g_mtpndd_pal_config.edge_map_slab_capacity = config->edge_map_slab_capacity;
    mtpndd_apply_pal_config_defaults();

    // Initialize TRUE/FALSE as leaves for 1/1 and 0/1. They will be
    // pre-seeded into the leaf table so that mtpndd_make_fraction(1,1)
    // and mtpndd_make_fraction(0,1) return these canonical pointers.
    MTPNDD_TRUE.field_id = MTPNDD_LEAF_FIELD_ID;
    MTPNDD_TRUE.leaf_value = mtpndd_pack_fraction(1, 1);
    mtpndd_protect(&MTPNDD_TRUE);
    MTPNDD_FALSE.field_id = MTPNDD_LEAF_FIELD_ID;
    MTPNDD_FALSE.leaf_value = mtpndd_pack_fraction(0, 1);
    mtpndd_protect(&MTPNDD_FALSE);

    memset(&g_mtpndd_config, 0, sizeof(g_mtpndd_config));
    g_mtpndd_config.field_count = 0;
    g_mtpndd_config.field_capacity = DEFAULT_FIELD_CAPACITY;
    g_mtpndd_config.field_info = (mtpndd_field_info_t **)calloc(
            DEFAULT_FIELD_CAPACITY, sizeof(mtpndd_field_info_t *));
    if (!g_mtpndd_config.field_info) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
    }
    g_mtpndd_config.field_info[0] = &MTPNDD_TERMINAL_FIELD; // Reserve field 0 for terminal nodes
    // aligned allocation for hot node_tables_by_field to reduce cache-line crossings
    void *aligned_nt = NULL;
    if (posix_memalign(&aligned_nt, 64, sizeof(mtpndd_nodetable_t *) * DEFAULT_FIELD_CAPACITY) != 0) {
        free(g_mtpndd_config.field_info);
        g_mtpndd_config.field_info = NULL;
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
    }
    g_mtpndd_config.node_tables_by_field = (mtpndd_nodetable_t **)aligned_nt;
    memset(g_mtpndd_config.node_tables_by_field, 0, sizeof(mtpndd_nodetable_t *) * DEFAULT_FIELD_CAPACITY);
    g_mtpndd_config.pending_field_capacity = DEFAULT_FIELD_CAPACITY;
    g_mtpndd_config.pending_field_bit_widths = (uint32_t *)calloc(
            DEFAULT_FIELD_CAPACITY, sizeof(uint32_t));
    if (!g_mtpndd_config.pending_field_bit_widths) {
        free(g_mtpndd_config.field_info);
        g_mtpndd_config.field_info = NULL;
        free(g_mtpndd_config.node_tables_by_field);
        g_mtpndd_config.node_tables_by_field = NULL;
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
    }
    g_mtpndd_config.pending_field_count = 0;
    g_mtpndd_config.fields_generated = false;
    g_mtpndd_config.max_bit_width = 0;
    g_mtpndd_config.shared_bdd_vars = NULL;
    g_mtpndd_config.shared_bdd_not_vars = NULL;

    if (!mtpndd_lace_init()) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_PARALLEL_INIT);
    }
    if (mtpndd_temp_refs_runtime_init() != MTPNDD_SUCCESS) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_OUT_OF_MEMORY);
    }
    if (!mtpndd_op_cache_initialize(
                config->op_cache_size,
                &g_mtpndd_config.and_cache,
                &g_mtpndd_config.or_cache,
                &g_mtpndd_config.not_cache)) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_INITIALIZE_FAILED);
    }

    mtpndd_log_init_config(config);

    mtpndd_memory_pools_init();

    // Canonical leaf tables and seeding of TRUE/FALSE.
    g_mtpndd_config.leaf_table = mtpndd_leaf_table_create(0);
    if (!g_mtpndd_config.leaf_table) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_INITIALIZE_FAILED);
    }
    g_mtpndd_config.double_leaf_table = mtpndd_leaf_table_create(0);
    if (!g_mtpndd_config.double_leaf_table) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_INITIALIZE_FAILED);
    }
    if (mtpndd_leaf_table_insert_sentinel(
            g_mtpndd_config.leaf_table,
            MTPNDD_FALSE.leaf_value, &MTPNDD_FALSE) != MTPNDD_SUCCESS) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_INITIALIZE_FAILED);
    }
    if (mtpndd_leaf_table_insert_sentinel(
            g_mtpndd_config.leaf_table,
            MTPNDD_TRUE.leaf_value, &MTPNDD_TRUE) != MTPNDD_SUCCESS) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_INITIALIZE_FAILED);
    }

    // Attach GC logging hooks for both Sylvan and MTPNDD
    sylvan_gc_hook_pregc(mtpndd_gc_hook_sylvan_pre);
    sylvan_gc_hook_postgc(mtpndd_gc_hook_sylvan_post);
    mtpndd_gc_hook_pregc(mtpndd_gc_hook_mtpndd_pre);
    mtpndd_gc_hook_postgc(mtpndd_gc_hook_mtpndd_post);

    // Register mark callback so Sylvan GC preserves BDD nodes used as
    // MTPNDD edge labels (see docs/sylvan_gc_bdd_label_bug.md).
    sylvan_gc_add_mark(mtpndd_gc_mark_bdd_labels);

    g_mtpndd_node_count = 0;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    memset(&g_mtpndd_stats, 0, sizeof(g_mtpndd_stats));
#endif

    return MTPNDD_SUCCESS;
}

static void mtpndd_log_init_config(const mtpndd_pal_config_t *requested) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_INFO
    int32_t requested_workers = requested ? requested->n_workers : g_mtpndd_pal_config.n_workers;
    int32_t actual_workers = (int32_t)lace_workers();
    MTPNDD_LOG_INFO(
            "[MTPNDD INIT] workers requested=%d actual=%d dq=%zu bdd_nodes=%zu mtpndd_nodes=%zu cache=%zu\n",
            requested_workers,
            actual_workers,
            g_mtpndd_pal_config.lace_dqsize,
            g_mtpndd_pal_config.bdd_nodetable_size,
            g_mtpndd_pal_config.mtpndd_nodetable_size,
            g_mtpndd_pal_config.op_cache_size);
    MTPNDD_LOG_INFO(
            "[MTPNDD INIT] quick_growth=%.3f edge_buckets=%zu nodetable_buckets=%zu slabs(node/edge/nodetable/emap)=%zu/%zu/%zu/%zu\n",
            g_mtpndd_pal_config.quick_growth_threshold,
            g_mtpndd_pal_config.edge_bucket_count,
            g_mtpndd_pal_config.nodetable_bucket_count,
            g_mtpndd_pal_config.node_slab_capacity,
            g_mtpndd_pal_config.edge_entry_slab_capacity,
            g_mtpndd_pal_config.nodetable_entry_slab_capacity,
            g_mtpndd_pal_config.edge_map_slab_capacity);
#else
    (void)requested;
#endif
}

static bool mtpndd_lace_init(void) {
    lace_start(g_mtpndd_pal_config.n_workers, g_mtpndd_pal_config.lace_dqsize);

    sylvan_set_sizes(
            g_mtpndd_pal_config.bdd_nodetable_size,
            g_mtpndd_pal_config.bdd_nodetable_size,
            g_mtpndd_pal_config.op_cache_size,
            g_mtpndd_pal_config.op_cache_size);
    sylvan_init_package();
    sylvan_init_bdd();

    // Disable Sylvan GC — it uses NEWFRAME barrier which causes SIGBUS/SIGSEGV
    // when Lace workers concurrently access BDD nodes during GC's clear_aligned
    // (mmap MAP_FIXED) or rehash. MTPNDD manages its own node lifecycle;
    // Sylvan BDD nodes are only used as edge labels and are long-lived.
    // sylvan_gc_disable();

    // check if sylvan is initialized
    return true;
}

static void mtpndd_gc_hook_sylvan_pre(WorkerP *worker, Task *task) {
    (void)worker;
    (void)task;

    // MTPNDD GC before Sylvan GC
    mtpndd_gc_before_sylvan();

#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_INFO
    size_t refs = sylvan_count_refs();
    MTPNDD_LOG_INFO("[Sylvan GC] start refs=%zu capacity=%zu\n",
            refs, g_mtpndd_pal_config.bdd_nodetable_size);
#endif
}

static void mtpndd_gc_hook_sylvan_post(WorkerP *worker, Task *task) {
    (void)worker;
    (void)task;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_INFO
    size_t refs = sylvan_count_refs();
    MTPNDD_LOG_INFO("[Sylvan GC] end refs=%zu\n", refs);
#endif
}

static void mtpndd_gc_hook_mtpndd_pre(void) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_INFO
    size_t nodes = __atomic_load_n(&g_mtpndd_node_count, __ATOMIC_RELAXED);
    size_t capacity = g_mtpndd_pal_config.mtpndd_nodetable_size;
    MTPNDD_LOG_INFO("[MTPNDD GC] start nodes=%zu capacity=%zu\n", nodes, capacity);
#endif
}

static void mtpndd_gc_hook_mtpndd_post(void) {
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_INFO
    size_t nodes = __atomic_load_n(&g_mtpndd_node_count, __ATOMIC_RELAXED);
    unsigned long long reclaimed = 0;
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
    reclaimed = __atomic_load_n(&g_mtpndd_stats.nodes_collected_last, __ATOMIC_RELAXED);
#endif
    MTPNDD_LOG_INFO("[MTPNDD GC] end nodes=%zu reclaimed=%llu\n", nodes, reclaimed);
#endif
}

mtpndd_error_t mtpndd_quit() {
    if (!mtpndd_is_initialized()) {
        MTPNDD_RETURN_ERROR(MTPNDD_ERROR_NOT_INITIALIZED);
    }

    // Release any worker-local temporary refs before tearing down nodes/tables.
    mtpndd_temp_refs_runtime_shutdown();

    // free every fields and their node tables
    for (uint32_t i = 1; i <= g_mtpndd_config.field_count; i++) {
        mtpndd_field_info_t *field = g_mtpndd_config.field_info[i];
        if (field) {
            mtpndd_field_info_free(field);
            free(field);
            g_mtpndd_config.field_info[i] = NULL;
        }
        if (g_mtpndd_config.node_tables_by_field[i]) {
            mtpndd_nodetable_free(g_mtpndd_config.node_tables_by_field[i]);
            g_mtpndd_config.node_tables_by_field[i] = NULL;
        }
    }
    free(g_mtpndd_config.field_info);
    g_mtpndd_config.field_info = NULL;
    free(g_mtpndd_config.node_tables_by_field);
    g_mtpndd_config.node_tables_by_field = NULL;
    g_mtpndd_config.field_count = 0;
    g_mtpndd_config.field_capacity = 0;

    // Unprotect and free shared BDD variables
    if (g_mtpndd_config.shared_bdd_vars) {
        for (uint32_t i = 0; i < g_mtpndd_config.max_bit_width; ++i) {
            sylvan_unprotect(g_mtpndd_config.shared_bdd_vars + i);
        }
        free(g_mtpndd_config.shared_bdd_vars);
        g_mtpndd_config.shared_bdd_vars = NULL;
    }
    if (g_mtpndd_config.shared_bdd_not_vars) {
        for (uint32_t i = 0; i < g_mtpndd_config.max_bit_width; ++i) {
            sylvan_unprotect(g_mtpndd_config.shared_bdd_not_vars + i);
        }
        free(g_mtpndd_config.shared_bdd_not_vars);
        g_mtpndd_config.shared_bdd_not_vars = NULL;
    }
    g_mtpndd_config.max_bit_width = 0;

    if (g_mtpndd_config.pending_field_bit_widths) {
        free(g_mtpndd_config.pending_field_bit_widths);
        g_mtpndd_config.pending_field_bit_widths = NULL;
    }
    g_mtpndd_config.pending_field_count = 0;
    g_mtpndd_config.pending_field_capacity = 0;
    g_mtpndd_config.fields_generated = false;

    // Leaf tables must be freed before the memory pool shuts down, since
    // pooled leaf nodes live in the slab allocator.
    if (g_mtpndd_config.leaf_table) {
        mtpndd_leaf_table_free(g_mtpndd_config.leaf_table);
        g_mtpndd_config.leaf_table = NULL;
    }
    if (g_mtpndd_config.double_leaf_table) {
        mtpndd_leaf_table_free(g_mtpndd_config.double_leaf_table);
        g_mtpndd_config.double_leaf_table = NULL;
    }

    mtpndd_op_cache_destroy();
    mtpndd_memory_pools_shutdown();

    /* Lace 1.6 requires sylvan_quit() before lace_stop() — sylvan_quit()
     * may issue RUN() for GC hooks and needs workers still alive. */
    sylvan_quit();
    lace_stop();

    memset(&g_mtpndd_pal_config, 0, sizeof(g_mtpndd_pal_config));
    g_mtpndd_node_count = 0;
    memset(&g_mtpndd_stats, 0, sizeof(g_mtpndd_stats));

    mtpndd_clear_error();

    return MTPNDD_SUCCESS;
}
