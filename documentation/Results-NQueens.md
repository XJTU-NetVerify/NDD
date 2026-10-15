# Results: NQueens

> The `ndd-zdd` rows on this page are archived results from the removed finite-domain ZDD backend.
> That backend used a one-of-`w` domain and is not the current set-family `ZDD` implementation,
> whose width-`w` domain contains `2^w` Boolean bit vectors. New backend comparisons must rerun the
> benchmark; the legacy rows below are retained only as experiment history.

This page preserves the detailed NQueens benchmark table from [`results/nqueens_metrics.csv`](../results/nqueens_metrics.csv).

For the dedicated NQueens benchmarking repository, including the broader cross-library harness and related implementations, see <https://github.com/XJTU-NetVerify/nqueensBenchmarkDDs>.

NQueens is the easiest benchmark in this repository to understand and rerun, so it is also the clearest place to see what the current `NDD` implementation changes in practice.

## What This Page Shows

- cross-library comparisons across C, Java, and C# implementations
- the gap between `NDD-Origin` and the current `NDD` implementation
- not just runtime, but also memory footprint and node counts
- a snapshot of the NQueens evaluation that complements the standalone benchmark repository

## Main Takeaways

- `NDD` preserves the same solution counts as the other exact DD implementations.
- The biggest gains over the `NDD-Origin` baseline show up from size `10` onward.
- The reduced BDD-node counts of `NDD` show the effect of the variable-reuse optimization together with the current array-backed execution.
- The archived backend comparison exercised an older finite-domain ZDD experiment. Its relative
  timings do not characterize the current `ZDD` backend.

## Highlight Table

| Size | NDD-Origin time (s) | NDD time (s) | Speedup | NDD-Origin RSS (KB) | NDD RSS (KB) | RSS reduction |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 0.082 | 0.040 | 2.04x | 122684 | 77304 | 37.0% |
| 9 | 0.188 | 0.067 | 2.80x | 194568 | 99060 | 49.1% |
| 10 | 0.548 | 0.156 | 3.52x | 297076 | 130416 | 56.1% |
| 11 | 2.039 | 0.565 | 3.61x | 628428 | 216192 | 65.6% |
| 12 | 12.419 | 3.823 | 3.25x | 1689668 | 514104 | 69.6% |
| 13 | 99.588 | 27.172 | 3.67x | 4587560 | 1913028 | 58.3% |

## Label Backend Comparison

These results compare plain decision diagram baselines and NDD using different edge-label backends.

Targets:

- `bdd`: plain BDD baseline
- `zdd`: plain ZDD baseline
- `bcdd`: plain complemented-edge BDD baseline
- `ndd`: NDD with standard BDD edge labels
- `ndd-zdd`: retired finite-domain ZDD edge labels (legacy rows only)
- `ndd-bcdd`: NDD with complemented-edge BDD edge labels

### N = 8

| target | solutions | time (s) | memory (MB) | nodes created | nodes alive |
| --- | ---: | ---: | ---: | ---: | ---: |
| bdd | 92 | 0.0330 | 58.9 | 52,474 | 2,451 |
| zdd | 92 | 0.0125 | 50.2 | 21,107 | 373 |
| bcdd | 92 | 0.0878 | 83.2 | 52,412 | 2,450 |
| ndd | 92 | 0.0377 | 61.3 | 10,797 | 600 |
| ndd-zdd | 92 | 0.0342 | 64.9 | 9,067 | 451 |
| ndd-bcdd | 92 | 0.0363 | 60.8 | 10,823 | 1,312 |

### N = 9

| target | solutions | time (s) | memory (MB) | nodes created | nodes alive |
| --- | ---: | ---: | ---: | ---: | ---: |
| bdd | 352 | 0.1009 | 62.4 | 216,443 | 9,557 |
| zdd | 352 | 0.0483 | 61.3 | 81,984 | 1,309 |
| bcdd | 352 | 0.3326 | 210.6 | 216,215 | 9,556 |
| ndd | 352 | 0.0696 | 71.4 | 29,451 | 1,364 |
| ndd-zdd | 352 | 0.0632 | 65.8 | 26,578 | 1,178 |
| ndd-bcdd | 352 | 0.0699 | 77.5 | 29,485 | 2,773 |

### N = 10

