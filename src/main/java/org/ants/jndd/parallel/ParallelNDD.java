package org.ants.jndd.parallel;

import java.util.Objects;

/** Boolean NDD with native storage and Lace parallelism; independent of the
 * legacy static NDD API. Field bits are numbered from most to least significant.
 * One native engine may be active at a time. Public calls serialize admission;
 * applyBatch and tree reductions expose application-level parallel work. */
public final class ParallelNDD implements AutoCloseable {
    public enum Backend { BDD, BCDD, ZDD, BITSET }
    public enum Operation { AND, OR, DIFF, XOR, IMP, EQUIV, NAND, NOR }
    public enum Reduction { LEFT_FOLD, BALANCED_TREE }

    public static final class Field {
        public final int width;
        public final Backend backend;
        public Field(int width, Backend backend) {
            this.backend = Objects.requireNonNull(backend, "backend");
            if (width < 1 || width > 64 || (backend == Backend.BITSET && width > 7))
                throw new IllegalArgumentException("Width must be 1..64 (BITSET: 1..7)");
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

    private static ParallelNDD active;
    static {
        String path = System.getProperty("pndd.library");
        if (path == null) System.loadLibrary("pnddjni");
        else System.load(path);
    }
    private final Field[] fields;
    private final long nativeEngine;
    private volatile boolean closed;

    public ParallelNDD(ParallelNDDConfig config, Field... fields) {
        Objects.requireNonNull(config, "config");
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
        synchronized (ParallelNDD.class) {
            if (active != null) throw new IllegalStateException("Only one active native engine is supported");
            int flags = (config.nddParallel ? 1 : 0) | (config.labelParallel ? 2 : 0) | (config.batchParallel ? 4 : 0);
            nativeEngine = openNative(widths, backends, config.workers, config.memoryBytes,
                    config.cacheEntries, config.taskThreshold, config.chunkSize,
                    config.spawnDepth, flags, config.gcInterval);
            active = this;
        }
    }

    public int fieldCount() { return fields.length; }
    public Field field(int index) { checkField(index); return fields[index]; }
    public boolean isClosed() { return closed; }
    private void checkOpen() { if (closed) throw new IllegalStateException("Native engine is closed"); }
    private void checkField(int field) {
        if (field < 0 || field >= fields.length) throw new IllegalArgumentException("Invalid field index");
    }
    private long handle(NDDNode node) {
        checkOpen(); Objects.requireNonNull(node, "node");
        if (node.engine != this) throw new IllegalArgumentException("Node belongs to another engine");
        if (node.closed) throw new IllegalStateException("Node is closed");
        return node.handle;
    }
    private NDDNode wrap(long handle) {
        try { return new NDDNode(this, handle); }
        catch (OutOfMemoryError error) { releaseNative(nativeEngine, handle); throw error; }
    }

    public synchronized NDDNode constant(boolean value) { checkOpen(); return wrap(constantNative(nativeEngine, value)); }
    public synchronized NDDNode literal(int field, int bit, boolean positive) {
        checkOpen(); checkField(field);
        if (bit < 0 || bit >= fields[field].width) throw new IllegalArgumentException("Invalid bit index");
        return wrap(literalNative(nativeEngine, field, bit, positive));
    }
    /** Values are unsigned; all long bit patterns are valid for width 64. */
    public synchronized NDDNode value(int field, long value) {
        checkOpen(); checkField(field);
        if (fields[field].width < 64 && (value < 0 || (value >>> fields[field].width) != 0))
            throw new IllegalArgumentException("Value outside field domain");
        return wrap(valueNative(nativeEngine, field, value));
    }
    public synchronized NDDNode apply(Operation operation, NDDNode a, NDDNode b) {
        Objects.requireNonNull(operation, "operation");
        return wrap(applyNative(nativeEngine, operation.ordinal(), handle(a), handle(b)));
    }
    public NDDNode and(NDDNode a, NDDNode b) { return apply(Operation.AND, a, b); }
    public NDDNode or(NDDNode a, NDDNode b) { return apply(Operation.OR, a, b); }
    public NDDNode diff(NDDNode a, NDDNode b) { return apply(Operation.DIFF, a, b); }
    public synchronized NDDNode not(NDDNode a) { return wrap(notNative(nativeEngine, handle(a))); }
    public synchronized NDDNode exist(NDDNode a, int field) {
        checkField(field); return wrap(existNative(nativeEngine, handle(a), field));
    }
    public synchronized double satCount(NDDNode a) { return countNative(nativeEngine, handle(a)); }
    public synchronized boolean evaluate(NDDNode a, long... values) {
        Objects.requireNonNull(values, "values");
        if (values.length != fields.length) throw new IllegalArgumentException("One value per field required");
        return evaluateNative(nativeEngine, handle(a), values);
    }
    public synchronized NDDNode retain(NDDNode a) { return wrap(retainNative(nativeEngine, handle(a))); }
    synchronized void release(NDDNode a) {
        if (!a.closed) {
            if (!closed) releaseNative(nativeEngine, a.handle);
            a.closed = true;
        }
    }
    public synchronized NDDNode[] applyBatch(Operation operation, NDDNode[] a, NDDNode[] b) {
        checkOpen(); Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(a, "left"); Objects.requireNonNull(b, "right");
        if (a.length != b.length) throw new IllegalArgumentException("Batch lengths differ");
        long[] left = new long[a.length], right = new long[a.length];
        for (int i = 0; i < a.length; ++i) { left[i] = handle(a[i]); right[i] = handle(b[i]); }
        NDDNode[] nodes = new NDDNode[a.length];
        long[] result = batchNative(nativeEngine, operation.ordinal(), left, right);
        int i = 0;
        try {
            for (; i < nodes.length; ++i) nodes[i] = new NDDNode(this, result[i]);
            return nodes;
        } catch (OutOfMemoryError error) {
            for (int j = 0; j < i; ++j) nodes[j].close();
            for (; i < result.length; ++i) releaseNative(nativeEngine, result[i]);
            throw error;
        }
    }
    public synchronized NDDNode reduce(Operation operation, Reduction order, NDDNode... nodes) {
        checkOpen(); Objects.requireNonNull(order, "order"); Objects.requireNonNull(nodes, "nodes");
        if (operation != Operation.AND && operation != Operation.OR)
            throw new IllegalArgumentException("Reduction supports AND and OR");
        long[] values = new long[nodes.length];
        for (int i = 0; i < nodes.length; ++i) values[i] = handle(nodes[i]);
        return wrap(reduceNative(nativeEngine, operation.ordinal(), values, order == Reduction.BALANCED_TREE));
    }
    public synchronized void collect() { checkOpen(); collectNative(nativeEngine); }
    public synchronized Stats stats() { checkOpen(); return new Stats(statsNative(nativeEngine)); }
    @Override public synchronized void close() {
        if (!closed) {
            synchronized (ParallelNDD.class) {
                closeNative(nativeEngine); closed = true; active = null;
            }
        }
    }

    private static native long openNative(int[] widths, int[] backends, int workers, long memory,
            int cache, int threshold, int chunk, int depth, int flags, int gcInterval);
    private static native void closeNative(long engine);
    private static native long constantNative(long engine, boolean value);
    private static native long literalNative(long engine, int field, int bit, boolean positive);
    private static native long valueNative(long engine, int field, long value);
    private static native long applyNative(long engine, int op, long a, long b);
    private static native long notNative(long engine, long a);
    private static native long existNative(long engine, long a, int field);
    private static native long retainNative(long engine, long a);
    private static native void releaseNative(long engine, long a);
    private static native double countNative(long engine, long a);
    private static native boolean evaluateNative(long engine, long a, long[] values);
    private static native long[] batchNative(long engine, int op, long[] a, long[] b);
    private static native long reduceNative(long engine, int op, long[] values, boolean tree);
    private static native void collectNative(long engine);
    private static native long[] statsNative(long engine);
}
