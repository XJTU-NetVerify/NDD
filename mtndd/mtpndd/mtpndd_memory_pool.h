// Multi-Terminal Parallel Network Decision Diagrams (MTPNDD)
// Memory pool management for frequently allocated objects.

#ifndef MTPNDD_MEMORY_POOL_H
#define MTPNDD_MEMORY_POOL_H

#include <stddef.h>
#include "mtpndd_node.h"

struct mtpndd_nodetable_bucket_entry_s;
typedef struct mtpndd_nodetable_bucket_entry_s mtpndd_nodetable_bucket_entry_t;

typedef struct {
    size_t node_slabs;
    size_t node_in_use;
    size_t node_capacity_per_slab;

    size_t edge_entry_slabs;
    size_t edge_entry_in_use;
    size_t edge_entry_capacity_per_slab;

    size_t nodetable_entry_slabs;
    size_t nodetable_entry_in_use;
    size_t nodetable_entry_capacity_per_slab;

    size_t edge_map_slabs;
    size_t edge_map_in_use;
    size_t edge_map_capacity_per_slab;

} mtpndd_memory_pool_stats_t;

void mtpndd_memory_pools_init(void);
void mtpndd_memory_pools_shutdown(void);
void mtpndd_memory_pools_snapshot(mtpndd_memory_pool_stats_t *stats);
#if MTPNDD_LOG_LEVEL >= MTPNDD_LOG_LEVEL_DEBUG
void mtpndd_log_memory_pools(const char *phase);
#endif

mtpndd_node_t *mtpndd_memory_acquire_node(void);
void mtpndd_memory_release_node(mtpndd_node_t *node);

edge_bucket_entry_t *mtpndd_memory_acquire_edge_entry(void);
void mtpndd_memory_release_edge_entry(edge_bucket_entry_t *entry);

mtpndd_edge_t *mtpndd_memory_acquire_edge_map(void);
void mtpndd_memory_release_edge_map(mtpndd_edge_t *edges);

mtpndd_nodetable_bucket_entry_t *mtpndd_memory_acquire_nodetable_entry(void);
void mtpndd_memory_release_nodetable_entry(mtpndd_nodetable_bucket_entry_t *entry);

#endif // MTPNDD_MEMORY_POOL_H