| target | solutions | time (s) | memory (MB) | nodes created | nodes alive |
| --- | ---: | ---: | ---: | ---: | ---: |
| bdd | 724 | 0.3264 | 68.5 | 955,575 | 25,945 |
| zdd | 724 | 0.1636 | 68.2 | 343,536 | 3,120 |
| bcdd | 724 | 1.4544 | 690.3 | 953,944 | 25,944 |
| ndd | 724 | 0.1847 | 93.5 | 97,595 | 2,905 |
| ndd-zdd | 724 | 0.1651 | 97.5 | 92,654 | 2,680 |
| ndd-bcdd | 724 | 0.1847 | 101.7 | 97,638 | 5,675 |

### N = 11

| target | solutions | time (s) | memory (MB) | nodes created | nodes alive |
| --- | ---: | ---: | ---: | ---: | ---: |
| bdd | 2,680 | 2.3780 | 94.4 | 4,691,834 | 94,822 |
| zdd | 2,680 | 0.7121 | 80.9 | 1,552,727 | 10,503 |
| bcdd | 2,680 | 8.6851 | 3133.5 | 4,685,028 | 94,821 |
| ndd | 2,680 | 0.5399 | 196.3 | 398,650 | 8,579 |
| ndd-zdd | 2,680 | 0.5556 | 207.9 | 388,606 | 8,310 |
| ndd-bcdd | 2,680 | 0.6840 | 202.1 | 398,703 | 14,023 |

### N = 12

| target | solutions | time (s) | memory (MB) | nodes created | nodes alive |
| --- | ---: | ---: | ---: | ---: | ---: |
| bdd | 14,200 | 16.9083 | 218.2 | 24,717,257 | 435,170 |
| zdd | 14,200 | 5.2012 | 115.6 | 7,592,039 | 45,833 |
| bcdd | 14,200 | 20.4717 | 315.5 | 24,717,255 | 435,169 |
| ndd | 14,200 | 3.0199 | 567.2 | 1,859,828 | 33,638 |
| ndd-zdd | 14,200 | 2.8683 | 559.4 | 1,831,431 | 33,561 |
| ndd-bcdd | 14,200 | 3.0480 | 742.1 | 1,859,892 | 33,637 |

### N = 13

| target | solutions | time (s) | memory (MB) | nodes created | nodes alive |
| --- | ---: | ---: | ---: | ---: | ---: |
| bdd | 73,712 | 138.8387 | 2127.1 | 136,722,638 | 2,044,394 |
| zdd | 73,712 | 42.8324 | 212.6 | 39,087,758 | 204,781 |
| bcdd | 73,712 | 139.4397 | 2188.9 | 136,587,014 | 2,044,393 |
| ndd | 73,712 | 22.3243 | 2460.7 | 9,378,160 | 145,368 |
| ndd-zdd | 73,712 | 20.9622 | 2446.8 | 9,267,948 | 145,278 |
| ndd-bcdd | 73,712 | 22.3683 | 3212.9 | 9,378,236 | 145,367 |

## Full Results Table

> The table below is generated from [`results/nqueens_metrics.csv`](../results/nqueens_metrics.csv).

> Note: `JSylvan` is a parallel BDD library and was run with 48 worker threads. All other implementations in the table are single-threaded.

