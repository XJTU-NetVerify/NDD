# perf lock report (N=12, 4 workers)

## Command
```
sudo perf lock record -o /tmp/perf_lock_n12_w4.data -- \
  taskset -c 0-3 ./sylvan/build/src/sylvan/mtpndd/mtpndd_nqueens_test 12
perf lock report -i /tmp/perf_lock_n12_w4.data --output /tmp/perf_lock_n12_w4.report
```

## Summary (global)
```
Name            acquired  contended   avg wait   total wait   max wait   min wait
<unknown>             108        108    80.19 us     8.66 ms     2.74 ms     3.52 us
<unknown>              27         27     5.90 us   159.42 us     8.15 us     3.29 us
<unknown>              18         18     4.61 us    83.08 us     9.43 us     1.47 us
<unknown>              14         14     3.72 us    52.06 us     8.52 us     1.26 us
rcu_state              13         13     6.93 us    90.03 us     9.17 us     4.11 us
```

## Per-thread view (top entries)
```
Name            acquired  contended   avg wait   total wait   max wait   min wait
:2584915             60         60     6.61 us   396.55 us     9.43 us     1.26 us
:2584916             56         56    54.53 us     3.05 ms     2.74 ms     1.37 us
mtpndd_nqueens_       54         54   102.24 us     5.52 ms     2.70 ms     3.04 us
:2584917             53         53     6.50 us   344.55 us    13.91 us     2.43 us
```

## Notes
- Most lock names are unresolved in this report (blank). This usually requires vmlinux symbols or CAP_SYS_RAWIO for /proc/kcore to resolve kernel locks.
- The data still shows limited lock contention volume (hundreds of contended locks, ms-scale total waits), which is small relative to overall runtime but may affect scaling.
