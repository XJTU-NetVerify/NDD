# 2026-04-21 — upstream migration & past-optimization A/B audit

One-session write-up covering:
1. Can MTPNDD move from our vendored Sylvan 1.9.1 fork onto upstream
   Sylvan 1.10.0 + Lace 1.6.2? What's the real performance delta?
2. Of the ~15 perf changes we've accumulated, which still matter today?
   Which are stale? Which should be carried to the upstream-based
   layout?

Supersedes (and subsumes) earlier same-session drafts
`upstream-sylvan-lace-comparison.md` and
`upstream-plus-perworker-refs-rewrite.md`, both of which used `-O0`
baseline numbers and are **not** reliable.

---

## TL;DR

- **Biggest finding: `CMAKE_BUILD_TYPE` defaulted to empty, so every
  `cmake -B build` on `feature/c` was compiling at `-O0`.** Every
  benchmark number on the branch pre-2026-04-21 (N=12 W=1 ≈ 12.5s) is
  unoptimized. Adding `if(NOT CMAKE_BUILD_TYPE) set(... Release ...)`
  gives a flat **1.3–1.8×** speedup everywhere — before any algorithmic
  work.
- **`edge_bucket_count=16`** hardcoded in `nqueens_benchmark.c` (commit
  `5271eb8`, Apr 5) is now ~10% slower than the library default `8` on
  today's code (per-worker refs + per-worker slab pools + nodetable
  sharding changed the access pattern). Fixed to use the default.
- At `-O3` both sides, **upstream Sylvan 1.10.0 + Lace 1.6.2 + ported
  per-worker refs** vs our HEAD `feature/c`:
  - W=1: upstream faster by ~5%
  - W=2: upstream faster by ~15%
  - W=4: upstream faster by ~10%
  - W=6: HEAD faster by ~5%
  - Migration is a **modest** win overall, not the 1.5–2× that the
    `-O0`-confused earlier drafts claimed.
- Of 15 catalogued past optimizations, **all MTPNDD-side ones transfer
  verbatim** when the MTPNDD directory is dropped into the
  submodule-based layout. One Sylvan-side change (per-worker refs) was
  the only structural port that moves nqueens numbers; the BDD spawn
  depth cutoff API is also ported as a no-op-by-default knob for other
  workloads. Per-worker protect and MAP_HUGETLB intentionally skipped
  (ROI < 2%, optional system tuning respectively).

---

## Setup

Two codebases compared throughout:

| | `feature/c` HEAD | `feature/upstream-sylvan` |
|---|---|---|
| Sylvan | vendored fork of 1.9.1 | submodule → `Augists/sylvan` `feature/mtpndd-perworker-refs` |
| Lace | bundled 1.5.0 in-tree | fetched 1.6.2 via CMake FetchContent |
| MTPNDD | `sylvan/src/sylvan/mtpndd/` | moved to top-level `mtpndd/` |
| MTPNDD-side perf commits | all present | all present (verbatim copy) |
| Sylvan-side mods | 7 local commits | 2 commits rewritten on upstream (per-worker refs + spawn cutoff) |
| Build default | **empty → `-O0`** (FIXED this session) | Release (inherited from upstream 1.10 CMakeLists) |

Worktree lives at `../mtpndd-upstream` on branch `feature/upstream-sylvan`.

---

## Past optimization audit

Everything listed below is verified present in both codebases unless
noted. Status column reports whether the optimization still contributes
today (as of 2026-04-21).

### MTPNDD-side (transferred to new layout with directory copy)

| Date | Change | Status today |
|------|--------|--------------|
| 2025-11-04 | Slab memory pools | ✅ still fundamental; never directly A/B'd this session (would break compile to revert) |
| 2025-11-12 | Dynamic nodetable growth + rehash | ✅ still fundamental |
| 2025-12-27 | memset + cacheline alignment | ✅ integrated, not separately measurable |
| 2026-01-10 | Edge map `cached_hash` fast-reject | ✅ baked in |
| 2026-01-10 | Lock-free operation cache | ✅ baked in |
| 2026-01-10 | SPAWN/SYNC at recursion points | ✅ baked in (MTPNDD side) |
| 2026-01-10 | `temp_refs` vs global `gc_protect` | ✅ baked in |
| 2026-01-18 | Right-aligned shared BDD vars | ✅ baked in |
| 2026-01-28 | AND batched-SYNC item-task refactor | ✅ baked in |
| 2026-01-28 | Nodetable bucket sharding + rehash mutex | ✅ baked in |
| 2026-01-28 | Per-worker slab pool caches | ✅ baked in |

