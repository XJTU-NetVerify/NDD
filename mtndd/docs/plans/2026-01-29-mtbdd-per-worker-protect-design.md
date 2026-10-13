# MTBDD per-worker protect design

## Goal
Shard MTBDD `protect` operations per worker while preserving Sylvan’s current semantics (linear probing, duplicate entries allowed). Detect and surface cross-worker or ordering anomalies by tracking “delete without prior add” and hard-failing if global reconciliation cannot match deletes.

## Scope
- **In scope:** MTBDD protect only (`mtbdd_protected` and `mtbdd_gc_mark_protected`).
- **Out of scope:** ZDD/LDD protect, MTPNDD temp refs list, MTPNDD node-level protect.

## Current semantics (must preserve)
- `protect_up` inserts without checking existing entries → duplicates allowed.
- `protect_down` removes one matching entry; asserts if not found.
- This is effectively a multiset without explicit counts.

## Proposed semantics
Per worker, maintain two linear-probe tables:
- `protect_add[w]`: multiset of protects.
- `protect_del[w]`: multiset of unmatched unprotects.

Operations:
- `mtbdd_protect(ptr)`: **insert into add** (no lookup in del). This preserves current behavior and keeps anomalies visible.
- `mtbdd_unprotect(ptr)`: try to **remove one** from add; if not found, insert into del and increment `del_only` counter.

GC reconciliation:
1) Build `global_add` by inserting all entries from `protect_add[w]` for all workers.
2) For each entry in `protect_del[w]`, remove one from `global_add`.
3) If any removal fails → **assert/abort** (hard error). Record `del_global_miss` just before abort.
4) Mark all remaining entries in `global_add` via `mtbdd_gc_mark_rec`.

## Data structures
- Two `refs_table_t`-style tables per worker, but only used as pointer sets.
- Same linear probing + tombstone behavior as existing protect tables.
- Maintain counts:
  - `del_only_total` (unprotect couldn’t find add on same worker)
  - `del_only_unique` (optional, count unique entries in del tables)
  - `del_global_miss` (GC reconciliation failure, triggers assert)

## Error handling & diagnostics
- Runtime anomaly signal: `del_only_total` increases.
- Fatal integrity failure: `del_global_miss > 0` → assert/abort during GC.

## Performance considerations
- Protect path stays O(1) expected, same as current.
- GC reconciliation adds linear passes over per-worker tables; cost bounded by total protected entries.

## Integration points
- Modify `mtbdd_protect/unprotect` to route through per-worker add/del tables.
- Replace `mtbdd_gc_mark_protected` with global reconciliation flow.
- Keep public API unchanged; internal helpers may be prefixed (e.g., `protect_add_insert`).

## Testing plan
- Unit test: repeated protect/unprotect on same pointer (including duplicates).
- Cross-worker test: protect on worker A, unprotect on worker B → del_only increments, GC reconciliation succeeds.
- Negative test: unprotect without any protect → del_only increments and GC reconciliation aborts (assert).

## Open questions
- Whether to extend to ZDD/LDD protect tables later.
- Whether to make GC reconciliation parallel (future optimization).
