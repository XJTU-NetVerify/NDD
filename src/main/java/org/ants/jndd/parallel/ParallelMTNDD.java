package org.ants.jndd.parallel;

import java.util.Objects;

/** Owned multi-terminal NDD roots over the compact parallel manager.
 * Fields are zero-based and bits are MSB-first. All calls, node close and engine
 * close serialize on this instance; internal tasks share one Lace pool. */
public final class ParallelMTNDD implements AutoCloseable {
    public enum Backend { BDD, BCDD, ZDD, BITSET }
    public enum Operation { AND, OR, DIFF, XOR, IMP, EQUIV, NAND, NOR, PLUS, MINUS, TIMES, DIVIDE }
    public enum Domain { RATIONAL, DOUBLE }
    public enum Reduction { LEFT_FOLD, BALANCED_TREE }

    public static final class Field {
        public final int width;
        public final Backend backend;
        public Field(int width, Backend backend) {
            this.backend = Objects.requireNonNull(backend, "backend");
            if (width < 1 || width > 4096 || (backend == Backend.BITSET && width > 7))
                throw new IllegalArgumentException("Width must be 1..4096 (BITSET: 1..7)");
            this.width = width;
        }
    }

    public static final class Stats {
        public final long nodesLive, nodesCreated, labelsLive, labelsCreated;
        public final long tasks, stolenTasks, cacheHits, cacheMisses, collections;
        public final long externalRuns, mergeNanos, nativeBytes, peakNativeBytes;
        public final long labelCollections;
        Stats(long[] s) {
            nodesLive = s[0]; nodesCreated = s[1]; labelsLive = s[2]; labelsCreated = s[3];
            tasks = s[4]; stolenTasks = s[5]; cacheHits = s[6]; cacheMisses = s[7];
            collections = s[8]; externalRuns = s[9]; mergeNanos = s[10];
            nativeBytes = s[11]; peakNativeBytes = s[12]; labelCollections = s[13];
        }
    }

    static {
        String path = System.getProperty("pmtndd.library");
        if (path == null) System.loadLibrary("pmtnddjni");
        else System.load(path);
    }
    private final Field[] fields;
    private final Domain domain;
    private final long nativeEngine;
    private volatile boolean closed;

    public ParallelMTNDD(ParallelNDDConfig config, Domain domain, Field... fields) {
        Objects.requireNonNull(config, "config");
        this.domain = Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(fields, "fields");
        if (fields.length < 1 || fields.length > 256)
            throw new IllegalArgumentException("An engine needs 1..256 fields");
        this.fields = fields.clone();
        int[] widths = new int[fields.length], backends = new int[fields.length];
        for (int i = 0; i < fields.length; ++i) {
            Objects.requireNonNull(this.fields[i], "field");
            widths[i] = this.fields[i].width;
            backends[i] = this.fields[i].backend.ordinal();
        }
        synchronized (NativeEngineAdmission.class) {
            if (NativeEngineAdmission.active != null) throw new IllegalStateException("Only one active native engine is supported");
            int flags = (config.nddParallel ? 1 : 0) | (config.labelParallel ? 2 : 0) | (config.batchParallel ? 4 : 0) | (config.reverseLabelOrder ? 8 : 0);
            nativeEngine = openNative(widths, backends, config.workers, config.memoryBytes,
                    config.cacheEntries, config.taskThreshold, config.chunkSize,
                    config.spawnDepth, flags, config.gcInterval, domain.ordinal());
            NativeEngineAdmission.active = this;
        }
    }

    public int fieldCount() { return fields.length; }
    public Field field(int index) { checkField(index); return fields[index]; }
    public boolean isClosed() { return closed; }
    private void checkOpen() { if (closed) throw new IllegalStateException("Native engine is closed"); }
    private void checkField(int field) {
        if (field < 0 || field >= fields.length) throw new IllegalArgumentException("Invalid field index");
    }
    private long handle(MTNDDNode node) {
        checkOpen(); Objects.requireNonNull(node, "node");
        if (node.engine != this) throw new IllegalArgumentException("Node belongs to another engine");
        if (node.closed) throw new IllegalStateException("Node is closed");
        return node.handle;
    }
    private MTNDDNode wrap(long handle) {
        try { return new MTNDDNode(this, handle); }
        catch (OutOfMemoryError error) { releaseNative(nativeEngine, handle); throw error; }
    }