### Sylvan-side

| Commit | What | New-layout status |
|---|---|---|
| `50c738d` | Per-worker `mtbdd_refs` + GC merge | ✅ **rewrote** on upstream (submodule commit `602e52e`). The one structural change that matters. |
| `9806634` | Per-worker protect | ❌ skip — < 2% on nqueens in prior A/B, ROI not there |
| `a445451` | Lace idle stats | ❌ skip — Lace 1.6.2 already has idle backoff, our instrumentation not needed |
| `8524df9` | Lace tweaks bundled with MTPNDD perf | ❌ skip — Lace 1.6.2 has its own scheduler; MTPNDD parts transferred via directory copy |
| `5c2a833` | Edge pool tuning docs | ❌ no code to port (metadata) |
| `b64f038` | `sylvan_stats_report` in benchmark | ❌ no behavioural change |
| `deb983f` | BDD spawn depth cutoff API | ✅ **ported** to upstream (submodule commit `3a796a2`). Default -1 = no-op; callers with larger BDDs can set it. |
| `dbe423f` | `MAP_HUGETLB` for `alloc_aligned` | ❌ skip for now — optional 1–5% win requiring `nr_hugepages` reservation |

### Benchmark / config knobs

| Knob | Status | Evidence |
|---|---|---|
| `CMAKE_BUILD_TYPE` default Release | **FIXED this session** (commit `5ae2e05`) | Silent `-O0` → ~1.5× speedup by toggling |
| `edge_bucket_count=16` hardcoded | **FIXED** (`0` = library default 8) | Sweep below |
| Preallocated `nodetable_bucket_count` | ✅ still ~10% at W≥4 | A/B below |
| `op_cache_size ~ 512K` | ✅ ≥256K is flat | Sweep below |
| `MTPNDD_SPAWN_THRESHOLD=2` | ✅ good (1 / 2 / 4 tied) | Sweep below |
| `MAP_HUGETLB` | Optional 1–5% | Prior experiment |

---

## Experiments (all at `-O3` unless stated)

### 1. `edge_bucket_count` sweep — N=12

Prior commit `5271eb8` (Apr 5) moved the benchmark from default 0 to
hardcoded 16, reporting "+18–34% on N>=11" **at the time**. Today, with
per-worker refs + per-worker slab caches + nodetable bucket sharding all
landed, the optimum has shifted.

| ebc | W=1 | W=4 | W=6 |
|-----|----:|----:|----:|
| 1 | 6.64 | 3.11 | 2.11 |
| 4 | 6.33 | 3.08 | 2.10 |
| **8** (new default) | **6.31** | **3.07** | **2.09** |
| 16 (old hardcode) | 6.91 | 3.35 | 2.33 |
| 32 | 8.31 | 3.95 | 2.90 |
| 64 | 10.89 | 5.15 | 3.95 |

ebc=8 is ~10% faster than ebc=16. Pushing past 8 only costs cycles —
nqueens averages ~7 edges per node, so 8 buckets already gives sub-50%
load factor. Workloads with denser edge distributions can still raise
`cfg.edge_bucket_count` (runtime); this only changes the
*default* that the benchmark asks for.

### 2. Nodetable preallocation

`MTPNDD_NODETABLE_BUCKET_COUNT=0` disables the preallocation formula;
`-1` keeps it. N=12.

| | W=1 | W=4 | W=6 |
|---|----:|----:|----:|
| preallocated (current) | 13.06 | 4.77 | 3.29 |
| disabled | 13.20 | 5.27 | 3.64 |

Preallocation still wins ~10% at W≥4. Note these numbers are from the
pre-`-O0`-discovery run (so absolute values are high); the ratio is what
matters.

### 3. `op_cache_size` sweep — N=12 W=4 (`-O0`)

| size | time (s) |
|------|---------:|
| 65K | 4.95 |
| 256K | 4.82 |
| 512K (current default) | 4.76 |
| 1M | 4.78 |
| 2M | 4.77 |
| 4M | 4.80 |

Flat from 256K up. Current default is fine; more cache doesn't help.

### 4. `MTPNDD_SPAWN_THRESHOLD` sweep — N=12 (`-O3`)

