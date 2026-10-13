# 2026-04-21 — Bottleneck analysis (post-migration)

Ran `perf stat` + `perf record -F 997 -g --call-graph=dwarf` on the
current `feature/c` tip (submodule layout, Release `-O3`,
`edge_bucket_count=8` default) for the reference workload
`mtpndd_nqueens_benchmark 12 6`.

## perf stat overview

Single N=12 W=6 run:

| Counter | Value | Derived |
|---|---|---|
| task-clock | 12.3 s across workers (wall 2.37 s) | 5.2× parallel efficiency / 6 workers |
| cache-references | 2.84 G | |
| cache-misses | 325 M | **11.5% miss rate** |
| branches | 5.45 G | |
| branch-misses | 73 M | 1.34% (low) |
| instructions | 25.4 G | |
| cycles | 42.9 G | **IPC 0.59** |
| context-switches | 0 | |

IPC 0.59 + 11.5% cache miss rate = workload is clearly memory-bound.
Not CPU-compute-bound, not branch-mispredict-bound, not syscall-bound.

## Hot functions (self %, `--no-children`)

| % | Symbol | Layer | Category |
|--:|---|---|---|
| 15.89 | refs_up | Sylvan | external-refs sharding |
| 11.98 | refs_down | Sylvan | external-refs sharding |
| 11.52 | find_node_in_nodetable | MTPNDD | nodetable lookup |
| 9.19 | mtpndd_and_rec_CALL | MTPNDD | AND recursion |
| 8.22 | mtpndd_memory_acquire_edge_entry | MTPNDD | slab |
| 5.62 | mtpndd_op_cache_lookup_binary | MTPNDD | op cache |
| 5.22 | mtpndd_memory_acquire_edge_map | MTPNDD | slab |
| 2.76 | mtpndd_is_terminal | MTPNDD | helper |
| 2.48 | mtpndd_ref | MTPNDD | API |
| 2.21 | sylvan_and_CALL | Sylvan | BDD op |
| 2.15 | pthread_spin_lock | libpthread | nodetable shard lock |
| 2.14 | mtpndd_and_two_phase_same_field | MTPNDD | AND variant |
| 1.98 | mtpndd_mk | MTPNDD | canonicalize |
| 1.86 | mtpndd_deref | MTPNDD | API |

Rolled up:

| Cluster | % |
|---|--:|
| Sylvan external refs (refs_up + refs_down) | 27.87 |
| MTPNDD hash / cache / core AND | 32.21 |
| Slab allocator acquire paths | ~14.5 |
| API thunks (ref/deref/temp_refs) | ~7.4 |
| Lace / pthread housekeeping | ~5.5 |

## Root-cause reading

1. **The per-worker refs sharding eliminated contention, but each
   ref/deref still runs a hash probe + atomic CAS.** 28% of CPU in the
   two functions is the dominant cost. MTPNDD protects every BDD edge
   label (213 `sylvan_ref(...)` call sites across `mtpndd_node.c` and
   `mtpndd_arith.c`), so the raw call frequency is enormous.
2. **MTPNDD core work (hash + AND recursion + op cache + mk) is ~32%.**
   This is the algorithmic baseline — canonicalization and recursive
   AND are what MTPNDD *is*. Hard to shrink without rethinking the
   node representation.
3. **Slab allocator acquire is ~14.5%.** Per-worker slab caches are
   already on; the remaining cost is the fast-path itself (pointer
   bump + occasional refill).
4. **Memory-bound characteristics** (IPC 0.59, 11.5% miss rate)
   suggest optimizations that improve locality will have more effect
   than raw algorithmic tweaks.

## Potentially under-explored optimizations

Ordered by estimated ROI. None have been A/B tested this session.

### 1. Shrink the `sylvan_ref` / `refs_up` hot path (target: 28%)

- **Inline `refs_up`/`refs_down`** — they're small, but the library
  boundary call overhead is ≥1% from the profile. Mark them
  `static inline` in the header.
- **Ref coalescing / label-cache**: detect when `sylvan_ref(x)` is
  called with an `x` that the current worker already holds, and skip
  the probe by using a tiny per-worker hot cache (a few recent BDDs
  in registers). Most nqueens AND fragments reference the same
  handful of labels repeatedly.
- **Lazy refs**: accumulate a per-operation list of "want-to-ref"
  handles and flush them in one batched insert at the operation
  boundary.
- **Widen existing `temp_refs` usage**: some call sites currently call
  `sylvan_ref` for values whose lifetime is local to an AND/OR merge
  — those could move to `temp_refs` and avoid the global table
  entirely.

### 2. Slab allocator fast path (target: 14.5%)

