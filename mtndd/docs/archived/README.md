# Archived Optimization Notes

This directory contains write-ups of specific performance/concurrency changes, intended as citable engineering notes (e.g., for papers).

Each file follows the same rough structure:
- Motivation / symptom
- Root cause (with evidence when available)
- Implementation approach (with file paths)
- Verification and performance results (commands + outputs)
- Risks and follow-ups

## Index

- `docs/archived/2025-11-04-slab-memory-pools-design.md`
- `docs/archived/2025-11-12-dynamic-nodetable-growth-and-rehash.md`
- `docs/archived/2025-12-27-memset-and-alignment-micro-optimizations.md`
- `docs/archived/2026-01-10-edge-map-cached-hash-and-fast-compare.md`
- `docs/archived/2026-01-10-lock-free-operation-cache.md`
- `docs/archived/2026-01-10-spawn-sync-parallelization-at-recursion-points.md`
- `docs/archived/2026-01-10-temp-refs-vs-gc-protect.md`
- `docs/archived/2026-01-18-right-aligned-shared-bdd-vars.md`
- `docs/archived/2026-01-28-mtpndd-nodetable-concurrency-fix.md` (parallel scalability gating change)
- `docs/archived/2026-01-28-mtpndd-and-batched-sync-item-tasks.md`
- `docs/archived/2026-01-28-lace-idle-and-leapfrog-backoff.md`
- `docs/archived/2026-01-28-per-worker-slab-pool-caches.md`
- `docs/archived/perf/optimization_docs_audit_2026-02-11.md` (documentation completeness audit)
- `docs/archived/perf/per_worker_protect_temp_refs_2026-02-11.md` (per-worker protect/temp-refs status + results)
