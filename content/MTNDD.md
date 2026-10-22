# MTNDD

MTNDD extends NDD from Boolean packet sets to multi-terminal symbolic functions. The outer
field-oriented decision structure is unchanged; the main semantic difference is that a terminal
may contain an exact rational value rather than only `0` or `1`.

This page describes only the MTNDD-specific algorithm and API. For the motivation for field-level
nodes, shared label variables, and the array-based NDD node table, see [Design Notes](Design-Notes.md)
and the original NDD material.

## Function Model

For fields `F0 ... Fn`, an MTNDD represents a function

```text
F0 × F1 × ... × Fn -> Rational
```

An internal node belongs to one field. Each outgoing edge has:

- a predicate over that field, represented by the field's label backend
- a target MTNDD node or terminal

Missing assignments implicitly lead to terminal zero. Outgoing predicates of a canonical node are
disjoint, predicates leading to the same target are merged, zero-target edges are omitted, and a
single universe edge is reduced to its target. Terminals are canonicalized by rational value.

These rules let one structure represent Boolean sets (`0`/`1` terminals), piecewise numeric
functions, and mixtures of the two.

## Recursive Operations

Operations follow field order. At a pair of nodes, the implementation distinguishes three cases:

1. both operands are terminals: apply the terminal operation directly
2. one operand has the earlier field: recurse on its targets and retain its edge predicates
3. both operands have the same field: intersect their predicates, recurse on matching target pairs,
   then process residual predicates according to the operation

The third case is the important MTNDD step. For arithmetic, every predicate intersection selects
the terminal arithmetic to apply downstream. For Boolean/set operations, it selects the appropriate
union, intersection, complement, or difference behavior. Candidate edges are collected in a shared
array stack and are sorted and merged before canonical node creation.

### Multi-Terminal Operations

| Operation | Static API | Object API |
| --- | --- | --- |
| addition | `NDD.add(a, b)` | `a.plus(b)` |
| subtraction | `NDD.sub(a, b)` | `a.minus(b)` |
| multiplication | `NDD.mul(a, b)` | `a.times(b)` |
| division | `NDD.div(a, b)` | `a.divide(b)` |
| Boolean complement | `NDD.not(a)` | `a.cmpl()` |
| sum over a field | `NDD.sumAbstract(a, field)` | — |

`sumAbstract` differs from Boolean existential abstraction. It weights each outgoing target by the
number of assignments in its edge predicate and adds the weighted targets. `exist` instead ORs the
targets and is intended for Boolean-set semantics.

## Label Backends and Mixed Diagrams

Edge labels are hidden behind `LabelDecisionDiagramBackend`. The current modes are:

| `NDD.LabelMode` | Representation | Universe behavior |
| --- | --- | --- |
| `BDD` | standard reduced ordered BDD | global Boolean universe handle |
| `COMPLEMENTED_BDD` | primitive complemented-edge BDD | global Boolean universe handle |
| `ZDD` | set family over true-bit variables | explicit universe per field |
| `BITSET` | canonical two-word truth table | explicit universe; widths 1–7 only |

All four modes describe the same width-`w` domain of `2^w` bit vectors. ZDD is not a one-of-`w`
finite domain: an assignment is encoded as the set of variables whose bits are one, and an edge
predicate is a family of those sets.

A mixed MTNDD records an owning backend for every field. All fields of one mode share one engine and
one right-aligned variable pool; different modes have separate engines. During recursion, label
intersection, difference, complement, counting, reference management, and GC are dispatched through
the backend of the current field. A raw label handle is meaningful only within its owning backend.

This is particularly useful when domains have different predicate structure. For example, packet
headers may use BDD labels while a small state field uses BITSET independently. BITSET stores one
membership bit per concrete value, making its set operations constant time at the cost of an
exponentially growing truth table; the implementation therefore rejects widths above 7.

## Initialization and Field Interface

The implementation currently uses one process-global manager. Complete field declaration before
building diagrams:

