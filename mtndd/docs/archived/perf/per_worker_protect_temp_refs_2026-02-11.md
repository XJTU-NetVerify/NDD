# Per-Worker Protect + Temp Refs (Status and Analysis, 2026-02-11)

## 1) Initial finding / symptom
- Goal: improve multi-worker efficiency on N-Queens (`N=12`) while keeping correctness stable.
- During iterative experiments, two signals appeared:
  1. runtime fluctuations across repeated runs and worker counts;
  2. occasional wrong answers (`14198/14199` instead of `14200`) in some experimental runs.
- We also suspected temporary reference management could add avoidable allocation overhead under parallel recursion.

## 2) Analysis / root cause understanding
- `mtbdd_refs` side has already been moved to per-worker shards with GC-time merge (see commit `50c738d` and `docs/archived/perf/mtbdd_per_worker_refs.md`).
- In MTPNDD node operations, `temp_refs` is currently a **per-operation dynamic array**, not a per-worker pool:
  - implementation: `sylvan/src/sylvan/mtpndd/mtpndd_node.c:75`
  - init with capacity `0`, first growth to `32`, then doubles on demand (`realloc`).
  - entries are appended directly; duplicates are allowed by design.
  - release stage dereferences all pushed nodes in order.
- This means current behavior is **array-based lifetime tracking**, not multiset-based dedup lookup.

## 3) What was changed and why
- No new code change in this doc step; this is a consolidation of existing behavior and recent experiment outputs.
- Clarified data-structure semantics for paper text:
  - MTPNDD temp refs: append-only array per operation (duplicates allowed).
  - Sylvan protect tables: hash-table style protect/unprotect infrastructure (separate layer).

## 4) Post-change results (archived from `/tmp`)
Source files: `/tmp/n12_perworker_summary.tsv`, `/tmp/n12_perworkerprotect_w1w6_r5.tsv`.

| workers | mean(s) | median(s) | stdev(s) | cv(%) | solutions summary |
|---:|---:|---:|---:|---:|---|
| 1 | 12.236 | 12.273 | 0.116 | 0.95 | 14200x5 |
| 2 | 7.675 | 7.692 | 0.046 | 0.59 | 14200x5 |
| 3 | 5.991 | 5.982 | 0.024 | 0.40 | 14199x1, 14200x4 |
| 4 | 5.606 | 5.600 | 0.024 | 0.42 | 14200x5 |
| 5 | 4.718 | 4.706 | 0.020 | 0.42 | 14200x5 |
| 6 | 4.761 | 4.705 | 0.147 | 3.08 | 14200x5 |

Observed: scaling improves from 1->5 workers in this dataset, then slightly plateaus at 6 workers with higher variance.

## 5) Pros / cons and risk discussion
Pros:
- Current temp-refs array keeps hot-path operations simple (append + linear release), with low per-entry overhead.
- Duplicate ref entries are naturally supported; correctness is based on balanced ref/deref counts, not dedup identity.

Cons / risks:
- Per-operation allocate/free model may cause repeated allocator traffic under deep/branchy recursion.
- Without explicit counters, we cannot yet quantify:
  - how often temp-refs grows (`realloc` pressure),
  - how often duplicate refs appear per operation,
  - whether duplicate density is high enough to justify a set/multiset structure.
- Occasional wrong-answer runs require continued correctness monitoring during parallel tuning.

## 6) Next-step recommendations
1. Add DEBUG/DEVELOP-level counters for:
   - `temp_refs_init_total`, `temp_refs_grow_total`, `temp_refs_max_capacity`,
   - `temp_refs_push_total`, `temp_refs_release_total`,
   - duplicate ref frequency (same node pushed multiple times within one operation).
2. Add correctness monitors for experimental branches:
   - count of non-`14200` outcomes in repeated `N=12` runs,
   - optional assert/report on suspicious ref/deref imbalance during debug runs.
3. Evaluate two follow-up designs with the same benchmark matrix (`N=12`, workers `1..6`, repeat >= 5):
   - A: increase temp-refs initial capacity (lower `realloc` risk),
   - B: per-worker temp-refs stack/pool reuse (reduce allocator churn).

## Repro command baseline
- Build: `cd sylvan && cmake -B build -DMTPNDD_LOG_LEVEL=0 && cmake --build build`
- Run: `./build/src/sylvan/mtpndd/mtpndd_nqueens_benchmark 12 <workers>`
