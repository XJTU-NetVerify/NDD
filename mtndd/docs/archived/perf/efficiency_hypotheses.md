# Efficiency improvement hypotheses (based on evidence)

This note lists candidate efficiency improvements derived from contention evidence. These are **not implemented** yet; they are prioritized by likely impact and cost.

## Evidence summary (links)
- Futex hotspots map to slab pool locks (`g_edge_map_pool`, `g_edge_entry_pool`, `g_nodetable_entry_pool`, `g_node_pool`): `docs/archived/perf/futex_addr_mapping.md`
- Refcount table cacheline dominates HITM (likely `mtbdd_refs`): `docs/archived/perf/c2c_addr_mapping.md`
- c2c intensity shows ~78% of HITM on a single cacheline: `docs/archived/perf/c2c_intensity_metrics.md`
- refs_stats (1c vs 4c): `docs/archived/perf/refs_stats_n12.md`
- Note: N=12 runtimes are sensitive to `MTPNDD_LOG_LEVEL`; DEBUG (>=2) builds are significantly slower. Use consistent build flags when comparing runs.

## Hypothesis 1: Slab pool lock contention is a primary scaling limiter
**Why:** perf futex tracepoints show ~95% of futex activity on slab pool locks (edge_map/edge_entry dominate), and futex WAKE/WAIT counts rise steeply with workers.

**Potential improvements:**
- Increase per-worker cache sizes (`MTPNDD_POOL_LOCAL_MAX`, `MTPNDD_POOL_REFILL_BATCH`) to reduce lock acquisitions.
- Add per-worker “owner” free lists: defer frees to the owning worker to reduce cross-worker contention.
- Consider separate pools per worker for edge_map/edge_entry with periodic global reconciliation.

## Hypothesis 2: Refcount table (`mtbdd_refs`) cacheline bouncing dominates HITM
**Why:** c2c shows ~78% of HITM on a single cacheline mapped to `mtbdd_refs`, and perf stacks show heavy `refs_*` calls in hot paths.

**Potential improvements:**
- Batch refcount updates per worker (local delta buffers) and flush periodically (e.g., at sync points or GC boundaries).
- Reduce refs traffic on ephemeral temporaries by widening local “protected” stacks or using region-based lifetimes for short-lived nodes.
- Explore sharded refs tables (per-worker shards, periodic merge) to reduce shared-line updates.

## Hypothesis 3: Work scheduling overhead adds tail latency under load
**Why:** futex tracepoints show WAKE-heavy behavior inside `lace_steal_loop`, implying frequent wakeups in worker scheduling under heavy recursion.

**Potential improvements:**
- Increase task granularity only when needed (avoid too-fine tasks that amplify scheduler contention).
- Use worker-local queues or batching in mtpndd AND recursion to reduce inter-worker steals.

## Next steps to validate
1. Vary pool cache sizes and measure change in futex counts (plot from `plot_data.md`).
2. Add refcount batching and compare HITM ratios from c2c.
3. Re-run N=12 with 1–4 workers to confirm scaling improvements.