```java
NDD.initNDD(
    10_000_000, // MTNDD table threshold
    1_000_000,  // MTNDD operation cache
    10_000_000, // default label table per active backend
    1_000_000   // default label cache per active backend
);

NDD.configureBackendCapacity(NDD.LabelMode.BDD, 10_000_000, 1_000_000);
NDD.configureBackendCapacity(NDD.LabelMode.ZDD, 20_000_000, 2_000_000);

int header = NDD.declareField(32, NDD.LabelMode.BDD);
int links = NDD.declareField(24, NDD.LabelMode.ZDD);
int state = NDD.declareField(4, NDD.LabelMode.COMPLEMENTED_BDD);
NDD.generateFields();
```

`configureBackendCapacity` must be called before the first field using that backend is declared.
The default capacities passed to `initNDD` are assigned in full to each active label engine; they
are not divided among modes.

After `generateFields()`, use:

- `getVar(field, bit)` and `getNotVar(field, bit)` for literals
- `encodePrefix(bits, field)` and `encodePrefixs(...)` for field predicates
- `getFieldLabelMode(field)` and `hasMixedLabelModes()` for layout inspection
- `getBDDEngine()`, `getBCDDEngine()`, or `getZDDEngine()` only when backend-specific access is
  intentionally required

Raw-BDD conversion helpers such as `toBDD` and the field-less `toNDD` require a homogeneous BDD
layout. Prefer the backend-neutral construction APIs in reusable applications.

## Terminals, Evaluation, and Counting

`createTerminal(int)`, `createTerminal(double)`, and `createTerminal(Rational)` create or reuse a
canonical numeric leaf. `getFalse()` and `getTrue()` are terminals zero and one.

`evaluate(root, int[][] assignment)` follows one complete MSB-first bit vector per field and returns
a `Rational`. A compatibility overload accepts one non-negative Java `int` per field and returns a
`double`. `satCount` counts assignments represented by a Boolean diagram or assignments reaching a
selected terminal, depending on the overload.

## Lifetime and Collection

MTNDD and its label engines use explicit root references in addition to JVM memory management:

```java
NDD result = left.times(right).withRef();
// retain result across later allocation or NDD.gc()
result.recursiveDeref();
```

The outer MTNDD collector traces referenced roots and temporarily protected recursive results.
Label handles stored on live edges are reference-counted in their owning engine. Edge compaction and
retired-slot reuse run only at safe points after recursion has unwound.

Relevant diagnostics include:

- MTNDD: `getNodeCount`, `getInternalNodeCount`, `getTotalCreated`, `getLivePhysicalEdgeCount`,
  `getPhysicalEdgeSlots`, `getGcCount`, `getGcFreedCount`, `getGcTimeMillis`
- per label mode: `getLabelNodeCount`, `getLabelTotalCreated`, `getLabelGcCount`,
  `getLabelGcFreedCount`, `getLabelGcTimeMillis`, `getLabelGrowCount`, `getLabelGrowTimeMillis`

`gc()` collects the outer MTNDD and `gcLabelEngines()` explicitly collects every active label
engine. Normal table growth may also trigger collection.

## Implementation Map

| Path | Responsibility |
| --- | --- |
| `org/ants/jndd/diagram/NDD.java` | public API, recursive operations, field/backend ownership, safe points |
| `org/ants/jndd/diagram/LabelDecisionDiagramBackend.java` | backend-neutral label contract |
| `org/ants/jndd/diagram/LabelDecisionDiagramBackends.java` | BDD, BCDD, set-family ZDD, and BITSET adapters |
| `org/ants/jndd/nodetable/NodeTable.java` | canonical MTNDD nodes, terminals, edge blocks, tracing GC |
| `org/ants/jndd/bdd/ComplementedBDD.java` | primitive complemented-edge BDD label manager |
| `org/ants/jndd/utils/Rational.java` | exact multi-terminal values |

## Current Constraints

- The manager is static and is not designed for concurrent independent MTNDD instances in one JVM.
- Fields and their backend modes are fixed after `generateFields()`.
- Backend-local label handles must not be moved to another field/backend.
- `NDD` wrappers are lightweight views; callers must use explicit references for long-lived roots.
- Backend choice is workload-dependent. BDD, BCDD, ZDD, and BITSET have equivalent semantics but can differ
  substantially in node count, GC pressure, time, and memory.
