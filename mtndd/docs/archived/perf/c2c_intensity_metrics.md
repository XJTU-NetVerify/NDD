# perf c2c intensity metrics (N=12, 4 workers)

## Source
- `perf c2c record` → `/tmp/perf_c2c_n12_w4_latest.data`
- `perf c2c report --stdio --show-all --full-symbols` → `/tmp/perf_c2c_n12_w4_latest.report`

## Key totals
| metric | value |
|---|---:|
| total records | 209,626 |
| load operations | 103,093 |
| store operations | 106,533 |
| load local HITM | 1,238 |
| locked load/store ops | 4,869 |
| total shared cache lines | 41 |
| total merged records | 12,850 |
| locked access on shared lines | 4,028 |

## Hot cacheline dominance
| metric | value |
|---|---:|
| top cacheline address | 0x5632175b6b00 |
| top cacheline HITM | 970 |
| top cacheline HITM share | 78.35% |
| top HITM / total HITM | 78.35% (970 / 1,238) |

## Derived ratios (for plotting)
| metric | value |
|---|---:|
| HITM per load | 1.20% (1,238 / 103,093) |
| HITM per store | 1.16% (1,238 / 106,533) |
| locked shared access ratio | 31.35% (4,028 / 12,850) |

## Interpretation
The majority of cacheline bouncing (HITM) is concentrated on a single cacheline, aligning with earlier mapping to `mtbdd_refs` (see `docs/archived/perf/c2c_addr_mapping.md`). This suggests refcount updates are the dominant source of inter-core cacheline invalidation during multi-worker runs.
