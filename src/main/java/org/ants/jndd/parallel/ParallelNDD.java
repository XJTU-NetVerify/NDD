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
        synchronized (NativeEngineAdmission.class) {
            if (NativeEngineAdmission.active != null) throw new IllegalStateException("Only one active native engine is supported");
            int flags = (config.nddParallel ? 1 : 0) | (config.labelParallel ? 2 : 0) | (config.batchParallel ? 4 : 0) | (config.reverseLabelOrder ? 8 : 0);
            nativeEngine = openNative(widths, backends, config.workers, config.memoryBytes,
                    config.cacheEntries, config.taskThreshold, config.chunkSize,
                    config.spawnDepth, flags, config.gcInterval);
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
    /** Values are unsigned; fields wider than 64 use zero extension. Use literals
     * to constrain arbitrary bits of a wide field. */
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
    /** Select ordered condition groups inside one worker-pool admission.
     * A selected condition is intersected with budget and excludes all earlier
     * groups. When multipath is false it also excludes earlier conditions in
     * its own group. Outputs are owned roots for conditions[start..length). */
    public synchronized NDDNode[] prioritySelect(NDDNode budget, NDDNode[] conditions,
            int[] groupEnds, int start, boolean multipath) {
        return prioritySelect(budget, conditions, groupEnds, start, multipath, false);
    }
    /** Equivalent selection with per-condition budget intersection first,
     * useful for small header regions inside a large accumulated predicate. */
    public synchronized NDDNode[] prioritySelect(NDDNode budget, NDDNode[] conditions,
            int[] groupEnds, int start, boolean multipath, boolean intersectFirst) {
        checkOpen(); Objects.requireNonNull(conditions); Objects.requireNonNull(groupEnds);
        if (start < 0 || start > conditions.length) throw new IllegalArgumentException("Invalid start");
        int[] ends = groupEnds.clone(); int previous = 0;
        for (int end : ends) {
            if (end <= previous || end > conditions.length) throw new IllegalArgumentException("Invalid priority groups");
            previous = end;
        }
        if (previous != conditions.length) throw new IllegalArgumentException("Groups must cover all conditions");
        long root = handle(budget); long[] input = new long[conditions.length];
        for (int i = 0; i < input.length; ++i) input[i] = handle(conditions[i]);
        NDDNode[] nodes = new NDDNode[conditions.length - start];
        long[] result = prioritySelectNative(nativeEngine, root, input, ends, start,
                (multipath ? 1 : 0) | (intersectFirst ? 2 : 0));
        int i = 0;
        try { for (; i < nodes.length; ++i) nodes[i] = new NDDNode(this, result[i]); return nodes; }
        catch (OutOfMemoryError error) {
            for (int j = 0; j < i; ++j) nodes[j].close();
            for (; i < result.length; ++i) releaseNative(nativeEngine, result[i]);
            throw error;
        }
    }
    public synchronized void collect() { checkOpen(); collectNative(nativeEngine); }
    /** Immutable description of one independent ordered selection. Roots are
     * borrowed and must remain open until prioritySelectBatch returns. */
    public static final class PriorityRequest {
        private final NDDNode budget;
        private final NDDNode[] conditions;
        private final int[] groupEnds;
        private final int start, flags;
        public PriorityRequest(NDDNode budget, NDDNode[] conditions, int[] groupEnds,
                int start, boolean multipath, boolean intersectFirst) {
            this.budget = Objects.requireNonNull(budget);
            this.conditions = Objects.requireNonNull(conditions).clone();
            this.groupEnds = Objects.requireNonNull(groupEnds).clone();
            if (start < 0 || start > conditions.length) throw new IllegalArgumentException("Invalid start");
            int previous = 0;
            for (int end : this.groupEnds) {
                if (end <= previous || end > conditions.length) throw new IllegalArgumentException("Invalid priority groups");
                previous = end;
            }
            if (previous != conditions.length) throw new IllegalArgumentException("Groups must cover all conditions");
            this.start = start;
            this.flags = (multipath ? 1 : 0) | (intersectFirst ? 2 : 0);
        }
    }
    /** Complete independent lists share one native admission. Each returned
     * row owns its selected suffix; inputs and request descriptions are retained. */
    public synchronized NDDNode[][] prioritySelectBatch(PriorityRequest... requests) {
        checkOpen(); Objects.requireNonNull(requests);
        int n = requests.length; long inputCount = 0, groupCount = 0;
        PriorityRequest[] stable = requests.clone();
        for (PriorityRequest r : stable) {
            Objects.requireNonNull(r); inputCount += r.conditions.length; groupCount += r.groupEnds.length;
        }
        if (inputCount > Integer.MAX_VALUE || groupCount > Integer.MAX_VALUE || n == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Batch is too large");
        long[] budgets = new long[n], input = new long[(int)inputCount];
        int[] offsets = new int[n + 1], groups = new int[(int)groupCount], groupOffsets = new int[n + 1];
        int[] starts = new int[n], flags = new int[n];
        NDDNode[][] nodes = new NDDNode[n][];
        int in = 0, g = 0;
        for (int i = 0; i < n; ++i) {
            PriorityRequest r = stable[i]; budgets[i] = handle(r.budget);
            for (NDDNode node : r.conditions) input[in++] = handle(node);
            System.arraycopy(r.groupEnds, 0, groups, g, r.groupEnds.length); g += r.groupEnds.length;
            offsets[i + 1] = in; groupOffsets[i + 1] = g; starts[i] = r.start; flags[i] = r.flags;
            nodes[i] = new NDDNode[r.conditions.length - r.start];
        }
        long[] result = priorityBatchNative(nativeEngine, budgets, input, offsets, groups, groupOffsets, starts, flags);
        int wrapped = 0;
        try {
            for (NDDNode[] row : nodes) for (int j = 0; j < row.length; ++j) {
                row[j] = new NDDNode(this, result[wrapped]); ++wrapped;
            }
            return nodes;
        } catch (OutOfMemoryError failure) {
            for (NDDNode[] row : nodes) for (NDDNode node : row) if (node != null) node.close();
            for (int i = wrapped; i < result.length; ++i) releaseNative(nativeEngine, result[i]);
            throw failure;
        }
    }
    public synchronized Stats stats() { checkOpen(); return new Stats(statsNative(nativeEngine)); }

    /** Owned child roots and field conditions. A condition expresses just its
     * edge label, with a TRUE target, and hides the backend's raw handle. */
    public static final class Snapshot implements AutoCloseable {
        public final int field;
        public final NDDNode[] targets, conditions;
        Snapshot(int field, NDDNode[] targets, NDDNode[] conditions) {
            this.field = field; this.targets = targets; this.conditions = conditions;
        }
        @Override public void close() {
            for (NDDNode node : targets) node.close();
            for (NDDNode node : conditions) node.close();
        }
    }
    public synchronized Snapshot snapshot(NDDNode root) {
        long[] data = snapshotNative(nativeEngine, handle(root));
        try {
            int count = (data.length - 1) / 2;
            NDDNode[] targets = new NDDNode[count], labels = new NDDNode[count];
            for (int i = 0; i < count; ++i) {
                targets[i] = new NDDNode(this, data[i * 2 + 1]);
                labels[i] = new NDDNode(this, data[i * 2 + 2]);
            }
            return new Snapshot((int)data[0], targets, labels);
        } catch (OutOfMemoryError failure) {
            for (int i = 1; i < data.length; ++i) releaseNative(nativeEngine, data[i]);
            throw failure;
        }
    }
    /** Tests graph reachability without changing ownership. Used by adapters
     * that retain borrowed integer handles while external roots remain alive. */
    public synchronized boolean[] reachable(NDDNode[] roots, NDDNode[] candidates) {
        checkOpen();
        long[] rr = new long[roots.length], cc = new long[candidates.length];
        for (int i = 0; i < rr.length; ++i) rr[i] = handle(roots[i]);
        for (int i = 0; i < cc.length; ++i) cc[i] = handle(candidates[i]);
        return reachableNative(nativeEngine, rr, cc);
    }
    public synchronized int minLabelCost(NDDNode condition, int field, boolean costlyValue) {
        checkField(field);
        if (fields[field].backend == Backend.ZDD && !costlyValue)
            throw new UnsupportedOperationException("ZDD cost supports counting present elements only");
        return minLabelCostNative(nativeEngine, handle(condition), field, costlyValue);
    }
    /** Enumerates bounded MSB-first label cubes; -1 denotes a don't-care bit. */
    public synchronized byte[][] labelCubes(NDDNode condition, int field, int limit) {
        checkField(field);
        if (limit < 1 || limit > 65536) throw new IllegalArgumentException("Cube limit must be 1..65536");
        return labelCubesNative(nativeEngine, handle(condition), field, fields[field].width, limit);
    }
    /** Diagnostic 64-bit checksum using shared variable indices and an
     * edge-order independent combination. BDD/BCDD labels use structural hashes;
     * ZDD labels use a weighted family sum independent of variable order.
     * This probabilistic checksum is not an equality proof. */
    public synchronized long fingerprint(NDDNode root) {
        for (Field field : fields) if (field.backend == Backend.BITSET)
            throw new UnsupportedOperationException("Fingerprint supports BDD/BCDD/ZDD fields");
        return fingerprintNative(nativeEngine, handle(root));
    }
    @Override public synchronized void close() {
        if (!closed) {
            synchronized (NativeEngineAdmission.class) {
                closeNative(nativeEngine); closed = true; NativeEngineAdmission.active = null;
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
    private static native long[] prioritySelectNative(long engine, long budget, long[] input, int[] groupEnds, int start, int flags);
    private static native long[] priorityBatchNative(long engine, long[] budgets, long[] input,
            int[] inputOffsets, int[] groupEnds, int[] groupOffsets, int[] starts, int[] flags);
    private static native void collectNative(long engine);
    private static native long[] statsNative(long engine);
    private static native long[] snapshotNative(long engine, long root);
    private static native boolean[] reachableNative(long engine, long[] roots, long[] candidates);
    private static native int minLabelCostNative(long engine, long condition, int field, boolean costlyValue);
    private static native byte[][] labelCubesNative(long engine, long condition, int field, int width, int limit);
    private static native long fingerprintNative(long engine, long root);
}