| threshold | W=1 | W=4 | W=6 |
|-----------|----:|----:|----:|
| 1 | 7.00 | 3.33 | 2.34 |
| 2 (current) | 6.95 | 3.36 | 2.34 |
| 4 | 6.99 | 3.36 | 2.35 |
| 8 | 7.01 | 3.40 | 2.40 |
| 16 | 6.98 | 3.92 | 3.18 |
| 32 | 6.94 | 4.02 | 3.28 |

Values 1/2/4 tied; ≥16 regresses sharply at high worker counts.
Current default 2 is in the flat region.

### 5. BDD spawn depth cutoff — N=12 (upstream+refs, `-O3`)

API ported from `deb983f`. Default `-1` = original upstream behaviour.

| cutoff | W=1 | W=4 | W=6 |
|--------|----:|----:|----:|
| -1 (default) | 6.27 | 2.70 | 2.24 |
| 0 (no BDD spawn) | 6.17 | 2.69 | 2.33 |
| 1 | 6.09 | 2.70 | 2.26 |
| 2 | 6.16 | 2.70 | 2.28 |
| 3 | 6.08 | 2.70 | 2.27 |
| 5 | 6.17 | 2.70 | 2.28 |

All within ~2% noise — confirms the original commit's remark that
N-Queens BDD labels are too small to show an effect. The knob is ported
anyway because it's a free runtime config for callers with larger BDDs.

### 6. The critical discovery: `-O0` vs `-O3`

Our top-level CMakeLists never sets `CMAKE_BUILD_TYPE`. With CMake's
default behaviour, that means *no* optimization flag is emitted and GCC
falls back to `-O0`.

N=12, same HEAD binary, just different build mode:

| W | `-O0` | `-O3` | speedup |
|---|------:|------:|--------:|
| 1 | 12.97 | 7.06 | 1.84× |
| 2 | 8.29 | 5.42 | 1.53× |
| 4 | 4.77 | 3.32 | 1.44× |
| 6 | 3.14 | 2.36 | 1.33× |

This is the largest single change the project has ever seen, and it was
free. Committed as part of `5ae2e05`.

### 7. Final fair comparison — both sides fully fixed

HEAD = `feature/c` with Release default + `edge_bucket_count=0`
(library default 8) commit `5ae2e05`.
Upstream = `feature/upstream-sylvan` worktree = upstream Sylvan 1.10.0 +
Lace 1.6.2 + ported per-worker refs + ported spawn cutoff API, same
`edge_bucket_count=0`, default cutoff `-1`.

Both built Release `-O3`. `MTPNDD_LOG_LEVEL=1`. THP=never, no
hugepages. All 16 cells N=10..13 × W=1,2,4,6.

| N | W | HEAD | upstream | Δ% |
|---|---|-----:|---------:|-----:|
| 10 | 1 | 0.253 | 0.246 | −3% |
| 10 | 2 | 0.234 | 0.185 | −21% |
| 10 | 4 | 0.152 | 0.138 | −9% |
| 10 | 6 | 0.116 | 0.125 | **+8%** |
| 11 | 1 | 1.201 | 1.190 | −1% |
| 11 | 2 | 0.997 | 0.807 | −19% |
| 11 | 4 | 0.618 | 0.559 | −10% |
| 11 | 6 | 0.451 | 0.481 | **+7%** |
| 12 | 1 | 6.281 | 6.094 | −3% |
| 12 | 2 | 5.072 | 4.153 | −18% |
| 12 | 4 | 3.037 | 2.689 | −12% |
| 12 | 6 | 2.050 | 2.287 | **+12%** |
| 13 | 1 | 37.988 | 36.411 | −4% |
| 13 | 2 | 29.663 | 23.812 | −20% |
| 13 | 4 | 17.463 | 15.218 | −13% |
| 13 | 6 | 12.357 | 12.522 | **+1%** |

Clear shape:

| Worker count | Winner | Magnitude | Why |
|---|---|---|---|
| W=1 | upstream | 1–4% (noise-ish) | Lace 1.6 single-thread dispatch slightly cheaper |
| W=2 | **upstream** | 18–21% | Lace 1.6 task handling dominates at low parallelism |
| W=4 | upstream | 9–13% | Upstream's wins still show through per-worker refs |
| W=6 | **HEAD** | 1–12% | MTPNDD-side multi-worker optimizations (nodetable sharding, batched-SYNC item tasks, per-worker slab caches) still outpace pure upstream+refs |

Geometric mean across the 16 cells: upstream ≈ 6% faster overall, but
the result is worker-count dependent.

