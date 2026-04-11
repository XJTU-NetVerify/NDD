# Usage

## JNDD (`org.ants.jndd`)

The optimized JNDD implementation uses integer node IDs rather than per-node Java objects.

### Initialization

```java
NDD.initNDD(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize);

for (int bitNum : fieldBitWidths) {
    NDD.declareField(bitNum);
}
NDD.generateFields();
```

`declareField(bitNum)` records field widths first, and `generateFields()` later builds the shared BDD-variable layout.

### Basic Operations

```java
int x0 = NDD.getVar(0, 0);
int x1 = NDD.getVar(0, 1);

int value = NDD.getFalse();
value = NDD.orTo(value, x0);
value = NDD.orTo(value, x1);

int neg = NDD.not(value);
int both = NDD.and(value, neg);
double sat = NDD.satCount(value);
```

### Reference Handling

- `and`, `or`, `not`, `diff`, `imp` return node IDs that are not permanently protected
- `andTo` and `orTo` consume the first operand by dereferencing it after building the new result
- variable nodes created during `generateFields()` are fixed in the node table and are not garbage collected

### GC Diagnostics

You can enable GC logging through JDD:

```java
import jdd.util.Options;

Options.gc_log = true;
```

## JavaNDD (`org.ants.javandd`)

For a `BDDFactory`-style API:

```java
BDDFactory factory = new NDDFactory(bddTableSize, bddCacheSize);
((NDDFactory) factory).setVarNum(fieldBitWidths, nddTableSize);

BDD one = factory.one();
BDD var = factory.ithVar(0);
BDD result = one.and(var);
```

This is the easier drop-in path when migrating code that already uses `JavaBDD`.

## Examples in This Repository

- NQueens driver: [`src/main/java/application/nqueen/NQueensExp.java`](../src/main/java/application/nqueen/NQueensExp.java)
- JNDD NQueens solution: [`src/main/java/application/nqueen/NDDSolution.java`](../src/main/java/application/nqueen/NDDSolution.java)
- JavaNDD NQueens solution: [`src/main/java/application/nqueen/JavaNDDSolution.java`](../src/main/java/application/nqueen/JavaNDDSolution.java)
