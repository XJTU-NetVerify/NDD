# MTBDD per-worker refs (signed counts) design

## Context & goal
We see ref-table contention and uneven load under 2–4 workers. The goal is to eliminate write-side contention in external refs by sharding refs per worker while keeping GC correctness. We do **not** change LDD/ZDD usage in MTPNDD, but we accept that `refs_*` semantics change globally (signed counts) for simplicity.

## Chosen approach
- Use **one refs table per worker** for MTBDD external refs.
- Allow **signed ref counts** in `refs_table_t` so a worker can see negative counts when a `deref` happens on a different worker than the matching `ref`.
- During GC, **merge all worker tables** into a persistent **merge table** (cleared each GC) and only mark keys whose **global net count > 0**.

## Data structure changes
- `refs_table_t` keeps the same bucket layout (key in low 40 bits, count in high 24 bits) but treats count as **signed 24‑bit**.
- Add `refs_next_full(...)` (or equivalent) to return both key and count while iterating buckets.
- Add a non-concurrent `refs_merge_add(...)` helper (linear probing, no CAS) for GC merge.

## MTBDD integration
- `mtbdd_refs` becomes `refs_table_t* mtbdd_refs` (array sized by `lace_workers()`), plus `refs_table_t mtbdd_refs_merge`.
- `mtbdd_ref/mtbdd_deref` route to `mtbdd_refs[worker_id]`. If not a lace worker, default to worker 0.
- `mtbdd_count_refs` returns the sum across worker tables.

## GC merge algorithm
1) `refs_merge_clear(&mtbdd_refs_merge)` (memset whole table).
2) For each worker table: iterate buckets with `refs_next_full`, then `refs_merge_add(merge, key, count)`.
3) Iterate merge table and mark roots for entries where `count > 0`.

## Correctness & risk controls
- Remove/relax the debug assert in `refs_down` that requires prior `refs_up`; instead, optionally assert after merge that global net count is non-negative.
- GC is stop-the-world, so merge reads are not concurrent with updates.
- LDD/ZDD will inherit signed ref semantics; MTPNDD does not rely on them, so risk is acceptable for now.

## Implementation steps & checkpoints
1) Signed refs + `refs_next_full` + relaxed asserts. **Checkpoint A** (single worker N=8/12 OK).
2) Per-worker MTBDD refs tables + merge table init/free. **Checkpoint B** (single worker OK).
3) GC merge + net>0 mark. **Checkpoint C** (N=8/12 OK; 1–4 workers performance).

## Validation
- NQueens N=8/12, workers=1–4.
- Compare wall time and refs stats (probes, retries, avg time).
- Record results to `docs/archived/perf/` and `docs/archived/perf/plot_data.md`.

## Rollback plan
- Each checkpoint is a git commit; revert to prior commit if correctness or performance regressions appear.