- Bump per-worker local cache capacities — today's defaults are
  `edge_entry/edge_map = 64/512`. Growing the cache trades memory for
  fewer refill paths.
- Consider thread-local storage (`_Thread_local`) instead of
  `g_slab_pools[worker_id]` indirection; may save one load per
  acquire.

### 3. Cache-line-conscious node layout (target: memory bound)

The 11.5% cache miss rate suggests node / edge-entry layout touches
cold lines on every hash probe. Worth a `pahole` or similar struct
layout pass, especially for `mtpndd_node_t` / `edge_bucket_entry_t`.

### 4. PGO / LTO (target: broad)

We're at `-O3` but no PGO and no LTO. Typical wins 5-15% on
pointer-heavy workloads. Easy to try.

### 5. NUMA / CPU pinning

`perf stat` showed 0 context switches and 0 cpu-migrations on this run
— Linux scheduler happened to keep workers put. Explicit
`sched_setaffinity` + NUMA-aware allocation could reduce cross-socket
cache misses on larger machines.

### 6. Op-cache sharding

Currently a single `mtpndd_op_cache`; 5.6% of samples on
`mtpndd_op_cache_lookup_binary`. Sharding it per-worker (like we did
for `mtbdd_refs`) is unlikely to help much because the cache is
probe-based (lock-free) and reads-heavy, but worth a one-shot test.

## Not worth chasing further (already ruled out)

- **Lace backoff tuning** — investigated in depth, unresolved ≤10% at
  W=6; accepted as migration cost (see same-day audit doc).
- **Per-worker protect** — tested, negative result on nqueens.
- **BDD spawn depth cutoff** — tested, <1% on nqueens (useful only for
  large-BDD workloads like sre-ndd BGP).
- **Hugepage** — tested, 1-5%, optional system-level tuning.

## Recommendation

If another perf push is on the table, tackle items 1 (refs hot path)
and 4 (PGO/LTO) in that order. Both are locally testable with a
well-defined A/B. Item 2 (slab caches) is next. Items 3, 5, 6 are
speculative without more measurement.

## Follow-up: implemented items 4 and 1

### Item 4 — PGO + LTO

Shipped as `scripts/build-pgo.sh`. LTO alone 1–2% (noise); PGO carries
the weight. Geometric mean ≈ −7%, consistent across the full N=10..13
× W=1..6 matrix with no regressions. See
`2026-04-21-upstream-migration-and-ab-audit.md` for the full table.

### Item 1 — ref coalescing cache

Shipped in the submodule on branch `feature/mtpndd-perworker-refs`,
commit `c8bf561`. A 16-entry per-worker thread-local cache sits in
front of the per-worker refs tables: mtbdd_ref / mtbdd_deref first try
to coalesce into the cache (linear scan, 4 cache lines), evict
round-robin when full, and flush at GC time via TOGETHER. Consecutive
ref/deref of the same handle cancel out with no global traffic.

Matrix (3-run interleaved means, plain Release -O3, no PGO):

| N | W | no-cache | cache | Δ% |
|---|---|---------:|------:|----:|
| 10 | 1 | 0.255 | 0.255 | 0.0% |
| 10 | 2 | 0.192 | 0.190 | −1.0% |
| 10 | 4 | 0.138 | 0.131 | −5.1% |
| 10 | 6 | 0.129 | 0.113 | **−12.4%** |
| 11 | 1 | 1.161 | 1.177 | +1.4% |
| 11 | 2 | 0.809 | 0.794 | −1.9% |
| 11 | 4 | 0.548 | 0.503 | −8.2% |
| 11 | 6 | 0.459 | 0.381 | **−17.0%** |
| 12 | 1 | 6.198 | 6.253 | +0.9% |
| 12 | 2 | 4.181 | 4.152 | −0.7% |
| 12 | 4 | 2.719 | 2.497 | −8.2% |
| 12 | 6 | 2.237 | 1.841 | **−17.7%** |
| 13 | 1 | 36.345 | 37.048 | +1.9% |
| 13 | 2 | 24.092 | 24.039 | −0.2% |
| 13 | 4 | 15.328 | 14.072 | −8.2% |
| 13 | 6 | 12.531 | 10.236 | **−18.3%** |

Exactly the profile we'd predict: neutral on W=1 (no contention to
coalesce), scales with worker count up to −18% at W=6. The small W=1
regression (≤2%) is the 16-entry linear scan overhead without any
coalescing benefit; could be fixed with a per-worker-count threshold
but not worth the complexity given the gains elsewhere.

### Stacked end-to-end gain

At N=12 (3-run means), showing plain Release → +cache → +cache+PGO+LTO:

| W | base | +cache | +cache+PGO+LTO | Δ base → all |
|---|-----:|-------:|---------------:|-------------:|
| 1 | 6.42 | 6.54 | 5.80 | **−10%** |
| 4 | 2.76 | 2.54 | 2.33 | **−16%** |
| 6 | 2.43 | 2.00 | 1.81 | **−25%** |

The W=6 number is now 15% ahead of the legacy-vendored-sylvan
pre-migration measurement (2.13s). The "migration cost" story is
fully inverted — the submodule layout with the two new optimizations
is meaningfully faster than what we left behind.

### Item 2 — slab allocator fast path

Shipped as `perf(slab): per-worker in_use counter to drop
shared-cacheline atomic`. The slab pool previously did an atomic RMW
on a single shared `pool->in_use` counter on every acquire and every
release. Under 6 workers, that's a single cache line bouncing between
cores at the rate of allocation. The counter is only read by the
debug stats snapshot, so moving it per-worker (padded to a cache
line, summed at read time) is safe.

Matrix (3-run interleaved means, Release -O3, on top of the ref
coalescing cache):

| N | W | rc-only | rc+slab | Δ% |
|---|---|--------:|--------:|----:|
| 10 | 1 | 0.264 | 0.269 | +1.9% |
| 10 | 2 | 0.191 | 0.177 | −7.3% |
| 10 | 4 | 0.130 | 0.115 | −11.5% |
| 10 | 6 | 0.117 | 0.102 | **−12.8%** |
| 11 | 1 | 1.192 | 1.176 | −1.3% |
| 11 | 2 | 0.795 | 0.732 | −7.9% |
| 11 | 4 | 0.504 | 0.451 | −10.5% |
| 11 | 6 | 0.383 | 0.336 | **−12.3%** |
| 12 | 1 | 6.254 | 6.214 | −0.6% |
| 12 | 2 | 4.127 | 3.839 | −7.0% |
| 12 | 4 | 2.495 | 2.226 | −10.8% |
| 12 | 6 | 1.842 | 1.591 | **−13.6%** |
| 13 | 1 | 37.358 | 37.115 | −0.7% |
| 13 | 2 | 24.047 | 22.397 | −6.9% |
| 13 | 4 | 14.069 | 12.590 | −10.5% |
| 13 | 6 | 10.240 | 8.911 | **−13.0%** |

Same scaling pattern as the ref cache: neutral at W=1, strong at W≥4.

### Final stacked totals at N=12

| W | baseline | +cache | +cache+slab | +all+PGO+LTO |
|--:|---------:|-------:|------------:|-------------:|
| 1 | 6.42 | 6.54 | 6.48 | **5.76** (−10%) |
| 2 | 4.18 | 4.15 | 3.94 | **3.56** (−15%) |
| 4 | 2.76 | 2.54 | 2.27 | **2.09** (−24%) |
| 6 | 2.43 | 2.00 | 1.76 | **1.65** (−32%) |

N=12 W=6 is now 23% ahead of the pre-migration legacy-vendored-sylvan
baseline (1.65s vs 2.13s).

### Item 3 — cache-line node layout (negative result)

Inspected hot structs with `gdb ptype /o`:

| struct | size | notes |
|---|---:|---|
| `mtpndd_node_s` | 24 B | 4-byte hole before `field_id` (from `atomic_uint_fast32_t` being 8 bytes on x86_64) |
| `edge_bucket_entry_s` | 24 B | clean, but 24 B is cacheline-awkward |
| `mtpndd_edge_s` | 48 B | 7 bytes padding after `bool buckets_malloced` |

Shipped the cleanest of these: switched `ref_count` from
`atomic_uint_fast32_t` to `_Atomic uint32_t`, shrinking the node from
24 to 16 bytes (4/cacheline, no straddle).

Result: **noise**. Full N=10..13 × W=1..6 matrix, most cells within
±1%, one W=6 outlier at +4%. Hypothesis was wrong: MTPNDD node access
is dispersed by hash lookups, so cacheline density at struct
granularity doesn't materialize into miss-rate gains. Kept the change
as hygiene (400 KB memory saved at N=12 scale, cleaner layout) but
it doesn't contribute to the performance story.

Decided not to pursue the two other candidates (packing
`buckets_malloced` into `bucket_count` to reclaim the 7-byte pad in
`mtpndd_edge_s`; round `edge_bucket_entry_s` to 32 B with padding):
the first adds masking overhead on every access, the second would
*grow* the struct. With Item 3's node shrink producing nothing, these
have even less probability of helping.

### Items left for future work

- **Item 5** (NUMA / CPU pinning): needs a larger machine.
- **Item 6** (op-cache sharding): speculative, 5.6% ceiling.

## Final profile snapshot (post-optimizations)

