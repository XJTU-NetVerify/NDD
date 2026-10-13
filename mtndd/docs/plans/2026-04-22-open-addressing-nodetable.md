# Plan: open-addressing nodetable

Status: **prototyped 2026-04-22 on branch `feature/c-open-addressing`,
not merged** — regresses N-queens by 7–12% at W=6. Captured here as a
reference for workloads where the cache-locality assumption *might*
hold; N-queens is not one of them. Findings at end of doc.

## Motivation

After the 2026-04-21 optimization pass (per-worker refs sharding, ref
coalescing cache, slab in_use per-worker, misc inlining), the hot
function distribution at N=12 W=6 is:

| % | Symbol | Layer |
|--:|---|---|
| 19.41 | `mtpndd_and_rec_CALL` | algorithm |
| 18.69 | `find_node_in_nodetable` | nodetable |
| 10.42 | `mtpndd_op_cache_lookup_binary` | op cache (inlined) |
|  5.70 | `mtpndd_and_two_phase_same_field` | algorithm |
|  4.70 | `mtpndd_mk` | nodetable insert |
|  4.27 | `pthread_spin_lock` | per-shard bucket lock |

`find_node_in_nodetable` is ~19% and the nodetable is a **chained**
hash table today. Each lookup:
1. Acquires the per-shard spinlock (`pthread_spin_lock` shows ~4% on
   its own).
2. Computes the bucket index.
3. Walks a singly-linked list of `mtpndd_nodetable_bucket_entry_t` (40
   bytes each after the cached_hash mirror) — each node touch is a
   potentially cold cacheline.
4. For each entry that survives the cached_hash fast-reject, calls
   `nodetable_edges_equal` which walks the edge map.

The bucket list is short on nqueens (pre-allocated buckets give ~0.7%
load factor at N=12) so the walk usually ends in 0-1 steps. Most cost
is the lock + the first cacheline load on the entry. For denser
workloads the list walk dominates.

## Target design

Open-addressing (linear or quadratic probing) hash table. Advantages:

- **Cacheline-friendly probes.** Consecutive slots live in the same /
  adjacent lines, so after one miss the next probe hits warm cache.
  Chained lists scatter entries across the arena.
- **No per-node `next`/`prev` pointers.** Each slot is just the payload
  (`edges *`, `node *`, cached_hash = 24 bytes), so we can fit ~2.7
  slots per cacheline vs ~1.6 bucket entries today.
- **No per-slot allocator.** Slots are a flat array, so
  `mtpndd_memory_acquire_nodetable_entry` (3% self) disappears too.