| Implementation | Lang | Size | Time (s) | Max RSS (KB) | Nodes Created | Nodes Alive | NDD Nodes Created | NDD Nodes Alive | BDD Nodes Created | BDD Nodes Alive | Solutions |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| BuDDy | C | 8 | 0.103430 | 22692 | 53611 | 2451 | 0 | 0 | 53611 | 2451 | 92 |
| CUDD | C | 8 | 0.016474 | 748 | 52385 | 2451 | 0 | 0 | 52385 | 2451 | 92 |
| JDD | Java | 8 | 0.112667 | 56144 | 53083 | 2451 | 0 | 0 | 53083 | 2451 | 92 |
| JavaBDD-C | Java/C | 8 | 0.006634 | 42508 | 53026 | 2451 | 0 | 0 | 53026 | 2451 | 92 |
| JavaBDD | Java | 8 | 0.034019 | 48264 | 53026 | 2451 | 0 | 0 | 53026 | 2451 | 92 |
| JSylvan | Java | 8 | 0.273734 | 97448 | 54140 | 2451 | 0 | 0 | 54140 | 2451 | 92 |
| NDD-Origin | Java | 8 | 0.082139 | 122684 | 18779 | 2980 | 9877 | 1274 | 8902 | 1706 | 92 |
| NDD | Java | 8 | 0.040243 | 77304 | 10797 | 600 | 9877 | 415 | 920 | 185 | 92 |
| DD-BDD | C# | 8 | 0.095511 | 29592 | 52181 | 2458 | 0 | 0 | 52181 | 2458 | 92 |
| DD-CBDD | C# | 8 | 0.095805 | 28632 | 46171 | 772 | 0 | 0 | 46171 | 772 | 92 |
| BuDDy | C | 9 | 0.042215 | 528 | 218386 | 9557 | 0 | 0 | 218386 | 9557 | 352 |
| CUDD | C | 9 | 0.058183 | 17908 | 216602 | 9557 | 0 | 0 | 216602 | 9557 | 352 |
| JDD | Java | 9 | 0.163918 | 69760 | 217230 | 9557 | 0 | 0 | 217230 | 9557 | 352 |
| JavaBDD-C | Java/C | 9 | 0.027981 | 40988 | 217222 | 9557 | 0 | 0 | 217222 | 9557 | 352 |
| JavaBDD | Java | 9 | 0.090533 | 61052 | 217222 | 9557 | 0 | 0 | 217222 | 9557 | 352 |
| JSylvan | Java | 9 | 0.282830 | 100112 | 219050 | 9557 | 0 | 0 | 219050 | 9557 | 352 |
| NDD-Origin | Java | 9 | 0.188161 | 194568 | 50676 | 4838 | 27781 | 2360 | 22895 | 2478 | 352 |
| NDD | Java | 9 | 0.067197 | 99060 | 29451 | 1364 | 27781 | 1133 | 1670 | 231 | 352 |
| DD-BDD | C# | 9 | 0.245210 | 33628 | 215883 | 9565 | 0 | 0 | 215883 | 9565 | 352 |
| DD-CBDD | C# | 9 | 0.201833 | 32832 | 185654 | 2795 | 0 | 0 | 185654 | 2795 | 352 |
| BuDDy | C | 10 | 0.228837 | 12032 | 958740 | 25945 | 0 | 0 | 958740 | 25945 | 724 |
| CUDD | C | 10 | 0.426347 | 37700 | 954767 | 25945 | 0 | 0 | 954767 | 25945 | 724 |
| JDD | Java | 10 | 0.414331 | 68620 | 956019 | 25945 | 0 | 0 | 956019 | 25945 | 724 |
| JavaBDD-C | Java/C | 10 | 0.243670 | 45664 | 956173 | 25945 | 0 | 0 | 956173 | 25945 | 724 |
| JavaBDD | Java | 10 | 0.574028 | 66220 | 956173 | 25945 | 0 | 0 | 956173 | 25945 | 724 |
| JSylvan | Java | 10 | 0.278899 | 110204 | 958344 | 25945 | 0 | 0 | 958344 | 25945 | 724 |
| NDD-Origin | Java | 10 | 0.547849 | 297076 | 146679 | 7747 | 94507 | 4310 | 52172 | 3437 | 724 |
| NDD | Java | 10 | 0.155855 | 130416 | 97595 | 2905 | 94507 | 2625 | 3088 | 280 | 724 |
| DD-BDD | C# | 10 | 0.580980 | 65500 | 77081 | 25954 | 0 | 0 | 77081 | 25954 | 724 |
| DD-CBDD | C# | 10 | 0.442454 | 61244 | 793143 | 6601 | 0 | 0 | 793143 | 6601 | 724 |
| BuDDy | C | 11 | 3.851756 | 32752 | 4699647 | 94822 | 0 | 0 | 4699647 | 94822 | 2680 |
| CUDD | C | 11 | 3.900772 | 165176 | 4687448 | 94822 | 0 | 0 | 4687448 | 94822 | 2680 |
| JDD | Java | 11 | 3.072741 | 99040 | 4691203 | 94822 | 0 | 0 | 4691203 | 94822 | 2680 |
| JavaBDD-C | Java/C | 11 | 2.789785 | 66344 | 4691203 | 94822 | 0 | 0 | 4691203 | 94822 | 2680 |
| JavaBDD | Java | 11 | 4.002750 | 93584 | 4691203 | 94822 | 0 | 0 | 4691203 | 94822 | 2680 |
| JSylvan | Java | 11 | 0.654096 | 208464 | 4693383 | 94822 | 0 | 0 | 4693383 | 94822 | 2680 |
| NDD-Origin | Java | 11 | 2.039495 | 628428 | 549688 | 15116 | 392824 | 10487 | 156864 | 4629 | 2680 |
| NDD | Java | 11 | 0.565156 | 216192 | 398650 | 8579 | 392824 | 8244 | 5826 | 335 | 2680 |
| DD-BDD | C# | 11 | 2.856501 | 296340 | 3808918 | 94832 | 0 | 0 | 3808918 | 94832 | 2680 |
| DD-CBDD | C# | 11 | 2.395312 | 150620 | 1374411 | 22455 | 0 | 0 | 1374411 | 22455 | 2680 |
| BuDDy | C | 12 | 53.600987 | 131840 | 24741296 | 435170 | 0 | 0 | 24741296 | 435170 | 14200 |
| CUDD | C | 12 | 31.968450 | 290488 | 24740519 | 435170 | 0 | 0 | 24740519 | 435170 | 14200 |
| JDD | Java | 12 | 26.877723 | 226428 | 24719128 | 435170 | 0 | 0 | 24719128 | 435170 | 14200 |
| JavaBDD-C | Java/C | 12 | 24.439052 | 158912 | 24715771 | 435170 | 0 | 0 | 24715771 | 435170 | 14200 |
| JavaBDD | Java | 12 | 27.867097 | 209596 | 24715771 | 435170 | 0 | 0 | 24715771 | 435170 | 14200 |
| JSylvan | Java | 12 | 3.749704 | 1189200 | 24701951 | 435170 | 0 | 0 | 24701951 | 435170 | 14200 |
| NDD-Origin | Java | 12 | 12.418996 | 1689668 | 2481709 | 42799 | 1848510 | 36749 | 633199 | 6050 | 14200 |
| NDD | Java | 12 | 3.823246 | 514104 | 1859828 | 34230 | 1848510 | 33837 | 11318 | 393 | 14200 |
| DD-BDD | C# | 12 | 15.510662 | 1053348 | 12943122 | 435181 | 0 | 0 | 12943122 | 435181 | 14200 |
| DD-CBDD | C# | 12 | 13.054784 | 706752 | 2190375 | 98900 | 0 | 0 | 2190375 | 98900 | 14200 |
| BuDDy | C | 13 | 500.000000 | 542896 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| CUDD | C | 13 | 224.422472 | 1249208 | 136780708 | 2044394 | 0 | 0 | 136780708 | 2044394 | 73712 |
| JDD | Java | 13 | 219.003002 | 1578404 | 136725331 | 2044394 | 0 | 0 | 136725331 | 2044394 | 73712 |
| JavaBDD-C | Java/C | 13 | 230.308548 | 868332 | 136589564 | 2044394 | 0 | 0 | 136589564 | 2044394 | 73712 |
| JavaBDD | Java | 13 | 293.618748 | 2078664 | 136711984 | 2044394 | 0 | 0 | 136711984 | 2044394 | 73712 |
| JSylvan | Java | 13 | 28.725073 | 1424508 | 136728750 | 2044394 | 0 | 0 | 136728750 | 2044394 | 73712 |
| NDD-Origin | Java | 13 | 99.588182 | 4587560 | 12178652 | 157061 | 9355909 | 149303 | 2822743 | 7758 | 73712 |
| NDD | Java | 13 | 27.172086 | 1913028 | 9378160 | 146060 | 9355909 | 145603 | 22251 | 457 | 73712 |
| DD-BDD | C# | 13 | 105.935577 | 8164992 | 101810366 | 2044406 | 0 | 0 | 101810366 | 2044406 | 73712 |
| DD-CBDD | C# | 13 | 83.637180 | 2585552 | 24376279 | 445755 | 0 | 0 | 24376279 | 445755 | 73712 |

## Plots

### Time

![NQueens time](../results/nqueens_time_sec.png)

### Memory

![NQueens max RSS](../results/nqueens_max_rss_kb.png)

### Node Counts

![NQueens nodes created](../results/nqueens_nodes_created.png)

![NQueens nodes alive](../results/nqueens_nodes_alive.png)
