# Futex address → data structure mapping (N=12, 4 workers)

## Inputs
- perf futex tracepoint report: `/tmp/perf_futex_n12_w4.report`
- Hot futex uaddrs (sys_enter_futex):
  - `0x5601809db6f0` (~59.7% of samples)
  - `0x5601809db5f0` (~35.24%)
  - `0x5601809db670` (~1.73%)
  - `0x5601809db570` (~1.60%)

## Method
1. Extract global symbol offsets from `nm -an` for slab pools in `mtpndd_memory_pool.c`:
   - `g_node_pool` @ `0x0fc540`
   - `g_edge_entry_pool` @ `0x0fc5c0`
   - `g_nodetable_entry_pool` @ `0x0fc640`
   - `g_edge_map_pool` @ `0x0fc6c0`
2. Compute the offset of `pthread_mutex_t lock` within `mtpndd_slab_pool_t`.
   - Built a small standalone struct replica and printed `offsetof(lock)`.
   - Result: `offsetof(lock) = 48` bytes (`0x30`).
3. Compare low 12 bits (page offset) of uaddr with `(symbol_offset + 0x30) & 0xfff`.
   - This is ASLR-robust because PIE base changes but page offsets are preserved.

## Offset match
| Futex uaddr (low 12 bits) | Matches `symbol + 0x30` | Likely structure |
|---|---|---|
| `0x6f0` | `g_edge_map_pool` | `mtpndd_slab_pool_t.lock` |
| `0x5f0` | `g_edge_entry_pool` | `mtpndd_slab_pool_t.lock` |
| `0x670` | `g_nodetable_entry_pool` | `mtpndd_slab_pool_t.lock` |
| `0x570` | `g_node_pool` | `mtpndd_slab_pool_t.lock` |

## Conclusion
The dominant futex activity is consistent with the global slab pool mutexes (`mtpndd_slab_pool_t.lock`) for:
- edge map pool (highest)
- edge entry pool (second)
- nodetable entry pool (minor)
- node pool (minor)

This aligns with the call chains that show futex activity inside the Lace worker path while executing `mtpndd_and_same_field_item_CALL` / `mtpndd_and_rec_CALL`.