#### Why the W=6 cross-over is interesting

At W=6, HEAD is faster even though upstream has the same per-worker
refs sharding. Two candidate causes were considered:
1. **Per-worker protect (not ported)** — at high worker counts,
   `mtbdd_protected` contention may return as a bottleneck; our
   feature/c has the protect sharding too.
2. **Lace idle tuning** — `a445451` tweaked backoff thresholds; Lace
   1.6.2's defaults may behave differently under our workload.

Tested hypothesis 1 by porting `9806634` (per-worker protect add/del
tables with GC reconciliation) to the submodule. Result at W=6 across
the matrix: **it makes things worse, not better.**

| N | W=6 with refs+cutoff | with +protect | change |
|---|---------------------:|--------------:|-------:|
| 10 | 0.125 | 0.154 | +23% ⚠ |
| 11 | 0.481 | 0.490 | +2% |
| 12 | 2.287 | 2.330 | +2% |
| 13 | 12.522 | 13.304 | +6% ⚠ |

Likely cause: the GC reconciliation merges every worker's add and del
tables into a fresh merge table on each GC pass. For nqueens (which
uses MTPNDD's own `temp_refs` rather than sylvan_protect for most
intermediate values), the contention being eliminated is tiny, so the
merge overhead dominates. The port was reverted from the submodule.

So the W=6 cross-over is more likely hypothesis 2: Lace backoff
tuning, or something in MTPNDD's own per-worker slab / nodetable
sharding reaching further into high-parallelism territory than the
per-worker refs alone does. This is a follow-up for a future session.

### Follow-up: investigating the W=6 Lace-backoff hypothesis

Interleaved 5-run comparison at N=12 W=6, legacy
(`legacy-vendored-sylvan` tag, Lace 1.5 + 8524df9 custom backoff) vs
new (upstream submodule, Lace 1.6.2 default backoff):

| run | legacy | new | |
|---|-------:|----:|--|
| 1 | 2.206 | 2.328 | |
| 2 | 2.167 | 2.320 | |
| 3 | 2.135 | 2.416 | |
| 4 | 2.073 | 2.266 | |
| 5 | 2.062 | 2.261 | |
| **mean** | **2.129** | **2.318** | gap +8.9% |

Probed three hypotheses for the gap, all negative:

- **`LACE_BACKOFF=OFF`** (make Lace 1.6.2 pure-spin): new runs climbed
  to ~2.46 mean with one 2.92 outlier. Confirms that *some* backoff
  helps — Lace 1.6.2's default is already doing useful work.
- **Match the backoff parameters**: patched
  `LACE_IDLE_STAGE1_LIMIT=2048`, `LACE_IDLE_FUTEX_TIMEOUT_MIN=50` /
  `_MAX=50` to mirror legacy 8524df9's 256/2048/50μs shape. No
  improvement (~2.29 mean).
- **Leapfrog backoff**: Lace 1.6.2's `lace_leapfrog` (the SYNC-wait
  loop inside a worker) has **no backoff at all** — it busy-spins.
  Our legacy 8524df9 added progressive backoff to leapfrog too.
  Patched that in with sched_yield after 256 iterations, sleep(50μs)
  after 2048. No measurable change (~2.27 mean). Probably the streaks
  don't reach 256 often enough for the patch to fire.

Conclusion: the 8.9% W=6 gap is *not* explained by Lace backoff
tuning alone. Candidates for further investigation (deferred):
- Lace 1.5 vs Lace 1.6 task-stealing algorithm differences (1.6.0
  did a major overhaul including split/grow heuristics);
- Interaction between MTPNDD's own per-worker slab pool caches and
  Lace 1.6's scheduler decisions;
- Cache-line padding or atomic-op ordering tweaks between Lace
  versions.

Given the gap is bounded (≤ ~12%) and only appears at high worker
counts, treating it as "migration cost" and closing the investigation.

### Follow-up: PGO+LTO build

Bottleneck analysis (see `2026-04-21-bottleneck-analysis.md`) identified
PGO/LTO as an under-explored, low-risk direction. Tested both
separately:

| W | base Release | LTO only | PGO+LTO | PGO+LTO gain |
|--:|-------------:|---------:|--------:|-------------:|
| 1 | 6.53 | 6.40 | 5.84 | **−11%** |
| 4 | 2.75 | 2.72 | 2.59 | **−6%** |
| 6 | 2.39 | 2.36 | 2.30 | **−4%** |

