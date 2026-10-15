# Usage

This repository exposes two main ways to use NDD:

| API | Package | Best for |
| --- | --- | --- |
| `JNDD` | `org.ants.jndd` | Performance-oriented code that is comfortable working with integer node IDs |
| `JavaNDD` | `org.ants.javandd` | Codebases that already use `JavaBDD` and want a factory/object-style API |

## JNDD Quick Start

The low-level API uses integer node IDs.

```java
import org.ants.jndd.diagram.NDD;

int[] fieldBitWidths = {32, 32, 16, 16, 8};

NDD.initNDD(
    10_000_000, // nddTableSize
    1_000_000,  // nddCacheSize
    10_000_000, // bddTableSize
    1_000_000   // bddCacheSize
);

for (int bitWidth : fieldBitWidths) {
    NDD.declareField(bitWidth);
}
NDD.generateFields();

int srcIpHighBit = NDD.getVar(0, 0);
int dstIpHighBit = NDD.getVar(1, 0);
int packet = NDD.and(srcIpHighBit, dstIpHighBit);
double sat = NDD.satCount(packet);
```

The important ordering rule is:

1. `initNDD(...)`
2. `declareField(...)` for every field
3. `generateFields()`
4. perform logical operations

`generateFields()` materializes the shared, right-aligned variable layout. Fields using the same
label mode share one backend engine and one variable layout.

Backends may be selected per field. The overload without a mode uses the initialization default,
which is `BDD` for the normal `initNDD(...)` overload:

```java
int src = NDD.declareField(32); // default BDD
int dst = NDD.declareField(32, NDD.LabelMode.COMPLEMENTED_BDD);
int protocol = NDD.declareField(8, NDD.LabelMode.ZDD);
int flags = NDD.declareField(6, NDD.LabelMode.BITSET);
NDD.generateFields();
```

The declaration width is a binary bit width for every backend. A width-`w` field always denotes
the same `2^w` MSB-first bit vectors. In the ZDD backend, a concrete assignment is represented by
the set of bit variables whose values are one; the edge label is a family of such sets. Operations
dispatch edge-label work through the backend belonging to the node's field; labels from different
fields/backends are never combined.

To select a non-default edge-label backend, pass a `LabelMode` to `initNDD`:

```java
NDD.initNDD(
    10_000_000,
    1_000_000,
    10_000_000,
    1_000_000,
    NDD.LabelMode.ZDD
);
```

The supported low-level label modes are:

| Mode | Backend | Notes |
| --- | --- | --- |
| `BDD` | Standard BDD labels | Default |
| `COMPLEMENTED_BDD` | Complemented-edge BDD labels | Selected by `--bcdd` in the NQueens driver |
| `ZDD` | Set-family ZDD labels over Boolean bit vectors | Selected by `--zdd` in the NQueens driver |
| `BITSET` | Canonical two-word truth table | Constant-time set operations; field width must be 1–7 bits |

BITSET explicitly stores the membership bit for every value in the largest BITSET field. It is a
useful non-DD backend for small domains, but its storage requirement grows as `2^w`; field
generation rejects BITSET widths above 7. As with the other modes, smaller BITSET fields share the
right-aligned layout of the largest field and retain the same width-`w` logical semantics.

Internally, each label mode used by at least one field is constructed once through a common
label-backend factory. NDD operations call the same label API for ref/deref, Boolean or set
operations, counting, and GC. Homogeneous diagrams retain a single-backend fast path.

The legacy `initNDD(..., mode)` overload remains supported: it selects the default used by
`declareField(width)`. Explicit per-field modes may still be mixed with that default.

In mixed mode, use `getFieldLabelMode(field)` to inspect a field and the field-aware
`refLabel(field, label)` / `derefLabel(field, label)` overloads for raw edge-label handles. The
legacy overloads without a field remain available for homogeneous diagrams and reject ambiguous
mixed-mode use. Aggregate label statistics are available through `getLabelNodeCount()` and
`getLabelTotalCreated()`; overloads accepting a `LabelMode` return per-engine values.

