# N=12 occasional wrong-answer root cause: MTPNDD AND op-cache concurrent read/write race

## Scope
- Target workload: `mtpndd_nqueens_test 12`
- Symptom: low-frequency wrong answer (`14198/14199` instead of `14200`) and occasional crash under multi-worker runs.
- Focus: isolate whether this is environment noise, GC/ref issue, satcount issue, or parallel algorithm race.
- Worker-count note: `nqueens_test` uses `n_workers=0` (Lace auto worker count). `taskset` controls CPU affinity but not an explicit fixed Lace worker count argument.

## Reproduction evidence

### Baseline branch (`feature/c`) reproducible wrong answer
- File: `/tmp/w3_featurec_solution_check.tsv`
- Key row: run `24` -> `rc=1`, `got=14199`, expected `14200`.

### Worktree (before this fix) reproducible wrong answer
- File: `/tmp/w3_worktree_solution_check.tsv`
- Key row: run `14` -> `rc=1`, `got=14198`, expected `14200`.

### Satcount path excluded
- Added temporary diagnostic (later reverted) to compare:
  - `mtpndd_satcount_ndd` vs `mtpndd_satcount_mtbdd`
- File: `/tmp/w3_satcount_compare.tsv`
- Result on failing run: `ndd=14199`, `mtbdd=14199` (both wrong, but equal).
- Conclusion: not a satcount-implementation mismatch; formula/result itself is wrong.

## Isolation experiments

### Experiment A: disable SPAWN path (temporary switch, later reverted)
- Condition: `workers=3`, `MTPNDD_FORCE_NO_SPAWN=1`
- File: `/tmp/n12_w3_nospawn.tsv`
- Result: 5 consecutive runs all `14200`.

### Experiment B: keep SPAWN, disable AND op-cache (temporary switch, later reverted)
- Condition: `workers=3`, `MTPNDD_FORCE_NO_CACHE=1`
- File: `/tmp/n12_w3_nocache.tsv`
- Result: 8 consecutive runs all `14200`.

### Inference from A/B
- Failures require concurrent parallel path.
- Disabling AND op-cache removes failures while keeping parallel execution.
- Root cause is highly likely in AND op-cache concurrent lookup/store consistency.

## Root-cause analysis (code-level)
Location: `sylvan/src/sylvan/mtpndd/mtpndd_operation_cache.c`

Before fix, lookup order was conceptually:
1. read `result` once
2. read `operands`
3. if operands match query, return previously-read result

Under concurrent writer update, a reader can observe:
- old `result` + new `operands`
- operands match new key, but returned value is stale old result

This is a torn read of key/value pair and can produce semantically wrong cache hits.

## Fix implemented
File changed:
- `sylvan/src/sylvan/mtpndd/mtpndd_operation_cache.c`

Change:
- Use seqlock-style lookup validation:
  1. `res_before = load(result)`
  2. load operands
  3. `res_after = load(result)`
  4. accept hit only if:
     - `res_before == res_after`
     - result is neither `NULL` nor write-lock sentinel
     - operands match query

This prevents returning stale `result` for newly-written operands.

## Post-fix verification
- File: `/tmp/n12_default_after_opcache_fix.tsv`
- Condition: default path (`taskset -c 2,4,5`, no temporary debug switches)
- Result: 12/12 runs all correct (`14200`), no crash.

## Runtime impact snapshot (LOG_LEVEL=0, N=12, taskset `2,4,5`)
- Before fix reference (first 12 successful runs from pre-fix stress file): `/tmp/w3_worktree_solution_check.tsv`
  - mean `6.300s`, std `0.024s`
- After fix: `/tmp/n12_log0_after_opcache_fix.tsv`
  - mean `6.411s`, std `0.027s`
- Delta:
  - `+0.111s` (~`+1.8%`) in this sample window
  - correctness improves from intermittent failure to 12/12 clean in this recheck

## Notes
- Temporary diagnostic switches added during isolation were reverted.
- Kept fix is only the cache lookup consistency correction in `mtpndd_operation_cache.c`.
- Existing pending branch changes unrelated to this bug remain untouched.
