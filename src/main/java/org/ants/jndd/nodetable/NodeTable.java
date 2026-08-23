package org.ants.jndd.nodetable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import org.ants.jndd.diagram.NDD;
import org.ants.jndd.utils.Rational;

public class NodeTable {
    public static final int TERMINAL_FIELD = Integer.MAX_VALUE;

    private long totalCreated;
    private long currentSize;
    private long nddTableSize;
    private long gcCount;
    private long gcFreedCount;
    private long gcTimeNanos;
    private long thresholdGrowCount;
    private final ArrayList<UniqueTable> nodeTable;

    private int nodeCapacity;
    private int edgeCapacity;
    private int blockCapacity;
    private int nextNodeId;
    private int freeNodeHead;
    private int retiredNodeHead;
    private int edgeTop;
    private int peakEdgeTop;
    private int nextBlockId;
    private int freeBlockHead;
    private int retiredBlockHead;
    private long liveEdgeCount;
    private boolean gcRequested;

    public int[] nodeField;
    public int[] nodeEdgeBlock;
    public int[] nodeEdgeCount;
    int[] nodeNext;
    int[] nodeHash;
    public int[] refCount;
    public int[] edgeTarget;
    public int[] edgeLabel;
    private boolean[] nodeAlive;
    private int[] blockStart;
    private int[] blockNext;

    private int[] nodeTerminalIndex;
    private final boolean doubleTerminals;
    private final boolean intervalTerminals;
    private final boolean nominalRadiusIntervals;
    private final int doubleMantissaBits;
    private final int intervalRadiusMantissaBits;
    private final double doubleZeroCutoff;
    private final double doubleQuantizationStep;
    private Rational[] terminalValues;
    private double[] terminalDoubleValues;
    private double[] terminalLowerValues;
    private double[] terminalUpperValues;
    private long[] terminalRadiusClassBits;
    private boolean[] terminalRadiusNonnegative;
    private boolean[] terminalRadiusMultiplicative;
    private int[] terminalNodeIds;
    private int terminalCount;
    private int[] terminalBuckets;
    private int terminalMask;
    private int terminalThreshold;

    public NodeTable(long nddTableSize) {
        this(nddTableSize, false, 52, 0.0, 0.0);
    }

    public NodeTable(long nddTableSize, boolean doubleTerminals) {
        this(nddTableSize, doubleTerminals, 52, 0.0, 0.0);
    }

    public NodeTable(long nddTableSize, boolean doubleTerminals, int doubleMantissaBits) {
        this(nddTableSize, doubleTerminals, doubleMantissaBits, 0.0, 0.0);
    }

    public NodeTable(long nddTableSize, boolean doubleTerminals, int doubleMantissaBits,
            double doubleZeroCutoff) {
        this(nddTableSize, doubleTerminals, doubleMantissaBits, doubleZeroCutoff, 0.0);
    }

    public NodeTable(long nddTableSize, boolean doubleTerminals, int doubleMantissaBits,
            double doubleZeroCutoff, double doubleQuantizationStep) {
        this(nddTableSize, doubleTerminals, false, doubleMantissaBits,
                doubleZeroCutoff, doubleQuantizationStep);
    }

    public NodeTable(long nddTableSize, boolean doubleTerminals, boolean intervalTerminals,
            int doubleMantissaBits, double doubleZeroCutoff, double doubleQuantizationStep) {
        this(nddTableSize, doubleTerminals, intervalTerminals, doubleMantissaBits,
                doubleZeroCutoff, doubleQuantizationStep, false);
    }

    public NodeTable(long nddTableSize, boolean doubleTerminals, boolean intervalTerminals,
            int doubleMantissaBits, double doubleZeroCutoff, double doubleQuantizationStep,
            boolean nominalRadiusIntervals) {
        this(nddTableSize, doubleTerminals, intervalTerminals, doubleMantissaBits,
                doubleZeroCutoff, doubleQuantizationStep, nominalRadiusIntervals, 3);
    }

