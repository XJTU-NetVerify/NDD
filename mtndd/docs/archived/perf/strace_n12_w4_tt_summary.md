# strace futex detail (N=12, 4 workers)

## Command
```
strace -tt -f -e futex -o /tmp/strace_tt_n12_w4.log -- \
  taskset -c 0-3 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
```

## Aggregate counts (from /tmp/strace_tt_n12_w4.log)
- Total futex calls: 73,896
- Operations:
  - FUTEX_WAKE_PRIVATE: 45,368
  - FUTEX_WAIT_PRIVATE: 28,528
- Errors:
  - EAGAIN: 43 (all from FUTEX_WAIT_PRIVATE)

## Hot futex addresses (call share)
- 0x55f9504896f0: 44,804 calls (60.6%)
- 0x55f9504895f0: 25,559 calls (34.6%)
- 0x55f950489670: 1,879 calls (2.5%)
- 0x55f950489570: 1,649 calls (2.2%)
- Top two addresses account for ~95.2% of futex traffic.

## Notes
- This run used the same N=12 workload as other contention measurements.
- Addresses are ASLR-dependent; mapping to symbols requires live mapping or instrumentation.