## JNDD Reference-Handling Notes

- `and`, `or`, `not`, `diff`, and `imp` return node IDs that are not permanently protected
- `andTo` and `orTo` consume the first operand and are often the cheaper update-style form in tight loops
- variable nodes created by `generateFields()` are pinned in the table and are not reclaimed by GC

The NQueens example in [`application/nqueen/NDDSolution.java`](../src/main/java/application/nqueen/NDDSolution.java) shows the intended style for building constraints while explicitly balancing `ref` / `deref`.

For field-level cofactors, satisfying-assignment enumeration, quantification, substitution, and the generic Boolean-operation API, see [Manipulation APIs](Manipulation-APIs.md).

## JavaNDD Quick Start

If you are migrating an existing `JavaBDD` codebase, use `NDDFactory`:

```java
import org.ants.javandd.BDD;
import org.ants.javandd.BDDFactory;
import org.ants.javandd.NDDFactory;

int[] fieldBitWidths = {32, 32, 16, 16, 8};

BDDFactory factory = new NDDFactory(1_000_000, 1_000_000);
((NDDFactory) factory).setVarNum(fieldBitWidths, 10_000_000);

BDD one = factory.one();
BDD var = factory.ithVar(0);
BDD result = one.and(var);
```

Compared with plain `JavaBDD`, the main conceptual change is that NDD wants the field partition up front. Growing variables one bit at a time is supported poorly or not at all for realistic use cases; declare the full domain layout first whenever possible.

See also: [`src/main/java/org/ants/javandd/README.md`](../src/main/java/org/ants/javandd/README.md)

## Running Repository Examples

### NQueens Metrics Driver

Build first:

```bash
mvn -DskipTests package
```

Then run the maintained low-level NDD driver:

```bash
java -cp target/ndd-1.0.1-jar-with-dependencies.jar application.nqueen.NDDSolution 8 9 10 11 12
```

To exercise the complemented-BDD label mode:

```bash
java -cp target/ndd-1.0.1-jar-with-dependencies.jar application.nqueen.NDDSolution --bcdd 10 11 12
```

To exercise the set-family ZDD label mode:

```bash
java -cp target/ndd-1.0.1-jar-with-dependencies.jar application.nqueen.NDDSolution --zdd 10 11 12
```

The program emits one `NQUEENS_METRICS ...` line per board size, including runtime, created/alive node counts, and solution counts.

### Product Configuration Quick Start

This example models three finite-domain fields and compatibility constraints without network
terminology. It also provides a direct semantic comparison between a DD label backend and BITSET:

```bash
java -cp target/ndd-1.0.1-jar-with-dependencies.jar application.examples.ProductConfigurationExample
java -cp target/ndd-1.0.1-jar-with-dependencies.jar application.examples.ProductConfigurationExample BITSET
```

The expected counts are 8 valid configurations and 3 valid blue configurations for both runs.

### Legacy NQueens Harness

[`application/nqueen/NQueensExp.java`](../src/main/java/application/nqueen/NQueensExp.java) is an older harness that writes a text file under `results`. It is still useful for quick comparisons, but `NDDSolution` is the better starting point if you want machine-readable metrics.

### JavaNDD Example

[`application/nqueen/JavaNDDSolution.java`](../src/main/java/application/nqueen/JavaNDDSolution.java) shows the factory-style API. It is especially useful if your target codebase already expects `BDD`, `BDDFactory`, and `ithVar(...)`.

## Network-Verification-Oriented Usage

For network workloads, model each semantic packet field as one declared NDD field, for example:

- source IP
- destination IP
- source port
- destination port
- protocol
- flags or metadata fields

That field boundary is what allows NDD to reuse BDD structure across compatible domains. More on that workflow, plus the WAN/SRE and Batfish examples in this repository, is documented in [Network Verification Applications](Network-Verification-Applications.md).

## Next Reading

- [Parameters](Parameters.md) for sizing and field-layout guidance
- [Manipulation APIs](Manipulation-APIs.md) for field-level construction and queries
- [Benchmarks](Benchmarks.md) for how the result pages were generated
