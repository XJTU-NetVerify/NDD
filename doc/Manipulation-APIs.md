# Manipulation APIs

This page documents the field-aware operations in the low-level `org.ants.jndd.diagram.NDD` API. NDD node IDs are integers; initialize the engine, declare every field, and call `generateFields()` before using any operation.

```java
NDD.initNDD(100_000, 10_000, 100_000, 10_000);
int src = NDD.declareField(32);
int dst = NDD.declareField(32);
NDD.generateFields();
```

Unless noted otherwise, every operation returns an NDD node ID that is not permanently protected. Call `NDD.ref(result)` if the result must survive later allocations or garbage collection, and balance it with `NDD.deref(result)` when it is no longer needed.

## Overview

| Need | API | Field-aware behavior |
| --- | --- | --- |
| Canonical construction | `mk(field, edges)`, `addAtField(field, edges)` | Builds or reuses a node whose outgoing edges are labeled by field predicates. |
| Boolean operations | `and`, `or`, `not`, `diff`, `imp` | Combines complete NDDs. |
| Generic Boolean operation | `apply(operation, left, right)` | Supports `AND`, `OR`, `XOR`, `NAND`, `NOR`, `BIIMP`, `IMP`, and `DIFF`. |
| Care-set simplification | `simplify(function, careSet)` | Preserves `function` on the care set and returns FALSE outside it. |
| Cofactor | `restrict(root, field, value)` | Fixes one field and removes it from the returned NDD. |
| Counting and witnesses | `satCount`, `anySat`, `allSat` | Counts or enumerates complete assignments over declared fields. |
| Quantification | `exist(root, fields...)` | Projects out one or more complete fields. |
| Replacement | `substitute(root, sourceField, targetField)` | Replaces the source field with the target field. |

## Construction and Boolean Operations

`mk` is the NDD equivalent of an ROBDD unique-table constructor. Instead of a single variable and two successors, it receives a field and a target-to-label map. Equal nodes are canonicalized by the node table.

```java
Map<Integer, Integer> edges = new HashMap<>();
edges.put(NDD.getTrue(), someFieldLabel);
int node = NDD.addAtField(src, edges);
```

Most callers should use the supplied encoders or literal nodes instead:

```java
int sourceHighBit = NDD.getVar(src, 0);
int destinationHighBit = NDD.getVar(dst, 0);
int allowed = NDD.and(sourceHighBit, destinationHighBit);

int different = NDD.apply(NDD.BinaryOperation.XOR, sourceHighBit, destinationHighBit);
int implication = NDD.apply(NDD.BinaryOperation.IMP, sourceHighBit, destinationHighBit);
```

`simplify(function, careSet)` computes a generalized cofactor. It guarantees:

```text
simplify(function, careSet) AND careSet
    == function AND careSet
```

Values outside `careSet` are don't-cares. The recursive algorithm uses them to remove fields and merge equal subgraphs:

- if the care set is FALSE, the result is FALSE
- if the care set is TRUE or the function is terminal, the function is returned unchanged
- when the care set alone tests an earlier field, that field is removed if every cared-for branch simplifies to the same target
- when both operands test the same field, their edge-label predicates/value sets are intersected and only care-covered regions are retained
- if every care-covered region requires the same target, the field node is eliminated

This is the NDD counterpart of BDD restrict-style simplification. It is implemented for every
label backend: BDD and complemented BDD use Boolean edge predicates, while set-family ZDD uses
field-universe-relative family intersection and difference.

## Restriction (Cofactor)

`restrict` fixes a field to one value and existentially removes that field from the result. This is the field-level counterpart of BDD `restrict`/cofactor.

For every label backend, use either an unsigned `long` for fields up to 63 bits or an MSB-first bit
vector of exactly the field width:

```java
// Assume tcp was declared before generateFields().

int onlyPort443 = NDD.restrict(policy, tcp, 443L);
int sameResult = NDD.restrict(policy, tcp,
    new int[]{0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1});
```

Bit index 0 is the most significant bit, matching `getVar(field, index)` and `encodePrefix`.

## Satisfying Assignments

```java
double numberOfPackets = NDD.satCount(policy);

int[][] witness = NDD.anySat(policy);
if (witness != null) {
    // For every backend, witness[field][bit] is 0 or 1.
}

long visited = NDD.allSat(policy, assignment -> {
    inspect(assignment);
    return true;  // return false to stop early
});
```

`anySat` returns `null` for FALSE. For every backend, each `assignment[field]` contains the complete
MSB-first bit vector for that field.

`allSat` enumerates concrete, complete assignments and passes a defensive copy to the callback. It can be exponential in the number of free field values; use `anySat` for one witness or stop early by returning `false` from the callback. Its return value is the number of assignments delivered to the callback.

## Existential Quantification

Quantification is at field granularity. The one-field and multi-field overloads use the same semantics:

```java
int withoutSource = NDD.exist(reachability, src);
int headerIndependent = NDD.exist(reachability, src, dst);
```

The result no longer constrains the projected field(s). `satCount` still counts assignments in the original declared field universe, so a projected field contributes all of its possible values to the count.

Universal quantification can be expressed using the usual dual: `not(exist(not(root), field))`.

## Field Substitution and Replacement

`substitute(root, sourceField, targetField)` returns the function formed by replacing every
occurrence of `sourceField` with `targetField`. The two fields must have identical widths and use
the same label backend.

```java
// Turns a predicate over src into the corresponding predicate over dst.
int destinationPredicate = NDD.substitute(sourcePredicate, src, dst);
```

The implementation constructs equality between the fields, conjoins it with the input, and existentially quantifies the source field. This preserves correct semantics even when the target field already occurs in `root`.

## Backend Notes

`apply`, `simplify`, `exist`, `anySat`, and `allSat` work across diagrams whose fields use any
mixture of supported label modes. `substitute` supports fields using the same backend and rejects
cross-backend replacement. `restrict`, `anySat`, `allSat`, and prefix encoding use the same binary
bit-vector semantics for `BDD`, `COMPLEMENTED_BDD`, `ZDD`, and `BITSET` fields. BITSET fields are
limited to widths of at most 7 bits.

For basic setup and encoding helpers, return to [Usage](Usage.md). For sizing and field-layout guidance, see [Parameters](Parameters.md).
