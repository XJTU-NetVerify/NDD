package org.ants.jndd.diagram;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.IntConsumer;

import javafx.util.Pair;
import jdd.bdd.BDD;
import jdd.zdd.ZDD;
import org.ants.jndd.bdd.ComplementedBDD;
import org.ants.jndd.nodetable.NodeTable;
import org.ants.jndd.utils.DecomposeBDD;
import org.ants.jndd.utils.Rational;

public class NDD {
    public enum LabelMode {
        BDD,
        COMPLEMENTED_BDD,
        ZDD,
        BITSET
    }

    public enum TerminalMode {
        RATIONAL,
        DOUBLE,
        INTERVAL
    }

    public enum IntervalRoundingMode {
        /** Expand every arithmetic endpoint by one floating-point neighbor. */
        COMPACT,
        /** Expand only toward a nonzero IEEE-754 operation residual. */
        TIGHT
    }

    public enum IntervalEncoding {
        /** Canonicalize independently quantized lower and upper endpoints. */
        ENDPOINTS,
        /** Canonicalize a nominal mantissa bucket and an outward radius class. */
        NOMINAL_RADIUS
    }

    public static final class PruneResult {
        public final NDD diagram;
        public final double maximumPrunedValue;
        public final int prunedTerminalCount;

        private PruneResult(NDD diagram, double maximumPrunedValue,
                int prunedTerminalCount) {
            this.diagram = diagram;
            this.maximumPrunedValue = maximumPrunedValue;
            this.prunedTerminalCount = prunedTerminalCount;
        }
    }

    private static int CACHE_SIZE = 10000;
    private static final int INITIAL_STACK_SIZE = 100000;
    private static final int FALSE_ID = 0;
    private static final int TRUE_ID = 1;

    private static NodeTable nodeTable;
    protected static BDD bddEngine;
    private static ComplementedBDD bcddEngine;
    private static ZDD zddEngine;
    private static LabelMode labelMode = LabelMode.BDD;
    private static LabelDecisionDiagramBackend labelBackend;
    private static BackendContext[] backendContexts;
    private static ArrayList<LabelMode> pendingFieldModes;
    private static ArrayList<LabelDecisionDiagramBackend> fieldBackends;
    private static LabelDecisionDiagramBackend[] fieldBackendArray;
    private static LabelMode[] fieldModeArray;
    private static boolean mixedLabelModes;
    private static int labelTableSize;
    private static int labelCacheSize;
    private static int[] backendTableSizes;
    private static int[] backendCacheSizes;
    protected static int fieldNum;
    private static boolean fieldsGenerated;
    private static ArrayList<Integer> pendingFieldBitNums;
    private static ArrayList<Integer> maxVariablePerField;
    private static ArrayList<Double> satCountDiv;
    private static ArrayList<int[]> bddVarsPerField;
    private static ArrayList<int[]> bddNotVarsPerField;
    private static ArrayList<int[]> nddVarsPerField;
    private static ArrayList<int[]> nddNotVarsPerField;
    private static ArrayList<Integer> fieldUniverseLabels;
    private static IntHashSet temporarilyProtect;
    private static IntOperationCache notCache;
    private static IntOperationCache andCache;
    private static IntOperationCache orCache;
    private static IntOperationCache addCache;
    private static IntOperationCache subCache;
    private static IntOperationCache mulCache;
    private static IntOperationCache divCache;
    private static IntOperationCache diffCache;
    private static IntOperationCache sumAbstractCache;
    private static IntOperationCache multiplySumAbstractCache;
    private static NaryContractionStats lastNaryContractionStats;
    private static int[] stackTargets;
    private static int[] stackLabels;
    private static int stackTop;
    private static int[] residualLabels;
    private static int residualTop;
    private static int[] assignmentTargets;
    private static int assignmentTop;
    private static NDD[] wrappers;
    private static Runnable externalCacheCleaner = () -> {};
    private static double eps = 0.000001;
    private static TerminalMode terminalMode = TerminalMode.RATIONAL;
    private static IntervalRoundingMode intervalRoundingMode = IntervalRoundingMode.COMPACT;
    private static IntervalEncoding intervalEncoding = IntervalEncoding.ENDPOINTS;
    private static int intervalRadiusMantissaBits = 3;
    private static int doubleTerminalMantissaBits = 52;
    private static double doubleTerminalZeroCutoff = 0.0;
    private static double doubleTerminalQuantizationStep = 0.0;

    private static final class BackendContext {
        final LabelMode mode;
        final LabelDecisionDiagramBackend backend;
        int maxWidth;
        int[] sharedVars;

        BackendContext(LabelMode mode, LabelDecisionDiagramBackend backend) {
            this.mode = mode;
            this.backend = backend;
        }
    }

    protected final int id;
    protected int field;

    protected NDD(int nodeId) {
        this.id = nodeId;
        this.field = nodeTable == null ? NodeTable.TERMINAL_FIELD : nodeTable.getField(nodeId);
    }

    public static void initNDD(int nddTableSize, int bddTableSize, int bddCacheSize) {
        initialize(nddTableSize, CACHE_SIZE, bddTableSize, bddCacheSize, LabelMode.BDD);
    }

    public static void initNDD(int nddTableSize, int bddTableSize, int bddCacheSize, LabelMode mode) {
        initialize(nddTableSize, CACHE_SIZE, bddTableSize, bddCacheSize, mode);
    }

