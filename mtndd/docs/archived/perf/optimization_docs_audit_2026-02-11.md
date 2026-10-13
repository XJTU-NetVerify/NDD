# Optimization Docs Audit (2026-02-11)

## Scope
This audit checks whether optimization documents cover the six required items for paper use:
1) initial finding/symptom, 2) analysis/root cause, 3) what changed and why, 4) post-change results,
5) pros/cons, 6) next-step suggestions.

## Coverage Matrix
Legend: `Y` = explicitly covered, `P` = partially covered, `N` = not covered.

| Doc | Finding | Analysis | Change + Principle | Results | Pros/Cons | Next Step |
|---|---|---|---|---|---|---|
| `docs/archived/2026-01-28-mtpndd-and-batched-sync-item-tasks.md` | Y | Y | Y | Y | P | Y |
| `docs/archived/2026-01-28-per-worker-slab-pool-caches.md` | Y | Y | Y | Y | Y | Y |
| `docs/archived/2026-01-28-lace-idle-and-leapfrog-backoff.md` | Y | Y | Y | Y | Y | Y |
| `docs/archived/2026-01-28-mtpndd-nodetable-concurrency-fix.md` | Y | Y | Y | Y | Y | Y |
| `docs/archived/2026-01-10-temp-refs-vs-gc-protect.md` | Y | Y | Y | Y | Y | Y |
| `docs/archived/perf/mtbdd_per_worker_refs.md` | Y | Y | Y | Y | P | P |
| `docs/archived/perf/per_worker_protect_temp_refs_2026-02-11.md` | Y | Y | Y | Y | Y | Y |
| `docs/archived/perf/slab_pool_tune_A.md` | Y | Y | Y | Y | Y | Y |
| `docs/archived/perf/slab_pool_tune_B.md` | Y | P | Y | Y | P | Y |
| `docs/archived/perf/slab_pool_tune_C.md` | Y | P | Y | Y | P | Y |
| `docs/archived/perf/plot_data.md` | P | P | N | Y | N | N |
| `docs/archived/perf/efficiency_hypotheses.md` | Y | Y | N | N | P | Y |

## Current Gaps
- `mtbdd_per_worker_refs` has benchmark data and implementation summary, but lacks explicit downside analysis section and a concrete follow-up plan with acceptance criteria.
- `plot_data.md` is a data source, not a narrative doc; it should not be used alone in paper text.
- `efficiency_hypotheses.md` is a planning/evidence note and intentionally has no implementation/result section.
- Per-worker protect/temp-refs direction now has a standalone note, but runtime counters are still pending implementation.

## Recommended Additions (Paper-facing)
1. Done: added `docs/archived/perf/per_worker_protect_temp_refs_2026-02-11.md` for:
   - symptom and anomaly signal,
   - exact data structure choice,
   - correctness constraints,
   - benchmark deltas and risk notes.
   Next: add runtime counters referenced by that note.
2. For `mtbdd_per_worker_refs`, add:
   - explicit trade-off section (merge saturation risk, negative-net monitoring),
   - next actions with metrics (e.g., merge saturation ratio, `net<0` key count).
3. Keep `plot_data.md` as raw table source only; reference it from narrative docs rather than using it directly in conclusions.

## Notes
- This audit is documentation-only; no code behavior is changed.
- Benchmark semantics note for reproducibility: `n_workers=0` means auto worker count (all available workers), not single-worker mode.
