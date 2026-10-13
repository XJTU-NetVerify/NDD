# perf c2c hot cacheline mapping (N=12, 4 workers)

## Inputs
- perf c2c summary (previous run): top shared cacheline address `0x55db40bd6b00` (~77.75% HITM)
- binary: `sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test`

## Method
- Use ASLR-robust page offset matching: compare low 12 bits of the hot address with `nm -an` symbol offsets.
- `0x55db40bd6b00 & 0xfff = 0xb00`
- `nm -an` entries with offset low 12 bits `0xb00`:
  - `0x00fcb00 mtbdd_refs`
  - `0x00d7b00 CACHE_MTBDD_ITE` (likely rodata)
  - `0x00d9b00 __PRETTY_FUNCTION__.7` (rodata)

## Conclusion (inference)
The hot cacheline most plausibly corresponds to `mtbdd_refs` (global refcount table), which lives at offset `0x00fcb00` and matches the `0xb00` page offset. This aligns with the perf flamegraph/stack showing heavy `refs_*` activity and suggests refcount updates are a major source of cacheline bouncing under multi-worker runs.

> Note: This is an inference based on page-offset matching; exact confirmation would require symbolized perf c2c or runtime mapping.