Ran perf again on the fully-optimized binary (`build/` — cache + slab +
node shrink, no PGO) at N=12 W=6:

| Counter | Before | After | Δ |
|---|--:|--:|--:|
| wall time | 2.37 s | 1.81 s | **−24%** |
| task-clock | 12.3 s | 8.54 s | −31% |
| instructions | 25.4 G | 25.8 G | ~same |
| cycles | 42.9 G | 28.7 G | −33% |
| IPC | 0.59 | **0.90** | +53% |
| cache-refs | 2.84 G | 0.996 G | −65% |
| cache-misses | 325 M | 313 M | ~same |

IPC jumped from 0.59 to 0.90 — the workload is meaningfully less
memory-bound now. Same instruction count but 33% fewer cycles means
the CPU is stalling much less.

### Top function redistribution

| Function | Before | After | Δ |
|---|--:|--:|--:|
| `refs_up` | 15.89% | *dropped out of top 15* | |
| `refs_down` | 11.98% | *dropped out of top 15* | |
| `find_node_in_nodetable` | 11.52% | **17.03%** (new #1) | |
| `mtpndd_and_rec_CALL` | 9.19% | **15.31%** (new #2) | |
| `mtpndd_op_cache_lookup_binary` | 5.62% | **12.21%** (new #3) | |
| `mtpndd_memory_acquire_edge_entry` | 8.22% | 3.21% | |
| `mtbdd_ref` | 2.48% | 4.98% | |
| `mtbdd_deref` | 1.86% | 3.13% | |

The ref cache obliterated the refs hot path. The slab fix cut acquire
costs in half. The remaining top three functions are the algorithmic
core — nodetable canonicalization, AND recursion, op cache lookup —
which are the "work MTPNDD is paid to do". Further gains would need
algorithmic rework, not plumbing fixes.

### One more squeeze: inlining mtpndd_hash_node_identity

While reviewing the post-opt profile, noticed that
`mtpndd_hash_node_identity` is called twice per op-cache lookup
(lhs + rhs) and from three other hot sites, but was a non-inline
cross-TU function — every call cost register spills plus the call
itself. Moving it to `mtpndd_node.h` as `static inline` gave a flat
−1.6 to −5.0% across the 16-cell matrix (mean ~−2.5%).

This is the shape you expect from a pure code-density fix: no worker
count dependence, applied uniformly.

### Final end-to-end comparison (legacy vs new)

3-run interleaved means, plain Release -O3 on both sides.

| N | W | legacy | new | new+PGO | new Δ | new+PGO Δ |
|---|---|------:|----:|-------:|-----:|-------:|
| 10 | 1 | 0.256 | 0.246 | 0.224 | −3.9% | −12.5% |
| 10 | 2 | 0.228 | 0.173 | 0.158 | −24.1% | −30.7% |
| 10 | 4 | 0.150 | 0.115 | 0.107 | −23.3% | −28.7% |
| 10 | 6 | 0.133 | 0.101 | 0.099 | −24.1% | −25.6% |
| 11 | 1 | 1.162 | 1.141 | 1.032 | −1.8% | −11.2% |
| 11 | 2 | 0.965 | 0.714 | 0.664 | −26.0% | −31.2% |
| 11 | 4 | 0.608 | 0.440 | 0.412 | −27.6% | −32.2% |
| 11 | 6 | 0.435 | 0.329 | 0.314 | −24.4% | −27.8% |
| 12 | 1 | 6.320 | 6.051 | 5.542 | −4.3% | −12.3% |
| 12 | 2 | 5.139 | 3.693 | 3.442 | −28.1% | −33.0% |
| 12 | 4 | 3.082 | 2.148 | 2.027 | −30.3% | −34.2% |
| 12 | 6 | 2.061 | 1.546 | 1.479 | −25.0% | −28.2% |
| 13 | 1 | 38.018 | 35.580 | 33.061 | −6.4% | −13.0% |
| 13 | 2 | 29.663 | 21.569 | 20.050 | −27.3% | −32.4% |
| 13 | 4 | 17.604 | 12.163 | 11.420 | −30.9% | −35.1% |
| 13 | 6 | 11.664 | 8.633 | 8.234 | −26.0% | −29.4% |

Pattern:
- W=1: modest (−2 to −6% plain, −11 to −13% with PGO). Single-thread
  baseline gets the Lace/compiler wins but not the contention fixes.
- **W≥2: −24 to −35% across the board.** Contention removal
  (per-worker refs sharding, ref coalescing cache, slab in_use
  per-worker) dominates.

Geometric mean across all 16 cells:
- new:      ~−22% vs legacy
- new+PGO:  ~−27% vs legacy