- **Lock-free lookups** are easier (atomic reads of slot contents);
  writes still need synchronization but the granularity can be
  per-slot (CAS on the slot's `edges` pointer) instead of per-shard.

## Design sketch

```c
typedef struct {
    _Atomic(mtpndd_edge_t *) edges;     /* NULL = empty, TOMBSTONE = deleted */
    mtpndd_node_t *node;                 /* stable once set */
    uint64_t cached_hash;                /* for verify-after-load */
} mtpndd_ot_slot_t;                      /* 24 bytes */

typedef struct {
    mtpndd_ot_slot_t *slots;             /* power-of-2 sized flat array */
    size_t mask;
    _Atomic size_t entry_count;
    size_t load_threshold;               /* resize at ~75% */
    /* concurrency: single rehash_mutex; reads are lock-free atomic loads */
    pthread_mutex_t rehash_mutex;
} mtpndd_ot_t;
```

- **Lookup** (lock-free):
  1. `h = edges->cached_hash; idx = h & mask;`
  2. Load `slots[idx].edges` relaxed. If NULL -> miss.
  3. If `slots[idx].cached_hash == h` and `nodetable_edges_equal(slot.edges, edges)`
     -> hit, return `slots[idx].node`.
  4. Else `idx = (idx + 1) & mask;` (linear probe) or `idx = (idx + step*step) & mask`
     (quadratic) and loop.
- **Insert** with CAS:
  1. Probe to find empty or tombstone.
  2. `atomic_compare_exchange_strong(&slot.edges, &expected, new_edges)`.
  3. On success write `slot.node` and `slot.cached_hash`. On failure
     reprobe (another thread claimed the slot).
- **Delete** (GC): write `TOMBSTONE` into `slot.edges`.
- **Rehash**: single-writer under `rehash_mutex`; copy live slots into
  a new larger array, replace `slots`/`mask` atomically, free old.

## Migration hazards

- `mtpndd_gc_mark_bdd_labels` currently walks `nodetable->buckets[i]`
  lists. Iteration API needs updating to walk the flat slot array.
- Rehash is currently per-field-table; under the new design it's still
  per-field but the implementation differs.
- Tombstones accumulate across GC cycles and eventually force rehash;
  need a tombstone-ratio threshold.
- Reference-count management on nodes doesn't change — the table only
  stores pointers, not node storage.

## Expected payoff

Hard to predict without prototyping. Upper bound is the combined
`find_node` + `mtpndd_mk` + `pthread_spin_lock` cost, ~27% of CPU at
N=12 W=6. If we can halve the lookup's memory-access cost we'd see
~10% end-to-end gain. Realistic target is **5-10%**.

## When to pick this up

Not urgent. Items that should be done first if coming back to perf:

- Algorithmic shrinks to `mtpndd_and_rec` itself (19% — tweak
  granularity, combine two-phase branches, etc).
- Look at `pthread_spin_lock` contention specifically (4%) — may be
  addressable without a full open-addressing rewrite.

Estimated effort for open-addressing rewrite: **1-2 weeks of focused
work** including:
- Prototype on a branch
- Concurrency audit (CAS ordering, ABA potential)
- GC integration
- Full benchmark regression
- Decision to merge / keep-chained

## 2026-04-22 prototype results

Prototype lives on `feature/c-open-addressing` (2 commits on top of
`feature/c` at `7b736be`):

- `edfdb8d` — flat slot array (24 B: atomic `edges*`, `node*`,
  `cached_hash`), lock-free linear-probe lookup, CAS insert with
  PENDING sentinel, TOMBSTONE on GC delete. Drops shard spinlocks
  and the `mtpndd_nodetable_bucket_entry` slab.
- `c8f7111` — correctness fix: rehash must run inside Sylvan NEWFRAME
  (workers parked). The prototype shipped with rehash in `gcOrGrow`
  which races with concurrent lock-free inserters — latent at 2 M
  slots because rehash never triggered, blatant when slots are
  shrunk (lost entries, 14199 vs 14200 solutions at N=12).

### A/B at N=12 (release build)

| W | chained (feature/c) | open-addressing | Δ |
|---|---:|---:|---|
| 1 | 6.02 s | 6.07 s | +1% |
| 4 | 2.13 s | 2.20 s | +3% |
| 6 | 1.54 s | 1.72 s | **+12%** |

### Hotspot shift (N=12 W=6)

| Symbol | chained | OA |
|--------|--------:|---:|
| `find_node_in_nodetable` | 18.69% | 19.26% |
| `mtpndd_mk` | 4.70% | 4.93% |
| `pthread_spin_lock` | 4.27% | **0%** |

Spinlock fully eliminated, but the 4.3% was absorbed by CAS cost and
extra atomic loads on the lock-free read path. Net: slower.

### Why the design-doc win didn't materialize

The doc upper-bounded the win by the combined cost of `find_node` +
`mtpndd_mk` + `pthread_spin_lock` (~27%). The hypothesis was that
cache-friendly probing would shrink `find_node` itself. In practice
load factor at N=12 is ~4% (88 k live nodes in 2 M slots): chained
buckets are already mostly empty (0-1 entry), so a list walk costs
one dereference — the same as an open-addressed probe. There is no
list-walk cost to cut.

### Experiment 2a: shrink initial slots to push load factor up

Capping initial slots at 128 k pushes load to ~68%, so probes become
the expected 2-4 hops. Result was **much worse** (N=12 W=1: 9.5 s,
W=6: 4.3 s) because every rehash now runs under Sylvan NEWFRAME —
each rehash triggers a full `sylvan_gc()` stop-the-world. A single
field at high load can pay 3-5 rehashes over a run; each one
dominates the savings.

### When this might still be worth it

The workload has to pay the table for the walk-vs-probe trade to
matter. Candidates:

- **High-load workloads** where buckets average > 2 entries. The
  chained walk touches cold cachelines per hop; open addressing
  stays in the first line(s).
- **Miss-dominant lookups**. Chained walks to end-of-list before
  returning miss; open addressing returns at the first NULL probe.
- **Low-parallelism**. Per-slot CAS scales well, but the 6-worker
  case here didn't see parallelism benefits — we were already
  shard-parallel via 4 k spinlocks.

### Migration note for downstream forks

If porting to a codebase that matches the "worth it" criteria (e.g.
the SRE NDD project at `~/sre-ndd`): the five files on
`feature/c-open-addressing` are self-contained (no Sylvan submodule
changes needed). Replicate the NEWFRAME rehash-site invariant — it
is a correctness requirement, not a tuning knob.

## 2026-04-22 cross-validation on sre-ndd

Tested the "worth-it" hypothesis on the SRE NDD project workload
(bgp_fattree08 MF=3 workers=4). sre-ndd hits **34% nodetable load
factor** (vs 4% for N-queens at N=12), so per the criteria above
should have been a good candidate for open addressing.

Procedure: bumped sre-ndd's mtpndd submodule from the pre-migration
`ea7bcfe` pin to our current `feature/c` head, updated sre-ndd's
`run.sh` to the new single-tree cmake layout, verified build + smoke
runs, captured chained baseline, swapped submodule to
`feature/c-open-addressing`, re-ran.

### Result

| | Chained | OA | Δ |
|---|---:|---:|---|
| `total` (NDDConfig2spec) | 85.36 s | 91.02 s | **+6.6%** |
| final load factor        | 34.13% | 25.87% | (OA rehashed up 3×) |

OA still regressed on the higher-load workload. The explanation is
the same as for N-queens, amplified: OA's rehash requires Sylvan
NEWFRAME stop-the-world (`mtpndd_gc_before_sylvan`), so every
per-table rehash costs one full `sylvan_gc()`. Chained's
`maybe_rehash` runs under a shard spinlock in-place, with no global
pause. On the sre-ndd run we observed **3 sylvan_gc's that reclaimed
zero nodes** — pure rehash overhead, triggered by OA's load
threshold.

This is an architectural cost that no amount of tuning will fix
under the current synchronization model.

## 2026-04-22 unrelated finding: Lace 1.6 external-RUN regression

While setting up the sre-ndd A/B, chained-on-new-layout ran at
85 s while chained-on-old-layout (Sylvan 1.9.1 + Lace 1.5, pinned
at `ea7bcfe`) had been 44 s on the same workload. A **2× regression
independent of open addressing**, introduced by the 2026-04-21
Sylvan+Lace migration.

`perf record` on the chained-new-layout run puts **`lace_run_task`
at 23.8% and `lace_worker_thread` at 17.7%** of CPU, together ~45%.
Hot functions from mtpndd itself (`find_node_in_nodetable`,
`mtpndd_mk`, `mtpndd_and_rec`) dropped out of the top 10. The
regression is entirely in Lace scheduling, not in mtpndd logic.

Mechanism: Lace 1.6 routes `RUN()` calls from non-worker threads
(e.g. the JVM main thread calling `mtpndd_and` via JNI) through an
external-task queue, parking the caller and waking a worker per
call. Lace 1.5 let external callers temporarily steal a worker
slot. N-queens is unaffected because its benchmark wraps all work
in one top-level `VOID_TASK`; sre-ndd's JNI-per-op pattern fires
hundreds of thousands of `RUN()`s and so pays the queue cost on
each one.

Mitigations (not attempted, out of scope here):
- Donate the JVM main thread as a persistent Lace worker at init
  (requires a Sylvan/Lace API addition).
- Add a fast path to Lace 1.6 for single-external-caller RUN().
- Push `mtpndd_and_batch` / `mtpndd_*_reduce` into sre-ndd's Java
  layer so the RUN() frequency drops.

Impact on this document: the open-addressing comparison stays valid
as a relative measurement (both arms paid the same Lace tax), but
the absolute numbers here and in sre-ndd's bench archive are
inflated vs historical pre-migration baselines until the Lace
regression is addressed.