    public synchronized MTNDDNode constant(boolean value) { checkOpen(); return wrap(constantNative(nativeEngine, value)); }
    public synchronized MTNDDNode literal(int field, int bit, boolean positive) {
        checkOpen(); checkField(field);
        if (bit < 0 || bit >= fields[field].width) throw new IllegalArgumentException("Invalid bit index");
        return wrap(literalNative(nativeEngine, field, bit, positive));
    }
    /** Values are unsigned; fields wider than 64 use zero extension. Use literals
     * to constrain arbitrary bits of a wide field. */
    public synchronized MTNDDNode value(int field, long value) {
        checkOpen(); checkField(field);
        if (fields[field].width < 64 && (value < 0 || (value >>> fields[field].width) != 0))
            throw new IllegalArgumentException("Value outside field domain");
        return wrap(valueNative(nativeEngine, field, value));
    }
    public synchronized MTNDDNode apply(Operation operation, MTNDDNode a, MTNDDNode b) {
        Objects.requireNonNull(operation, "operation");
        return wrap(applyNative(nativeEngine, operation.ordinal(), handle(a), handle(b)));
    }
    public MTNDDNode and(MTNDDNode a, MTNDDNode b) { return apply(Operation.AND, a, b); }
    public MTNDDNode or(MTNDDNode a, MTNDDNode b) { return apply(Operation.OR, a, b); }
    public MTNDDNode diff(MTNDDNode a, MTNDDNode b) { return apply(Operation.DIFF, a, b); }
    public synchronized MTNDDNode not(MTNDDNode a) {
        try (MTNDDNode one = constant(true)) { return apply(Operation.DIFF, one, a); }
    }
    public synchronized MTNDDNode exist(MTNDDNode a, int field) {
        checkField(field); return wrap(existNative(nativeEngine, handle(a), field));
    }
    public synchronized double satCount(MTNDDNode a) { return countNative(nativeEngine, handle(a)); }
    public synchronized Number evaluate(MTNDDNode a, long... values) {
        Objects.requireNonNull(values, "values");
        if (values.length != fields.length) throw new IllegalArgumentException("One value per field required");
        return new Number(evaluateNative(nativeEngine, handle(a), values));
    }
    public synchronized MTNDDNode retain(MTNDDNode a) { return wrap(retainNative(nativeEngine, handle(a))); }
    synchronized void release(MTNDDNode a) {
        if (!a.closed) {
            if (!closed) releaseNative(nativeEngine, a.handle);
            a.closed = true;
        }
    }
    public synchronized MTNDDNode[] applyBatch(Operation operation, MTNDDNode[] a, MTNDDNode[] b) {
        checkOpen(); Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(a, "left"); Objects.requireNonNull(b, "right");
        if (a.length != b.length) throw new IllegalArgumentException("Batch lengths differ");
        long[] left = new long[a.length], right = new long[a.length];
        for (int i = 0; i < a.length; ++i) { left[i] = handle(a[i]); right[i] = handle(b[i]); }
        MTNDDNode[] nodes = new MTNDDNode[a.length];
        long[] result = batchNative(nativeEngine, operation.ordinal(), left, right);
        int i = 0;
        try {
            for (; i < nodes.length; ++i) nodes[i] = new MTNDDNode(this, result[i]);
            return nodes;
        } catch (OutOfMemoryError error) {
            for (int j = 0; j < i; ++j) nodes[j].close();
            for (; i < result.length; ++i) releaseNative(nativeEngine, result[i]);
            throw error;
        }
    }
    public synchronized MTNDDNode reduce(Operation operation, Reduction order, MTNDDNode... nodes) {
        checkOpen(); Objects.requireNonNull(order, "order"); Objects.requireNonNull(nodes, "nodes");
        if (operation != Operation.AND && operation != Operation.OR && operation != Operation.PLUS && operation != Operation.TIMES)
            throw new IllegalArgumentException("Reduction supports AND, OR, PLUS and TIMES");
        long[] values = new long[nodes.length];
        for (int i = 0; i < nodes.length; ++i) values[i] = handle(nodes[i]);
        return wrap(reduceNative(nativeEngine, operation.ordinal(), values, order == Reduction.BALANCED_TREE));
    }
    /** Exact normalized rational, or finite IEEE double according to engine domain. */
    public static final class Number {
        public final long numerator, denominator;
        public final double value;
        public final Domain domain;
        Number(long[] raw) {
            numerator = raw[0]; denominator = raw[1];
            value = Double.longBitsToDouble(raw[2]); domain = Domain.values()[(int) raw[3]];
        }
        @Override public String toString() { return domain == Domain.RATIONAL ? numerator + "/" + denominator : Double.toString(value); }
    }
    public Domain domain() { return domain; }
    public synchronized MTNDDNode fraction(long numerator, long denominator) {
        checkOpen(); if (domain != Domain.RATIONAL) throw new IllegalStateException("Engine is not rational");
        if (denominator == 0) throw new ArithmeticException("Zero denominator");
        return wrap(fractionNative(nativeEngine, numerator, denominator));
    }
    public synchronized MTNDDNode number(double value) {
        checkOpen(); if (domain != Domain.DOUBLE) throw new IllegalStateException("Engine is not double");
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Finite value required");
        return wrap(doubleNative(nativeEngine, value));
    }
    public MTNDDNode plus(MTNDDNode a, MTNDDNode b) { return apply(Operation.PLUS, a, b); }
    public MTNDDNode minus(MTNDDNode a, MTNDDNode b) { return apply(Operation.MINUS, a, b); }
    public MTNDDNode times(MTNDDNode a, MTNDDNode b) { return apply(Operation.TIMES, a, b); }
    public MTNDDNode divide(MTNDDNode a, MTNDDNode b) { return apply(Operation.DIVIDE, a, b); }
    public synchronized MTNDDNode sumAbstract(MTNDDNode a, int field) {
        checkField(field); return wrap(sumNative(nativeEngine, handle(a), field));
    }
    public synchronized MTNDDNode multiplySumAbstract(MTNDDNode a, MTNDDNode b, int field) {
        checkField(field); return wrap(fusedNative(nativeEngine, handle(a), handle(b), field));
    }
    public synchronized Number terminalValue(MTNDDNode a) { return new Number(terminalNative(nativeEngine, handle(a))); }
    public synchronized void collect() { checkOpen(); collectNative(nativeEngine); }
    /** Dense import, field 0 slowest, last field fastest; one native RUN. */
    public synchronized MTNDDNode fromArray(double[] values) {
        checkOpen(); Objects.requireNonNull(values, "values");
        if (domain != Domain.DOUBLE) throw new IllegalStateException("Engine is not double");
        checkDenseSize(values.length);
        for (double v : values) if (!Double.isFinite(v)) throw new IllegalArgumentException("Finite values required");
        return wrap(importNative(nativeEngine, values, null, null));
    }
    public synchronized MTNDDNode fromFractions(long[] numerators, long[] denominators) {
        checkOpen(); Objects.requireNonNull(numerators, "numerators"); Objects.requireNonNull(denominators, "denominators");
        if (domain != Domain.RATIONAL) throw new IllegalStateException("Engine is not rational");
        checkDenseSize(numerators.length);
        if (numerators.length != denominators.length) throw new IllegalArgumentException("Array lengths differ");
        for (long d : denominators) if (d == 0) throw new ArithmeticException("Zero denominator");
        return wrap(importNative(nativeEngine, null, numerators, denominators));
    }
    private void checkDenseSize(int length) {
        int bits = 0; for (Field field : fields) { if (field.width > 30 - bits) throw new IllegalArgumentException("Dense domain too large"); bits += field.width; }
        if (length != (1 << bits)) throw new IllegalArgumentException("One entry per assignment required");
    }
    public synchronized Stats stats() { checkOpen(); return new Stats(statsNative(nativeEngine)); }
    @Override public synchronized void close() {
        if (!closed) {
            synchronized (NativeEngineAdmission.class) {
                closeNative(nativeEngine); closed = true; NativeEngineAdmission.active = null;
            }
        }
    }
    /** Exact maximum over assignments, including implicit zero regions. */
    public synchronized Number maxTerminal(MTNDDNode node) { return new Number(maxNative(nativeEngine, handle(node))); }
    public synchronized Number evaluateBits(MTNDDNode node, boolean[][] values) {
        Objects.requireNonNull(values, "values");
        if (values.length != fields.length) throw new IllegalArgumentException("One bit array per field required");
        int count = 0;
        for (int f = 0; f < fields.length; ++f) {
            if (values[f] == null || values[f].length != fields[f].width) throw new IllegalArgumentException("Field bit width mismatch");
            count += values[f].length;
        }
        byte[] bits = new byte[count]; int i = 0;
        for (boolean[] field : values) for (boolean bit : field) bits[i++] = (byte)(bit ? 1 : 0);
        return new Number(evaluateBitsNative(nativeEngine, handle(node), bits));
    }
    public synchronized MTNDDNode[] sumAbstractBatch(MTNDDNode[] nodes, int field) { return contractBatch(nodes, null, field); }
    public synchronized MTNDDNode[] multiplySumAbstractBatch(MTNDDNode[] left, MTNDDNode[] right, int field) {
        Objects.requireNonNull(right, "right"); return contractBatch(left, right, field);
    }
    private MTNDDNode[] contractBatch(MTNDDNode[] left, MTNDDNode[] right, int field) {
        checkOpen(); checkField(field); Objects.requireNonNull(left, "left");
        if (right != null && left.length != right.length) throw new IllegalArgumentException("Batch lengths differ");
        long[] a = new long[left.length], b = right == null ? null : new long[left.length];
        for (int i = 0; i < a.length; ++i) { a[i] = handle(left[i]); if (b != null) b[i] = handle(right[i]); }
        MTNDDNode[] result = new MTNDDNode[a.length];
        long[] owned = contractBatchNative(nativeEngine, a, b, field); int i = 0;
        try { for (; i < result.length; ++i) result[i] = new MTNDDNode(this, owned[i]); return result; }
        catch (OutOfMemoryError error) {
            for (int j = 0; j < i; ++j) result[j].close();
            for (; i < owned.length; ++i) releaseNative(nativeEngine, owned[i]); throw error;
        }
    }
    private static native long[] maxNative(long engine, long node);
    private static native long[] evaluateBitsNative(long engine, long node, byte[] bits);
    private static native long[] contractBatchNative(long engine, long[] left, long[] right, int field);
    private static native long openNative(int[] widths, int[] backends, int workers, long memory,
            int cache, int threshold, int chunk, int depth, int flags, int gcInterval, int domain);
    private static native void closeNative(long engine);
    private static native long constantNative(long engine, boolean value);
    private static native long literalNative(long engine, int field, int bit, boolean positive);
    private static native long valueNative(long engine, int field, long value);
    private static native long applyNative(long engine, int op, long a, long b);
    private static native long existNative(long engine, long a, int field);
    private static native long retainNative(long engine, long a);
    private static native void releaseNative(long engine, long a);
    private static native double countNative(long engine, long a);
    private static native long[] evaluateNative(long engine, long a, long[] values);
    private static native long[] batchNative(long engine, int op, long[] a, long[] b);
    private static native long reduceNative(long engine, int op, long[] values, boolean tree);
    private static native long fractionNative(long engine, long numerator, long denominator);
    private static native long doubleNative(long engine, double value);
    private static native long sumNative(long engine, long a, int field);
    private static native long fusedNative(long engine, long a, long b, int field);
    private static native long[] terminalNative(long engine, long a);
    private static native void collectNative(long engine);
    private static native long[] statsNative(long engine);
    private static native long importNative(long engine, double[] values, long[] numerators, long[] denominators);
}