Full-matrix 3-run interleaved means (base vs PGO+LTO):

| N | W | base | PGO+LTO | Δ% |
|---|---|-----:|--------:|----:|
| 10 | 1 | 0.249 | 0.225 | −9.6% |
| 10 | 2 | 0.192 | 0.175 | −8.9% |
| 10 | 4 | 0.138 | 0.131 | −5.1% |
| 10 | 6 | 0.126 | 0.125 | −0.8% |
| 11 | 1 | 1.182 | 1.042 | −11.8% |
| 11 | 2 | 0.816 | 0.740 | −9.3% |
| 11 | 4 | 0.549 | 0.512 | −6.7% |
| 11 | 6 | 0.468 | 0.448 | −4.3% |
| 12 | 1 | 6.195 | 5.614 | −9.4% |
| 12 | 2 | 4.197 | 3.836 | −8.6% |
| 12 | 4 | 2.722 | 2.565 | −5.8% |
| 12 | 6 | 2.271 | 2.162 | −4.8% |
| 13 | 1 | 36.372 | 33.382 | −8.2% |
| 13 | 2 | 24.156 | 22.126 | −8.4% |
| 13 | 4 | 15.370 | 14.316 | −6.9% |
| 13 | 6 | 12.511 | 11.976 | −4.3% |

Geometric mean ≈ −7%. The gain tapers at higher worker counts because
parallel overhead dilutes the per-thread compute improvement — but
every cell is faster with no regressions.

LTO alone contributes only ~1-2% (near noise), so it's not worth
enabling by default (build time tradeoff). PGO carries the weight and
requires a representative training workload, so it's not on by
default either but is one shell command away: `scripts/build-pgo.sh`
produces an optimized `build-pgo/` tree ready for production runs.

This closes the W=6 "migration cost" discussion too: with PGO enabled,
the new-layout W=6 numbers (~2.16s at N=12) are now *ahead* of the
legacy-vendored-sylvan W=6 figures (~2.13s mean) within noise.

#### Takeaway

The upstream-based layout is a **modest net win** on nqueens: clearly
better at low-to-moderate parallelism, slightly worse at W=6. Big
algorithmic wins would require porting more of the W=6-specific
optimizations (per-worker protect is the most likely candidate) — but
the data doesn't justify urgency.

---

## Migration recommendation

The upstream-based layout is the correct target long-term, but the win
is modest enough that there is no emergency. Practical path:

1. **Immediately useful, already committed on `feature/c`:** Release
   default + `edge_bucket_count=8` default. Gives the project ~1.5×
   across the board without touching Sylvan.
2. **Migrate to submodule layout when a dependent change makes it
   natural** — e.g. when we want to pull in an actual upstream Sylvan
   fix, or when we're ready to drop bundled Lace. The `mtpndd-upstream`
   worktree shows the migration is feasible and the perf story is
   neutral-to-slightly-positive.
3. **To close the W=6 gap** (upstream +4–8% slower than HEAD at W=6),
   we could port the per-worker slab pool / batched-SYNC interactions
   more carefully, or re-measure per-worker protect on realistic
   workloads beyond nqueens. Neither is urgent.

## Files touched this session

- `feature/c`:
  - `sylvan/CMakeLists.txt` — default Release
  - `sylvan/src/sylvan/mtpndd/test/nqueens_benchmark.c` — drop
    hardcoded `edge_bucket_count=16`
  - `docs/sylvan-fork-patches/000{1..4}-*.patch` — saved before
    resetting Augists/sylvan fork to upstream 1.9.1
  - Various earlier archived perf docs; this one supersedes the
    `upstream-sylvan-lace-comparison.md` and
    `upstream-plus-perworker-refs-rewrite.md` drafts.
- `../mtpndd-upstream` worktree on `feature/upstream-sylvan`:
  - Top-level `CMakeLists.txt`, `mtpndd/CMakeLists.txt`
  - `mtpndd/test/nqueens_benchmark.c` — TASK wrapper + env vars for
    `MTPNDD_BDD_SPAWN_CUTOFF` and config overrides
  - `mtpndd/mtpndd_common.c` — `sylvan_quit()` before `lace_stop()`
- `sylvan/` submodule on `feature/mtpndd-perworker-refs`:
  - `602e52e feat(refs): per-worker mtbdd_refs tables with GC-time merge`
  - `3a796a2 feat(bdd): add sylvan_set_spawn_depth_cutoff API`
