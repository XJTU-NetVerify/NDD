package org.ants.mtpndd;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime façade responsible for loading the native library and managing the global MTPNDD state.
 *
 * <p>The {@link MTPNDD} class models individual nodes. User code should only manipulate nodes while
 * relying on this class for global lifecycle management (initialisation, shutdown, configuration).</p>
 */
public final class MTPNDDEngine {

    // ── Timing instrumentation ──
    private static final AtomicLong andCount = new AtomicLong();
    private static final AtomicLong andNanos = new AtomicLong();
    private static final AtomicLong orCount = new AtomicLong();
    private static final AtomicLong orNanos = new AtomicLong();
    private static final AtomicLong notCount = new AtomicLong();
    private static final AtomicLong notNanos = new AtomicLong();
    private static final AtomicLong diffCount = new AtomicLong();
    private static final AtomicLong diffNanos = new AtomicLong();
    private static final AtomicLong existCount = new AtomicLong();
    private static final AtomicLong existNanos = new AtomicLong();
    private static final AtomicLong refCount = new AtomicLong();
    private static final AtomicLong refNanos = new AtomicLong();
    private static final AtomicLong derefCount = new AtomicLong();
    private static final AtomicLong derefNanos = new AtomicLong();
    private static final AtomicLong andBatchCount = new AtomicLong();
    private static final AtomicLong andBatchNanos = new AtomicLong();
    private static final AtomicLong andBatchElements = new AtomicLong();
    private static final AtomicLong orReduceCount = new AtomicLong();
    private static final AtomicLong orReduceNanos = new AtomicLong();
    private static final AtomicLong orReduceElements = new AtomicLong();
    private static final AtomicLong andReduceCount = new AtomicLong();
    private static final AtomicLong andReduceNanos = new AtomicLong();
    private static final AtomicLong andReduceElements = new AtomicLong();
    private static final AtomicLong fromMtbddCount = new AtomicLong();
    private static final AtomicLong fromMtbddNanos = new AtomicLong();
    private static final AtomicLong satCountCount = new AtomicLong();
    private static final AtomicLong satCountNanos = new AtomicLong();

    public static void resetTiming() {
        andCount.set(0); andNanos.set(0);
        orCount.set(0); orNanos.set(0);
        notCount.set(0); notNanos.set(0);
        diffCount.set(0); diffNanos.set(0);
        existCount.set(0); existNanos.set(0);
        refCount.set(0); refNanos.set(0);
        derefCount.set(0); derefNanos.set(0);
        andBatchCount.set(0); andBatchNanos.set(0); andBatchElements.set(0);
        orReduceCount.set(0); orReduceNanos.set(0); orReduceElements.set(0);
        andReduceCount.set(0); andReduceNanos.set(0); andReduceElements.set(0);
        fromMtbddCount.set(0); fromMtbddNanos.set(0);
        satCountCount.set(0); satCountNanos.set(0);
    }

    public static void printTimingReport() {
        long totalNanos = andNanos.get() + orNanos.get() + notNanos.get() + diffNanos.get()
                + existNanos.get() + refNanos.get() + derefNanos.get()
                + andBatchNanos.get() + orReduceNanos.get() + andReduceNanos.get()
                + fromMtbddNanos.get() + satCountNanos.get();
        System.err.println("=== MTPNDD Timing Report ===");
        printLine("and", andCount.get(), andNanos.get());
        printLine("or", orCount.get(), orNanos.get());
        printLine("not", notCount.get(), notNanos.get());
        printLine("diff", diffCount.get(), diffNanos.get());
        printLine("exist", existCount.get(), existNanos.get());
        printLine("ref", refCount.get(), refNanos.get());
        printLine("deref", derefCount.get(), derefNanos.get());
        printLine("andBatch", andBatchCount.get(), andBatchNanos.get(),
                  "elements=" + andBatchElements.get());
        printLine("orReduce", orReduceCount.get(), orReduceNanos.get(),
                  "elements=" + orReduceElements.get());
        printLine("andReduce", andReduceCount.get(), andReduceNanos.get(),
                  "elements=" + andReduceElements.get());
        printLine("fromMtbdd", fromMtbddCount.get(), fromMtbddNanos.get());
        printLine("satCount", satCountCount.get(), satCountNanos.get());
        System.err.printf("[MTPNDD TOTAL] %.6fs%n", totalNanos / 1e9);
    }

