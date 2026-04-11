# Parameters

## Core Initialization Parameters

### `NDD.initNDD(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize)`

| Parameter | Meaning |
| --- | --- |
| `nddTableSize` | Initial logical capacity of the NDD node table before GC/grow |
| `nddCacheSize` | Size of the NDD operation caches (`not`, `and`, `or`) |
| `bddTableSize` | Size of the underlying BDD node table |
| `bddCacheSize` | Size of the underlying BDD operation cache |

Use the four-argument form when tuning, and the three-argument form when the default NDD cache size is acceptable.

## Field Layout

### `declareField(bitNum)`

Declares one NDD field width. No BDD variables are created yet.

### `generateFields()`

Materializes all fields after declaration:

- computes the maximum field width
- creates one shared BDD variable array
- right-aligns every field into that array
- builds the corresponding positive and negative field literals

The right-alignment is the main reuse optimization in this branch.

## Label Modes

`NDD` currently exposes three label modes:

| Mode | Meaning |
| --- | --- |
| `BOOLEAN_BDD` | Standard BDD labels |
| `COMPLEMENTED_BDD` | Experimental complemented-edge BDD backend |
| `FINITE_DOMAIN_ZDD` | Experimental finite-domain ZDD label backend |

The default mode is `BOOLEAN_BDD`.

## JavaNDD Parameters

For `NDDFactory`, the main extra parameter is the field-width array passed to:

```java
((NDDFactory) factory).setVarNum(fieldBitWidths, nddTableSize);
```

Unlike a plain BDD factory, NDD benefits from declaring the full domain layout up front instead of growing variables incrementally.

## Benchmark Terms

The benchmark pages use the following column names:

| Column | Meaning |
| --- | --- |
| `time_sec` / `total(s)` | End-to-end runtime |
| `max_rss_kb` / `peak rss` | Peak resident memory |
| `nodes_created` | Total created DD nodes during the run |
| `nodes_alive` | Nodes alive at measurement time |
| `bdd nodes` | BDD nodes used by the benchmark variant |
| `MF` | Benchmark harness parameter as emitted by the WAN/SRE runs |