    public NodeTable(long nddTableSize, boolean doubleTerminals, boolean intervalTerminals,
            int doubleMantissaBits, double doubleZeroCutoff, double doubleQuantizationStep,
            boolean nominalRadiusIntervals, int intervalRadiusMantissaBits) {
        if (doubleMantissaBits < 0 || doubleMantissaBits > 52) {
            throw new IllegalArgumentException("double mantissa bits must be between 0 and 52");
        }
        if (intervalRadiusMantissaBits < 0 || intervalRadiusMantissaBits > 52) {
            throw new IllegalArgumentException(
                    "interval radius mantissa bits must be between 0 and 52");
        }
        if (!Double.isFinite(doubleZeroCutoff)
                || doubleZeroCutoff < 0.0 || doubleZeroCutoff >= 1.0) {
            throw new IllegalArgumentException("double zero cutoff must be in [0, 1)");
        }
        if (!Double.isFinite(doubleQuantizationStep)
                || doubleQuantizationStep < 0.0 || doubleQuantizationStep > 1.0) {
            throw new IllegalArgumentException("double quantization step must be in [0, 1]");
        }
        if (intervalTerminals && !doubleTerminals) {
            throw new IllegalArgumentException("interval terminals require double storage");
        }
        if (intervalTerminals && doubleZeroCutoff != 0.0) {
            throw new IllegalArgumentException("interval terminals do not support zero cutoff");
        }
        if (nominalRadiusIntervals && !intervalTerminals) {
            throw new IllegalArgumentException(
                    "nominal-radius encoding requires interval terminals");
        }
        this.totalCreated = 0L;
        this.currentSize = 0L;
        this.nddTableSize = nddTableSize;
        this.nodeTable = new ArrayList<>();
        this.doubleTerminals = doubleTerminals;
        this.intervalTerminals = intervalTerminals;
        this.nominalRadiusIntervals = nominalRadiusIntervals;
        this.doubleMantissaBits = doubleMantissaBits;
        this.intervalRadiusMantissaBits = intervalRadiusMantissaBits;
        this.doubleZeroCutoff = doubleZeroCutoff;
        this.doubleQuantizationStep = doubleQuantizationStep;
        this.nextBlockId = 1;

        int initialNodeCap = (int) Math.max(16, Math.min(4096, nddTableSize + 2));
        int initialEdgeCap = Math.max(16, initialNodeCap * 4);
        this.nodeCapacity = initialNodeCap;
        this.edgeCapacity = initialEdgeCap;
        this.blockCapacity = initialNodeCap;
        this.nextNodeId = 0;

        this.nodeField = new int[nodeCapacity];
        this.nodeEdgeBlock = new int[nodeCapacity];
        this.nodeEdgeCount = new int[nodeCapacity];
        this.nodeNext = new int[nodeCapacity];
        this.nodeHash = new int[nodeCapacity];
        this.refCount = new int[nodeCapacity];
        this.edgeTarget = new int[edgeCapacity];
        this.edgeLabel = new int[edgeCapacity];
        this.nodeAlive = new boolean[nodeCapacity];
        this.blockStart = new int[blockCapacity];
        this.blockNext = new int[blockCapacity];
        this.nodeTerminalIndex = new int[nodeCapacity];
        Arrays.fill(nodeTerminalIndex, -1);
        this.terminalValues = doubleTerminals ? null : new Rational[16];
        this.terminalDoubleValues = doubleTerminals ? new double[16] : null;
        this.terminalLowerValues = intervalTerminals ? new double[16] : null;
        this.terminalUpperValues = intervalTerminals ? new double[16] : null;
        this.terminalRadiusClassBits = nominalRadiusIntervals ? new long[16] : null;
        this.terminalRadiusNonnegative = nominalRadiusIntervals ? new boolean[16] : null;
        this.terminalRadiusMultiplicative = nominalRadiusIntervals ? new boolean[16] : null;
        this.terminalNodeIds = new int[16];
        this.terminalBuckets = new int[32];
        this.terminalMask = terminalBuckets.length - 1;
        this.terminalThreshold = (int) (terminalBuckets.length * 0.7);
        Arrays.fill(nodeField, -1);

        int zero = intervalTerminals ? mkInterval(0.0, 0.0, true)
                : doubleTerminals ? mkTerminal(0.0) : mkTerminal(new Rational(0));
        int one = intervalTerminals ? mkInterval(1.0, 1.0, true)
                : doubleTerminals ? mkTerminal(1.0) : mkTerminal(new Rational(1));
        if (zero != 0 || one != 1) {
            throw new IllegalStateException("terminal bootstrap failed");
        }
        fixNDDNodeRefCount(0);
        fixNDDNodeRefCount(1);
    }

    public void declareField() {
        nodeTable.add(new UniqueTable(64));
    }

    public long getCurrentSize() {
        return currentSize;
    }

    public long getTotalCreated() {
        return totalCreated;
    }

    public long getGcCount() {
        return gcCount;
    }

    public long getGcFreedCount() {
        return gcFreedCount;
    }

    public long getGcTimeMillis() {
        return gcTimeNanos / 1_000_000L;
    }

    public long getThresholdGrowCount() {
        return thresholdGrowCount;
    }

    public int getField(int nodeId) {
        return nodeField[nodeId];
    }

    public boolean isTerminal(int nodeId) {
        return nodeId >= 0 && nodeId < nextNodeId && nodeField[nodeId] == TERMINAL_FIELD;
    }

    public Rational getTerminalValue(int nodeId) {
        if (doubleTerminals) {
            throw new IllegalStateException("Rational value unavailable in double-terminal mode");
        }
        int terminalIndex = nodeTerminalIndex[nodeId];
        if (terminalIndex < 0) {
            throw new IllegalArgumentException("node " + nodeId + " is not a terminal");
        }
        return terminalValues[terminalIndex];
    }

    public double getTerminalDouble(int nodeId) {
        int terminalIndex = nodeTerminalIndex[nodeId];
        if (terminalIndex < 0) {
            throw new IllegalArgumentException("node " + nodeId + " is not a terminal");
        }
        return intervalTerminals
                ? nominalRadiusIntervals ? terminalDoubleValues[terminalIndex]
                    : terminalLowerValues[terminalIndex] / 2.0
                        + terminalUpperValues[terminalIndex] / 2.0
                : doubleTerminals ? terminalDoubleValues[terminalIndex]
                : terminalValues[terminalIndex].doubleValue();
    }

    public double getTerminalLower(int nodeId) {
        int terminalIndex = nodeTerminalIndex[nodeId];
        if (terminalIndex < 0) {
            throw new IllegalArgumentException("node " + nodeId + " is not a terminal");
        }
        return intervalTerminals ? terminalLowerValues[terminalIndex]
                : getTerminalDouble(nodeId);
    }

    public double getTerminalUpper(int nodeId) {
        int terminalIndex = nodeTerminalIndex[nodeId];
        if (terminalIndex < 0) {
            throw new IllegalArgumentException("node " + nodeId + " is not a terminal");
        }
        return intervalTerminals ? terminalUpperValues[terminalIndex]
                : getTerminalDouble(nodeId);
    }

    public String getTerminalDisplay(int nodeId) {
        int terminalIndex = nodeTerminalIndex[nodeId];
        if (terminalIndex < 0) {
            throw new IllegalArgumentException("node " + nodeId + " is not a terminal");
        }
        return intervalTerminals
                ? "[" + terminalLowerValues[terminalIndex] + ","
                    + terminalUpperValues[terminalIndex] + "]"
                : doubleTerminals ? Double.toString(terminalDoubleValues[terminalIndex])
                : terminalValues[terminalIndex].toString();
    }

    public boolean usesDoubleTerminals() {
        return doubleTerminals;
    }

    public boolean usesIntervalTerminals() {
        return intervalTerminals;
    }

    public boolean usesNominalRadiusIntervals() {
        return nominalRadiusIntervals;
    }

    public int getTerminalCount() {
        return terminalCount;
    }

    public int getIntervalNominalBucketCount() {
        if (!nominalRadiusIntervals) return 0;
        HashSet<Long> values = new HashSet<>();
        for (int i = 0; i < terminalCount; i++) {
            values.add(Double.doubleToLongBits(terminalDoubleValues[i]));
        }
        return values.size();
    }

    public int getIntervalRadiusClassCount() {
        if (!nominalRadiusIntervals) return 0;
        HashSet<Long> values = new HashSet<>();
        for (int i = 0; i < terminalCount; i++) {
            values.add(terminalRadiusClassBits[i]
                    ^ (terminalRadiusMultiplicative[i] ? Long.MIN_VALUE : 0L));
        }
        return values.size();
    }