    private static void printLine(String name, long count, long nanos) {
        System.err.printf("[MTPNDD %-10s] calls=%-10d time=%.6fs%n", name, count, nanos / 1e9);
    }

    private static void printLine(String name, long count, long nanos, String extra) {
        System.err.printf("[MTPNDD %-10s] calls=%-10d time=%.6fs %s%n", name, count, nanos / 1e9, extra);
    }
    private static long generation;
    static synchronized long currentGeneration() {
        if (!isInitializedNative()) throw new IllegalStateException("MTNDD engine is not initialized");
        return generation;
    }
    private static long checkedPointer(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        if (!isInitializedNative() || node.generation != generation)
            throw new IllegalStateException("Node belongs to a closed or previous MTNDD engine");
        return node.nativePtr;
    }
    private static final String LIBRARY_PROPERTY = "org.ants.mtpndd.library.path";

    static {
        loadNativeLibrary();
    }

    private MTPNDDEngine() {}

    private static synchronized void loadNativeLibrary() {
        try {
            String explicitPath = System.getProperty(LIBRARY_PROPERTY);
            if (explicitPath != null && !explicitPath.isEmpty()) {
                System.load(explicitPath);
            } else {
                System.loadLibrary("mtpnddjni");
            }
        } catch (UnsatisfiedLinkError error) {
            throw new MTPNDDException("Unable to load libmtpnddjni. "
                    + "Set -D" + LIBRARY_PROPERTY + "=<absolute path> if the library "
                    + "is not on java.library.path.", error);
        }
    }

    public static synchronized void init(MTPNDDConfig config) {
        Objects.requireNonNull(config, "config");
        if (isInitializedNative()) throw new IllegalStateException("MTNDD engine already initialized");
        if (generation == Long.MAX_VALUE) throw new IllegalStateException("Engine generation exhausted");
        double quickGrowth = config.quickGrowthThreshold();
        initNative(config.workers(),
                config.laceDequeSize(),
                config.bddNodeTableSize(),
                config.mtpnddNodeTableSize(),
                config.operationCacheSize(),
                Double.isNaN(quickGrowth) ? -1.0d : quickGrowth,
                config.edgeBucketCount(),
                config.nodetableBucketCount(),
                config.nodeSlabCapacity(),
                config.edgeEntrySlabCapacity(),
                config.nodetableEntrySlabCapacity(),
                config.edgeMapSlabCapacity());
        ++generation;
    }

    public static synchronized void shutdown() {
        quitNative();
    }

    public static synchronized boolean isInitialized() {
        return isInitializedNative();
    }

    public static synchronized int declareField(int bitWidth) {
        return declareFieldNative(bitWidth);
    }

    public static synchronized void generateFields() {
        generateFieldsNative();
    }

    public static synchronized MTPNDDFieldInfo getFieldInfo(int fieldId) {
        return getFieldInfoNative(fieldId);
    }

    static synchronized long getBddVar(int fieldId, int index) {
        return getBddVarNative(fieldId, index);
    }

    static synchronized long getBddNotVar(int fieldId, int index) {
        return getBddNotVarNative(fieldId, index);
    }

    static synchronized long bddTrue() {
        return bddTrueNative();
    }

    static synchronized long bddFalse() {
        return bddFalseNative();
    }

    static synchronized long bddRef(long handle) {
        return bddRefNative(handle);
    }

    static synchronized void bddDeref(long handle) {
        bddDerefNative(handle);
    }

    static synchronized long bddAnd(long left, long right) {
        return bddAndNative(left, right);
    }

    static synchronized long bddOr(long left, long right) {
        return bddOrNative(left, right);
    }

    static synchronized long bddNot(long value) {
        return bddNotNative(value);
    }

