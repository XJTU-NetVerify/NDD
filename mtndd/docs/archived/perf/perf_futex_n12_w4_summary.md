# perf futex trace (N=12, 4 workers)

## Command
```
sudo perf record -e syscalls:sys_enter_futex -e syscalls:sys_exit_futex -g \
  -o /tmp/perf_futex_n12_w4.data -- \
  taskset -c 0-3 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
perf report -i /tmp/perf_futex_n12_w4.data --stdio > /tmp/perf_futex_n12_w4.report
```

## Sample summary (sys_enter_futex, ~76K samples)
Top futex addresses by sample share:
- 0x5601809db6f0: ~59.7%
- 0x5601809db5f0: ~35.24%
- 0x5601809db670: ~1.73%
- 0x5601809db570: ~1.60%

Top futex op types (by sample share):
- 0x00000081 (WAKE): ~59.9%
- 0x00000080 (WAIT): ~38.4%

Top address + op combinations:
- 0x5601809db6f0 WAKE: ~35.69%
- 0x5601809db6f0 WAIT: ~24.01%
- 0x5601809db5f0 WAKE: ~22.31%
- 0x5601809db5f0 WAIT: ~12.93%

## Call chain context (from perf report)
The hottest futex sites occur while workers are inside:
`lace_default_worker_thread → lace_run_worker → lace_steal_loop_CALL → lace_steal → mtpndd_and_same_field_item_CALL → mtpndd_and_rec_CALL`

This indicates futex waits/wakes are triggered from the main recursive AND path under Lace worker scheduling.

## Notes
- Addresses are ASLR-dependent; to map to exact variables/locks we need runtime mapping or targeted instrumentation.