    public HashMap<Rational, Integer> getTerminalTable() {
        if (doubleTerminals) {
            throw new IllegalStateException("Rational table unavailable in double-terminal mode");
        }
        HashMap<Rational, Integer> result = new HashMap<>(terminalCount * 2);
        for (int i = 0; i < terminalCount; i++) {
            result.put(terminalValues[i], terminalNodeIds[i]);
        }
        return result;
    }

    public int mkTerminal(Rational value) {
        if (doubleTerminals) return mkTerminal(value.doubleValue());
        int bucket = terminalBucket(value.numerator(), value.denominator());
        int encodedNodeId;
        while ((encodedNodeId = terminalBuckets[bucket]) != 0) {
            int nodeId = encodedNodeId - 1;
            Rational existing = terminalValues[nodeTerminalIndex[nodeId]];
            if (existing.numerator() == value.numerator()
                    && existing.denominator() == value.denominator()) {
                return nodeId;
            }
            bucket = (bucket + 1) & terminalMask;
        }

        int id = allocateNode();
        ensureTerminalCapacity();
        nodeField[id] = TERMINAL_FIELD;
        nodeEdgeBlock[id] = 0;
        nodeEdgeCount[id] = 0;
        nodeHash[id] = value.hashCode();
        refCount[id] = 0;
        nodeAlive[id] = true;
        int terminalIndex = terminalCount++;
        nodeTerminalIndex[id] = terminalIndex;
        terminalValues[terminalIndex] = value;
        terminalNodeIds[terminalIndex] = id;
        terminalBuckets[bucket] = id + 1;
        if (terminalCount >= terminalThreshold) {
            resizeTerminalBuckets();
        }
        totalCreated++;
        currentSize++;
        return id;
    }

    public int mkTerminal(double value) {
        if (!doubleTerminals) return mkTerminal(new Rational(value));
        if (intervalTerminals) {
            return nominalRadiusIntervals
                    ? mkNominalRadiusInterval(value, value, false, true)
                    : mkInterval(value, value, false);
        }
        if (!Double.isFinite(value)) {
            throw new ArithmeticException("non-finite double terminal " + value);
        }
        value = quantizeDouble(value);
        if (Math.abs(value) <= doubleZeroCutoff) value = 0.0;
        if (value == 0.0) value = 0.0; // Canonicalize negative zero.
        long bits = Double.doubleToLongBits(value);
        int bucket = terminalBucket(bits, 0x6a09e667f3bcc909L);
        int encodedNodeId;
        while ((encodedNodeId = terminalBuckets[bucket]) != 0) {
            int nodeId = encodedNodeId - 1;
            double existing = terminalDoubleValues[nodeTerminalIndex[nodeId]];
            if (Double.doubleToLongBits(existing) == bits) return nodeId;
            bucket = (bucket + 1) & terminalMask;
        }

        int id = allocateNode();
        ensureTerminalCapacity();
        nodeField[id] = TERMINAL_FIELD;
        nodeEdgeBlock[id] = 0;
        nodeEdgeCount[id] = 0;
        nodeHash[id] = Long.hashCode(bits);
        refCount[id] = 0;
        nodeAlive[id] = true;
        int terminalIndex = terminalCount++;
        nodeTerminalIndex[id] = terminalIndex;
        terminalDoubleValues[terminalIndex] = value;
        terminalNodeIds[terminalIndex] = id;
        terminalBuckets[bucket] = id + 1;
        if (terminalCount >= terminalThreshold) resizeTerminalBuckets();
        totalCreated++;
        currentSize++;
        return id;
    }

    public int mkInterval(double lower, double upper, boolean exact) {
        if (!intervalTerminals) {
            if (lower != upper) {
                throw new IllegalStateException("interval terminal unavailable in scalar mode");
            }
            return mkTerminal(lower);
        }
        if (!Double.isFinite(lower) || !Double.isFinite(upper) || lower > upper) {
            throw new ArithmeticException("invalid interval terminal [" + lower + "," + upper + "]");
        }
        if (nominalRadiusIntervals) {
            return mkNominalRadiusInterval(lower, upper, exact, false);
        }
        if (!exact) {
            lower = quantizeOutwardLower(lower);
            upper = quantizeOutwardUpper(upper);
        }
        if (lower == 0.0) lower = 0.0;
        if (upper == 0.0) upper = 0.0;
        long lowerBits = Double.doubleToLongBits(lower);
        long upperBits = Double.doubleToLongBits(upper);
        int bucket = terminalBucket(lowerBits, upperBits);
        int encodedNodeId;
        while ((encodedNodeId = terminalBuckets[bucket]) != 0) {
            int nodeId = encodedNodeId - 1;
            int index = nodeTerminalIndex[nodeId];
            if (Double.doubleToLongBits(terminalLowerValues[index]) == lowerBits
                    && Double.doubleToLongBits(terminalUpperValues[index]) == upperBits) {
                return nodeId;
            }
            bucket = (bucket + 1) & terminalMask;
        }

        int id = allocateNode();
        ensureTerminalCapacity();
        nodeField[id] = TERMINAL_FIELD;
        nodeEdgeBlock[id] = 0;
        nodeEdgeCount[id] = 0;
        nodeHash[id] = 31 * Long.hashCode(lowerBits) + Long.hashCode(upperBits);
        refCount[id] = 0;
        nodeAlive[id] = true;
        int terminalIndex = terminalCount++;
        nodeTerminalIndex[id] = terminalIndex;
        terminalLowerValues[terminalIndex] = lower;
        terminalUpperValues[terminalIndex] = upper;
        terminalDoubleValues[terminalIndex] = lower / 2.0 + upper / 2.0;
        terminalNodeIds[terminalIndex] = id;
        terminalBuckets[bucket] = id + 1;
        if (terminalCount >= terminalThreshold) resizeTerminalBuckets();
        totalCreated++;
        currentSize++;
        return id;
    }