    static synchronized MTPNDD fromMtbdd(long handle) {
        long t = System.nanoTime();
        MTPNDD result = wrap(fromMtbddNative(handle));
        fromMtbddNanos.addAndGet(System.nanoTime() - t);
        fromMtbddCount.incrementAndGet();
        return result;
    }

    static synchronized MTPNDD getVar(int fieldId, int index) {
        return wrap(getVarNative(fieldId, index));
    }

    static synchronized MTPNDD getNotVar(int fieldId, int index) {
        return wrap(getNotVarNative(fieldId, index));
    }

    static synchronized int getFieldId(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return getFieldIdNative(checkedPointer(node));
    }

    static synchronized MTPNDDEdges getEdges(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return MTPNDDEdges.fromPairs(getEdgesNative(checkedPointer(node)));
    }

    static synchronized void ref(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        long t = System.nanoTime();
        refNative(checkedPointer(node));
        refNanos.addAndGet(System.nanoTime() - t);
        refCount.incrementAndGet();
    }

    static synchronized void deref(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        long t = System.nanoTime();
        derefNative(checkedPointer(node));
        derefNanos.addAndGet(System.nanoTime() - t);
        derefCount.incrementAndGet();
    }

    static synchronized MTPNDD and(MTPNDD left, MTPNDD right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        long t = System.nanoTime();
        MTPNDD result = wrap(andNative(checkedPointer(left), checkedPointer(right)));
        andNanos.addAndGet(System.nanoTime() - t);
        andCount.incrementAndGet();
        return result;
    }

    static synchronized MTPNDD or(MTPNDD left, MTPNDD right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        long t = System.nanoTime();
        MTPNDD result = wrap(orNative(checkedPointer(left), checkedPointer(right)));
        orNanos.addAndGet(System.nanoTime() - t);
        orCount.incrementAndGet();
        return result;
    }

    static synchronized MTPNDD not(MTPNDD value) {
        Objects.requireNonNull(value, "value");
        long t = System.nanoTime();
        MTPNDD result = wrap(notNative(checkedPointer(value)));
        notNanos.addAndGet(System.nanoTime() - t);
        notCount.incrementAndGet();
        return result;
    }

    static synchronized MTPNDD diff(MTPNDD left, MTPNDD right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        long t = System.nanoTime();
        MTPNDD result = wrap(diffNative(checkedPointer(left), checkedPointer(right)));
        diffNanos.addAndGet(System.nanoTime() - t);
        diffCount.incrementAndGet();
        return result;
    }

    static synchronized MTPNDD exist(MTPNDD value, int fieldId) {
        Objects.requireNonNull(value, "value");
        long t = System.nanoTime();
        MTPNDD result = wrap(existNative(checkedPointer(value), fieldId));
        existNanos.addAndGet(System.nanoTime() - t);
        existCount.incrementAndGet();
        return result;
    }

    static synchronized double satCount(MTPNDD value) {
        Objects.requireNonNull(value, "value");
        long t = System.nanoTime();
        double result = satCountNative(checkedPointer(value));
        satCountNanos.addAndGet(System.nanoTime() - t);
        satCountCount.incrementAndGet();
        return result;
    }

    static synchronized int minZeros(MTPNDD value) {
        Objects.requireNonNull(value, "value");
        return minZerosNative(checkedPointer(value));
    }

    // ---- MT (fraction / double leaf) operations --------------------------

    public static synchronized MTPNDD makeFraction(int numer, int denom) {
        return wrap(makeFractionNative(numer, denom));
    }

    public static synchronized MTPNDD makeDouble(double value) {
        return wrap(makeDoubleNative(value));
    }

    public static synchronized boolean isLeaf(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return isLeafNative(checkedPointer(node));
    }

    public static synchronized boolean isFractionLeaf(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return isFractionLeafNative(checkedPointer(node));
    }

    public static synchronized boolean isDoubleLeaf(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return isDoubleLeafNative(checkedPointer(node));
    }

    public static synchronized int getNumer(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return getNumerNative(checkedPointer(node));
    }

    public static synchronized int getDenom(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return getDenomNative(checkedPointer(node));
    }

