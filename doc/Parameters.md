# Parameters

This page explains the initialization parameters, field-layout controls, and benchmark terms used throughout the repository.

## Core Initialization

### `NDD.initNDD(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize)`

| Parameter | Meaning | Practical guidance |
| --- | --- | --- |
| `nddTableSize` | Initial capacity of the NDD node table | Start large enough to avoid repeated growth on your target workload |
| `nddCacheSize` | Size of the NDD operation caches | Increase when repeated logical subproblems dominate runtime |
| `bddTableSize` | Capacity of the underlying label decision-diagram node table | This matters directly because edge labels are stored in the selected backend |
| `bddCacheSize` | Cache size for the underlying label backend | Raise it when label operations are heavy |

There are also convenience overloads:

- `initNDD(nddTableSize, bddTableSize, bddCacheSize)` uses the default NDD cache size
- `initNDD(..., LabelMode mode)` selects a non-default label backend

## Field Declaration And Layout

### `declareField(bitNum)`

Registers one field width, but does not create any label-backend variables yet.

### `generateFields()`

Finalizes the full field layout after all declarations:

- computes the maximum field width across declared fields
- creates the shared label-variable pool
- right-aligns each field against that pool
- materializes the positive and negative literals used by NDD operations

This right-alignment is the key reuse optimization in the optimized branch. If two packet fields have compatible suffix widths, they can reuse the same underlying label variables rather than duplicating the label structure.

### Modeling Rule

Treat each semantic packet field as one declared NDD field. For example:

- a 32-bit IPv4 address field should usually be one `declareField(32)`
- source and destination ports should be separate 16-bit fields
- primed and unprimed versions of the same logical header should still be separate fields if the algorithm distinguishes them

Do not flatten everything into one long bitstring unless you explicitly want plain BDD-like behavior.

## JavaNDD Parameters

For the `BDDFactory`-style API, the main extra method is:

```java
((NDDFactory) factory).setVarNum(fieldBitWidths, nddTableSize);
```

Here:

- `fieldBitWidths` is the full field partition, for example `{32, 32, 16, 16, 8, ...}`
- `nddTableSize` is the NDD node-table capacity used by the factory-backed implementation

This repository's Batfish patch and JavaNDD examples both use the "declare all fields up front" style. That is the recommended approach.

## Label Modes

The low-level API currently exposes four label modes:

| Mode | Meaning | Status |
| --- | --- | --- |
| `BDD` | Standard BDD labels | Default and maintained |
| `COMPLEMENTED_BDD` | Primitive complemented-edge BDD labels with constant-time negation | Experimental |
| `ZDD` | Set-family ZDD labels over Boolean bit vectors | Experimental |
| `BITSET` | Canonical two-word truth table for all field values | Experimental; widths 1–7 only |

The NQueens example can be invoked with `--bcdd` or `--zdd` to exercise the non-default modes.

For every mode, `declareField(w)` declares a `w`-bit field with `2^w` logical assignments. ZDD and
BITSET change only the representation of a set of assignments, not the field's logical domain.

The implementation creates the concrete BDD, BCDD, ZDD, or BITSET engine through an internal factory and then routes common label operations through the same backend interface. Most NDD logic therefore does not need to know which concrete label engine is active. BITSET ignores table/cache capacity settings because its labels are interned truth tables retained for the engine lifetime.

## Example Sizing From This Repository

The example programs are a useful guide for scale, but not a universal tuning rule:

- `application.nqueen.NDDSolution` uses `NDD_TABLE_SIZE = 100000000`
- the same example chooses the NDD cache size with a simple workload-dependent heuristic based on `n`
- `application.wan.ndd.verifier.apkeep.utils.Parameters` currently sets `BDD_TABLE_SIZE = 10000000` for the WAN/SRE research path

Use those values as starting points for similar workloads, not as one-size-fits-all defaults.

## JVM Tuning Properties

A few internal thresholds can be overridden with `-D` system properties at JVM start. These are advanced knobs; the defaults are tuned for typical NDD workloads.

| Property | Default | Meaning |
| --- | --- | --- |
| `ndd.radixThreshold` | `64` | Per-node frame size (number of collected edges) at or above which `edgeFlush` switches from comparison/insertion sort to an O(n) LSD radix sort when canonicalizing edges. Lower it if your workload has consistently high node fan-out; raise it to keep comparison sorting for larger frames. |

These affect performance only, not results. See [Design Notes](Design-Notes.md) for how edge collection and flushing work.

## Common Pitfalls

- Calling `generateFields()` before all fields are declared
- Trying to add fields dynamically after the layout has been fixed
- Under-sizing the BDD table while tuning only the NDD table
- Modeling packet headers as one monolithic field and then expecting NDD-style locality benefits
- Mixing maintained example code with WAN/SRE experiment code without accounting for the older dependencies in the latter

## Benchmark Terms

The benchmark and result pages use the following columns:

| Column | Meaning |
| --- | --- |
| `time_sec` / `total(s)` | End-to-end runtime |
| `src(s)` | Source or preprocessing portion reported by the WAN/SRE harness |
| `max_rss_kb` / `peak rss` | Peak resident memory |
| `nodes_created` | Total decision-diagram nodes created during the run |
| `nodes_alive` | Nodes still alive at the measurement point |
| `ndd_nodes_*` | Counts attributed to NDD nodes only |
| `bdd_nodes_*` | Counts attributed to label-backend nodes only; the field name is kept for compatibility with existing metrics |
| `MF` | Harness parameter used in the WAN/SRE datasets |

For workload-specific interpretation, see [Benchmarks](Benchmarks.md), [Results: NQueens](Results-NQueens.md), and [Results: SRE](Results-SRE.md).