    private int mkNominalRadiusInterval(double lower, double upper, boolean exact,
            boolean minimumBucketRadius) {
        boolean algebraicIdentity = lower == upper && (lower == 0.0 || lower == 1.0);
        if (exact || algebraicIdentity) {
            double value = lower == 0.0 ? 0.0 : lower;
            boolean multiplicative = value > 0.0;
            return storeNominalRadiusInterval(value, value, value,
                    Double.doubleToLongBits(multiplicative ? 1.0 : 0.0),
                    value >= 0.0, multiplicative);
        }

        boolean multiplicative = lower > 0.0;
        double center = multiplicative
                ? Math.sqrt(lower) * Math.sqrt(upper)
                : lower / 2.0 + upper / 2.0;
        center = Math.max(lower, Math.min(upper, center));
        double nominal = quantizeDouble(center);
        if (nominal == 0.0) nominal = 0.0;
        boolean nonnegative = lower >= 0.0;
        if (multiplicative && nominal > 0.0) {
            return mkMultiplicativeRadiusInterval(lower, upper, nominal,
                    minimumBucketRadius);
        }
        double requiredRadius = Math.max(
                outwardDistance(nominal, lower), outwardDistance(nominal, upper));
        double baseRadius = nominalRadiusBase(nominal);
        if (minimumBucketRadius) requiredRadius = Math.max(requiredRadius, baseRadius);
        double radius = quantizeRadiusUp(requiredRadius);

        double canonicalLower = radius == 0.0
                ? nominal : directedSubtractDown(nominal, radius);
        double canonicalUpper = radius == 0.0
                ? nominal : directedAddUp(nominal, radius);
        if (nonnegative && canonicalLower < 0.0) canonicalLower = 0.0;
        while (canonicalLower > lower || canonicalUpper < upper) {
            radius = quantizeRadiusUp(Math.nextUp(radius));
            canonicalLower = directedSubtractDown(nominal, radius);
            canonicalUpper = directedAddUp(nominal, radius);
            if (nonnegative && canonicalLower < 0.0) canonicalLower = 0.0;
        }
        if (!Double.isFinite(canonicalLower) || !Double.isFinite(canonicalUpper)) {
            throw new ArithmeticException("nominal-radius interval endpoint overflow");
        }
        return storeNominalRadiusInterval(canonicalLower, canonicalUpper, nominal,
                Double.doubleToLongBits(radius), nonnegative, false);
    }

    private int mkMultiplicativeRadiusInterval(double lower, double upper, double nominal,
            boolean minimumBucketRadius) {
        double factor = Math.max(divideUp(upper, nominal), divideUp(nominal, lower));
        if (minimumBucketRadius) {
            double baseRadius = nominalRadiusBase(nominal);
            if (baseRadius > 0.0 && baseRadius < nominal) {
                double bucketLower = directedSubtractDown(nominal, baseRadius);
                double bucketUpper = directedAddUp(nominal, baseRadius);
                factor = Math.max(factor, Math.max(
                        divideUp(bucketUpper, nominal), divideUp(nominal, bucketLower)));
            }
        }
        factor = quantizeRadiusUp(Math.max(1.0, factor));
        double canonicalLower = divideDown(nominal, factor);
        double canonicalUpper = multiplyUp(nominal, factor);
        while (canonicalLower > lower || canonicalUpper < upper) {
            factor = quantizeRadiusUp(Math.nextUp(factor));
            canonicalLower = divideDown(nominal, factor);
            canonicalUpper = multiplyUp(nominal, factor);
        }
        if (!Double.isFinite(canonicalLower) || !Double.isFinite(canonicalUpper)) {
            throw new ArithmeticException("multiplicative interval endpoint overflow");
        }
        return storeNominalRadiusInterval(canonicalLower, canonicalUpper, nominal,
                Double.doubleToLongBits(factor), true, true);
    }

    private int storeNominalRadiusInterval(double lower, double upper, double nominal,
            long radiusClassBits, boolean nonnegative, boolean multiplicative) {
        long nominalBits = Double.doubleToLongBits(nominal);
        long radiusMetadata = radiusClassBits ^ (nonnegative
                ? 0x9e3779b97f4a7c15L : 0x6a09e667f3bcc909L)
                ^ (multiplicative ? 0xc2b2ae3d27d4eb4fL : 0L);
        int bucket = terminalBucket(nominalBits, radiusMetadata);
        int encodedNodeId;
        while ((encodedNodeId = terminalBuckets[bucket]) != 0) {
            int nodeId = encodedNodeId - 1;
            int index = nodeTerminalIndex[nodeId];
            if (Double.doubleToLongBits(terminalDoubleValues[index]) == nominalBits
                    && terminalRadiusClassBits[index] == radiusClassBits
                    && terminalRadiusNonnegative[index] == nonnegative
                    && terminalRadiusMultiplicative[index] == multiplicative) {
                return nodeId;
            }
            bucket = (bucket + 1) & terminalMask;
        }

        int id = allocateNode();
        ensureTerminalCapacity();
        nodeField[id] = TERMINAL_FIELD;
        nodeEdgeBlock[id] = 0;
        nodeEdgeCount[id] = 0;
        nodeHash[id] = 31 * Long.hashCode(nominalBits) + Long.hashCode(radiusMetadata);
        refCount[id] = 0;
        nodeAlive[id] = true;
        int terminalIndex = terminalCount++;
        nodeTerminalIndex[id] = terminalIndex;
        terminalDoubleValues[terminalIndex] = nominal;
        terminalLowerValues[terminalIndex] = lower;
        terminalUpperValues[terminalIndex] = upper;
        terminalRadiusClassBits[terminalIndex] = radiusClassBits;
        terminalRadiusNonnegative[terminalIndex] = nonnegative;
        terminalRadiusMultiplicative[terminalIndex] = multiplicative;
        terminalNodeIds[terminalIndex] = id;
        terminalBuckets[bucket] = id + 1;
        if (terminalCount >= terminalThreshold) resizeTerminalBuckets();
        totalCreated++;
        currentSize++;
        return id;
    }

    private static double divideDown(double numerator, double denominator) {
        double result = numerator / denominator;
        double residual = Math.fma(-result, denominator, numerator);
        if (residual < 0.0) return Math.nextDown(result);
        return result;
    }

    private static double divideUp(double numerator, double denominator) {
        double result = numerator / denominator;
        double residual = Math.fma(-result, denominator, numerator);
        if (residual > 0.0) return Math.nextUp(result);
        return result;
    }