    public static synchronized double getDoubleLeaf(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return getDoubleLeafNative(checkedPointer(node));
    }

    public static synchronized MTPNDD plus(MTPNDD left, MTPNDD right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return wrap(plusNative(checkedPointer(left), checkedPointer(right)));
    }

    public static synchronized MTPNDD minus(MTPNDD left, MTPNDD right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return wrap(minusNative(checkedPointer(left), checkedPointer(right)));
    }

    public static synchronized MTPNDD times(MTPNDD left, MTPNDD right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return wrap(timesNative(checkedPointer(left), checkedPointer(right)));
    }

    public static synchronized MTPNDD divide(MTPNDD left, MTPNDD right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return wrap(divideNative(checkedPointer(left), checkedPointer(right)));
    }

    public static synchronized long leafCount(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return leafCountNative(checkedPointer(node));
    }

    public static synchronized MTPNDD abstractPlus(MTPNDD node, int fieldId) {
        Objects.requireNonNull(node, "node");
        return wrap(abstractPlusNative(checkedPointer(node), fieldId));
    }

    public static synchronized boolean abstractPlusValidate(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return abstractPlusValidateNative(checkedPointer(node));
    }

    public static synchronized long leafGc() {
        return leafGcNative();
    }

    static synchronized MTPNDD getTrue() {
        return wrap(terminalTrueNative());
    }

    static synchronized MTPNDD getFalse() {
        return wrap(terminalFalseNative());
    }

    static synchronized boolean isTrue(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return isTrueNative(checkedPointer(node));
    }

    static synchronized boolean isFalse(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return isFalseNative(checkedPointer(node));
    }

    static synchronized boolean isTerminal(MTPNDD node) {
        Objects.requireNonNull(node, "node");
        return isTerminalNative(checkedPointer(node));
    }

    public static synchronized boolean bddIsTrue(long handle) {
        return bddIsTrueNative(handle);
    }

    public static synchronized boolean bddIsFalse(long handle) {
        return bddIsFalseNative(handle);
    }

    public static synchronized int bddVar(long handle) {
        return bddVarNative(handle);
    }

    public static synchronized long bddLow(long handle) {
        return bddLowNative(handle);
    }

    public static synchronized long bddHigh(long handle) {
        return bddHighNative(handle);
    }

    static synchronized MTPNDD[] andBatch(MTPNDD[] lefts, MTPNDD[] rights) {
        if (lefts.length != rights.length) {
            throw new IllegalArgumentException("andBatch: lefts and rights must have same length");
        }
        if (lefts.length == 0) {
            return new MTPNDD[0];
        }
        long[] leftPtrs = new long[lefts.length];
        long[] rightPtrs = new long[rights.length];
        for (int i = 0; i < lefts.length; i++) {
            Objects.requireNonNull(lefts[i], "lefts[" + i + "]");
            Objects.requireNonNull(rights[i], "rights[" + i + "]");
            leftPtrs[i] = checkedPointer(lefts[i]);
            rightPtrs[i] = checkedPointer(rights[i]);
        }
        long t = System.nanoTime();
        long[] resultPtrs = andBatchNative(leftPtrs, rightPtrs);
        andBatchNanos.addAndGet(System.nanoTime() - t);
        andBatchCount.incrementAndGet();
        andBatchElements.addAndGet(lefts.length);
        MTPNDD[] results = new MTPNDD[resultPtrs.length];
        for (int i = 0; i < resultPtrs.length; i++) {
            results[i] = wrap(resultPtrs[i]);
        }
        return results;
    }