    private static void initialize(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                mode, TerminalMode.RATIONAL);
    }

    private static void initialize(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode, TerminalMode numericMode) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                mode, numericMode, 52);
    }

    private static void initialize(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode, TerminalMode numericMode, int mantissaBits) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                mode, numericMode, mantissaBits, 0.0);
    }

    private static void initialize(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode, TerminalMode numericMode, int mantissaBits,
            double zeroCutoff) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                mode, numericMode, mantissaBits, zeroCutoff, 0.0);
    }

    private static void initialize(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode, TerminalMode numericMode, int mantissaBits,
            double zeroCutoff, double quantizationStep) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize, mode,
                numericMode, mantissaBits, zeroCutoff, quantizationStep,
                IntervalEncoding.ENDPOINTS);
    }

    private static void initialize(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode, TerminalMode numericMode, int mantissaBits,
            double zeroCutoff, double quantizationStep, IntervalEncoding encoding) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize, mode,
                numericMode, mantissaBits, zeroCutoff, quantizationStep, encoding, 3);
    }

    private static void initialize(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode, TerminalMode numericMode, int mantissaBits,
            double zeroCutoff, double quantizationStep, IntervalEncoding encoding,
            int radiusMantissaBits) {
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        if (numericMode == null) throw new IllegalArgumentException("terminal mode must not be null");
        if (encoding == null) throw new IllegalArgumentException("interval encoding is null");
        if (mantissaBits < 0 || mantissaBits > 52) {
            throw new IllegalArgumentException("double mantissa bits must be between 0 and 52");
        }
        if (radiusMantissaBits < 0 || radiusMantissaBits > 52) {
            throw new IllegalArgumentException(
                    "interval radius mantissa bits must be between 0 and 52");
        }
        if (!Double.isFinite(zeroCutoff) || zeroCutoff < 0.0 || zeroCutoff >= 1.0) {
            throw new IllegalArgumentException("double zero cutoff must be in [0, 1)");
        }
        if (!Double.isFinite(quantizationStep)
                || quantizationStep < 0.0 || quantizationStep > 1.0) {
            throw new IllegalArgumentException("double quantization step must be in [0, 1]");
        }
        if (numericMode == TerminalMode.INTERVAL && zeroCutoff != 0.0) {
            throw new IllegalArgumentException("interval terminals do not support zero cutoff");
        }
        if (numericMode != TerminalMode.INTERVAL && encoding != IntervalEncoding.ENDPOINTS) {
            throw new IllegalArgumentException(
                    "nominal-radius encoding requires interval terminals");
        }
        terminalMode = numericMode;
        intervalRoundingMode = IntervalRoundingMode.COMPACT;
        intervalEncoding = encoding;
        intervalRadiusMantissaBits = radiusMantissaBits;
        doubleTerminalMantissaBits = mantissaBits;
        doubleTerminalZeroCutoff = zeroCutoff;
        doubleTerminalQuantizationStep = quantizationStep;
        CACHE_SIZE = nddCacheSize;
        labelMode = mode;
        labelTableSize = bddTableSize;
        labelCacheSize = bddCacheSize;
        backendTableSizes = new int[LabelMode.values().length];
        backendCacheSizes = new int[LabelMode.values().length];
        Arrays.fill(backendTableSizes, bddTableSize);
        Arrays.fill(backendCacheSizes, bddCacheSize);
        nodeTable = new NodeTable(nddTableSize, terminalMode != TerminalMode.RATIONAL,
                terminalMode == TerminalMode.INTERVAL, doubleTerminalMantissaBits,
                doubleTerminalZeroCutoff,
                doubleTerminalQuantizationStep,
                intervalEncoding == IntervalEncoding.NOMINAL_RADIUS,
                intervalRadiusMantissaBits);
        backendContexts = new BackendContext[LabelMode.values().length];
        labelBackend = null;
        bddEngine = null;
        bcddEngine = null;
        zddEngine = null;
        mixedLabelModes = false;
        fieldNum = -1;
        fieldsGenerated = false;
        pendingFieldBitNums = new ArrayList<>();
        pendingFieldModes = new ArrayList<>();
        fieldBackends = new ArrayList<>();
        fieldBackendArray = null;
        fieldModeArray = null;
        maxVariablePerField = new ArrayList<>();
        satCountDiv = new ArrayList<>();
        bddVarsPerField = new ArrayList<>();
        bddNotVarsPerField = new ArrayList<>();
        nddVarsPerField = new ArrayList<>();
        nddNotVarsPerField = new ArrayList<>();
        fieldUniverseLabels = new ArrayList<>();
        temporarilyProtect = new IntHashSet(1024);
        notCache = new IntOperationCache(CACHE_SIZE);
        andCache = new IntOperationCache(CACHE_SIZE);
        orCache = new IntOperationCache(CACHE_SIZE);
        addCache = new IntOperationCache(CACHE_SIZE);
        subCache = new IntOperationCache(CACHE_SIZE);
        mulCache = new IntOperationCache(CACHE_SIZE);
        divCache = new IntOperationCache(CACHE_SIZE);
        diffCache = new IntOperationCache(CACHE_SIZE);
        sumAbstractCache = new IntOperationCache(CACHE_SIZE);
        multiplySumAbstractCache = new IntOperationCache(CACHE_SIZE);
        lastNaryContractionStats = NaryContractionStats.EMPTY;
        stackTargets = new int[INITIAL_STACK_SIZE];
        stackLabels = new int[INITIAL_STACK_SIZE];
        stackTop = 0;
        residualLabels = new int[1024];
        residualTop = 0;
        assignmentTargets = new int[256];
        assignmentTop = 0;
        wrappers = new NDD[16];
        wrap(FALSE_ID);
        wrap(TRUE_ID);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize, int bddCacheSize) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize, LabelMode.BDD);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize, mode);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, TerminalMode numericMode) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                LabelMode.BDD, numericMode);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, TerminalMode numericMode, int mantissaBits) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                LabelMode.BDD, numericMode, mantissaBits);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, TerminalMode numericMode, int mantissaBits, double zeroCutoff) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                LabelMode.BDD, numericMode, mantissaBits, zeroCutoff);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, TerminalMode numericMode, int mantissaBits,
            double zeroCutoff, double quantizationStep) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                LabelMode.BDD, numericMode, mantissaBits, zeroCutoff, quantizationStep);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, TerminalMode numericMode, int mantissaBits,
            double zeroCutoff, double quantizationStep, IntervalEncoding encoding) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                LabelMode.BDD, numericMode, mantissaBits, zeroCutoff, quantizationStep,
                encoding);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, TerminalMode numericMode, int mantissaBits,
            double zeroCutoff, double quantizationStep, IntervalEncoding encoding,
            int radiusMantissaBits) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                LabelMode.BDD, numericMode, mantissaBits, zeroCutoff, quantizationStep,
                encoding, radiusMantissaBits);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode, TerminalMode numericMode) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize, mode, numericMode);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize,
            int bddCacheSize, LabelMode mode, TerminalMode numericMode,
            int mantissaBits, double zeroCutoff) {
        initialize(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize,
                mode, numericMode, mantissaBits, zeroCutoff);
    }

    public static TerminalMode getTerminalMode() {
        return terminalMode;
    }

    public static void setIntervalRoundingMode(IntervalRoundingMode mode) {
        if (mode == null) throw new IllegalArgumentException("interval rounding mode is null");
        intervalRoundingMode = mode;
    }

    public static IntervalRoundingMode getIntervalRoundingMode() {
        return intervalRoundingMode;
    }

    public static IntervalEncoding getIntervalEncoding() {
        return intervalEncoding;
    }

    public static int getIntervalRadiusMantissaBits() {
        return intervalRadiusMantissaBits;
    }

    public static int getDoubleTerminalMantissaBits() {
        return doubleTerminalMantissaBits;
    }

    public static double getDoubleTerminalZeroCutoff() {
        return doubleTerminalZeroCutoff;
    }

    public static double getDoubleTerminalQuantizationStep() {
        return doubleTerminalQuantizationStep;
    }

    public static int declareField(int bitNum) {
        return declareField(bitNum, labelMode);
    }

    public static int declareField(int bitNum, LabelMode mode) {
        if (fieldsGenerated) {
            throw new IllegalStateException("Cannot declare field after generateFields()");
        }
        if (bitNum <= 0) throw new IllegalArgumentException("field width must be positive");
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        BackendContext context = backendContext(mode);
        fieldNum++;
        pendingFieldBitNums.add(bitNum);
        pendingFieldModes.add(mode);
        fieldBackends.add(context.backend);
        if (fieldNum == 0) {
            labelBackend = context.backend;
        } else if (mode != pendingFieldModes.get(0)) {
            mixedLabelModes = true;
        }
        if (maxVariablePerField.isEmpty()) {
            maxVariablePerField.add(bitNum - 1);
        } else {
            maxVariablePerField.add(maxVariablePerField.get(maxVariablePerField.size() - 1) + bitNum);
        }

        return fieldNum;
    }

    private static BackendContext backendContext(LabelMode mode) {
        int index = mode.ordinal();
        BackendContext context = backendContexts[index];
        if (context != null) return context;
        LabelDecisionDiagramBackend backend = LabelDecisionDiagramBackends.create(mode,
                backendTableSizes[index], backendCacheSizes[index]);
        context = new BackendContext(mode, backend);
        backendContexts[index] = context;
        Object raw = backend.rawEngine();
        if (mode == LabelMode.BDD) bddEngine = (BDD) raw;
        else if (mode == LabelMode.COMPLEMENTED_BDD) bcddEngine = (ComplementedBDD) raw;
        else if (mode == LabelMode.ZDD) zddEngine = (ZDD) raw;
        return context;
    }

    public static void configureBackendCapacity(LabelMode mode, int tableSize, int cacheSize) {
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        if (tableSize <= 0 || cacheSize <= 0) {
            throw new IllegalArgumentException("backend table and cache sizes must be positive");
        }
        if (backendContexts[mode.ordinal()] != null) {
            throw new IllegalStateException("backend already created for " + mode);
        }
        backendTableSizes[mode.ordinal()] = tableSize;
        backendCacheSizes[mode.ordinal()] = cacheSize;
    }

    public static void generateFields() {
        if (fieldsGenerated) {
            return;
        }
        fieldsGenerated = true;
        if (pendingFieldBitNums.isEmpty()) {
            return;
        }
        fieldBackendArray = fieldBackends.toArray(new LabelDecisionDiagramBackend[0]);
        fieldModeArray = pendingFieldModes.toArray(new LabelMode[0]);
        for (int field = 0; field < pendingFieldBitNums.size(); field++) {
            BackendContext context = backendContexts[pendingFieldModes.get(field).ordinal()];
            context.maxWidth = Math.max(context.maxWidth, pendingFieldBitNums.get(field));
        }
        for (BackendContext context : backendContexts) {
            if (context == null || context.maxWidth == 0) continue;
            context.sharedVars = new int[context.maxWidth];
            if (context.mode == LabelMode.ZDD) {
                for (int i = 0; i < context.maxWidth; i++) {
                    context.sharedVars[i] = context.backend.ref(context.backend.createVariableLabel());
                }
            } else {
                for (int i = context.maxWidth - 1; i >= 0; i--) {
                    context.sharedVars[i] = context.backend.ref(context.backend.createVariableLabel());
                }
            }
        }
        for (int field = 0; field < pendingFieldBitNums.size(); field++) {
            generateFieldVars(field, pendingFieldBitNums.get(field));
        }
    }

    private static void ensureFieldsGenerated() {
        if (!fieldsGenerated) {
            throw new IllegalStateException("Call generateFields() after declaring all fields and before using NDD variables");
        }
    }

    private static void generateFieldVars(int field, int bitNum) {
        BackendContext context = backendContexts[pendingFieldModes.get(field).ordinal()];
        LabelDecisionDiagramBackend backend = context.backend;
        int[] bddVars = new int[bitNum];
        int[] bddNotVars = new int[bitNum];
        int[] nddVars = new int[bitNum];
        int[] nddNotVars = new int[bitNum];
        int offset = context.maxWidth - bitNum;
        nodeTable.declareField();
        int universe = backend.hasExplicitUniverse()
                ? backend.ref(backend.buildUniverse(context.sharedVars, offset, bitNum))
                : TRUE_ID;
        fieldUniverseLabels.add(universe);
        for (int i = 0; i < bitNum; i++) {
            int variable = context.sharedVars[offset + i];
            bddVars[i] = backend.positiveLiteral(universe, variable);
            bddNotVars[i] = backend.negativeLiteral(universe, variable);
            nddVars[i] = nodeTable.mk(field, new int[] { TRUE_ID },
                    new int[] { refLabel(field, bddVars[i]) });
            nddNotVars[i] = nodeTable.mk(field, new int[] { TRUE_ID },
                    new int[] { refLabel(field, bddNotVars[i]) });
            nodeTable.fixNDDNodeRefCount(nddVars[i]);
            nodeTable.fixNDDNodeRefCount(nddNotVars[i]);
        }
        bddVarsPerField.add(bddVars);
        bddNotVarsPerField.add(bddNotVars);
        nddVarsPerField.add(nddVars);
        nddNotVarsPerField.add(nddNotVars);
    }

    public static int getFieldNum() {
        return fieldNum;
    }

    public static int getFieldWidth(int field) {
        validateField(field);
        return pendingFieldBitNums.get(field);
    }

    /** Evaluate a diagram for one complete bit-vector assignment per field. */
    public static Rational evaluate(NDD ndd, int[][] assignment) {
        if (terminalMode == TerminalMode.DOUBLE) {
            throw new IllegalStateException("Use evaluateDouble in double-terminal mode");
        }
        return valueRational(evaluateId(ndd, assignment));
    }

    public static double evaluateDouble(NDD ndd, int[][] assignment) {
        return valueDouble(evaluateId(ndd, assignment));
    }

    private static int evaluateId(NDD ndd, int[][] assignment) {
        if (assignment == null || assignment.length != fieldNum + 1) {
            throw new IllegalArgumentException("assignment must contain every field");
        }
        int current = id(ndd);
        while (!isTerminalId(current)) {
            int field = nodeTable.getField(current);
            int[] bits = assignment[field];
            if (bits == null || bits.length != pendingFieldBitNums.get(field)) {
                throw new IllegalArgumentException("assignment width mismatch for field " + field);
            }
            int assignmentLabel = buildFieldAssignmentLabel(field, bits);
            int next = FALSE_ID;
            int count = nodeTable.getEdgeCount(current);
            for (int i = 0; i < count; i++) {
                if (backendForField(field).matches(nodeTable.getEdgeLabel(current, i), assignmentLabel)) {
                    next = nodeTable.getEdgeTarget(current, i);
                    break;
                }
            }
            derefLabel(field, assignmentLabel);
            current = next;
        }
        return current;
    }

    private static int buildFieldAssignmentLabel(int field, int[] bits) {
        int assignment = refLabel(field, getFieldUniverseLabel(field));
        for (int bit = 0; bit < bits.length; bit++) {
            if (bits[bit] != 0 && bits[bit] != 1) {
                derefLabel(field, assignment);
                throw new IllegalArgumentException("assignment bits must be 0 or 1");
            }
            int literal = bits[bit] == 0
                    ? bddNotVarsPerField.get(field)[bit]
                    : bddVarsPerField.get(field)[bit];
            int next = refLabel(field, labelAnd(field, assignment, literal));
            derefLabel(field, assignment);
            assignment = next;
        }
        return assignment;
    }

    /**
     * Restrict several fields to concrete integer values in one traversal.
     * Restricted fields are removed from the returned diagram.  Fields may be
     * listed in any order, but each field may occur only once.
     */
    public static NDD restrictFieldValues(NDD ndd, int[] fields, int[] values) {
        if (fields == null || values == null || fields.length != values.length) {
            throw new IllegalArgumentException("fields and values must have the same length");
        }
        boolean[] restricted = new boolean[fieldNum + 1];
        int[] labels = new int[fieldNum + 1];
        for (int i = 0; i < fields.length; i++) {
            int field = fields[i];
            validateField(field);
            if (restricted[field]) throw new IllegalArgumentException("duplicate field " + field);
            restricted[field] = true;
            labels[field] = encodeValueLabel(values[i], field);
        }
        temporarilyProtect.clear();
        int result;
        try {
            result = restrictFieldValuesRec(id(ndd), restricted, labels,
                    new HashMap<Integer, Integer>());
            temporarilyProtect.add(result);
            runSafePointMaintenance(result);
        } finally {
            for (int field = 0; field < restricted.length; field++) {
                if (restricted[field]) derefLabel(field, labels[field]);
            }
        }
        return wrap(result);
    }

    private static int restrictFieldValuesRec(int current, boolean[] restricted,
            int[] labels, HashMap<Integer, Integer> memo) {
        if (isTerminalId(current)) return current;
        Integer cached = memo.get(current);
        if (cached != null) return cached;
        int field = nodeTable.getField(current);
        int result;
        if (restricted[field]) {
            result = FALSE_ID;
            int edgeCount = nodeTable.getEdgeCount(current);
            for (int edge = 0; edge < edgeCount; edge++) {
                if (backendForField(field).matches(
                        nodeTable.getEdgeLabel(current, edge), labels[field])) {
                    result = restrictFieldValuesRec(
                            nodeTable.getEdgeTarget(current, edge), restricted, labels, memo);
                    break;
                }
            }
        } else {
            int frameStart = stackTop;
            int edgeCount = nodeTable.getEdgeCount(current);
            for (int edge = 0; edge < edgeCount; edge++) {
                int target = restrictFieldValuesRec(
                        nodeTable.getEdgeTarget(current, edge), restricted, labels, memo);
                edgeCollect(frameStart, field, target,
                        refLabel(field, nodeTable.getEdgeLabel(current, edge)));
            }
            result = edgeFlush(frameStart, field);
            temporarilyProtect.add(result);
        }
        memo.put(current, result);
        return result;
    }

    public static NDD getVar(int field, int index) {
        ensureFieldsGenerated();
        return wrap(nddVarsPerField.get(field)[index]);
    }

    public static NDD getNotVar(int field, int index) {
        ensureFieldsGenerated();
        return wrap(nddNotVarsPerField.get(field)[index]);
    }

    public static int[] getBDDVars(int field) {
        ensureFieldsGenerated();
        ensureFieldMode(field, LabelMode.BDD, "BDD variable handles");
        return bddVarsPerField.get(field);
    }

    public static int[] getNotBDDVars(int field) {
        ensureFieldsGenerated();
        ensureFieldMode(field, LabelMode.BDD, "BDD negated variable handles");
        return bddNotVarsPerField.get(field);
    }

    public static BDD getBDDEngine() {
        return bddEngine;
    }

    public static ComplementedBDD getBCDDEngine() { return bcddEngine; }

    public static ZDD getZDDEngine() { return zddEngine; }

    public static LabelMode getFieldLabelMode(int field) {
        validateField(field);
        return pendingFieldModes.get(field);
    }

    public static boolean hasMixedLabelModes() { return mixedLabelModes; }

    public static int getFalseId() {
        return FALSE_ID;
    }

    public static boolean isUniverseEdgeLabel(int field, int label) {
        return label == getFieldUniverseLabel(field);
    }

    public static int refLabel(int label) {
        ensureHomogeneousLabels("refLabel(label)");
        return labelBackend.ref(label);
    }

    public static void derefLabel(int label) {
        ensureHomogeneousLabels("derefLabel(label)");
        labelBackend.deref(label);
    }

    public static int refLabel(int field, int label) {
        return backendForField(field).ref(label);
    }

    public static void derefLabel(int field, int label) {
        backendForField(field).deref(label);
    }

    private static int getFieldUniverseLabel(int field) {
        return fieldUniverseLabels.get(field);
    }

    private static LabelMode fieldMode(int field) {
        return mixedLabelModes ? fieldModeArray[field] : labelBackend.mode();
    }

    private static LabelDecisionDiagramBackend backendForField(int field) {
        return mixedLabelModes ? fieldBackendArray[field] : labelBackend;
    }

    private static void ensureHomogeneousLabels(String feature) {
        if (mixedLabelModes) {
            throw new IllegalStateException(feature + " requires a field argument in mixed-backend mode");
        }
        if (labelBackend == null) throw new IllegalStateException("No fields have been declared");
    }

    private static void ensureFieldMode(int field, LabelMode expected, String feature) {
        validateField(field);
        if (pendingFieldModes.get(field) != expected) {
            throw new UnsupportedOperationException(feature + " requires field " + field + " to use " + expected);
        }
    }

    private static void ensureHomogeneousBdd(String feature) {
        ensureHomogeneousLabels(feature);
        if (labelBackend.mode() != LabelMode.BDD) {
            throw new UnsupportedOperationException(feature + " is only supported in homogeneous BDD mode");
        }
    }

    private static void validateField(int field) {
        if (field < 0 || field > fieldNum) {
            throw new IllegalArgumentException("field out of range: " + field);
        }
    }

    private static int labelAnd(int field, int left, int right) {
        return backendForField(field).and(left, right);
    }

    private static int labelOrTo(int current, int add, int field) {
        return backendForField(field).orTo(current, add);
    }

    private static int labelDiff(int field, int left, int right) {
        return backendForField(field).diff(getFieldUniverseLabel(field), left, right);
    }

    private static int labelDiffTo(int current, int remove, int field) {
        int result = refLabel(field, labelDiff(field, current, remove));
        derefLabel(field, current);
        return result;
    }

    public static double getLabelSatCount(int field, int label) {
        validateField(field);
        BackendContext context = backendContexts[fieldMode(field).ordinal()];
        return backendForField(field).satCount(label, pendingFieldBitNums.get(field), context.maxWidth);
    }

    public static long getLabelNodeCount(LabelMode mode) {
        BackendContext context = backendContexts[mode.ordinal()];
        return context == null ? 0 : context.backend.nodeCount();
    }

    public static long getLabelTotalCreated(LabelMode mode) {
        BackendContext context = backendContexts[mode.ordinal()];
        return context == null ? 0 : context.backend.totalCreated();
    }

    public static void gcLabelEngines() {
        clearCaches();
        for (BackendContext context : backendContexts) {
            if (context != null) context.backend.gc();
        }
        clearCaches();
    }

    public static void clearCaches() {
        notCache.clear();
        andCache.clear();
        orCache.clear();
        addCache.clear();
        subCache.clear();
        mulCache.clear();
        divCache.clear();
        diffCache.clear();
        sumAbstractCache.clear();
        multiplySumAbstractCache.clear();
        externalCacheCleaner.run();
    }

    public static void setExternalCacheCleaner(Runnable cleaner) {
        externalCacheCleaner = cleaner == null ? () -> {} : cleaner;
    }

    public static void forEachTemporarilyProtect(IntConsumer consumer) {
        temporarilyProtect.forEach(consumer);
    }

    private static void runSafePointMaintenance(int result) {
        // Recursive calls protect every intermediate result against an in-flight GC.
        // At a top-level safe point only the value crossing the public API boundary
        // needs that temporary root; retaining the full set defeats terminal GC.
        temporarilyProtect.clear();
        temporarilyProtect.add(result);
        if (nodeTable != null) {
            nodeTable.compactEdgesIfNeeded();
        }
    }

    private static NDD wrap(int nodeId) {
        ensureWrapperCapacity(nodeId);
        NDD existing = wrappers[nodeId];
        if (existing != null) {
            return existing;
        }
        NDD created = nodeTable.isTerminal(nodeId)
                ? new Terminal(nodeId)
                : new NDD(nodeId);
        wrappers[nodeId] = created;
        return created;
    }

    private static void ensureWrapperCapacity(int nodeId) {
        if (nodeId < wrappers.length) {
            return;
        }
        int newCapacity = wrappers.length;
        while (newCapacity <= nodeId) {
            newCapacity <<= 1;
        }
        wrappers = Arrays.copyOf(wrappers, newCapacity);
    }

    public static void onNodeRetired(int nodeId) {
        if (wrappers != null && nodeId >= 0 && nodeId < wrappers.length) {
            wrappers[nodeId] = null;
        }
    }

    private static int id(NDD ndd) {
        return ndd.id;
    }

    private static boolean isTerminalId(int nodeId) {
        return nodeTable.isTerminal(nodeId);
    }

    private static boolean isZeroId(int nodeId) {
        return nodeId == FALSE_ID;
    }

    private static boolean isOneId(int nodeId) {
        return nodeId == TRUE_ID;
    }

    private static Rational valueRational(int nodeId) {
        return nodeTable.getTerminalValue(nodeId);
    }

    private static double valueDouble(int nodeId) {
        return nodeTable.getTerminalDouble(nodeId);
    }

    private static double valueLower(int nodeId) {
        return nodeTable.getTerminalLower(nodeId);
    }

    private static double valueUpper(int nodeId) {
        return nodeTable.getTerminalUpper(nodeId);
    }

    private static String valueDisplay(int nodeId) {
        return nodeTable.getTerminalDisplay(nodeId);
    }

    private static int terminal(Rational value) {
        return nodeTable.mkTerminal(value);
    }

    private static int terminal(double value) {
        return nodeTable.mkTerminal(value);
    }

    private static int terminalInterval(double lower, double upper) {
        return nodeTable.mkInterval(lower, upper, false);
    }

    private static int exactInterval(double value) {
        return nodeTable.mkInterval(value, value, true);
    }

    private static void edgeCollect(int frameStart, int field, int target, int label) {
        if (target == FALSE_ID) {
            derefLabel(field, label);
            return;
        }
        if (stackTop >= stackTargets.length) {
            growStack();
        }
        stackTargets[stackTop] = target;
        stackLabels[stackTop] = label;
        stackTop++;
    }

    private static final int RADIX_THRESHOLD = Integer.getInteger("ndd.radixThreshold", 64);
    private static long[] packScratch = new long[1024];
    private static long[] packScratch2 = new long[1024];
    private static final int[] radixCount = new int[257];

    private static int edgeFlush(int frameStart, int field) {
        int size = stackTop - frameStart;
        if (size == 0) {
            stackTop = frameStart;
            return FALSE_ID;
        }
        if (size == 1 && isUniverseEdgeLabel(field, stackLabels[frameStart])) {
            int target = stackTargets[frameStart];
            derefLabel(field, stackLabels[frameStart]);
            stackTop = frameStart;
            return target;
        }
        int res = size >= RADIX_THRESHOLD
                ? flushRadixMerge(frameStart, field, size)
                : flushQsortMerge(frameStart, field, size);
        stackTop = frameStart;
        return res;
    }

    private static int flushQsortMerge(int frameStart, int field, int size) {
        qsortPairs(frameStart, frameStart + size - 1);
        return mergeRunsAndMk(frameStart, field, size);
    }

    private static int flushRadixMerge(int frameStart, int field, int size) {
        if (packScratch.length < size) {
            packScratch = new long[Math.max(size, packScratch.length << 1)];
        }
        if (packScratch2.length < size) {
            packScratch2 = new long[Math.max(size, packScratch2.length << 1)];
        }
        long[] packed = packScratch;
        for (int i = 0; i < size; i++) {
            packed[i] = ((long) stackTargets[frameStart + i] << 32)
                    | (stackLabels[frameStart + i] & 0xffffffffL);
        }
        radixSortByTarget(size);
        packed = packScratch;
        for (int i = 0; i < size; i++) {
            stackTargets[frameStart + i] = (int) (packed[i] >>> 32);
            stackLabels[frameStart + i] = (int) packed[i];
        }
        return mergeRunsAndMk(frameStart, field, size);
    }

    private static int mergeRunsAndMk(int frameStart, int field, int size) {
        int write = frameStart;
        int currentTarget = stackTargets[frameStart];
        int currentLabel = stackLabels[frameStart];
        for (int i = frameStart + 1; i < frameStart + size; i++) {
            int target = stackTargets[i];
            int label = stackLabels[i];
            if (target == currentTarget) {
                currentLabel = labelOrTo(currentLabel, label, field);
            } else {
                stackTargets[write] = currentTarget;
                stackLabels[write++] = currentLabel;
                currentTarget = target;
                currentLabel = label;
            }
        }
        stackTargets[write] = currentTarget;
        stackLabels[write++] = currentLabel;
        return nodeTable.mk(field, stackTargets, stackLabels, frameStart, write - frameStart);
    }

    private static void qsortPairs(int low, int high) {
        while (high - low > 16) {
            int middle = (low + high) >>> 1;
            int a = stackTargets[low];
            int b = stackTargets[middle];
            int c = stackTargets[high];
            int pivotIndex = a < b
                    ? (b < c ? middle : (a < c ? high : low))
                    : (a < c ? low : (b < c ? high : middle));
            swapPair(pivotIndex, high);
            int pivot = stackTargets[high];
            int i = low - 1;
            for (int j = low; j < high; j++) {
                if (stackTargets[j] < pivot) {
                    swapPair(++i, j);
                }
            }
            swapPair(i + 1, high);
            int partition = i + 1;
            if (partition - low < high - partition) {
                qsortPairs(low, partition - 1);
                low = partition + 1;
            } else {
                qsortPairs(partition + 1, high);
                high = partition - 1;
            }
        }
        for (int i = low + 1; i <= high; i++) {
            int target = stackTargets[i];
            int label = stackLabels[i];
            int j = i - 1;
            while (j >= low && stackTargets[j] > target) {
                stackTargets[j + 1] = stackTargets[j];
                stackLabels[j + 1] = stackLabels[j];
                j--;
            }
            stackTargets[j + 1] = target;
            stackLabels[j + 1] = label;
        }
    }

    private static void swapPair(int i, int j) {
        int target = stackTargets[i];
        stackTargets[i] = stackTargets[j];
        stackTargets[j] = target;
        int label = stackLabels[i];
        stackLabels[i] = stackLabels[j];
        stackLabels[j] = label;
    }

    private static void radixSortByTarget(int size) {
        long[] source = packScratch;
        long[] destination = packScratch2;
        for (int shift = 32; shift < 64; shift += 8) {
            Arrays.fill(radixCount, 0);
            for (int i = 0; i < size; i++) {
                radixCount[((int) (source[i] >>> shift) & 0xff) + 1]++;
            }
            for (int i = 0; i < 256; i++) {
                radixCount[i + 1] += radixCount[i];
            }
            for (int i = 0; i < size; i++) {
                destination[radixCount[(int) (source[i] >>> shift) & 0xff]++] = source[i];
            }
            long[] swap = source;
            source = destination;
            destination = swap;
        }
        packScratch = source;
        packScratch2 = destination;
    }

    private static void growStack() {
        int newCapacity = stackTargets.length << 1;
        stackTargets = Arrays.copyOf(stackTargets, newCapacity);
        stackLabels = Arrays.copyOf(stackLabels, newCapacity);
    }

    public static NDD mk(int field, NDD[] targets, int[] labels) {
        if (targets.length != labels.length) {
            throw new IllegalArgumentException("targets and labels must have the same length");
        }
        int frameStart = stackTop;
        for (int i = 0; i < targets.length; i++) {
            edgeCollect(frameStart, field, id(targets[i]), refLabel(field, labels[i]));
        }
        return wrap(edgeFlush(frameStart, field));
    }

    public static NDD and(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = andRec(id(a), id(b));
        runSafePointMaintenance(res);
        return wrap(res);
    }

    public static NDD or(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = orRec(id(a), id(b));
        runSafePointMaintenance(res);
        return wrap(res);
    }

    public static NDD not(NDD a) {
        temporarilyProtect.clear();
        int res = notRec(id(a));
        runSafePointMaintenance(res);
        return wrap(res);
    }

    public static NDD add(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = addRec(id(a), id(b));
        runSafePointMaintenance(res);
        return wrap(res);
    }

    public static NDD sub(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = subRec(id(a), id(b));
        runSafePointMaintenance(res);
        return wrap(res);
    }

    public static NDD mul(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = mulRec(id(a), id(b));
        runSafePointMaintenance(res);
        return wrap(res);
    }

    public static NDD div(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = divRec(id(a), id(b));
        runSafePointMaintenance(res);
        return wrap(res);
    }

    public static NDD diff(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = diffRec(id(a), id(b));
        runSafePointMaintenance(res);
        return wrap(res);
    }

    private static int diffRec(int a, int b) {
        if (isZeroId(a) || a == b) return FALSE_ID;
        if (isZeroId(b)) return a;
        if (isTerminalId(b)) return FALSE_ID;
        if (diffCache.getEntryOrdered(a, b)) return diffCache.result;
        int cacheIndex = diffCache.hashValue;

        int frameStart = stackTop;
        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);
        if (aField == bField) {
            int aCount = nodeTable.getEdgeCount(a);
            int bCount = nodeTable.getEdgeCount(b);
            for (int i = 0; i < aCount; i++) {
                int aTarget = nodeTable.getEdgeTarget(a, i);
                int remaining = refLabel(aField, nodeTable.getEdgeLabel(a, i));
                for (int j = 0; j < bCount && remaining != 0; j++) {
                    int bLabel = nodeTable.getEdgeLabel(b, j);
                    int intersection = refLabel(aField, labelAnd(aField, remaining, bLabel));
                    if (intersection != 0) {
                        edgeCollect(frameStart, aField,
                                diffRec(aTarget, nodeTable.getEdgeTarget(b, j)), intersection);
                    } else {
                        derefLabel(aField, intersection);
                    }
                    remaining = labelDiffTo(remaining, bLabel, aField);
                }
                if (remaining != 0) edgeCollect(frameStart, aField, aTarget, remaining);
                else derefLabel(aField, remaining);
            }
        } else if (aField < bField) {
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                edgeCollect(frameStart, aField,
                        diffRec(nodeTable.getEdgeTarget(a, i), b),
                        refLabel(aField, nodeTable.getEdgeLabel(a, i)));
            }
        } else {
            int residual = refLabel(bField, getFieldUniverseLabel(bField));
            int count = nodeTable.getEdgeCount(b);
            for (int i = 0; i < count; i++) {
                int label = nodeTable.getEdgeLabel(b, i);
                edgeCollect(frameStart, bField,
                        diffRec(a, nodeTable.getEdgeTarget(b, i)), refLabel(bField, label));
                residual = labelDiffTo(residual, label, bField);
            }
            if (residual != 0) edgeCollect(frameStart, bField, a, residual);
            else derefLabel(bField, residual);
        }
        int result = edgeFlush(frameStart, Math.min(aField, bField));
        temporarilyProtect.add(result);
        diffCache.setEntry(cacheIndex, a, b, result);
        return result;
    }

    public static NDD imp(NDD a, NDD b) {
        temporarilyProtect.clear();
        int n = notRec(id(a));
        temporarilyProtect.add(n);
        int res = orRec(n, id(b));
        runSafePointMaintenance(res);
        return wrap(res);
    }

    private static int andRec(int a, int b) {
        if (isZeroId(a) || isOneId(b)) return a;
        if (isOneId(a) || isZeroId(b) || a == b) return b;
        if (andCache.getEntry(a, b)) return andCache.result;
        int cacheIndex = andCache.hashValue;

        int frameStart = stackTop;
        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);
        if (aField == bField) {
            int aCount = nodeTable.getEdgeCount(a);
            int bCount = nodeTable.getEdgeCount(b);
            for (int i = 0; i < aCount; i++) {
                int aTarget = nodeTable.getEdgeTarget(a, i);
                int aLabel = nodeTable.getEdgeLabel(a, i);
                for (int j = 0; j < bCount; j++) {
                    int intersect = refLabel(aField,
                            labelAnd(aField, aLabel, nodeTable.getEdgeLabel(b, j)));
                    if (intersect != 0) {
                        edgeCollect(frameStart, aField,
                                andRec(aTarget, nodeTable.getEdgeTarget(b, j)), intersect);
                    } else {
                        derefLabel(aField, intersect);
                    }
                }
            }
        } else {
            if (aField > bField) {
                int t = a; a = b; b = t;
                int tf = aField; aField = bField; bField = tf;
            }
            int aCount = nodeTable.getEdgeCount(a);
            for (int i = 0; i < aCount; i++) {
                int sub = andRec(nodeTable.getEdgeTarget(a, i), b);
                edgeCollect(frameStart, aField, sub,
                        refLabel(aField, nodeTable.getEdgeLabel(a, i)));
            }
        }
        int res = edgeFlush(frameStart, aField);
        temporarilyProtect.add(res);
        andCache.setEntry(cacheIndex, a, b, res);
        return res;
    }

    private static int orRec(int a, int b) {
        if (isOneId(a) || isZeroId(b)) return a;
        if (isZeroId(a) || isOneId(b) || a == b) return b;
        if (orCache.getEntry(a, b)) return orCache.result;
        int cacheIndex = orCache.hashValue;

        int frameStart = stackTop;
        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);
        if (aField == bField) {
            combineSameField(a, b, frameStart, true, TerminalOp.OR);
        } else {
            if (aField > bField) {
                int t = a; a = b; b = t;
                int tf = aField; aField = bField; bField = tf;
            }
            int residualB = refLabel(aField, getFieldUniverseLabel(aField));
            int aCount = nodeTable.getEdgeCount(a);
            for (int i = 0; i < aCount; i++) {
                int aLabel = nodeTable.getEdgeLabel(a, i);
                residualB = labelDiffTo(residualB, aLabel, aField);
                int sub = orRec(nodeTable.getEdgeTarget(a, i), b);
                edgeCollect(frameStart, aField, sub, refLabel(aField, aLabel));
            }
            if (residualB != 0) edgeCollect(frameStart, aField, b, residualB);
            else derefLabel(aField, residualB);
        }
        int res = edgeFlush(frameStart, aField);
        temporarilyProtect.add(res);
        orCache.setEntry(cacheIndex, a, b, res);
        return res;
    }

    private static int notRec(int a) {
        if (isOneId(a)) return FALSE_ID;
        if (isZeroId(a)) return TRUE_ID;
        if (isTerminalId(a)) {
            return valueDouble(a) == 0.0 ? TRUE_ID : FALSE_ID;
        }
        if (notCache.getEntry(a)) return notCache.result;
        int cacheIndex = notCache.hashValue;

        int frameStart = stackTop;
        int field = nodeTable.getField(a);
        int residual = refLabel(field, getFieldUniverseLabel(field));
        int count = nodeTable.getEdgeCount(a);
        for (int i = 0; i < count; i++) {
            int label = nodeTable.getEdgeLabel(a, i);
            residual = labelDiffTo(residual, label, field);
            edgeCollect(frameStart, field, notRec(nodeTable.getEdgeTarget(a, i)),
                    refLabel(field, label));
        }
        if (residual != 0) edgeCollect(frameStart, field, TRUE_ID, residual);
        else derefLabel(field, residual);
        int res = edgeFlush(frameStart, field);
        temporarilyProtect.add(res);
        notCache.setEntry(cacheIndex, a, res);
        return res;
    }

    private enum TerminalOp { ADD, SUB, MUL, DIV, OR }

    private static int terminalOp(int a, int b, TerminalOp op) {
        if (terminalMode == TerminalMode.INTERVAL) {
            double al = valueLower(a);
            double au = valueUpper(a);
            double bl = valueLower(b);
            double bu = valueUpper(b);
            switch (op) {
                case ADD:
                    return terminalInterval(intervalDownAdd(al, bl), intervalUpAdd(au, bu));
                case SUB:
                    return terminalInterval(intervalDownAdd(al, -bu), intervalUpAdd(au, -bl));
                case MUL:
                    return multiplyIntervals(al, au, bl, bu);
                case DIV:
                    if (bl <= 0.0 && bu >= 0.0) {
                        throw new ArithmeticException("interval division by a range containing zero");
                    }
                    return divideIntervals(al, au, bl, bu);
                case OR:
                    return al != 0.0 || au != 0.0 ? a : b;
                default:
                    throw new AssertionError(op);
            }
        }
        if (terminalMode == TerminalMode.DOUBLE) {
            double av = valueDouble(a);
            double bv = valueDouble(b);
            switch (op) {
                case ADD: return terminal(av + bv);
                case SUB: return terminal(av - bv);
                case MUL: return terminal(av * bv);
                case DIV: return bv == 0.0 ? FALSE_ID : terminal(av / bv);
                case OR: return av != 0.0 ? a : b;
                default: throw new AssertionError(op);
            }
        }
        Rational av = valueRational(a);
        Rational bv = valueRational(b);
        switch (op) {
            case ADD: return terminal(av.add(bv));
            case SUB: return terminal(av.subtract(bv));
            case MUL: return terminal(av.multiply(bv));
            case DIV:
                try {
                    return terminal(av.divide(bv));
                } catch (ArithmeticException e) {
                    return FALSE_ID;
                }
            case OR:
                return av.doubleValue() != 0.0 ? a : b;
            default:
                throw new AssertionError(op);
        }
    }

    private static int multiplyIntervals(double al, double au, double bl, double bu) {
        if (intervalRoundingMode == IntervalRoundingMode.COMPACT) {
            double p1 = al * bl;
            double p2 = al * bu;
            double p3 = au * bl;
            double p4 = au * bu;
            return terminalInterval(
                    expandDown(Math.min(Math.min(p1, p2), Math.min(p3, p4))),
                    expandUp(Math.max(Math.max(p1, p2), Math.max(p3, p4))));
        }
        double lower = Math.min(
                Math.min(multiplyDown(al, bl), multiplyDown(al, bu)),
                Math.min(multiplyDown(au, bl), multiplyDown(au, bu)));
        double upper = Math.max(
                Math.max(multiplyUp(al, bl), multiplyUp(al, bu)),
                Math.max(multiplyUp(au, bl), multiplyUp(au, bu)));
        return terminalInterval(lower, upper);
    }

    private static int divideIntervals(double al, double au, double bl, double bu) {
        if (intervalRoundingMode == IntervalRoundingMode.COMPACT) {
            double p1 = al / bl;
            double p2 = al / bu;
            double p3 = au / bl;
            double p4 = au / bu;
            return terminalInterval(
                    expandDown(Math.min(Math.min(p1, p2), Math.min(p3, p4))),
                    expandUp(Math.max(Math.max(p1, p2), Math.max(p3, p4))));
        }
        double lower = Math.min(
                Math.min(divideDown(al, bl), divideDown(al, bu)),
                Math.min(divideDown(au, bl), divideDown(au, bu)));
        double upper = Math.max(
                Math.max(divideUp(al, bl), divideUp(al, bu)),
                Math.max(divideUp(au, bl), divideUp(au, bu)));
        return terminalInterval(lower, upper);
    }

    private static double intervalDownAdd(double a, double b) {
        return intervalRoundingMode == IntervalRoundingMode.COMPACT
                ? expandDown(a + b) : addDown(a, b);
    }

    private static double intervalUpAdd(double a, double b) {
        return intervalRoundingMode == IntervalRoundingMode.COMPACT
                ? expandUp(a + b) : addUp(a, b);
    }

    private static double expandDown(double value) {
        return value == 0.0 ? 0.0 : Math.nextDown(value);
    }

    private static double expandUp(double value) {
        return value == 0.0 ? 0.0 : Math.nextUp(value);
    }

    /** Directed rounding for a+b using Knuth's error-free TwoSum residual. */
    private static double addDown(double a, double b) {
        double result = a + b;
        double residual = additionResidual(a, b, result);
        return residual < 0.0 ? Math.nextDown(result) : result;
    }

    private static double addUp(double a, double b) {
        double result = a + b;
        double residual = additionResidual(a, b, result);
        return residual > 0.0 ? Math.nextUp(result) : result;
    }

    private static double additionResidual(double a, double b, double result) {
        double resultMinusA = result - a;
        return (a - (result - resultMinusA)) + (b - resultMinusA);
    }

    /** Math.fma exposes the exact product residual unless it underflows to zero. */
    private static double multiplyDown(double a, double b) {
        double result = a * b;
        if (result == 0.0 && a != 0.0 && b != 0.0) {
            return sameSign(a, b) ? 0.0 : -Double.MIN_VALUE;
        }
        double residual = Math.fma(a, b, -result);
        if (residual < 0.0) return Math.nextDown(result);
        if (residual == 0.0 && result != 0.0 && Math.abs(result) < Double.MIN_NORMAL) {
            return Math.nextDown(result);
        }
        return result;
    }

    private static double multiplyUp(double a, double b) {
        double result = a * b;
        if (result == 0.0 && a != 0.0 && b != 0.0) {
            return sameSign(a, b) ? Double.MIN_VALUE : 0.0;
        }
        double residual = Math.fma(a, b, -result);
        if (residual > 0.0) return Math.nextUp(result);
        if (residual == 0.0 && result != 0.0 && Math.abs(result) < Double.MIN_NORMAL) {
            return Math.nextUp(result);
        }
        return result;
    }

    /** The sign of a-q*b determines which side of the exact quotient q lies on. */
    private static double divideDown(double a, double b) {
        double result = a / b;
        if (result == 0.0 && a != 0.0) {
            return sameSign(a, b) ? 0.0 : -Double.MIN_VALUE;
        }
        double residual = Math.fma(-result, b, a);
        int direction = quotientResidualDirection(residual, b);
        if (direction < 0) return Math.nextDown(result);
        if (direction == 0 && result != 0.0 && Math.abs(result) < Double.MIN_NORMAL) {
            return Math.nextDown(result);
        }
        return result;
    }

    private static double divideUp(double a, double b) {
        double result = a / b;
        if (result == 0.0 && a != 0.0) {
            return sameSign(a, b) ? Double.MIN_VALUE : 0.0;
        }
        double residual = Math.fma(-result, b, a);
        int direction = quotientResidualDirection(residual, b);
        if (direction > 0) return Math.nextUp(result);
        if (direction == 0 && result != 0.0 && Math.abs(result) < Double.MIN_NORMAL) {
            return Math.nextUp(result);
        }
        return result;
    }

    private static int quotientResidualDirection(double residual, double divisor) {
        if (residual == 0.0) return 0;
        return sameSign(residual, divisor) ? 1 : -1;
    }

    private static boolean sameSign(double a, double b) {
        return (Double.doubleToRawLongBits(a) ^ Double.doubleToRawLongBits(b)) >= 0L;
    }

    private static int addRec(int a, int b) {
        if (isZeroId(a)) return b;
        if (isZeroId(b)) return a;
        if (addCache.getEntry(a, b)) return addCache.result;
        int cacheIndex = addCache.hashValue;
        int res = isTerminalId(a) && isTerminalId(b)
                ? terminalOp(a, b, TerminalOp.ADD)
                : arithmeticRec(a, b, TerminalOp.ADD);
        addCache.setEntry(cacheIndex, a, b, res);
        return res;
    }

    private static int subRec(int a, int b) {
        if (isZeroId(b)) return a;
        if (a == b) return FALSE_ID;
        if (subCache.getEntryOrdered(a, b)) return subCache.result;
        int cacheIndex = subCache.hashValue;
        int res = isTerminalId(a) && isTerminalId(b)
                ? terminalOp(a, b, TerminalOp.SUB)
                : arithmeticRec(a, b, TerminalOp.SUB);
        subCache.setEntry(cacheIndex, a, b, res);
        return res;
    }

    private static int mulRec(int a, int b) {
        if (isZeroId(a) || isZeroId(b)) return FALSE_ID;
        if (isOneId(a)) return b;
        if (isOneId(b)) return a;
        if (mulCache.getEntry(a, b)) return mulCache.result;
        int cacheIndex = mulCache.hashValue;
        int res = isTerminalId(a) && isTerminalId(b)
                ? terminalOp(a, b, TerminalOp.MUL)
                : arithmeticRec(a, b, TerminalOp.MUL);
        mulCache.setEntry(cacheIndex, a, b, res);
        return res;
    }

    private static int divRec(int a, int b) {
        if (isZeroId(a)) return FALSE_ID;
        if (isOneId(b)) return a;
        if (divCache.getEntryOrdered(a, b)) return divCache.result;
        int cacheIndex = divCache.hashValue;
        int res = isTerminalId(a) && isTerminalId(b)
                ? terminalOp(a, b, TerminalOp.DIV)
                : arithmeticRec(a, b, TerminalOp.DIV);
        divCache.setEntry(cacheIndex, a, b, res);
        return res;
    }

    private static int arithmeticRec(int a, int b, TerminalOp op) {
        int frameStart = stackTop;
        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);
        if (aField == bField) {
            combineSameField(a, b, frameStart, false, op);
            int res = edgeFlush(frameStart, aField);
            temporarilyProtect.add(res);
            return res;
        }

        boolean aFirst = (!isTerminalId(a) && (isTerminalId(b) || aField < bField));
        if (aFirst) {
            int residualB = refLabel(aField, getFieldUniverseLabel(aField));
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                int label = nodeTable.getEdgeLabel(a, i);
                residualB = labelDiffTo(residualB, label, aField);
                int sub = arithmeticChild(nodeTable.getEdgeTarget(a, i), b, op);
                edgeCollect(frameStart, aField, sub, refLabel(aField, label));
            }
            if (op != TerminalOp.MUL && residualB != 0) {
                int sub = arithmeticChild(FALSE_ID, b, op);
                edgeCollect(frameStart, aField, sub, residualB);
            } else {
                derefLabel(aField, residualB);
            }
            int res = edgeFlush(frameStart, aField);
            temporarilyProtect.add(res);
            return res;
        }

        int residualA = refLabel(bField, getFieldUniverseLabel(bField));
        int count = nodeTable.getEdgeCount(b);
        for (int i = 0; i < count; i++) {
            int label = nodeTable.getEdgeLabel(b, i);
            residualA = labelDiffTo(residualA, label, bField);
            int sub = arithmeticChild(a, nodeTable.getEdgeTarget(b, i), op);
            edgeCollect(frameStart, bField, sub, refLabel(bField, label));
        }
        if (op != TerminalOp.MUL && residualA != 0) {
            int sub = arithmeticChild(a, FALSE_ID, op);
            edgeCollect(frameStart, bField, sub, residualA);
        } else {
            derefLabel(bField, residualA);
        }
        int res = edgeFlush(frameStart, bField);
        temporarilyProtect.add(res);
        return res;
    }

    private static int arithmeticChild(int a, int b, TerminalOp op) {
        switch (op) {
            case ADD: return addRec(a, b);
            case SUB: return subRec(a, b);
            case MUL: return mulRec(a, b);
            case DIV: return divRec(a, b);
            default: throw new AssertionError(op);
        }
    }

    /**
     * Sum all values over one field while preserving the remaining field structure.
     * Edge-label multiplicity is obtained through the backend assigned to that field.
     */
    public static NDD sumAbstract(NDD a, int field) {
        validateField(field);
        temporarilyProtect.clear();
        int result = sumAbstractRec(id(a), field);
        runSafePointMaintenance(result);
        return wrap(result);
    }

    /**
     * Sum several fields in one traversal while preserving all other fields.
     * Fields must be strictly increasing, but may be interleaved with retained
     * fields.  This avoids repeatedly traversing a materialized product.
     */
    public static NDD sumAbstractFields(NDD a, int[] fields) {
        if (a == null) throw new IllegalArgumentException("NDD must not be null");
        if (fields == null) throw new IllegalArgumentException("fields must not be null");
        int previous = -1;
        for (int field : fields) {
            validateField(field);
            if (field <= previous) {
                throw new IllegalArgumentException("fields must be strictly increasing");
            }
            previous = field;
        }
        temporarilyProtect.clear();
        ArrayList<HashMap<Integer, Integer>> caches = new ArrayList<>(fields.length + 1);
        for (int i = 0; i <= fields.length; i++) caches.add(new HashMap<>());
        int result = sumAbstractFieldsRec(id(a), fields, 0, caches);
        runSafePointMaintenance(result);
        return wrap(result);
    }

    private static int sumAbstractFieldsRec(int a, int[] fields, int fieldIndex,
            ArrayList<HashMap<Integer, Integer>> caches) {
        if (fieldIndex == fields.length) return a;
        HashMap<Integer, Integer> cache = caches.get(fieldIndex);
        Integer cached = cache.get(a);
        if (cached != null) return cached;

        int currentField = nodeTable.getField(a);
        int field = fields[fieldIndex];
        final int result;
        if (currentField > field) {
            int child = sumAbstractFieldsRec(a, fields, fieldIndex + 1, caches);
            result = multiplyByMultiplicity(child, fieldCardinality(field));
        } else if (currentField == field) {
            int sum = FALSE_ID;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                long multiplicity = Math.round(getLabelSatCount(
                        field, nodeTable.getEdgeLabel(a, i)));
                if (multiplicity == 0L) continue;
                int child = sumAbstractFieldsRec(
                        nodeTable.getEdgeTarget(a, i), fields, fieldIndex + 1, caches);
                int weighted = multiplyByMultiplicity(child, multiplicity);
                sum = addRec(sum, weighted);
                temporarilyProtect.add(sum);
            }
            result = sum;
        } else {
            int frameStart = stackTop;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                int child = sumAbstractFieldsRec(
                        nodeTable.getEdgeTarget(a, i), fields, fieldIndex, caches);
                edgeCollect(frameStart, currentField, child,
                        refLabel(currentField, nodeTable.getEdgeLabel(a, i)));
            }
            result = edgeFlush(frameStart, currentField);
        }
        temporarilyProtect.add(result);
        cache.put(a, result);
        return result;
    }

    /**
     * Rename every field occurring in {@code a}.  Both field lists must be
     * strictly increasing, have matching widths/backends, and the source DD may
     * not contain an unlisted field.  The increasing-order restriction keeps the
     * operation a linear DAG rebuild rather than a general variable permutation.
     */
    public static NDD renameFields(NDD a, int[] fromFields, int[] toFields) {
        if (a == null) throw new IllegalArgumentException("NDD must not be null");
        if (fromFields == null || toFields == null
                || fromFields.length != toFields.length) {
            throw new IllegalArgumentException("field lists must have equal length");
        }
        int previousFrom = -1;
        int previousTo = -1;
        for (int i = 0; i < fromFields.length; i++) {
            validateField(fromFields[i]);
            validateField(toFields[i]);
            if (fromFields[i] <= previousFrom || toFields[i] <= previousTo) {
                throw new IllegalArgumentException("field lists must be strictly increasing");
            }
            if (pendingFieldBitNums.get(fromFields[i]).intValue()
                    != pendingFieldBitNums.get(toFields[i]).intValue()
                    || fieldMode(fromFields[i]) != fieldMode(toFields[i])) {
                throw new IllegalArgumentException("renamed fields must have matching layouts");
            }
            previousFrom = fromFields[i];
            previousTo = toFields[i];
        }
        temporarilyProtect.clear();
        HashMap<Integer, Integer> cache = new HashMap<>();
        int result = renameFieldsRec(id(a), fromFields, toFields, cache);
        runSafePointMaintenance(result);
        return wrap(result);
    }

    private static int renameFieldsRec(int a, int[] fromFields, int[] toFields,
            HashMap<Integer, Integer> cache) {
        if (isTerminalId(a)) return a;
        Integer cached = cache.get(a);
        if (cached != null) return cached;
        int sourceField = nodeTable.getField(a);
        int position = Arrays.binarySearch(fromFields, sourceField);
        if (position < 0) {
            throw new IllegalArgumentException("source DD contains unlisted field " + sourceField);
        }
        int targetField = toFields[position];
        int frameStart = stackTop;
        int count = nodeTable.getEdgeCount(a);
        for (int i = 0; i < count; i++) {
            int child = renameFieldsRec(
                    nodeTable.getEdgeTarget(a, i), fromFields, toFields, cache);
            collectTranslatedLabelEdges(frameStart, child, sourceField, targetField,
                    nodeTable.getEdgeLabel(a, i));
        }
        int result = edgeFlush(frameStart, targetField);
        temporarilyProtect.add(result);
        cache.put(a, result);
        return result;
    }

    private static void collectTranslatedLabelEdges(
            int frameStart, int child, int sourceField, int targetField, int sourceLabel) {
        LabelDecisionDiagramBackend source = backendForField(sourceField);
        LabelDecisionDiagramBackend target = backendForField(targetField);
        int capacity = source.assignmentCapacity();
        if (capacity == 0) {
            long assignments = fieldCardinality(sourceField);
            if (assignments > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("field is too wide to rename");
            }
            for (int assignment = 0; assignment < assignments; assignment++) {
                int sourceAssignment = encodeValueLabel(assignment, sourceField);
                boolean matches = source.matches(sourceLabel, sourceAssignment);
                derefLabel(sourceField, sourceAssignment);
                if (matches) {
                    edgeCollect(frameStart, targetField, child,
                            encodeValueLabel(assignment, targetField));
                }
            }
            return;
        }
        if (capacity != target.assignmentCapacity()) {
            throw new IllegalArgumentException("renamed fields have incompatible capacities");
        }
        int assignmentFrame = assignmentTop;
        ensureAssignmentCapacity(capacity);
        Arrays.fill(assignmentTargets, assignmentTop, assignmentTop + capacity, FALSE_ID);
        source.fillAssignmentTargets(sourceLabel, TRUE_ID, assignmentTargets, assignmentTop);
        for (int assignment = 0; assignment < capacity; assignment++) {
            if (assignmentTargets[assignmentTop + assignment] != TRUE_ID) continue;
            edgeCollect(frameStart, targetField, child,
                    refLabel(targetField, target.assignmentLabel(assignment)));
        }
        assignmentTop = assignmentFrame;
    }

    /**
     * Compute {@code sum_field(a * b)} without materializing the complete product.
     * The per-call cache is cleared because the abstracted field is implicit in its key.
     */
    public static NDD multiplySumAbstract(NDD a, NDD b, int field) {
        return multiplySumAbstract(a, b, field, 0);
    }

    /**
     * Fused multiply/sum with optional downstream field chunking. Chunking is
     * intended for very large products: completed branches are explicitly
     * rooted while caches and unreachable construction debris are reclaimed.
     */
    public static NDD multiplySumAbstract(
            NDD a, NDD b, int field, int chunkDepth) {
        validateField(field);
        if (chunkDepth < 0) {
            throw new IllegalArgumentException("chunk depth must be nonnegative");
        }
        temporarilyProtect.clear();
        multiplySumAbstractCache.clear();
        int result = multiplySumAbstractRec(id(a), id(b), field, chunkDepth);
        runSafePointMaintenance(result);
        return wrap(result);
    }

    /** Statistics for the most recent whole-bucket product/sum contraction. */
    public static final class NaryContractionStats {
        private static final NaryContractionStats EMPTY =
                new NaryContractionStats(0, 0L, 0L, 0L);

        public final int factorCount;
        public final long fieldCardinality;
        public final long distinctTargetTuples;
        public final long productComputations;

        private NaryContractionStats(int factorCount, long fieldCardinality,
                long distinctTargetTuples, long productComputations) {
            this.factorCount = factorCount;
            this.fieldCardinality = fieldCardinality;
            this.distinctTargetTuples = distinctTargetTuples;
            this.productComputations = productComputations;
        }
    }

    /** Return statistics from the last {@link #contractAndSumAbstract} call. */
    public static NaryContractionStats getLastNaryContractionStats() {
        return lastNaryContractionStats;
    }

    public static final class ScalarContractionStats {
        private static final ScalarContractionStats EMPTY =
                new ScalarContractionStats(0L, 0L, 0L, 0L, 0);

        public final long recursiveCalls;
        public final long cacheHits;
        public final long cacheEntries;
        public final long targetTuples;
        public final int peakFactorCount;

        private ScalarContractionStats(long recursiveCalls, long cacheHits,
                long cacheEntries, long targetTuples, int peakFactorCount) {
            this.recursiveCalls = recursiveCalls;
            this.cacheHits = cacheHits;
            this.cacheEntries = cacheEntries;
            this.targetTuples = targetTuples;
            this.peakFactorCount = peakFactorCount;
        }
    }

    private static ScalarContractionStats lastScalarContractionStats =
            ScalarContractionStats.EMPTY;

    public static ScalarContractionStats getLastScalarContractionStats() {
        return lastScalarContractionStats;
    }

    /**
     * Exactly contract a product of double-terminal NDDs to a scalar while
     * summing the specified fields.  Unlike a sequence of apply/abstract calls,
     * this routine never materializes an intermediate DD over the retained
     * fields.  It memoizes downstream target tuples produced by the label
     * partitions at each abstracted field.
     */
    public static double contractAllSumProductDouble(
            NDD[] factors, int[] abstractFields, int cacheLimit) {
        if (terminalMode != TerminalMode.DOUBLE) {
            throw new IllegalStateException(
                    "lazy scalar contraction currently requires double terminals");
        }
        if (factors == null || factors.length == 0) {
            throw new IllegalArgumentException("at least one factor is required");
        }
        if (abstractFields == null) {
            throw new IllegalArgumentException("abstract fields must not be null");
        }
        int previous = -1;
        for (int field : abstractFields) {
            validateField(field);
            if (field <= previous) {
                throw new IllegalArgumentException(
                        "abstract fields must be strictly increasing");
            }
            previous = field;
        }
        int[] roots = new int[factors.length];
        for (int i = 0; i < factors.length; i++) {
            if (factors[i] == null) throw new IllegalArgumentException("null factor");
            roots[i] = id(factors[i]);
        }
        ScalarContractionContext context = new ScalarContractionContext(
                abstractFields.length, cacheLimit);
        int[] normalized = normalizeProductTuple(roots, roots.length);
        double result = contractAllSumProductDoubleRec(
                normalized, abstractFields, 0, context);
        lastScalarContractionStats = new ScalarContractionStats(
                context.recursiveCalls, context.cacheHits, context.cacheEntries,
                context.targetTuples, context.peakFactorCount);
        return result;
    }

    private static double contractAllSumProductDoubleRec(
            int[] factors, int[] abstractFields, int fieldIndex,
            ScalarContractionContext context) {
        context.recursiveCalls++;
        context.peakFactorCount = Math.max(context.peakFactorCount, factors.length);
        if (Boolean.getBoolean("mtndd.probabilityTraceBuckets")
                && (context.recursiveCalls & ((1L << 20) - 1L)) == 0L) {
            System.err.printf("lazy_progress calls=%d cache_hits=%d cache_entries=%d "
                    + "target_tuples=%d field_index=%d factors=%d%n",
                    context.recursiveCalls, context.cacheHits, context.cacheEntries,
                    context.targetTuples, fieldIndex, factors.length);
        }
        if (isZeroTuple(factors)) return 0.0;

        // Terminal leaves are scalar coefficients, not structural state.  Keeping
        // their node ids in the tuple makes otherwise identical downstream
        // subproblems look different for every numeric path and destroys lazy
        // memoization.  Pull them out before the cache lookup.
        int nonterminalCount = 0;
        double coefficient = 1.0;
        for (int factor : factors) {
            if (isTerminalId(factor)) coefficient *= valueDouble(factor);
            else nonterminalCount++;
        }
        if (coefficient == 0.0) return 0.0;
        if (nonterminalCount != factors.length) {
            int[] structural = new int[nonterminalCount];
            int cursor = 0;
            for (int factor : factors) {
                if (!isTerminalId(factor)) structural[cursor++] = factor;
            }
            return coefficient * contractAllSumProductDoubleRec(
                    structural, abstractFields, fieldIndex, context);
        }

        HashMap<IntArrayKey, Double> cache = context.cacheByField.get(fieldIndex);
        IntArrayKey lookup = new IntArrayKey(factors, false);
        Double cached = cache.get(lookup);
        if (cached != null) {
            context.cacheHits++;
            return cached;
        }

        final double result;
        if (fieldIndex == abstractFields.length) {
            if (factors.length != 0) {
                throw new IllegalStateException(
                        "lazy scalar contraction left field "
                        + nodeTable.getField(factors[0]));
            }
            result = 1.0;
        } else {
            int field = abstractFields[fieldIndex];
            int currentField = minimumField(factors);
            if (currentField < field) {
                throw new IllegalStateException(
                        "unlisted field " + currentField
                        + " precedes abstract field " + field);
            }
            if (currentField > field) {
                result = fieldCardinality(field)
                        * contractAllSumProductDoubleRec(
                                factors, abstractFields, fieldIndex + 1, context);
            } else {
                TupleMultiplicity groups = groupTargetsAtAbstractedField(factors, field);
                context.targetTuples += groups.counts.size();
                double sum = 0.0;
                for (Map.Entry<IntArrayKey, Long> entry : groups.counts.entrySet()) {
                    sum += entry.getValue()
                            * contractAllSumProductDoubleRec(
                                    entry.getKey().values, abstractFields,
                                    fieldIndex + 1, context);
                }
                result = sum;
            }
        }
        if (context.cacheEntries < context.cacheLimit) {
            cache.put(new IntArrayKey(factors, true), result);
            context.cacheEntries++;
        }
        return result;
    }

    private static final class ScalarContractionContext {
        final ArrayList<HashMap<IntArrayKey, Double>> cacheByField;
        final long cacheLimit;
        long recursiveCalls;
        long cacheHits;
        long cacheEntries;
        long targetTuples;
        int peakFactorCount;

        ScalarContractionContext(int fieldCount, int cacheLimit) {
            this.cacheLimit = Math.max(0, cacheLimit);
            cacheByField = new ArrayList<>(fieldCount + 1);
            for (int i = 0; i <= fieldCount; i++) {
                cacheByField.add(new HashMap<>());
            }
        }
    }

    /**
     * Contract several fields of a factorized product while retaining every
     * other field as one NDD.  Abstracted and retained fields may be interleaved;
     * this permits a fused matrix/vector product under the usual
     * {@code current,next,current,next,...} variable order without materializing
     * the full pointwise product.
     */
    public static NDD contractAndSumAbstractFields(
            NDD[] factors, int[] abstractFields, int cacheLimit) {
        if (factors == null || factors.length == 0) {
            throw new IllegalArgumentException("at least one factor is required");
        }
        if (abstractFields == null || abstractFields.length == 0) {
            throw new IllegalArgumentException("at least one abstract field is required");
        }
        int previous = -1;
        for (int field : abstractFields) {
            validateField(field);
            if (field <= previous) {
                throw new IllegalArgumentException(
                        "abstract fields must be strictly increasing");
            }
            previous = field;
        }
        int[] roots = new int[factors.length];
        for (int i = 0; i < factors.length; i++) {
            if (factors[i] == null) throw new IllegalArgumentException("null factor");
            roots[i] = id(factors[i]);
        }
        temporarilyProtect.clear();
        FactorizedContractionContext context = new FactorizedContractionContext(
                abstractFields.length, cacheLimit);
        int[] normalized = normalizeProductTuple(roots, roots.length);
        int result = contractAndSumAbstractFieldsRec(
                normalized, abstractFields, 0, context);
        nodeTable.ref(result);
        context.releaseCachedRoots();
        clearCaches();
        temporarilyProtect.clear();
        nodeTable.gc();
        nodeTable.compactEdgesAtSafePoint();
        nodeTable.deref(result);
        lastScalarContractionStats = new ScalarContractionStats(
                context.recursiveCalls, context.cacheHits, context.cacheEntries,
                context.targetTuples, context.peakFactorCount);
        runSafePointMaintenance(result);
        return wrap(result);
    }

    private static int contractAndSumAbstractFieldsRec(
            int[] factors, int[] abstractFields, int fieldIndex,
            FactorizedContractionContext context) {
        context.recursiveCalls++;
        context.peakFactorCount = Math.max(context.peakFactorCount, factors.length);
        if (isZeroTuple(factors)) return FALSE_ID;

        int nonterminalCount = 0;
        int coefficient = TRUE_ID;
        for (int factor : factors) {
            if (isTerminalId(factor)) coefficient = mulRec(coefficient, factor);
            else nonterminalCount++;
        }
        if (coefficient == FALSE_ID) return FALSE_ID;
        if (nonterminalCount != factors.length) {
            int[] structural = new int[nonterminalCount];
            int cursor = 0;
            for (int factor : factors) {
                if (!isTerminalId(factor)) structural[cursor++] = factor;
            }
            nodeTable.ref(coefficient);
            int child = contractAndSumAbstractFieldsRec(
                    structural, abstractFields, fieldIndex, context);
            nodeTable.ref(child);
            int scaled = mulRec(coefficient, child);
            nodeTable.deref(child);
            nodeTable.deref(coefficient);
            return scaled;
        }

        HashMap<IntArrayKey, Integer> cache = context.cacheByField.get(fieldIndex);
        IntArrayKey lookup = new IntArrayKey(factors, false);
        Integer cached = cache.get(lookup);
        if (cached != null) {
            context.cacheHits++;
            return cached;
        }

        final int result;
        if (fieldIndex == abstractFields.length) {
            result = multiplyManyRec(factors, context.productContext, 0);
        } else {
            int field = abstractFields[fieldIndex];
            int currentField = minimumField(factors);
            if (currentField < field) {
                result = retainFactorizedField(
                        factors, abstractFields, fieldIndex, currentField, context);
            } else if (currentField > field) {
                int child = contractAndSumAbstractFieldsRec(
                        factors, abstractFields, fieldIndex + 1, context);
                nodeTable.ref(child);
                result = multiplyByMultiplicity(child, fieldCardinality(field));
                nodeTable.deref(child);
            } else {
                TupleMultiplicity groups = groupTargetsAtAbstractedField(factors, field);
                context.targetTuples += groups.counts.size();
                int sum = FALSE_ID;
                nodeTable.ref(sum);
                for (Map.Entry<IntArrayKey, Long> entry : groups.counts.entrySet()) {
                    int target = contractAndSumAbstractFieldsRec(
                            entry.getKey().values, abstractFields,
                            fieldIndex + 1, context);
                    nodeTable.ref(target);
                    int weighted = multiplyByMultiplicity(target, entry.getValue());
                    nodeTable.ref(weighted);
                    int next = addRec(sum, weighted);
                    nodeTable.ref(next);
                    nodeTable.deref(weighted);
                    nodeTable.deref(target);
                    nodeTable.deref(sum);
                    sum = next;
                }
                result = sum;
                nodeTable.deref(sum);
            }
        }
        if (context.cacheEntries < context.cacheLimit) {
            cache.put(new IntArrayKey(factors, true), result);
            nodeTable.ref(result);
            context.cachedRoots.add(result);
            context.cacheEntries++;
        }
        return result;
    }

    private static int retainFactorizedField(
            int[] factors, int[] abstractFields, int fieldIndex, int field,
            FactorizedContractionContext context) {
        int frameStart = stackTop;
        LabelDecisionDiagramBackend backend = backendForField(field);
        int capacity = backend.assignmentCapacity();
        if (capacity != 0) {
            collectRetainedAssignmentEdges(factors, abstractFields, fieldIndex,
                    field, backend, capacity, frameStart, context);
        } else {
            int universe = refLabel(field, getFieldUniverseLabel(field));
            int[] targets = new int[factors.length];
            collectRetainedIntersectionEdges(factors, targets, 0, abstractFields,
                    fieldIndex, field, universe, frameStart, context);
            derefLabel(field, universe);
        }
        return edgeFlush(frameStart, field);
    }

    private static void collectRetainedAssignmentEdges(
            int[] factors, int[] abstractFields, int fieldIndex, int field,
            LabelDecisionDiagramBackend backend, int capacity, int frameStart,
            FactorizedContractionContext context) {
        int assignmentFrame = assignmentTop;
        ensureAssignmentCapacity(capacity * factors.length);
        int targetBase = assignmentTop;
        assignmentTop += capacity * factors.length;
        Arrays.fill(assignmentTargets, targetBase, assignmentTop, FALSE_ID);

        for (int i = 0; i < factors.length; i++) {
            int base = targetBase + i * capacity;
            if (nodeTable.getField(factors[i]) > field) {
                Arrays.fill(assignmentTargets, base, base + capacity, factors[i]);
                continue;
            }
            int count = nodeTable.getEdgeCount(factors[i]);
            for (int edge = 0; edge < count; edge++) {
                backend.fillAssignmentTargets(nodeTable.getEdgeLabel(factors[i], edge),
                        nodeTable.getEdgeTarget(factors[i], edge), assignmentTargets, base);
            }
        }

        int[] tuple = new int[factors.length];
        for (int assignment = 0; assignment < capacity; assignment++) {
            for (int i = 0; i < factors.length; i++) {
                tuple[i] = assignmentTargets[targetBase + i * capacity + assignment];
            }
            int[] normalized = normalizeProductTuple(tuple, tuple.length);
            if (isZeroTuple(normalized)) continue;
            int target = contractAndSumAbstractFieldsRec(
                    normalized, abstractFields, fieldIndex, context);
            if (target != FALSE_ID) {
                edgeCollect(frameStart, field, target,
                        refLabel(field, backend.assignmentLabel(assignment)));
            }
        }
        assignmentTop = assignmentFrame;
    }

    private static void collectRetainedIntersectionEdges(
            int[] factors, int[] targets, int index, int[] abstractFields,
            int fieldIndex, int field, int intersection, int frameStart,
            FactorizedContractionContext context) {
        if (index == factors.length) {
            int[] normalized = normalizeProductTuple(targets, targets.length);
            if (!isZeroTuple(normalized)) {
                int target = contractAndSumAbstractFieldsRec(
                        normalized, abstractFields, fieldIndex, context);
                if (target != FALSE_ID) {
                    edgeCollect(frameStart, field, target, refLabel(field, intersection));
                }
            }
            return;
        }
        int factor = factors[index];
        if (nodeTable.getField(factor) > field) {
            targets[index] = factor;
            collectRetainedIntersectionEdges(factors, targets, index + 1,
                    abstractFields, fieldIndex, field, intersection, frameStart, context);
            return;
        }
        int count = nodeTable.getEdgeCount(factor);
        for (int edge = 0; edge < count; edge++) {
            int next = refLabel(field, labelAnd(
                    field, intersection, nodeTable.getEdgeLabel(factor, edge)));
            if (next != 0) {
                targets[index] = nodeTable.getEdgeTarget(factor, edge);
                collectRetainedIntersectionEdges(factors, targets, index + 1,
                        abstractFields, fieldIndex, field, next, frameStart, context);
            }
            derefLabel(field, next);
        }
    }

    private static final class FactorizedContractionContext {
        final ArrayList<HashMap<IntArrayKey, Integer>> cacheByField;
        final long cacheLimit;
        final NaryProductContext productContext;
        final ArrayList<Integer> cachedRoots = new ArrayList<>();
        long recursiveCalls;
        long cacheHits;
        long cacheEntries;
        long targetTuples;
        int peakFactorCount;

        FactorizedContractionContext(int fieldCount, int cacheLimit) {
            this.cacheLimit = Math.max(0, cacheLimit);
            this.productContext = new NaryProductContext(cacheLimit);
            cacheByField = new ArrayList<>(fieldCount + 1);
            for (int i = 0; i <= fieldCount; i++) {
                cacheByField.add(new HashMap<>());
            }
        }

        void releaseCachedRoots() {
            productContext.cache.clear();
            for (int root : cachedRoots) nodeTable.deref(root);
            cachedRoots.clear();
        }
    }

    /** Count nonzero downstream target tuples without constructing numeric products. */
    public static long estimateContractionTargetTuples(NDD[] factors, int field) {
        validateField(field);
        if (factors == null || factors.length == 0) {
            throw new IllegalArgumentException("at least one factor is required");
        }
        int[] roots = new int[factors.length];
        for (int i = 0; i < factors.length; i++) {
            if (factors[i] == null) throw new IllegalArgumentException("null factor");
            roots[i] = id(factors[i]);
            if (nodeTable.getField(roots[i]) < field) return -1L;
        }
        int[] normalized = normalizeProductTuple(roots, roots.length);
        if (isZeroTuple(normalized)) return 0L;
        if (minimumField(normalized) > field) return 1L;
        return groupTargetsAtAbstractedField(normalized, field).counts.size();
    }

    /**
     * Compute {@code sum_field(product(factors))} as one whole-bucket contraction.
     * When the field is the first remaining field, equal tuples of downstream
     * targets are grouped before their product is built. An optional bounded depth
     * keeps downstream construction n-ary before returning to pairwise apply.
     */
    public static NDD contractAndSumAbstract(NDD[] factors, int field) {
        return contractAndSumAbstract(factors, field, 0);
    }

    /** Whole-bucket contraction with a bounded number of n-ary downstream fields. */
    public static NDD contractAndSumAbstract(
            NDD[] factors, int field, int downstreamNaryDepth) {
        validateField(field);
        if (downstreamNaryDepth < 0) {
            throw new IllegalArgumentException("downstream n-ary depth must be nonnegative");
        }
        if (factors == null || factors.length == 0) {
            throw new IllegalArgumentException("at least one factor is required");
        }
        int[] roots = new int[factors.length];
        for (int i = 0; i < factors.length; i++) {
            if (factors[i] == null) throw new IllegalArgumentException("null factor");
            roots[i] = id(factors[i]);
        }

        temporarilyProtect.clear();
        NaryProductContext context = new NaryProductContext(CACHE_SIZE);
        int[] normalized = normalizeProductTuple(roots, roots.length);
        int currentField = minimumField(normalized);
        long distinctTuples = 0L;
        int result;
        if (isZeroTuple(normalized)) {
            result = FALSE_ID;
        } else if (currentField < field) {
            // Correct fallback for callers that do not obey bucket-elimination order.
            result = sumAbstractRec(
                    multiplyManyRec(normalized, context, downstreamNaryDepth), field);
        } else if (currentField > field) {
            result = multiplyByMultiplicity(
                    multiplyManyRec(normalized, context, downstreamNaryDepth),
                    fieldCardinality(field));
        } else {
            TupleMultiplicity groups = groupTargetsAtAbstractedField(
                    normalized, field);
            distinctTuples = groups.counts.size();
            result = FALSE_ID;
            int groupsSinceMaintenance = 0;
            for (Map.Entry<IntArrayKey, Long> entry : groups.counts.entrySet()) {
                int product = multiplyManyRec(
                        entry.getKey().values, context, downstreamNaryDepth);
                int weighted = multiplyByMultiplicity(product, entry.getValue());
                result = addRec(result, weighted);
                temporarilyProtect.add(result);
                if (++groupsSinceMaintenance == 8) {
                    naryContractionSafePoint(result, context);
                    groupsSinceMaintenance = 0;
                }
            }
        }
        temporarilyProtect.add(result);
        lastNaryContractionStats = new NaryContractionStats(
                factors.length, fieldCardinality(field), distinctTuples,
                context.productsComputed);
        runSafePointMaintenance(result);
        return wrap(result);
    }

    private static void naryContractionSafePoint(
            int liveRoot, NaryProductContext context) {
        // A whole-bucket call may add dozens of distinct target products. Without
        // an internal safe point, all superseded sums remain temporarily protected
        // until the public call returns and terminal storage can exhaust the heap.
        nodeTable.ref(liveRoot);
        temporarilyProtect.clear();
        temporarilyProtect.add(liveRoot);
        context.cache.clear();
        clearCaches();
        nodeTable.gc();
        nodeTable.compactEdgesAtSafePoint();
        nodeTable.deref(liveRoot);
    }

    private static int multiplyManyRec(int[] factors, NaryProductContext context,
            int remainingNaryDepth) {
        if (isZeroTuple(factors)) return FALSE_ID;
        if (factors.length == 0) return TRUE_ID;
        if (factors.length == 1) return factors[0];

        IntArrayKey lookup = new IntArrayKey(factors, false);
        Integer cached = context.cache.get(lookup);
        if (cached != null) return cached;

        int currentField = minimumField(factors);
        if (remainingNaryDepth > 0 && currentField != NodeTable.TERMINAL_FIELD) {
            int frameStart = stackTop;
            LabelDecisionDiagramBackend backend = backendForField(currentField);
            int capacity = backend.assignmentCapacity();
            if (capacity != 0) {
                collectNaryAssignmentEdges(factors, currentField, backend,
                        capacity, frameStart, context, remainingNaryDepth - 1);
            } else {
                int universe = refLabel(currentField, getFieldUniverseLabel(currentField));
                int[] targets = new int[factors.length];
                collectNaryIntersectionEdges(factors, targets, 0, currentField,
                        universe, frameStart, context, remainingNaryDepth - 1);
                derefLabel(currentField, universe);
            }
            int result = edgeFlush(frameStart, currentField);
            context.productsComputed++;
            temporarilyProtect.add(result);
            if (context.cache.size() < context.limit) {
                context.cache.put(new IntArrayKey(factors, true), result);
            }
            return result;
        }

        // At the hybrid frontier, multiply smaller roots first and let the mature
        // pairwise apply caches retain decision-diagram sharing.
        int[] ordered = Arrays.copyOf(factors, factors.length);
        for (int i = 1; i < ordered.length; i++) {
            int value = ordered[i];
            int weight = productRootWeight(value);
            int j = i - 1;
            while (j >= 0 && productRootWeight(ordered[j]) > weight) {
                ordered[j + 1] = ordered[j];
                j--;
            }
            ordered[j + 1] = value;
        }
        int result = TRUE_ID;
        for (int factor : ordered) result = mulRec(result, factor);
        context.productsComputed++;
        temporarilyProtect.add(result);
        if (context.cache.size() < context.limit) {
            context.cache.put(new IntArrayKey(factors, true), result);
        }
        return result;
    }

    private static int productRootWeight(int node) {
        return isTerminalId(node) ? 0 : nodeTable.getEdgeCount(node);
    }

    private static void collectNaryAssignmentEdges(int[] factors, int field,
            LabelDecisionDiagramBackend backend, int capacity, int frameStart,
            NaryProductContext context, int remainingNaryDepth) {
        int assignmentFrame = assignmentTop;
        ensureAssignmentCapacity(capacity * factors.length);
        int targetBase = assignmentTop;
        assignmentTop += capacity * factors.length;
        Arrays.fill(assignmentTargets, targetBase, assignmentTop, FALSE_ID);

        for (int i = 0; i < factors.length; i++) {
            int base = targetBase + i * capacity;
            if (nodeTable.getField(factors[i]) > field) {
                Arrays.fill(assignmentTargets, base, base + capacity, factors[i]);
                continue;
            }
            int count = nodeTable.getEdgeCount(factors[i]);
            for (int edge = 0; edge < count; edge++) {
                backend.fillAssignmentTargets(nodeTable.getEdgeLabel(factors[i], edge),
                        nodeTable.getEdgeTarget(factors[i], edge), assignmentTargets, base);
            }
        }

        int[] tuple = new int[factors.length];
        for (int assignment = 0; assignment < capacity; assignment++) {
            for (int i = 0; i < factors.length; i++) {
                tuple[i] = assignmentTargets[targetBase + i * capacity + assignment];
            }
            int[] normalized = normalizeProductTuple(tuple, tuple.length);
            if (isZeroTuple(normalized)) continue;
            int target = multiplyManyRec(normalized, context, remainingNaryDepth);
            if (target != FALSE_ID) {
                edgeCollect(frameStart, field, target,
                        refLabel(field, backend.assignmentLabel(assignment)));
            }
        }
        assignmentTop = assignmentFrame;
    }

    private static void collectNaryIntersectionEdges(int[] factors, int[] targets,
            int index, int field, int intersection, int frameStart,
            NaryProductContext context, int remainingNaryDepth) {
        if (index == factors.length) {
            int[] normalized = normalizeProductTuple(targets, targets.length);
            if (!isZeroTuple(normalized)) {
                int target = multiplyManyRec(normalized, context, remainingNaryDepth);
                if (target != FALSE_ID) {
                    edgeCollect(frameStart, field, target, refLabel(field, intersection));
                }
            }
            return;
        }
        int factor = factors[index];
        if (nodeTable.getField(factor) > field) {
            targets[index] = factor;
            collectNaryIntersectionEdges(factors, targets, index + 1, field,
                    intersection, frameStart, context, remainingNaryDepth);
            return;
        }
        int count = nodeTable.getEdgeCount(factor);
        for (int edge = 0; edge < count; edge++) {
            int next = refLabel(field, labelAnd(
                    field, intersection, nodeTable.getEdgeLabel(factor, edge)));
            if (next != 0) {
                targets[index] = nodeTable.getEdgeTarget(factor, edge);
                collectNaryIntersectionEdges(factors, targets, index + 1, field,
                        next, frameStart, context, remainingNaryDepth);
            }
            derefLabel(field, next);
        }
    }

    private static TupleMultiplicity groupTargetsAtAbstractedField(
            int[] factors, int field) {
        LabelDecisionDiagramBackend backend = backendForField(field);
        int capacity = backend.assignmentCapacity();
        TupleMultiplicity result = new TupleMultiplicity();
        if (capacity == 0) {
            int universe = refLabel(field, getFieldUniverseLabel(field));
            int[] targets = new int[factors.length];
            groupNaryIntersections(factors, targets, 0, field, universe, result);
            derefLabel(field, universe);
            return result;
        }

        int assignmentFrame = assignmentTop;
        ensureAssignmentCapacity(capacity * factors.length);
        int targetBase = assignmentTop;
        assignmentTop += capacity * factors.length;
        Arrays.fill(assignmentTargets, targetBase, assignmentTop, FALSE_ID);
        for (int i = 0; i < factors.length; i++) {
            int base = targetBase + i * capacity;
            if (nodeTable.getField(factors[i]) > field) {
                Arrays.fill(assignmentTargets, base, base + capacity, factors[i]);
                continue;
            }
            int count = nodeTable.getEdgeCount(factors[i]);
            for (int edge = 0; edge < count; edge++) {
                backend.fillAssignmentTargets(nodeTable.getEdgeLabel(factors[i], edge),
                        nodeTable.getEdgeTarget(factors[i], edge), assignmentTargets, base);
            }
        }

        int[] tuple = new int[factors.length];
        long physicalPerLogical = 1L << (backendContexts[fieldMode(field).ordinal()].maxWidth
                - pendingFieldBitNums.get(field));
        for (int assignment = 0; assignment < capacity; assignment++) {
            for (int i = 0; i < factors.length; i++) {
                tuple[i] = assignmentTargets[targetBase + i * capacity + assignment];
            }
            int[] normalized = normalizeProductTuple(tuple, tuple.length);
            if (!isZeroTuple(normalized)) result.add(normalized, 1L);
        }
        assignmentTop = assignmentFrame;
        if (physicalPerLogical != 1L) result.divideCounts(physicalPerLogical);
        return result;
    }

    private static void groupNaryIntersections(int[] factors, int[] targets,
            int index, int field, int intersection, TupleMultiplicity result) {
        if (index == factors.length) {
            int[] normalized = normalizeProductTuple(targets, targets.length);
            if (!isZeroTuple(normalized)) {
                long multiplicity = Math.round(getLabelSatCount(field, intersection));
                if (multiplicity != 0L) result.add(normalized, multiplicity);
            }
            return;
        }
        int factor = factors[index];
        if (nodeTable.getField(factor) > field) {
            targets[index] = factor;
            groupNaryIntersections(factors, targets, index + 1,
                    field, intersection, result);
            return;
        }
        int count = nodeTable.getEdgeCount(factor);
        for (int edge = 0; edge < count; edge++) {
            int next = refLabel(field, labelAnd(
                    field, intersection, nodeTable.getEdgeLabel(factor, edge)));
            if (next != 0) {
                targets[index] = nodeTable.getEdgeTarget(factor, edge);
                groupNaryIntersections(factors, targets, index + 1, field, next, result);
            }
            derefLabel(field, next);
        }
    }

    private static int[] normalizeProductTuple(int[] values, int length) {
        int live = 0;
        for (int i = 0; i < length; i++) {
            if (values[i] == FALSE_ID) return new int[] {FALSE_ID};
            if (values[i] != TRUE_ID) live++;
        }
        if (live == 0) return new int[0];
        int[] result = new int[live];
        int cursor = 0;
        for (int i = 0; i < length; i++) {
            if (values[i] != TRUE_ID) result[cursor++] = values[i];
        }
        // Preserve bucket-factor order. It is stable throughout the recursive
        // contraction, so sorting every assignment tuple only adds O(m log m)
        // work and is not required for cache correctness.
        return result;
    }

    private static boolean isZeroTuple(int[] values) {
        return values.length == 1 && values[0] == FALSE_ID;
    }

    private static int minimumField(int[] values) {
        int result = NodeTable.TERMINAL_FIELD;
        for (int value : values) result = Math.min(result, nodeTable.getField(value));
        return result;
    }

    private static final class NaryProductContext {
        final int limit;
        final HashMap<IntArrayKey, Integer> cache = new HashMap<>();
        long productsComputed;

        NaryProductContext(int limit) {
            this.limit = Math.max(1, limit);
        }
    }

    private static final class TupleMultiplicity {
        final HashMap<IntArrayKey, Long> counts = new HashMap<>();

        void add(int[] tuple, long count) {
            IntArrayKey key = new IntArrayKey(tuple, true);
            counts.merge(key, count, Long::sum);
        }

        void divideCounts(long divisor) {
            for (Map.Entry<IntArrayKey, Long> entry : counts.entrySet()) {
                long count = entry.getValue();
                if (count % divisor != 0L) {
                    throw new IllegalStateException("assignment multiplicity is not integral");
                }
                entry.setValue(count / divisor);
            }
        }
    }

    private static final class IntArrayKey {
        final int[] values;
        final int hash;

        IntArrayKey(int[] values, boolean copy) {
            this.values = copy ? Arrays.copyOf(values, values.length) : values;
            this.hash = Arrays.hashCode(values);
        }

        @Override
        public int hashCode() { return hash; }

        @Override
        public boolean equals(Object other) {
            return other instanceof IntArrayKey
                    && Arrays.equals(values, ((IntArrayKey) other).values);
        }
    }

    private static int multiplySumAbstractRec(int a, int b, int field, int chunkDepth) {
        if (isZeroId(a) || isZeroId(b)) return FALSE_ID;
        if (isOneId(a)) return sumAbstractRec(b, field);
        if (isOneId(b)) return sumAbstractRec(a, field);
        if (multiplySumAbstractCache.getEntry(a, b)) {
            return multiplySumAbstractCache.result;
        }
        int cacheIndex = multiplySumAbstractCache.hashValue;
        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);
        int currentField = Math.min(aField, bField);
        int result;
        if (currentField > field) {
            result = multiplyByMultiplicity(mulRec(a, b), fieldCardinality(field));
        } else if (currentField == field) {
            result = abstractProductAtField(a, aField, b, bField, field, chunkDepth);
        } else {
            int frameStart = stackTop;
            if (aField == bField) {
                int aCount = nodeTable.getEdgeCount(a);
                int bCount = nodeTable.getEdgeCount(b);
                for (int i = 0; i < aCount; i++) {
                    int aLabel = nodeTable.getEdgeLabel(a, i);
                    int aTarget = nodeTable.getEdgeTarget(a, i);
                    for (int j = 0; j < bCount; j++) {
                        int intersection = refLabel(currentField,
                                labelAnd(currentField, aLabel,
                                    nodeTable.getEdgeLabel(b, j)));
                        if (intersection != 0) {
                            int target = multiplySumAbstractRec(aTarget,
                                    nodeTable.getEdgeTarget(b, j), field, chunkDepth);
                            edgeCollect(frameStart, currentField, target, intersection);
                        } else {
                            derefLabel(currentField, intersection);
                        }
                    }
                }
            } else if (aField < bField) {
                int count = nodeTable.getEdgeCount(a);
                for (int i = 0; i < count; i++) {
                    int target = multiplySumAbstractRec(
                            nodeTable.getEdgeTarget(a, i), b, field, chunkDepth);
                    edgeCollect(frameStart, aField, target,
                            refLabel(aField, nodeTable.getEdgeLabel(a, i)));
                }
            } else {
                int count = nodeTable.getEdgeCount(b);
                for (int i = 0; i < count; i++) {
                    int target = multiplySumAbstractRec(
                            a, nodeTable.getEdgeTarget(b, i), field, chunkDepth);
                    edgeCollect(frameStart, bField, target,
                            refLabel(bField, nodeTable.getEdgeLabel(b, i)));
                }
            }
            result = edgeFlush(frameStart, currentField);
        }
        temporarilyProtect.add(result);
        multiplySumAbstractCache.setEntry(cacheIndex, a, b, result);
        return result;
    }

    private static int abstractProductAtField(int a, int aField,
            int b, int bField, int field, int chunkDepth) {
        int result = FALSE_ID;
        if (aField == field && bField == field) {
            int aCount = nodeTable.getEdgeCount(a);
            int bCount = nodeTable.getEdgeCount(b);
            for (int i = 0; i < aCount; i++) {
                int aLabel = nodeTable.getEdgeLabel(a, i);
                int aTarget = nodeTable.getEdgeTarget(a, i);
                for (int j = 0; j < bCount; j++) {
                    int intersection = refLabel(field,
                            labelAnd(field, aLabel, nodeTable.getEdgeLabel(b, j)));
                    if (intersection != 0) {
                        long multiplicity = Math.round(getLabelSatCount(field, intersection));
                        if (multiplicity != 0) {
                            int product = multiplyChunked(aTarget,
                                    nodeTable.getEdgeTarget(b, j), chunkDepth, result);
                            result = addRec(result,
                                    multiplyByMultiplicity(product, multiplicity));
                            temporarilyProtect.add(result);
                        }
                    }
                    derefLabel(field, intersection);
                }
            }
            return result;
        }

        int node = aField == field ? a : b;
        int other = aField == field ? b : a;
        int count = nodeTable.getEdgeCount(node);
        for (int i = 0; i < count; i++) {
            long multiplicity = Math.round(getLabelSatCount(
                    field, nodeTable.getEdgeLabel(node, i)));
            if (multiplicity == 0) continue;
            int product = multiplyChunked(
                    nodeTable.getEdgeTarget(node, i), other, chunkDepth, result);
            result = addRec(result, multiplyByMultiplicity(product, multiplicity));
            temporarilyProtect.add(result);
        }
        return result;
    }

    private static int multiplyChunked(
            int a, int b, int depth, int protectedRoot) {
        if (depth <= 0 || isZeroId(a) || isZeroId(b)
                || isOneId(a) || isOneId(b)
                || (isTerminalId(a) && isTerminalId(b))) {
            return mulRec(a, b);
        }

        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);
        int currentField = Math.min(aField, bField);
        boolean traceChunks = Boolean.getBoolean("mtndd.probabilityTraceBuckets");
        if (traceChunks) {
            System.err.printf("chunk_start depth=%d field=%d a_field=%d b_field=%d "
                    + "a_edges=%d b_edges=%d live_nodes=%d physical_edges=%d%n",
                    depth, currentField, aField, bField,
                    isTerminalId(a) ? 0 : nodeTable.getEdgeCount(a),
                    isTerminalId(b) ? 0 : nodeTable.getEdgeCount(b),
                    getNodeCount(), nodeTable.getPhysicalEdgeSlots());
        }
        int frameStart = stackTop;
        ArrayList<Integer> branchRoots = new ArrayList<>();
        if (aField == bField) {
            int aCount = nodeTable.getEdgeCount(a);
            int bCount = nodeTable.getEdgeCount(b);
            for (int i = 0; i < aCount; i++) {
                int aLabel = nodeTable.getEdgeLabel(a, i);
                int aTarget = nodeTable.getEdgeTarget(a, i);
                for (int j = 0; j < bCount; j++) {
                    int intersection = refLabel(currentField,
                            labelAnd(currentField, aLabel,
                                    nodeTable.getEdgeLabel(b, j)));
                    if (intersection == 0) {
                        derefLabel(currentField, intersection);
                        continue;
                    }
                    int target = multiplyChunked(aTarget,
                            nodeTable.getEdgeTarget(b, j), depth - 1, protectedRoot);
                    protectChunkBranch(target, branchRoots);
                    edgeCollect(frameStart, currentField, target, intersection);
                    chunkedMultiplySafePoint(protectedRoot);
                }
            }
        } else if (aField < bField) {
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                int target = multiplyChunked(nodeTable.getEdgeTarget(a, i), b,
                        depth - 1, protectedRoot);
                protectChunkBranch(target, branchRoots);
                edgeCollect(frameStart, currentField, target,
                        refLabel(currentField, nodeTable.getEdgeLabel(a, i)));
                chunkedMultiplySafePoint(protectedRoot);
            }
        } else {
            int count = nodeTable.getEdgeCount(b);
            for (int i = 0; i < count; i++) {
                int target = multiplyChunked(a, nodeTable.getEdgeTarget(b, i),
                        depth - 1, protectedRoot);
                protectChunkBranch(target, branchRoots);
                edgeCollect(frameStart, currentField, target,
                        refLabel(currentField, nodeTable.getEdgeLabel(b, i)));
                chunkedMultiplySafePoint(protectedRoot);
            }
        }

        int result = edgeFlush(frameStart, currentField);
        for (int branchRoot : branchRoots) nodeTable.deref(branchRoot);
        temporarilyProtect.add(result);
        if (traceChunks) {
            System.err.printf("chunk_done depth=%d field=%d branches=%d result_field=%d "
                    + "result_edges=%d live_nodes=%d physical_edges=%d%n",
                    depth, currentField, branchRoots.size(), nodeTable.getField(result),
                    isTerminalId(result) ? 0 : nodeTable.getEdgeCount(result),
                    getNodeCount(), nodeTable.getPhysicalEdgeSlots());
        }
        return result;
    }

    private static void protectChunkBranch(int target, ArrayList<Integer> branchRoots) {
        if (target == FALSE_ID) return;
        nodeTable.ref(target);
        branchRoots.add(target);
    }

    private static void chunkedMultiplySafePoint(int protectedRoot) {
        temporarilyProtect.clear();
        temporarilyProtect.add(protectedRoot);
        clearCaches();
        nodeTable.gc();
        nodeTable.compactEdgesAtSafePoint();
    }

    private static int multiplyByMultiplicity(int value, long multiplicity) {
        return multiplicity == 1 ? value
                : mulRec(value, terminal(new Rational(multiplicity)));
    }

    private static int sumAbstractRec(int a, int field) {
        if (sumAbstractCache.getEntryOrdered(a, field)) {
            return sumAbstractCache.result;
        }
        int cacheIndex = sumAbstractCache.hashValue;
        int currentField = nodeTable.getField(a);
        int result;
        if (isTerminalId(a) || currentField > field) {
            result = mulRec(a, terminal(new Rational(fieldCardinality(field))));
        } else if (currentField == field) {
            result = FALSE_ID;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                long multiplicity = Math.round(getLabelSatCount(
                        field, nodeTable.getEdgeLabel(a, i)));
                if (multiplicity == 0) {
                    continue;
                }
                int target = nodeTable.getEdgeTarget(a, i);
                int weighted = multiplyByMultiplicity(target, multiplicity);
                result = addRec(result, weighted);
                temporarilyProtect.add(result);
            }
        } else {
            int frameStart = stackTop;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                int target = sumAbstractRec(nodeTable.getEdgeTarget(a, i), field);
                edgeCollect(frameStart, currentField, target,
                        refLabel(currentField, nodeTable.getEdgeLabel(a, i)));
            }
            result = edgeFlush(frameStart, currentField);
        }
        temporarilyProtect.add(result);
        sumAbstractCache.setEntry(cacheIndex, a, field, result);
        return result;
    }

    private static long fieldCardinality(int field) {
        int bits = pendingFieldBitNums.get(field);
        if (bits >= 63) {
            throw new ArithmeticException("field too wide for exact cardinality: " + bits);
        }
        return 1L << bits;
    }

    private static void combineSameField(int a, int b, int frameStart, boolean isOr, TerminalOp op) {
        int field = nodeTable.getField(a);
        LabelDecisionDiagramBackend backend = backendForField(field);
        int assignmentCapacity = backend.assignmentCapacity();
        int aCount = nodeTable.getEdgeCount(a);
        int bCount = nodeTable.getEdgeCount(b);
        if (assignmentCapacity != 0
                && (long) aCount * bCount > assignmentCapacity) {
            combineSameFieldAssignments(a, b, frameStart, field, backend,
                    assignmentCapacity, isOr, op);
            return;
        }
        int residualFrame = residualTop;
        ensureResidualCapacity(aCount + bCount);
        int residualA = residualTop;
        residualTop += aCount;
        int residualB = residualTop;
        residualTop += bCount;
        for (int i = 0; i < aCount; i++) {
            residualLabels[residualA + i] = refLabel(
                    nodeTable.getField(a), nodeTable.getEdgeLabel(a, i));
        }
        for (int i = 0; i < bCount; i++) {
            residualLabels[residualB + i] = refLabel(
                    nodeTable.getField(b), nodeTable.getEdgeLabel(b, i));
        }

        for (int i = 0; i < aCount; i++) {
            int aTarget = nodeTable.getEdgeTarget(a, i);
            int aLabel = nodeTable.getEdgeLabel(a, i);
            for (int j = 0; j < bCount; j++) {
                int bTarget = nodeTable.getEdgeTarget(b, j);
                int bLabel = nodeTable.getEdgeLabel(b, j);
                int intersect = refLabel(field, labelAnd(field, aLabel, bLabel));
                if (intersect != 0) {
                    residualLabels[residualA + i] = labelDiffTo(
                            residualLabels[residualA + i], intersect, field);
                    residualLabels[residualB + j] = labelDiffTo(
                            residualLabels[residualB + j], intersect, field);
                    int sub = isOr ? orRec(aTarget, bTarget) : arithmeticChild(aTarget, bTarget, op);
                    edgeCollect(frameStart, field, sub, intersect);
                } else {
                    derefLabel(field, intersect);
                }
            }
        }

        for (int i = 0; i < aCount; i++) {
            int target = nodeTable.getEdgeTarget(a, i);
            int label = residualLabels[residualA + i];
            if (label != 0) {
                edgeCollect(frameStart, field,
                        isOr ? target : arithmeticChild(target, FALSE_ID, op),
                        refLabel(field, label));
            }
            derefLabel(field, label);
        }
        for (int i = 0; i < bCount; i++) {
            int target = nodeTable.getEdgeTarget(b, i);
            int label = residualLabels[residualB + i];
            if (label != 0) {
                edgeCollect(frameStart, field,
                        isOr ? target : arithmeticChild(FALSE_ID, target, op),
                        refLabel(field, label));
            }
            derefLabel(field, label);
        }
        residualTop = residualFrame;
    }

    private static void combineSameFieldAssignments(int a, int b, int frameStart,
            int field, LabelDecisionDiagramBackend backend, int capacity,
            boolean isOr, TerminalOp op) {
        int assignmentFrame = assignmentTop;
        ensureAssignmentCapacity(capacity * 2);
        int aTargets = assignmentTop;
        int bTargets = aTargets + capacity;
        assignmentTop += capacity * 2;
        Arrays.fill(assignmentTargets, aTargets, assignmentTop, FALSE_ID);

        int aCount = nodeTable.getEdgeCount(a);
        for (int i = 0; i < aCount; i++) {
            backend.fillAssignmentTargets(nodeTable.getEdgeLabel(a, i),
                    nodeTable.getEdgeTarget(a, i), assignmentTargets, aTargets);
        }
        int bCount = nodeTable.getEdgeCount(b);
        for (int i = 0; i < bCount; i++) {
            backend.fillAssignmentTargets(nodeTable.getEdgeLabel(b, i),
                    nodeTable.getEdgeTarget(b, i), assignmentTargets, bTargets);
        }

        for (int assignment = 0; assignment < capacity; assignment++) {
            int left = assignmentTargets[aTargets + assignment];
            int right = assignmentTargets[bTargets + assignment];
            int target = isOr ? orRec(left, right) : arithmeticChild(left, right, op);
            if (target != FALSE_ID) {
                edgeCollect(frameStart, field, target,
                        refLabel(field, backend.assignmentLabel(assignment)));
            }
        }
        assignmentTop = assignmentFrame;
    }

    private static void ensureAssignmentCapacity(int additional) {
        int required = assignmentTop + additional;
        if (required <= assignmentTargets.length) return;
        int capacity = assignmentTargets.length;
        while (capacity < required) capacity <<= 1;
        assignmentTargets = Arrays.copyOf(assignmentTargets, capacity);
    }

    private static void ensureResidualCapacity(int additional) {
        int required = residualTop + additional;
        if (required <= residualLabels.length) return;
        int capacity = residualLabels.length;
        while (capacity < required) capacity <<= 1;
        residualLabels = Arrays.copyOf(residualLabels, capacity);
    }

    public static NDD exist(NDD a, int field) {
        temporarilyProtect.clear();
        int res = existRec(id(a), field);
        runSafePointMaintenance(res);
        return wrap(res);
    }

    private static int existRec(int a, int field) {
        if (isTerminalId(a) || nodeTable.getField(a) > field) return a;
        int result;
        if (nodeTable.getField(a) == field) {
            result = FALSE_ID;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) result = orRec(result, nodeTable.getEdgeTarget(a, i));
        } else {
            int frameStart = stackTop;
            int count = nodeTable.getEdgeCount(a);
            int aField = nodeTable.getField(a);
            for (int i = 0; i < count; i++) {
                edgeCollect(frameStart, aField, existRec(nodeTable.getEdgeTarget(a, i), field),
                        refLabel(aField, nodeTable.getEdgeLabel(a, i)));
            }
            result = edgeFlush(frameStart, aField);
        }
        temporarilyProtect.add(result);
        return result;
    }

    public static NDD encodePrefix(int[] prefixBinary, int field) {
        if (prefixBinary.length == 0) return getTrue();
        int prefixLabel = encodePrefixLabel(prefixBinary, field);
        return wrap(nodeTable.mk(field, new int[] { TRUE_ID }, new int[] { prefixLabel }));
    }

    /**
     * Build a referenced raw label for one concrete integer value of a field.
     * The caller owns the returned label and must release it with
     * {@link #derefLabel(int, int)}. This is useful for bulk construction via
     * {@link #mk(int, NDD[], int[])} without materializing one NDD per value.
     */
    public static int encodeValueLabel(int value, int field) {
        validateField(field);
        int width = pendingFieldBitNums.get(field);
        if (value < 0 || (width < 31 && value >= (1 << width))) {
            throw new IllegalArgumentException("value does not fit field " + field);
        }
        BackendContext context = backendContexts[fieldMode(field).ordinal()];
        int offset = context.maxWidth - width;
        return backendForField(field).concreteValueLabel(
                getFieldUniverseLabel(field), context.sharedVars, offset, width, value);
    }

    public static NDD encodePrefixs(ArrayList<int[]> prefixsBinary, int field) {
        int prefixsBDD = refLabel(field, 0);
        for (int[] prefix : prefixsBinary) {
            prefixsBDD = labelOrTo(prefixsBDD, encodePrefixLabel(prefix, field), field);
        }
        return wrap(nodeTable.mk(field, new int[] { TRUE_ID }, new int[] { prefixsBDD }));
    }

    private static int encodePrefixLabel(int[] prefixBinary, int field) {
        if (prefixBinary.length > pendingFieldBitNums.get(field)) {
            throw new IllegalArgumentException("prefix is wider than field " + field);
        }
        int label = refLabel(field, getFieldUniverseLabel(field));
        for (int i = 0; i < prefixBinary.length; i++) {
            if (prefixBinary[i] != 0 && prefixBinary[i] != 1) {
                derefLabel(field, label);
                throw new IllegalArgumentException("prefix bits must be 0 or 1");
            }
            int literal = prefixBinary[i] == 0
                    ? bddNotVarsPerField.get(field)[i]
                    : bddVarsPerField.get(field)[i];
            int next = refLabel(field, labelAnd(field, label, literal));
            derefLabel(field, label);
            label = next;
        }
        return label;
    }

    public static int encodePrefixBDD(int[] prefixBinary, int[] vars, int[] notVars) {
        if (bddEngine == null) throw new UnsupportedOperationException("BDD backend is not active");
        int prefixBDD = 1;
        for (int i = 0; i < prefixBinary.length; i++) {
            prefixBDD = bddEngine.andTo(prefixBDD, prefixBinary[i] == 0 ? notVars[i] : vars[i]);
        }
        return prefixBDD;
    }

    public static NDD encodeACL(ArrayList<Pair<Integer, Integer>> perFieldBDD) {
        int result = TRUE_ID;
        for (int i = perFieldBDD.size() - 1; i >= 0; i--) {
            if (perFieldBDD.get(i).getValue() != 1) {
                result = nodeTable.mk(perFieldBDD.get(i).getKey(), new int[] { result },
                        new int[] { perFieldBDD.get(i).getValue() });
            }
        }
        return wrap(result);
    }

    public static NDD toNDD(int a, int field) {
        ensureFieldMode(field, LabelMode.BDD, "toNDD(raw BDD)");
        if (a == 0) return getFalse();
        if (a == 1) return getTrue();
        return wrap(nodeTable.mk(field, new int[] { TRUE_ID }, new int[] { bddEngine.ref(a) }));
    }

    public static NDD toNDD(int a) {
        ensureHomogeneousBdd("toNDD(raw BDD)");
        HashMap<Integer, HashMap<Integer, Integer>> decomposed = DecomposeBDD.decompose(a, bddEngine, maxVariablePerField);
        HashMap<Integer, Integer> converted = new HashMap<>();
        converted.put(1, TRUE_ID);
        while (!decomposed.isEmpty()) {
            boolean progressed = false;
            for (Map.Entry<Integer, HashMap<Integer, Integer>> entry : new ArrayList<>(decomposed.entrySet())) {
                if (converted.keySet().containsAll(entry.getValue().keySet())) {
                    int frameStart = stackTop;
                    for (Map.Entry<Integer, Integer> edge : entry.getValue().entrySet()) {
                        int field = DecomposeBDD.bddGetField(entry.getKey());
                        edgeCollect(frameStart, field, converted.get(edge.getKey()),
                                refLabel(field, edge.getValue()));
                    }
                    int n = edgeFlush(frameStart, DecomposeBDD.bddGetField(entry.getKey()));
                    converted.put(entry.getKey(), n);
                    decomposed.remove(entry.getKey());
                    progressed = true;
                    break;
                }
            }
            if (!progressed) break;
        }
        return wrap(converted.get(a));
    }

    public static int toBDD(NDD root) {
        ensureHomogeneousBdd("toBDD");
        int result = toBDDRec(id(root));
        bddEngine.deref(result);
        return result;
    }

    private static int toBDDRec(int current) {
        if (isTerminalId(current)) return valueDouble(current) == 0.0 ? 0 : 1;
        int result = 0;
        int count = nodeTable.getEdgeCount(current);
        for (int i = 0; i < count; i++) {
            int temp = bddEngine.andTo(toBDDRec(nodeTable.getEdgeTarget(current, i)), nodeTable.getEdgeLabel(current, i));
            result = bddEngine.orTo(result, temp);
        }
        return result;
    }

    public static double satCount(NDD ndd) {
        return satCount(ndd, 0, 1);
    }

    public static double satCount(NDD ndd, int field, int target) {
        return satCountRec(id(ndd), field, target);
    }

    private static double satCountRec(int curr, int field, int target) {
        if (isTerminalId(curr)) {
            if (Math.abs(valueDouble(curr) - target) < eps) {
                if (field > fieldNum) return 1;
                int len = maxVariablePerField.get(maxVariablePerField.size() - 1);
                return Math.pow(2, len + 1 - (field == 0 ? 0 : maxVariablePerField.get(field - 1) + 1));
            }
            return 0;
        }
        double result = 0;
        int currField = nodeTable.getField(curr);
        if (field == currField) {
            int count = nodeTable.getEdgeCount(curr);
            for (int i = 0; i < count; i++) {
                double bddSat = getLabelSatCount(currField, nodeTable.getEdgeLabel(curr, i));
                result += bddSat * satCountRec(nodeTable.getEdgeTarget(curr, i), field + 1, target);
            }
        } else {
            int len = maxVariablePerField.get(field) - (field == 0 ? -1 : maxVariablePerField.get(field - 1));
            result = Math.pow(2, len) * satCountRec(curr, field + 1, target);
        }
        return result;
    }

    public static ArrayList<int[]> toArray(NDD curr) {
        ArrayList<int[]> array = new ArrayList<>();
        int[] vec = new int[fieldNum + 1];
        toArrayRec(id(curr), array, vec, 0);
        return array;
    }

    private static void toArrayRec(int curr, ArrayList<int[]> array, int[] vec, int currField) {
        if (curr == FALSE_ID) return;
        if (curr == TRUE_ID || isTerminalId(curr)) {
            for (int i = currField; i <= fieldNum; i++) vec[i] = 1;
            array.add(Arrays.copyOf(vec, fieldNum + 1));
            return;
        }
        int field = nodeTable.getField(curr);
        for (int i = currField; i < field; i++) vec[i] = 1;
        int count = nodeTable.getEdgeCount(curr);
        for (int i = 0; i < count; i++) {
            vec[field] = nodeTable.getEdgeLabel(curr, i);
            toArrayRec(nodeTable.getEdgeTarget(curr, i), array, vec, field + 1);
        }
    }

    public static void print(NDD root) {
        printRec(id(root), new HashSet<Integer>());
    }

    private static void printRec(int current, HashSet<Integer> visited) {
        if (isTerminalId(current)) {
            System.out.println("TERMINAL: " + valueDisplay(current) + " node:" + current);
            return;
        }
        if (!visited.add(current)) return;
        System.out.println("field:" + nodeTable.getField(current) + " node:" + current);
        int count = nodeTable.getEdgeCount(current);
        for (int i = 0; i < count; i++) {
            System.out.println("next:" + nodeTable.getEdgeTarget(current, i) + " label:" + nodeTable.getEdgeLabel(current, i));
        }
        for (int i = 0; i < count; i++) printRec(nodeTable.getEdgeTarget(current, i), visited);
    }

    public static void printDot(NDD root, String filename) {
        printDot(filename, root);
    }

    public static void printDot(String filename, NDD root) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph NDD_Graph {\n  rankdir=TD;\n");
        printDotRec(id(root), sb, new HashSet<Integer>());
        sb.append("}\n");
        try (FileWriter writer = new FileWriter(filename + ".dot")) {
            writer.write(sb.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void printDotRec(int current, StringBuilder sb, HashSet<Integer> visited) {
        if (isTerminalId(current)) {
            sb.append("  N").append(current).append(" [shape=box,label=\"").append(valueDisplay(current)).append("\"];\n");
            return;
        }
        if (!visited.add(current)) return;
        sb.append("  N").append(current).append(" [shape=circle,label=\"Field ").append(nodeTable.getField(current)).append("\"];\n");
        int count = nodeTable.getEdgeCount(current);
        for (int i = 0; i < count; i++) {
            int next = nodeTable.getEdgeTarget(current, i);
            sb.append("  N").append(current).append(" -> N").append(next)
                    .append(" [label=\"").append(nodeTable.getEdgeLabel(current, i)).append("\"];\n");
            printDotRec(next, sb, visited);
        }
    }

    public static NDD getTrue() {
        return wrap(TRUE_ID);
    }

    public static NDD getFalse() {
        return wrap(FALSE_ID);
    }

    public boolean isTrue() {
        return id == TRUE_ID;
    }

    public boolean isFalse() {
        return id == FALSE_ID;
    }

    public boolean isTerminal() {
        return isTerminalId(id);
    }

    public boolean isConstant() {
        return isTerminal();
    }

    public double getTerminalVal() {
        return valueDouble(id);
    }

    public Rational getTerminalRational() {
        return valueRational(id);
    }

    public double getTerminalLowerBound() {
        return valueLower(id);
    }

    public double getTerminalUpperBound() {
        return valueUpper(id);
    }

    public static NDD createTerminal(int terNum) {
        return wrap(terminal(new Rational(terNum)));
    }

    public static NDD createTerminal(double terNum) {
        return wrap(terminal(terNum));
    }

    public static NDD createTerminal(Rational num) {
        return wrap(terminal(num));
    }

    public static NDD createExactIntervalTerminal(double value) {
        if (terminalMode != TerminalMode.INTERVAL) return createTerminal(value);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("exact interval terminal must be finite");
        }
        return wrap(exactInterval(value));
    }

    public static NDD createIntervalTerminal(double lower, double upper) {
        if (terminalMode != TerminalMode.INTERVAL) {
            throw new IllegalStateException("interval terminal mode is not active");
        }
        return wrap(terminalInterval(lower, upper));
    }

    /**
     * Allocation-light construction path for dense importers. Handles returned by this
     * builder are owned references; {@link #node} consumes its target handles and
     * {@link #finish} transfers the final owned handle to the regular object API.
     */
    public static final class BulkBuilder {
        public int terminal(double value) {
            return nodeTable.ref(NDD.terminal(value));
        }

        public int valueLabel(int value, int field) {
            return NDD.encodeValueLabel(value, field);
        }

        /** Labels are borrowed; targets are consumed. */
        public int node(int field, int[] targets, int[] labels) {
            return node(field, targets, 0, targets.length, labels);
        }

        /** Labels are borrowed; the selected target slice is consumed. */
        public int node(int field, int[] targets, int offset, int length, int[] labels) {
            if (length != labels.length || offset < 0 || length < 0
                    || offset + length > targets.length) {
                throw new IllegalArgumentException(
                        "target slice and labels must have the same length");
            }
            int frameStart = stackTop;
            for (int i = 0; i < length; i++) {
                edgeCollect(frameStart, field, targets[offset + i],
                        refLabel(field, labels[i]));
            }
            int result = edgeFlush(frameStart, field);
            nodeTable.ref(result);
            for (int i = 0; i < length; i++) nodeTable.deref(targets[offset + i]);
            return result;
        }

        public NDD finish(int ownedRoot) {
            runSafePointMaintenance(ownedRoot);
            return wrap(ownedRoot);
        }
    }

    /**
     * Replace nonnegative terminal values at or below {@code cutoff} with zero.
     * The returned diagram is not referenced. The statistics count distinct
     * terminal nodes reached from {@code ndd}.
     */
    public static PruneResult pruneNonnegativeAtMost(NDD ndd, double cutoff) {
        if (terminalMode == TerminalMode.INTERVAL) {
            throw new IllegalStateException("explicit pruning is not implemented for intervals");
        }
        if (!Double.isFinite(cutoff) || cutoff < 0.0 || cutoff >= 1.0) {
            throw new IllegalArgumentException("prune cutoff must be in [0, 1)");
        }
        if (cutoff == 0.0) return new PruneResult(ndd, 0.0, 0);
        temporarilyProtect.clear();
        PruneAccumulator accumulator = new PruneAccumulator();
        int result = pruneNonnegativeRec(id(ndd), cutoff, new HashMap<Integer, Integer>(),
                accumulator);
        runSafePointMaintenance(result);
        return new PruneResult(wrap(result), accumulator.maximum, accumulator.count);
    }

    private static int pruneNonnegativeRec(int current, double cutoff,
            HashMap<Integer, Integer> memo, PruneAccumulator accumulator) {
        Integer cached = memo.get(current);
        if (cached != null) return cached;
        int result;
        if (isTerminalId(current)) {
            double value = valueDouble(current);
            if (value < 0.0) {
                throw new IllegalArgumentException("diagram contains a negative terminal");
            }
            if (value != 0.0 && value <= cutoff) {
                result = FALSE_ID;
                accumulator.maximum = Math.max(accumulator.maximum, value);
                accumulator.count++;
            } else {
                result = current;
            }
        } else {
            int field = nodeTable.getField(current);
            int frameStart = stackTop;
            int count = nodeTable.getEdgeCount(current);
            for (int i = 0; i < count; i++) {
                int target = pruneNonnegativeRec(nodeTable.getEdgeTarget(current, i), cutoff,
                        memo, accumulator);
                edgeCollect(frameStart, field, target,
                        refLabel(field, nodeTable.getEdgeLabel(current, i)));
            }
            result = edgeFlush(frameStart, field);
            temporarilyProtect.add(result);
        }
        memo.put(current, result);
        return result;
    }

    private static final class PruneAccumulator {
        double maximum;
        int count;
    }

    /** Return the largest terminal of a nonnegative diagram, including implicit zero. */
    public static double maxNonnegativeTerminalValue(NDD ndd) {
        return maxNonnegativeTerminalBound(ndd, false);
    }

    public static double maxNonnegativeTerminalUpperValue(NDD ndd) {
        return maxNonnegativeTerminalBound(ndd, true);
    }

    private static double maxNonnegativeTerminalBound(NDD ndd, boolean upperBound) {
        BitSet visited = new BitSet();
        int[] pending = new int[64];
        int size = 1;
        pending[0] = id(ndd);
        double maximum = 0.0; // Missing assignments have the implicit value zero.
        while (size > 0) {
            int current = pending[--size];
            if (visited.get(current)) continue;
            visited.set(current);
            if (isTerminalId(current)) {
                double lower = valueLower(current);
                double terminalValue = upperBound ? valueUpper(current) : valueDouble(current);
                if (lower < 0.0) {
                    throw new IllegalArgumentException("diagram contains a negative terminal");
                }
                maximum = Math.max(maximum, terminalValue);
                continue;
            }
            int count = nodeTable.getEdgeCount(current);
            if (size + count > pending.length) {
                pending = Arrays.copyOf(pending, Math.max(pending.length << 1, size + count));
            }
            for (int edge = 0; edge < count; edge++) {
                pending[size++] = nodeTable.getEdgeTarget(current, edge);
            }
        }
        return maximum;
    }

    public int getField() {
        return nodeTable.getField(id);
    }

    public int edgeCount() {
        return isTerminal() ? 0 : nodeTable.getEdgeCount(id);
    }

    public NDD edgeTarget(int offset) {
        return wrap(nodeTable.getEdgeTarget(id, offset));
    }

    public int edgeLabel(int offset) {
        return nodeTable.getEdgeLabel(id, offset);
    }

    public void ref() {
        nodeTable.ref(id);
    }

    public static NDD ref(NDD ndd) {
        ndd.ref();
        return ndd;
    }

    public static void deref(NDD ndd) {
        if (ndd != null) {
            ndd.recursiveDeref();
        }
    }

    public NDD withRef() {
        ref();
        return this;
    }

    public void recursiveDeref() {
        nodeTable.deref(id);
    }

    public NDD times(NDD ndd) {
        return mul(this, ndd);
    }

    public NDD plus(NDD ndd) {
        return add(this, ndd);
    }

    public NDD minus(NDD ndd) {
        return sub(this, ndd);
    }

    public NDD divide(NDD ndd) {
        return div(this, ndd);
    }

    public NDD cmpl() {
        return not(this);
    }

    public NDD or(NDD ndd) {
        return or(this, ndd);
    }

    public NDD and(NDD ndd) {
        return and(this, ndd);
    }

    /** Compatibility overload for callers that encode each field as an integer. */
    public static double evaluate(NDD ndd, int[] fieldValues) {
        if (fieldValues == null || fieldValues.length != fieldNum + 1) {
            throw new IllegalArgumentException("one value is required for every field");
        }
        int[][] assignment = new int[fieldValues.length][];
        for (int field = 0; field < fieldValues.length; field++) {
            int width = getFieldWidth(field);
            assignment[field] = new int[width];
            for (int bit = 0; bit < width; bit++) {
                int shift = width - 1 - bit;
                assignment[field][bit] = shift >= Integer.SIZE
                        ? 0
                        : (fieldValues[field] >>> shift) & 1;
            }
        }
        return evaluateDouble(ndd, assignment);
    }

    public static double readEpsilon() {
        return eps;
    }

    public static void setEpsilon(double e) {
        eps = e;
    }

    public static void gc() {
        nodeTable.gc();
        clearCaches();
        nodeTable.compactEdgesAtSafePoint();
    }

    public static long getTotalCreated() {
        return nodeTable.getTotalCreated();
    }

    public static long getGcCount() {
        return nodeTable.getGcCount();
    }

    public static long getGcFreedCount() {
        return nodeTable.getGcFreedCount();
    }

    public static long getGcTimeMillis() {
        return nodeTable.getGcTimeMillis();
    }

    public static long getThresholdGrowCount() {
        return nodeTable.getThresholdGrowCount();
    }

    public static long getNodeCount() {
        return nodeTable.getCurrentSize();
    }

    public static long getInternalNodeCount() {
        return nodeTable.getInternalNodeCount();
    }

    public static long getLivePhysicalEdgeCount() {
        return nodeTable.getLiveEdgeCount();
    }

    public static long getPhysicalEdgeSlots() {
        return nodeTable.getPhysicalEdgeSlots();
    }

    public static int getPeakPhysicalEdgeSlots() {
        return nodeTable.getPeakPhysicalEdgeSlots();
    }

    public static int getNodeCapacity() { return nodeTable.getNodeCapacity(); }

    public static int getEdgeCapacity() { return nodeTable.getEdgeCapacity(); }

    public static int getBlockCapacity() { return nodeTable.getBlockCapacity(); }

    public static int getTerminalCapacity() { return nodeTable.getTerminalCapacity(); }

    public static int getTerminalBucketCapacity() {
        return nodeTable.getTerminalBucketCapacity();
    }

    public static long getUniqueBucketCapacity() {
        return nodeTable.getUniqueBucketCapacity();
    }

    public static int getWrapperCapacity() { return wrappers.length; }

    public static int getEdgeWorkStackCapacity() { return stackTargets.length; }

    public static int getResidualWorkStackCapacity() { return residualLabels.length; }

    public static int getAssignmentWorkStackCapacity() { return assignmentTargets.length; }

    private static LabelDecisionDiagramBackend backendOrNull(LabelMode mode) {
        if (backendContexts == null || mode == null) return null;
        BackendContext context = backendContexts[mode.ordinal()];
        return context == null ? null : context.backend;
    }

    public static long getLabelGcCount(LabelMode mode) {
        LabelDecisionDiagramBackend backend = backendOrNull(mode);
        return backend == null ? 0L : backend.gcCount();
    }

    public static long getLabelGcFreedCount(LabelMode mode) {
        LabelDecisionDiagramBackend backend = backendOrNull(mode);
        return backend == null ? 0L : backend.gcFreedCount();
    }

    public static long getLabelGcTimeMillis(LabelMode mode) {
        LabelDecisionDiagramBackend backend = backendOrNull(mode);
        return backend == null ? 0L : backend.gcTimeMillis();
    }

    public static long getLabelGcNotifyTimeMillis(LabelMode mode) {
        LabelDecisionDiagramBackend backend = backendOrNull(mode);
        return backend == null ? 0L : backend.gcNotifyTimeMillis();
    }

    public static long getLabelGrowCount(LabelMode mode) {
        LabelDecisionDiagramBackend backend = backendOrNull(mode);
        return backend == null ? 0L : backend.growCount();
    }

    public static long getLabelGrowTimeMillis(LabelMode mode) {
        LabelDecisionDiagramBackend backend = backendOrNull(mode);
        return backend == null ? 0L : backend.growTimeMillis();
    }

    public static void printopcount() {
        nodeTable.showMKCnt();
        for (BackendContext context : backendContexts) {
            if (context != null) context.backend.showStats();
        }
    }

    public static int gettersize() {
        return nodeTable.getTerminalCount();
    }

    public static int getIntervalNominalBucketCount() {
        return nodeTable.getIntervalNominalBucketCount();
    }

    public static int getIntervalRadiusClassCount() {
        return nodeTable.getIntervalRadiusClassCount();
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof NDD && ((NDD) obj).id == id;
    }

    @Override
    public String toString() {
        return isTerminal() ? "NDD(" + valueDisplay(id) + ")" : "NDD#" + id;
    }

    private static class IntOperationCache {
        private final int mask;
        private final int[] op1;
        private final int[] op2;
        private final int[] res;
        private final int[] gen;
        private int generation;
        int result;
        int hashValue;

        IntOperationCache(int cacheSize) {
            int capacity = 1;
            while (capacity < cacheSize) {
                capacity <<= 1;
            }
            this.mask = capacity - 1;
            this.op1 = new int[capacity];
            this.op2 = new int[capacity];
            this.res = new int[capacity];
            this.gen = new int[capacity];
            this.generation = 1;
        }

        boolean getEntry(int a) {
            int hash = hashUnary(a);
            if (gen[hash] == generation && op1[hash] == a) {
                result = res[hash];
                return true;
            }
            hashValue = hash;
            return false;
        }

        boolean getEntry(int a, int b) {
            int hash = hashBinary(a, b);
            if (gen[hash] == generation) {
                int oa = op1[hash];
                int ob = op2[hash];
                if ((oa == a && ob == b) || (oa == b && ob == a)) {
                    result = res[hash];
                    return true;
                }
            }
            hashValue = hash;
            return false;
        }

        boolean getEntryOrdered(int a, int b) {
            int hash = hashOrdered(a, b);
            if (gen[hash] == generation && op1[hash] == a && op2[hash] == b) {
                result = res[hash];
                return true;
            }
            hashValue = hash;
            return false;
        }

        void setEntry(int index, int a, int result) {
            op1[index] = a;
            op2[index] = 0;
            res[index] = result;
            gen[index] = generation;
        }

        void setEntry(int index, int a, int b, int result) {
            op1[index] = a;
            op2[index] = b;
            res[index] = result;
            gen[index] = generation;
        }

        void clear() {
            generation++;
            if (generation == Integer.MAX_VALUE) {
                Arrays.fill(gen, 0);
                generation = 1;
            }
        }

        private int hashUnary(int a) {
            return mix(a) & mask;
        }

        private int hashBinary(int a, int b) {
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            return hashOrdered(lo, hi);
        }

        private int hashOrdered(int a, int b) {
            int h = a * 0x9e3779b9 + b * 0x517cc1b7;
            return mix(h) & mask;
        }
    }

    private static class IntHashSet {
        private static final int EMPTY = Integer.MIN_VALUE;
        private int[] table;
        private int size;
        private int mask;
        private int threshold;

        IntHashSet(int capacity) {
            int cap = 1;
            while (cap < capacity * 2) cap <<= 1;
            table = new int[cap];
            Arrays.fill(table, EMPTY);
            mask = cap - 1;
            threshold = (int) (cap * 0.7);
        }

        void clear() {
            Arrays.fill(table, EMPTY);
            size = 0;
        }

        void add(int value) {
            if (value <= 1) return;
            if (size >= threshold) rehash();
            int pos = mix(value) & mask;
            while (table[pos] != EMPTY) {
                if (table[pos] == value) return;
                pos = (pos + 1) & mask;
            }
            table[pos] = value;
            size++;
        }

        void forEach(IntConsumer consumer) {
            for (int value : table) if (value != EMPTY) consumer.accept(value);
        }

        private void rehash() {
            int[] old = table;
            table = new int[old.length << 1];
            Arrays.fill(table, EMPTY);
            mask = table.length - 1;
            threshold = (int) (table.length * 0.7);
            size = 0;
            for (int value : old) if (value != EMPTY) add(value);
        }
    }

    private static int mix(int x) {
        x ^= (x >>> 16);
        x *= 0x7feb352d;
        x ^= (x >>> 15);
        x *= 0x846ca68b;
        x ^= (x >>> 16);
        return x;
    }
}