    private static double multiplyUp(double left, double right) {
        double result = left * right;
        double residual = Math.fma(left, right, -result);
        return residual > 0.0 ? Math.nextUp(result) : result;
    }

    private double quantizeRadiusUp(double radius) {
        if (radius == 0.0 || intervalRadiusMantissaBits == 52) return radius;
        int clearedBits = 52 - intervalRadiusMantissaBits;
        long raw = Double.doubleToRawLongBits(radius);
        long mask = (1L << clearedBits) - 1L;
        long discarded = raw & mask;
        long quantized = raw & ~mask;
        if (discarded != 0L) quantized += 1L << clearedBits;
        double result = Double.longBitsToDouble(quantized);
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("nominal-radius interval overflow");
        }
        return result;
    }

    private double nominalRadiusBase(double nominal) {
        double result = 0.0;
        if (doubleMantissaBits < 52 && nominal != 0.0) {
            double spacing = Math.scalb(Math.ulp(nominal), 52 - doubleMantissaBits);
            result = spacing / 2.0;
        }
        if (doubleQuantizationStep > 0.0) {
            result = Math.max(result, doubleQuantizationStep / 2.0);
        }
        return Double.isFinite(result) ? result : 0.0;
    }

    private static double outwardDistance(double left, double right) {
        double larger = Math.max(left, right);
        double smaller = Math.min(left, right);
        double result = larger - smaller;
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("nominal-radius distance overflow");
        }
        double residual = additionResidual(larger, -smaller, result);
        return residual > 0.0 ? Math.nextUp(result) : result;
    }

    private static double directedSubtractDown(double left, double right) {
        double result = left - right;
        double residual = additionResidual(left, -right, result);
        return residual < 0.0 ? Math.nextDown(result) : result;
    }

    private static double directedAddUp(double left, double right) {
        double result = left + right;
        double residual = additionResidual(left, right, result);
        return residual > 0.0 ? Math.nextUp(result) : result;
    }

    private static double additionResidual(double left, double right, double result) {
        double resultMinusLeft = result - left;
        return (left - (result - resultMinusLeft)) + (right - resultMinusLeft);
    }

    private double quantizeOutwardLower(double value) {
        double result = quantizeMantissaDown(value);
        if (doubleQuantizationStep > 0.0) {
            result = Math.floor(result / doubleQuantizationStep) * doubleQuantizationStep;
        }
        if (!Double.isFinite(result)) throw new ArithmeticException("interval lower overflow");
        return result;
    }

    private double quantizeOutwardUpper(double value) {
        double result = quantizeMantissaUp(value);
        if (doubleQuantizationStep > 0.0) {
            result = Math.ceil(result / doubleQuantizationStep) * doubleQuantizationStep;
        }
        if (!Double.isFinite(result)) throw new ArithmeticException("interval upper overflow");
        return result;
    }

    private double quantizeMantissaDown(double value) {
        if (doubleMantissaBits == 52 || value == 0.0) return value;
        return value > 0.0 ? quantizePositiveMagnitude(value, false)
                : -quantizePositiveMagnitude(-value, true);
    }

    private double quantizeMantissaUp(double value) {
        if (doubleMantissaBits == 52 || value == 0.0) return value;
        return value > 0.0 ? quantizePositiveMagnitude(value, true)
                : -quantizePositiveMagnitude(-value, false);
    }

    private double quantizePositiveMagnitude(double value, boolean upward) {
        int clearedBits = 52 - doubleMantissaBits;
        long raw = Double.doubleToRawLongBits(value);
        long mask = (1L << clearedBits) - 1L;
        long discarded = raw & mask;
        long quantized = raw & ~mask;
        if (upward && discarded != 0L) quantized += 1L << clearedBits;
        return Double.longBitsToDouble(quantized);
    }

    private double quantizeDouble(double value) {
        double quantized = value;
        if (doubleMantissaBits != 52 && value != 0.0) {
            int clearedBits = 52 - doubleMantissaBits;
            long raw = Double.doubleToRawLongBits(value);
            long sign = raw & Long.MIN_VALUE;
            long magnitude = raw & Long.MAX_VALUE;
            long mask = (1L << clearedBits) - 1L;
            magnitude = (magnitude + (1L << (clearedBits - 1))) & ~mask;
            quantized = Double.longBitsToDouble(sign | magnitude);
        }
        if (doubleQuantizationStep > 0.0 && quantized != 0.0) {
            double bucket = Math.rint(quantized / doubleQuantizationStep);
            quantized = bucket * doubleQuantizationStep;
        }
        if (!Double.isFinite(quantized)) {
            throw new ArithmeticException("double terminal overflow after quantization");
        }
        return quantized;
    }

    private int terminalBucket(long numerator, long denominator) {
        long hash = numerator * 0x9e3779b97f4a7c15L
                ^ Long.rotateLeft(denominator * 0xc2b2ae3d27d4eb4fL, 29);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return ((int) hash) & terminalMask;
    }

    private void ensureTerminalCapacity() {
        int capacity = doubleTerminals ? terminalDoubleValues.length : terminalValues.length;
        if (terminalCount < capacity) {
            return;
        }
        int newCapacity = capacity << 1;
        if (doubleTerminals) {
            terminalDoubleValues = Arrays.copyOf(terminalDoubleValues, newCapacity);
            if (intervalTerminals) {
                terminalLowerValues = Arrays.copyOf(terminalLowerValues, newCapacity);
                terminalUpperValues = Arrays.copyOf(terminalUpperValues, newCapacity);
                if (nominalRadiusIntervals) {
                    terminalRadiusClassBits = Arrays.copyOf(
                            terminalRadiusClassBits, newCapacity);
                    terminalRadiusNonnegative = Arrays.copyOf(
                            terminalRadiusNonnegative, newCapacity);
                    terminalRadiusMultiplicative = Arrays.copyOf(
                            terminalRadiusMultiplicative, newCapacity);
                }
            }
        } else {
            terminalValues = Arrays.copyOf(terminalValues, newCapacity);
        }
        terminalNodeIds = Arrays.copyOf(terminalNodeIds, newCapacity);
    }

    private void resizeTerminalBuckets() {
        rebuildTerminalBuckets(terminalBuckets.length << 1);
    }

    private void rebuildTerminalBuckets(int requestedSize) {
        int size = 32;
        while (size < requestedSize) size <<= 1;
        terminalBuckets = new int[size];
        terminalMask = terminalBuckets.length - 1;
        terminalThreshold = (int) (terminalBuckets.length * 0.7);
        for (int i = 0; i < terminalCount; i++) {
            int bucket;
            if (doubleTerminals) {
                if (nominalRadiusIntervals) {
                    long radiusMetadata = terminalRadiusClassBits[i]
                            ^ (terminalRadiusNonnegative[i]
                                ? 0x9e3779b97f4a7c15L : 0x6a09e667f3bcc909L)
                            ^ (terminalRadiusMultiplicative[i]
                                ? 0xc2b2ae3d27d4eb4fL : 0L);
                    bucket = terminalBucket(
                            Double.doubleToLongBits(terminalDoubleValues[i]), radiusMetadata);
                } else if (intervalTerminals) {
                    bucket = terminalBucket(Double.doubleToLongBits(terminalLowerValues[i]),
                            Double.doubleToLongBits(terminalUpperValues[i]));
                } else {
                    bucket = terminalBucket(Double.doubleToLongBits(terminalDoubleValues[i]),
                            0x6a09e667f3bcc909L);
                }
            } else {
                Rational value = terminalValues[i];
                bucket = terminalBucket(value.numerator(), value.denominator());
            }
            while (terminalBuckets[bucket] != 0) {
                bucket = (bucket + 1) & terminalMask;
            }
            terminalBuckets[bucket] = terminalNodeIds[i] + 1;
        }
    }

    public int getEdgeStart(int nodeId) {
        return blockStart[nodeEdgeBlock[nodeId]];
    }

    public int getEdgeCount(int nodeId) {
        return nodeEdgeCount[nodeId];
    }

    public int getEdgeTarget(int edgeIndex) {
        return edgeTarget[edgeIndex];
    }

    public int getEdgeLabel(int edgeIndex) {
        return edgeLabel[edgeIndex];
    }

    public int getEdgeTarget(int nodeId, int offset) {
        return edgeTarget[blockStart[nodeEdgeBlock[nodeId]] + offset];
    }

    public int getEdgeLabel(int nodeId, int offset) {
        return edgeLabel[blockStart[nodeEdgeBlock[nodeId]] + offset];
    }

    public int mk(int field, int[] targets, int[] labels) {
        return mk(field, targets, labels, 0, targets.length);
    }

    public int mk(int field, int[] targets, int[] labels, int offset, int length) {
        if (length == 0) {
            return NDD.getFalseId();
        }
        if (length == 1 && NDD.isUniverseEdgeLabel(field, labels[offset])) {
            NDD.derefLabel(field, labels[offset]);
            return targets[offset];
        }

        UniqueTable table = nodeTable.get(field);
        int hash = computeHash(targets, labels, offset, length);
        int nodeId = table.lookup(hash, targets, labels, offset, length, this);
        if (nodeId != 0) {
            for (int i = 0; i < length; i++) {
                NDD.derefLabel(field, labels[offset + i]);
            }
            return nodeId;
        }

        if (currentSize >= nddTableSize) {
            gcOrGrow();
        }

        int id = allocateNode();
        int blockId = allocateBlock();
        ensureEdgeCapacity(length);
        int start = edgeTop;
        for (int i = 0; i < length; i++) {
            edgeTarget[edgeTop] = targets[offset + i];
            edgeLabel[edgeTop] = labels[offset + i];
            edgeTop++;
            if (edgeTop > peakEdgeTop) peakEdgeTop = edgeTop;
        }

        nodeField[id] = field;
        nodeEdgeBlock[id] = blockId;
        nodeEdgeCount[id] = length;
        nodeHash[id] = hash;
        nodeNext[id] = 0;
        refCount[id] = 0;
        nodeAlive[id] = true;
        nodeTerminalIndex[id] = -1;
        blockStart[blockId] = start;
        liveEdgeCount += length;

        for (int i = 0; i < length; i++) {
            int target = targets[offset + i];
            if (target >= 0 && nodeAlive[target] && refCount[target] != Integer.MAX_VALUE) {
                refCount[target]++;
            }
        }

        table.insert(id, this);
        totalCreated++;
        currentSize++;
        return id;
    }

    private int allocateNode() {
        int nodeId;
        if (freeNodeHead != 0) {
            nodeId = freeNodeHead;
            freeNodeHead = nodeNext[nodeId];
        } else {
            nodeId = nextNodeId++;
            ensureNodeCapacity(nodeId);
        }
        nodeNext[nodeId] = 0;
        return nodeId;
    }

    private int allocateBlock() {
        int blockId;
        if (freeBlockHead != 0) {
            blockId = freeBlockHead;
            freeBlockHead = blockNext[blockId];
        } else {
            blockId = nextBlockId++;
            ensureBlockCapacity(blockId);
        }
        blockNext[blockId] = 0;
        return blockId;
    }

    private void ensureNodeCapacity(int id) {
        if (id < nodeCapacity) {
            return;
        }
        int newCap = nodeCapacity;
        while (newCap <= id) {
            newCap <<= 1;
        }
        nodeField = Arrays.copyOf(nodeField, newCap);
        nodeEdgeBlock = Arrays.copyOf(nodeEdgeBlock, newCap);
        nodeEdgeCount = Arrays.copyOf(nodeEdgeCount, newCap);
        nodeNext = Arrays.copyOf(nodeNext, newCap);
        nodeHash = Arrays.copyOf(nodeHash, newCap);
        refCount = Arrays.copyOf(refCount, newCap);
        nodeAlive = Arrays.copyOf(nodeAlive, newCap);
        nodeTerminalIndex = Arrays.copyOf(nodeTerminalIndex, newCap);
        Arrays.fill(nodeField, nodeCapacity, newCap, -1);
        Arrays.fill(nodeTerminalIndex, nodeCapacity, newCap, -1);
        nodeCapacity = newCap;
    }

    private void ensureBlockCapacity(int blockId) {
        if (blockId < blockCapacity) {
            return;
        }
        int newCap = blockCapacity;
        while (newCap <= blockId) {
            newCap <<= 1;
        }
        blockStart = Arrays.copyOf(blockStart, newCap);
        blockNext = Arrays.copyOf(blockNext, newCap);
        blockCapacity = newCap;
    }

    private void ensureEdgeCapacity(int needed) {
        if (edgeTop + needed <= edgeCapacity) {
            return;
        }
        int newCap = edgeCapacity;
        while (newCap < edgeTop + needed) {
            newCap <<= 1;
        }
        edgeTarget = Arrays.copyOf(edgeTarget, newCap);
        edgeLabel = Arrays.copyOf(edgeLabel, newCap);
        edgeCapacity = newCap;
    }

    private void gcOrGrow() {
        gcRequested = true;
    }

    public void gc() {
        gcRequested = false;
        long gcStartedAt = System.nanoTime();
        long before = currentSize;
        NDD.forEachTemporarilyProtect(this::ref);

        IntQueue queue = new IntQueue((int) Math.max(16, currentSize));
        for (int i = 2; i < nextNodeId; i++) {
            if (nodeAlive[i] && !isTerminal(i) && refCount[i] == 0) {
                queue.add(i);
            }
        }

        while (!queue.isEmpty()) {
            int deadNode = queue.poll();
            int blockId = nodeEdgeBlock[deadNode];
            int start = blockStart[blockId];
            int count = nodeEdgeCount[deadNode];

            for (int i = 0; i < count; i++) {
                int target = edgeTarget[start + i];
                if (!nodeAlive[target] || refCount[target] == Integer.MAX_VALUE) continue;
                if (--refCount[target] == 0 && !isTerminal(target)) {
                    queue.add(target);
                }
            }

            for (int i = 0; i < count; i++) {
                NDD.derefLabel(nodeField[deadNode], edgeLabel[start + i]);
            }

            nodeTable.get(nodeField[deadNode]).remove(deadNode, this);
            NDD.onNodeRetired(deadNode);
            nodeAlive[deadNode] = false;
            nodeHash[deadNode] = 0;
            refCount[deadNode] = 0;
            currentSize--;
            liveEdgeCount -= count;
            nodeNext[deadNode] = retiredNodeHead;
            retiredNodeHead = deadNode;
            blockNext[blockId] = retiredBlockHead;
            retiredBlockHead = blockId;
        }

        collectUnreferencedTerminals();

        NDD.forEachTemporarilyProtect(this::deref);
        gcCount++;
        gcFreedCount += before - currentSize;
        gcTimeNanos += System.nanoTime() - gcStartedAt;
    }

    private void collectUnreferencedTerminals() {
        int write = 0;
        boolean removed = false;
        for (int read = 0; read < terminalCount; read++) {
            int nodeId = terminalNodeIds[read];
            if (nodeAlive[nodeId] && refCount[nodeId] != 0) {
                if (write != read) copyTerminal(read, write);
                terminalNodeIds[write] = nodeId;
                nodeTerminalIndex[nodeId] = write;
                write++;
                continue;
            }

            removed = true;
            NDD.onNodeRetired(nodeId);
            nodeAlive[nodeId] = false;
            nodeHash[nodeId] = 0;
            refCount[nodeId] = 0;
            nodeTerminalIndex[nodeId] = -1;
            currentSize--;
            nodeNext[nodeId] = retiredNodeHead;
            retiredNodeHead = nodeId;
        }
        if (!removed) return;

        clearTerminalRange(write, terminalCount);
        terminalCount = write;
        int bucketSize = 32;
        while (terminalCount >= bucketSize * 0.7) bucketSize <<= 1;
        rebuildTerminalBuckets(bucketSize);
    }

    private void copyTerminal(int source, int destination) {
        terminalNodeIds[destination] = terminalNodeIds[source];
        if (doubleTerminals) {
            terminalDoubleValues[destination] = terminalDoubleValues[source];
            if (intervalTerminals) {
                terminalLowerValues[destination] = terminalLowerValues[source];
                terminalUpperValues[destination] = terminalUpperValues[source];
                if (nominalRadiusIntervals) {
                    terminalRadiusClassBits[destination] = terminalRadiusClassBits[source];
                    terminalRadiusNonnegative[destination] = terminalRadiusNonnegative[source];
                    terminalRadiusMultiplicative[destination]
                            = terminalRadiusMultiplicative[source];
                }
            }
        } else {
            terminalValues[destination] = terminalValues[source];
        }
    }

    private void clearTerminalRange(int from, int to) {
        Arrays.fill(terminalNodeIds, from, to, 0);
        if (!doubleTerminals) Arrays.fill(terminalValues, from, to, null);
    }

    public void compactEdgesIfNeeded() {
        if (gcRequested) {
            gc();
            if (nddTableSize - currentSize <= nddTableSize * 0.1) {
                nddTableSize *= 2;
                thresholdGrowCount++;
            }
            NDD.clearCaches();
            if (edgeTop > 16384 && liveEdgeCount * 2 < edgeTop) compactEdges();
            recycleRetiredSlotsAtSafePoint();
            return;
        }
        if (++compactCheckCounter < COMPACT_CHECK_INTERVAL) {
            recycleRetiredSlotsAtSafePoint();
            return;
        }
        compactCheckCounter = 0;
        if (edgeTop > 16384 && liveEdgeCount * 2 < edgeTop) {
            compactEdges();
        }
        recycleRetiredSlotsAtSafePoint();
    }

    private int compactCheckCounter;
    private static final int COMPACT_CHECK_INTERVAL = 1000;

    private void compactEdges() {
        int newEdgeTop = 0;
        int[] newEdgeTarget = new int[Math.max(16, (int) Math.min(Integer.MAX_VALUE, liveEdgeCount))];
        int[] newEdgeLabel = new int[newEdgeTarget.length];
        for (int nodeId = 2; nodeId < nextNodeId; nodeId++) {
            if (!nodeAlive[nodeId] || isTerminal(nodeId)) {
                continue;
            }
            int count = nodeEdgeCount[nodeId];
            if (newEdgeTop + count > newEdgeTarget.length) {
                int newCapacity = newEdgeTarget.length;
                while (newCapacity < newEdgeTop + count) {
                    newCapacity <<= 1;
                }
                newEdgeTarget = Arrays.copyOf(newEdgeTarget, newCapacity);
                newEdgeLabel = Arrays.copyOf(newEdgeLabel, newCapacity);
            }
            int blockId = nodeEdgeBlock[nodeId];
            int oldStart = blockStart[blockId];
            System.arraycopy(edgeTarget, oldStart, newEdgeTarget, newEdgeTop, count);
            System.arraycopy(edgeLabel, oldStart, newEdgeLabel, newEdgeTop, count);
            blockStart[blockId] = newEdgeTop;
            newEdgeTop += count;
        }
        edgeTarget = newEdgeTarget;
        edgeLabel = newEdgeLabel;
        edgeTop = newEdgeTop;
        edgeCapacity = newEdgeTarget.length;
    }

    public void compactEdgesAtSafePoint() {
        if (liveEdgeCount < edgeTop) {
            compactEdges();
        }
        recycleRetiredSlotsAtSafePoint();
    }

    public long getInternalNodeCount() {
        return currentSize - terminalCount;
    }

    public long getLiveEdgeCount() {
        return liveEdgeCount;
    }

    public int getPhysicalEdgeSlots() {
        return edgeTop;
    }

    public int getPeakPhysicalEdgeSlots() { return peakEdgeTop; }

    public int getNextNodeId() {
        return nextNodeId;
    }

    public int getNodeCapacity() { return nodeCapacity; }

    public int getEdgeCapacity() { return edgeCapacity; }

    public int getBlockCapacity() { return blockCapacity; }

    public int getTerminalCapacity() {
        return doubleTerminals ? terminalDoubleValues.length : terminalValues.length;
    }

    public int getTerminalBucketCapacity() { return terminalBuckets.length; }

    public long getUniqueBucketCapacity() {
        long result = 0L;
        for (UniqueTable table : nodeTable) result += table.buckets.length;
        return result;
    }

    private void recycleRetiredSlotsAtSafePoint() {
        while (retiredNodeHead != 0) {
            int nodeId = retiredNodeHead;
            retiredNodeHead = nodeNext[nodeId];
            nodeNext[nodeId] = freeNodeHead;
            freeNodeHead = nodeId;
        }
        while (retiredBlockHead != 0) {
            int blockId = retiredBlockHead;
            retiredBlockHead = blockNext[blockId];
            blockNext[blockId] = freeBlockHead;
            freeBlockHead = blockId;
        }
    }

    public int ref(int nodeId) {
        if (nodeId >= 0 && nodeAlive[nodeId] && refCount[nodeId] != Integer.MAX_VALUE) {
            refCount[nodeId]++;
        }
        return nodeId;
    }

    public void fixNDDNodeRefCount(int nodeId) {
        refCount[nodeId] = Integer.MAX_VALUE;
    }

    public void deref(int nodeId) {
        if (nodeId >= 0 && nodeAlive[nodeId] && refCount[nodeId] != Integer.MAX_VALUE) {
            refCount[nodeId]--;
        }
    }

    public void showMKCnt() {
        System.out.println("NDD created nodes: " + totalCreated + ", live nodes: " + currentSize);
    }

    private static int computeHash(int[] targets, int[] labels, int offset, int length) {
        int h = 0;
        for (int i = 0; i < length; i++) {
            h = h * 31 + targets[offset + i];
            h = h * 31 + labels[offset + i];
        }
        return h;
    }

    private static class UniqueTable {
        int[] buckets;
        int size;
        int mask;
        int count;
        int threshold;

        UniqueTable(int initCap) {
            size = 1;
            while (size < initCap) {
                size <<= 1;
            }
            buckets = new int[size];
            mask = size - 1;
            threshold = (int) (size * 0.75);
        }

        int lookup(int hash, int[] targets, int[] labels, int offset, int length, NodeTable table) {
            int curr = buckets[hash & mask];
            while (curr != 0) {
                if (table.nodeHash[curr] == hash && arraysMatch(curr, targets, labels, offset, length, table)) {
                    return curr;
                }
                curr = table.nodeNext[curr];
            }
            return 0;
        }

        void insert(int nodeId, NodeTable table) {
            if (count >= threshold) {
                resize(table);
            }
            int pos = table.nodeHash[nodeId] & mask;
            table.nodeNext[nodeId] = buckets[pos];
            buckets[pos] = nodeId;
            count++;
        }

        void remove(int nodeId, NodeTable table) {
            int pos = table.nodeHash[nodeId] & mask;
            int curr = buckets[pos];
            int prev = 0;
            while (curr != 0) {
                if (curr == nodeId) {
                    if (prev == 0) {
                        buckets[pos] = table.nodeNext[curr];
                    } else {
                        table.nodeNext[prev] = table.nodeNext[curr];
                    }
                    count--;
                    return;
                }
                prev = curr;
                curr = table.nodeNext[curr];
            }
        }

        private boolean arraysMatch(int nodeId, int[] targets, int[] labels, int offset, int length, NodeTable table) {
            if (table.nodeEdgeCount[nodeId] != length) {
                return false;
            }
            int start = table.blockStart[table.nodeEdgeBlock[nodeId]];
            for (int i = 0; i < length; i++) {
                if (table.edgeTarget[start + i] != targets[offset + i]) {
                    return false;
                }
                if (table.edgeLabel[start + i] != labels[offset + i]) {
                    return false;
                }
            }
            return true;
        }

        private void resize(NodeTable table) {
            int newSize = size << 1;
            int[] newBuckets = new int[newSize];
            int newMask = newSize - 1;
            for (int i = 0; i < size; i++) {
                int curr = buckets[i];
                while (curr != 0) {
                    int next = table.nodeNext[curr];
                    int pos = table.nodeHash[curr] & newMask;
                    table.nodeNext[curr] = newBuckets[pos];
                    newBuckets[pos] = curr;
                    curr = next;
                }
            }
            size = newSize;
            buckets = newBuckets;
            mask = newMask;
            threshold = (int) (newSize * 0.75);
        }
    }

    private static class IntQueue {
        private int[] data;
        private int head;
        private int tail;

        IntQueue(int initialCapacity) {
            data = new int[Math.max(16, initialCapacity)];
        }

        boolean isEmpty() {
            return head == tail;
        }

        void add(int value) {
            if (tail >= data.length) {
                data = Arrays.copyOf(data, data.length << 1);
            }
            data[tail++] = value;
        }

        int poll() {
            return data[head++];
        }
    }
}