    static synchronized MTPNDD orReduce(MTPNDD[] values) {
        if (values.length == 0) {
            return getFalse();
        }
        long[] ptrs = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            Objects.requireNonNull(values[i], "values[" + i + "]");
            ptrs[i] = checkedPointer(values[i]);
        }
        long t = System.nanoTime();
        MTPNDD result = wrap(orReduceNative(ptrs));
        orReduceNanos.addAndGet(System.nanoTime() - t);
        orReduceCount.incrementAndGet();
        orReduceElements.addAndGet(values.length);
        return result;
    }

    static synchronized MTPNDD andReduce(MTPNDD[] values) {
        if (values.length == 0) {
            return getTrue();
        }
        long[] ptrs = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            Objects.requireNonNull(values[i], "values[" + i + "]");
            ptrs[i] = checkedPointer(values[i]);
        }
        long t = System.nanoTime();
        MTPNDD result = wrap(andReduceNative(ptrs));
        andReduceNanos.addAndGet(System.nanoTime() - t);
        andReduceCount.incrementAndGet();
        andReduceElements.addAndGet(values.length);
        return result;
    }

    public static synchronized MTPNDDStats stats() {
        return getStatsNative();
    }

    private static MTPNDD wrap(long nativePtr) {
        return new MTPNDD(nativePtr);
    }

    private static native void initNative(int workers,
                                          long laceDequeSize,
                                          long bddNodeTableSize,
                                          long mtpnddNodeTableSize,
                                          long operationCacheSize,
                                          double quickGrowthThreshold,
                                          long edgeBucketCount,
                                          long nodetableBucketCount,
                                          long nodeSlabCapacity,
                                          long edgeEntrySlabCapacity,
                                          long nodetableEntrySlabCapacity,
                                          long edgeMapSlabCapacity);

    private static native void quitNative();

    private static native boolean isInitializedNative();

    private static native int declareFieldNative(int bitWidth);
    private static native void generateFieldsNative();

    private static native MTPNDDFieldInfo getFieldInfoNative(int fieldId);

    private static native long getVarNative(int fieldId, int index);

    private static native long getNotVarNative(int fieldId, int index);
    private static native int getFieldIdNative(long handle);
    private static native long[] getEdgesNative(long handle);

    private static native void refNative(long handle);

    private static native void derefNative(long handle);

    private static native long andNative(long left, long right);

    private static native long orNative(long left, long right);

    private static native long notNative(long handle);

    private static native long diffNative(long left, long right);

    private static native long existNative(long handle, int fieldId);

    private static native double satCountNative(long handle);
    private static native int minZerosNative(long handle);

    private static native long terminalTrueNative();

    private static native long terminalFalseNative();

    private static native boolean isTrueNative(long handle);

    private static native boolean isFalseNative(long handle);

    private static native boolean isTerminalNative(long handle);

    private static native long getBddVarNative(int fieldId, int index);
    private static native long getBddNotVarNative(int fieldId, int index);
    private static native long bddTrueNative();
    private static native long bddFalseNative();
    private static native long bddRefNative(long handle);
    private static native void bddDerefNative(long handle);
    private static native long bddAndNative(long left, long right);
    private static native long bddOrNative(long left, long right);
    private static native long bddNotNative(long value);
    private static native long fromMtbddNative(long handle);
    private static native boolean bddIsTrueNative(long handle);
    private static native boolean bddIsFalseNative(long handle);
    private static native int bddVarNative(long handle);
    private static native long bddLowNative(long handle);
    private static native long bddHighNative(long handle);

    private static native MTPNDDStats getStatsNative();

    private static native long[] andBatchNative(long[] lefts, long[] rights);
    private static native long orReduceNative(long[] values);
    private static native long andReduceNative(long[] values);

    // ---- MT (fraction / double) JNI ----
    private static native long makeFractionNative(int numer, int denom);
    private static native long makeDoubleNative(double value);
    private static native boolean isLeafNative(long handle);
    private static native boolean isFractionLeafNative(long handle);
    private static native boolean isDoubleLeafNative(long handle);
    private static native int getNumerNative(long handle);
    private static native int getDenomNative(long handle);
    private static native double getDoubleLeafNative(long handle);
    private static native long plusNative(long left, long right);
    private static native long minusNative(long left, long right);
    private static native long timesNative(long left, long right);
    private static native long divideNative(long left, long right);
    private static native long leafCountNative(long handle);
    private static native long abstractPlusNative(long handle, int fieldId);
    private static native boolean abstractPlusValidateNative(long handle);
    private static native long leafGcNative();
}
