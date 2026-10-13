# Optimization Docs Completeness Audit (2026-02-09)

## Audit scope
- `docs/archived/perf/mtbdd_per_worker_refs.md`
- `docs/archived/perf/mtbdd_per_worker_protect.md`
- `docs/archived/perf/mtpndd_temp_refs_stack_plan.md`
- `docs/archived/perf/plot_data.md`
- `docs/plans/2026-01-29-mtbdd-per-worker-protect-impl.md`
- `docs/plans/2026-01-29-mtbdd-per-worker-protect-design.md` (in `feature/c`)

## Checklist definition
Required fields per optimization doc:
1. Initial findings/problem statement
2. Analysis and constraints
3. Modification details and principles
4. Post-change results
5. Further pros/cons analysis
6. Next-step recommendations

## Coverage result
| Document | 1 | 2 | 3 | 4 | 5 | 6 | Notes |
|---|---|---|---|---|---|---|---|
| `mtbdd_per_worker_refs.md` | yes | yes | yes | yes | yes | yes | Completed in this audit pass. |
| `mtbdd_per_worker_protect.md` | yes | yes | yes | yes | yes | yes | Completed in this audit pass. |
| `mtpndd_temp_refs_stack_plan.md` | yes | yes | yes | yes | yes | yes | Added for allocation-focused optimization track. |
| `plot_data.md` | n/a | n/a | n/a | yes | partial | partial | Data table document, not design narrative. |
| `...protect-impl.md` | yes | yes | yes | partial | partial | yes | Implementation plan document; runtime result details live in perf docs. |
| `...protect-design.md` | yes | yes | yes | n/a | yes | yes | Design-only doc. |

## Branch/storage status
- `feature/mtbdd-per-worker-protect` has complete per-worker protect perf doc set.
- `feature/c` currently does **not** contain `docs/archived/perf/mtbdd_per_worker_protect.md` yet (not merged from worktree).

## Follow-up recommendation
1. Merge/cherry-pick the doc commits from `feature/mtbdd-per-worker-protect` into `feature/c` to avoid documentation drift.
2. Keep `plot_data.md` as data-only; keep analysis narrative in per-optimization docs.
3. Keep branch-to-branch repeated benchmark tables (workers 1-6, 5 repeats) in both `mtbdd_per_worker_protect.md` and `plot_data.md` to avoid single-run misinterpretation.

## Update log (2026-02-09, later pass)
- Added a second `LOG_LEVEL=0` branch-to-branch rerun with explicit high-load evidence (`/proc/loadavg`) to:
  - `docs/archived/perf/mtbdd_per_worker_protect.md`
  - `docs/archived/perf/plot_data.md`
  - `docs/archived/perf/mtpndd_temp_refs_stack_plan.md`
- This keeps "/tmp-only" measurements persisted in repository docs.
- Added an interleaved high-load rerun (alternating branch order per repeat) and order-effect summary, to separate order bias from environment noise.
- Added Step B implementation status and quick validation notes to:
  - `docs/archived/perf/mtpndd_temp_refs_stack_plan.md`
  - `docs/archived/perf/mtbdd_per_worker_protect.md`
  - `docs/archived/perf/plot_data.md`
- Added low-load interleaved branch-to-branch table (custom CPU sets, workers 1-6, 5 repeats) as current best runtime comparison for Step B.
- Added wrong-answer root-cause record:
  - `docs/archived/perf/opcache_concurrency_root_cause_n12.md`
  - Includes: reproducible failure cases, satcount-path exclusion, isolation experiments (`no-spawn`, `no-cache`), op-cache race analysis, and post-fix verification.
