# MTNDD

MTNDD (Multi-Terminal Network Decision Diagram) is a field-oriented decision diagram whose
terminal nodes may hold numeric values instead of only Boolean `false` and `true`. It is useful
for symbolic functions such as route weights, traffic or link load, probabilities, costs, and
integer matrices, while retaining NDD's field-level structure.

This branch contains the standalone MTNDD core migrated from the implementation used by
[`llvf`](https://github.com/XJTU-NetVerify/llvf). The current core supports standard BDD,
complemented-edge BDD (BCDD), and set-family ZDD edge labels. A single MTNDD may assign different
label backends to different fields.

## Build

Requirements:

- JDK 8 or newer
- Maven 3.x

```bash
mvn -DskipTests package
```

The build produces `target/ndd-1.0.1.jar` and a jar with dependencies. Run the core regression
test with:

```bash
mvn -DskipTests=false -Dtest=org.ants.jndd.diagram.NDDMixedBackendTest test
```

## Minimal Example

The historical Java package name `org.ants.jndd` is retained for source compatibility.

```java
import org.ants.jndd.diagram.NDD;

NDD.initNDD(
    1_000_000, // MTNDD node-table threshold
    100_000,   // MTNDD operation-cache size
    1_000_000, // default label node-table size
    100_000    // default label operation-cache size
);

int header = NDD.declareField(32, NDD.LabelMode.BDD);
int links  = NDD.declareField(16, NDD.LabelMode.ZDD);
int state  = NDD.declareField(4, NDD.LabelMode.COMPLEMENTED_BDD);
NDD.generateFields();

NDD headerBit = NDD.getVar(header, 0);
NDD linkBit = NDD.getVar(links, 0);
NDD selected = headerBit.and(linkBit);

NDD weighted = selected.times(NDD.createTerminal(10)).withRef();
double assignmentCount = NDD.satCount(selected);

weighted.recursiveDeref();
```

The required initialization order is:

1. call `initNDD(...)`
2. optionally configure per-backend capacities
3. declare every field and its label mode
4. call `generateFields()` once
5. construct and manipulate diagrams

`declareField(width)` uses BDD labels by default. Fields using the same mode share one label
engine and a right-aligned variable layout; BDD, BCDD, and ZDD fields use separate engines.

## Backend Configuration

Each backend can be sized before its first field is declared:

```java
NDD.configureBackendCapacity(NDD.LabelMode.BDD, 2_000_000, 200_000);
NDD.configureBackendCapacity(NDD.LabelMode.ZDD, 4_000_000, 400_000);
NDD.configureBackendCapacity(NDD.LabelMode.COMPLEMENTED_BDD, 1_000_000, 100_000);
```

The modes have the same Boolean bit-vector field semantics:

| Mode | Edge-label representation | Main characteristic |
| --- | --- | --- |
| `BDD` | reduced ordered BDD | general-purpose default |
| `COMPLEMENTED_BDD` | BDD with complemented handles | constant-time label negation |
| `ZDD` | family of sets of true-bit variables | explicit per-field universe |
| `BITSET` | canonical two-word truth table | O(1) set operations for fields up to 7 bits |

Backend selection changes representation, not the logical domain of a field. A width-`w` field
always denotes `2^w` bit-vector assignments.

## Core API

MTNDD nodes are exposed as lightweight `NDD` wrappers over canonical integer node IDs.

| Purpose | Main methods |
| --- | --- |
| Initialization | `initNDD`, `configureBackendCapacity`, `declareField`, `generateFields` |
| Variables | `getVar`, `getNotVar`, `encodePrefix`, `encodePrefixs` |
| Boolean/set operations | `and`, `or`, `not`/`cmpl`, `diff`, `imp`, `exist` |
| Multi-terminal arithmetic | `add`/`plus`, `sub`/`minus`, `mul`/`times`, `div`/`divide`, `sumAbstract`, `multiplySumAbstract` |
| Terminals | `getFalse`, `getTrue`, `createTerminal(int/double/Rational)` |
| Inspection | `evaluate`, `satCount`, `toArray`, `print`, `printDot` |
| Lifetime | `ref`, `withRef`, `deref`, `recursiveDeref`, `gc`, `gcLabelEngines` |

Results are not permanently rooted by default. Protect values that must survive later allocation
or explicit collection with `withRef()`/`ref()`, then release them with
`recursiveDeref()`/`deref()`.

## Repository Structure

```text
src/main/java/
├── org/ants/jndd/
│   ├── diagram/       MTNDD operations, backend dispatch, manager and terminals
│   ├── nodetable/     array-backed canonical node/edge storage and MTNDD GC
│   ├── bdd/           primitive complemented-edge BDD implementation
│   ├── cache/         legacy generic operation-cache utility
│   └── utils/         rational terminals and BDD decomposition helpers
├── jdd/               bundled BDD/ZDD label engines
├── application/matrix symbolic matrix-multiplication example
├── application/wan/   retained network-verification research artifacts
└── org/ants/javandd/  legacy JavaBDD-compatible NDD facade

src/test/java/org/ants/jndd/
└── diagram/           mixed-backend and multi-terminal regression tests
```

The primary implementation is `org.ants.jndd.diagram.NDD`. `NDDManager` is a small object-style
facade. The WAN paths and atomized prototypes are retained for research reference and are excluded
from the default Maven compilation where noted in `pom.xml`.

## Implementation Notes

- MTNDD nodes and physical edges are stored in primitive arrays and canonicalized by a unique
  table.
- Recursive operations collect edges in a shared stack, then sort, merge equal targets, and create
  one canonical node at a safe point.
- Dense importers can use `NDD.BulkBuilder` to avoid allocating a wrapper per input terminal.
- Unreferenced numeric terminals participate in safe-point garbage collection; edge and terminal
  indices are compacted without changing live handles.
- Each field records the backend that owns its edge-label handles; label operations are dispatched
  through that backend and handles are never mixed between engines.
- Rational terminal values are canonicalized, so equal numeric leaves share a terminal node.
- Opt-in primitive-double and interval terminal modes avoid per-terminal `Rational` objects.
  Double terminals support configurable mantissa/grid clustering, tracked approximation bounds,
  and explicit near-zero pruning. Interval terminals canonicalize outward-rounded lower/upper
  pairs and provide compact or tight arithmetic-enclosure policies. An experimental nominal-bucket
  plus multiplicative-radius-class encoding is also available for representation studies.
  Rational remains the default.
- MTNDD and label engines expose live/created node counts plus collection and growth metrics for
  performance diagnosis.

See the [MTNDD wiki page](https://github.com/XJTU-NetVerify/NDD/wiki/MTNDD) for the algorithm and
interface model. The original NDD paper and NDD-specific design material remain available in the
repository wiki and are not repeated here.

## License

Apache-2.0. See [`LICENSE`](LICENSE).
