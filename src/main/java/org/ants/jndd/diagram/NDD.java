/**
 * NDD (Node Decision Diagram) main API.
 * Provides initialization, field declaration, Boolean operations (and, or, not, diff, imp),
 * encoding (prefix, ACL), and conversion between NDD and BDD.
 *
 * @author Zechun Li & Yichi Zhang - XJTU ANTS NetVerify Lab
 * @version 1.0
 */
package org.ants.jndd.diagram;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import org.ants.jndd.nodetable.NodeTable;
import org.ants.jndd.utils.DecomposeBDD;
import org.ants.jndd.bdd.ComplementedBDD;

import jdd.bdd.BDD;
import jdd.zdd.ZDD;

public class NDD {
    public enum LabelMode {
        BDD,
        COMPLEMENTED_BDD,
        ZDD
    }

    /** Operations accepted by {@link #apply(BinaryOperation, int, int)}. */
    public enum BinaryOperation {
        AND, OR, XOR, NAND, NOR, BIIMP, IMP, DIFF
    }

    /**
     * Receives one complete satisfying assignment during {@link #allSat(int, AssignmentConsumer)}.
     * Return {@code false} to stop enumeration early.
     */
    @FunctionalInterface
    public interface AssignmentConsumer {
        boolean accept(int[][] assignment);
    }

    /**
     * Size of operation caches (not, and, or).
     */
    private static int CACHE_SIZE = 10000;

    /**
     * The node table (node storage and unique table).
     */
    private static NodeTable nodeTable;

    /** Shared standard-BDD label engine, created lazily when a BDD field is declared. */
    protected static BDD bddEngine;

    /** Shared complemented-edge BDD label engine, created lazily when needed. */
    private static ComplementedBDD bcddEngine;

    /** Shared ZDD label engine, created lazily when needed. */
    private static ZDD zddEngine;

    /**
     * Default edge-label representation used by the legacy declareField(width) API.
     */
    private static LabelMode labelMode = LabelMode.BDD;

    /**
     * Homogeneous fast-path backend. In a mixed diagram this is the first field's backend and
     * fieldBackend(field) performs the per-field dispatch.
     */
    private static LabelDecisionDiagramBackend labelBackend;

    /** One shared decision-diagram engine and variable layout per label mode. */
    private static BackendContext[] backendContexts;

    /** Backend mode declared for each field. */
    private static ArrayList<LabelMode> pendingFieldModes;

    /** Direct per-field backend lookup, populated while fields are declared. */
    private static ArrayList<LabelDecisionDiagramBackend> fieldBackends;

    /** Array fast paths materialized at generateFields() for mixed-backend hot loops. */
    private static LabelDecisionDiagramBackend[] fieldBackendArray;
    private static LabelMode[] fieldModeArray;

    /** Whether declared fields use more than one backend. */
    private static boolean mixedLabelModes;

    /** Label-engine sizing retained for lazy per-mode engine creation. */
    private static int labelTableSize;
    private static int labelCacheSize;
    private static int[] backendTableSizes;
    private static int[] backendCacheSizes;

    /**
     * Current number of declared fields (0-based).
     */
    protected static int fieldNum;

    /**
     * Whether generateFields() has been called.
     */
    private static boolean fieldsGenerated;

    /**
     * Pending field bit numbers (before generateFields).
     */
    private static ArrayList<Integer> pendingFieldBitNums;

    /**
     * Per-field max variable index (cumulative bit index for BDD decomposition).
     */
    private static ArrayList<Integer> maxVariablePerField;

    /**
     * Per-field divisor for sat count normalization.
     */
    private static ArrayList<Double> satCountDiv;

    /**
     * BDD variable handles per field (for encoding).
     */
    private static ArrayList<int[]> bddVarsPerField;

    /**
     * BDD negated variable handles per field.
     */
    private static ArrayList<int[]> bddNotVarsPerField;

    /**
     * NDD node ids for positive literal per field per bit.
     */
    private static ArrayList<int[]> nddVarsPerField;

    /**
     * NDD node ids for negative literal per field per bit.
     */
    private static ArrayList<int[]> nddNotVarsPerField;

    /** Universe label per field. ZDD fields use an explicit powerset universe. */
    private static ArrayList<Integer> fieldUniverseLabels;

    /**
     * Node ids temporarily protected during an operation (e.g. and/or/not), to avoid gc.
     */
    private static IntHashSet temporarilyProtect;

    /**
     * Cache for not operation results.
     */
    private static IntOperationCache notCache;

    /**
     * Cache for and operation results.
     */
    private static IntOperationCache andCache;

    /**
     * Cache for or operation results.
     */
    private static IntOperationCache orCache;

    /** Cache for ordered set-difference operands. */
    private static IntOperationCache diffCache;

    /**
     * Initial capacity of edge-collection stack.
     */
    private static final int INITIAL_STACK_SIZE = 100000;

    /**
     * Stack of edge targets during edge collection.
     */
    private static int[] stackTargets;

    /** Stack of backend-specific edge-label handles during edge collection. */
    private static int[] stackLabels;

    /**
     * Top of the edge stack (next free index).
     */
    private static int stackTop;

    /**
     * Terminal node id for TRUE.
     */
    private static final int TRUE = 1;

    /**
     * Terminal node id for FALSE.
     */
    private static final int FALSE = 0;

    /** Shared state for all fields that use one label representation. */
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

    /**
     * Initialize NDD with default cache size.
     *
     * @param nddTableSize Max NDD node table size.
     * @param bddTableSize BDD node table size.
     * @param bddCacheSize BDD cache size.
     */
    public static void initNDD(int nddTableSize, int bddTableSize, int bddCacheSize) {
        initNDD(nddTableSize, CACHE_SIZE, bddTableSize, bddCacheSize);
    }

    public static void initNDD(int nddTableSize, int bddTableSize, int bddCacheSize, LabelMode mode) {
        initNDD(nddTableSize, CACHE_SIZE, bddTableSize, bddCacheSize, mode);
    }

    /**
     * Initialize NDD engine: node table, BDD engine, caches, and per-field arrays.
     *
     * @param nddTableSize  Max NDD node table size.
     * @param nddCacheSize  Size of not/and/or caches.
     * @param bddTableSize BDD node table size.
     * @param bddCacheSize BDD cache size.
     */
    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize, int bddCacheSize) {
        initNDD(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize, LabelMode.BDD);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize, int bddCacheSize, LabelMode mode) {
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        CACHE_SIZE = nddCacheSize;
        labelMode = mode;
        labelTableSize = bddTableSize;
        labelCacheSize = bddCacheSize;
        backendTableSizes = new int[LabelMode.values().length];
        backendCacheSizes = new int[LabelMode.values().length];
        Arrays.fill(backendTableSizes, bddTableSize);
        Arrays.fill(backendCacheSizes, bddCacheSize);
        nodeTable = new NodeTable(nddTableSize, bddTableSize, bddCacheSize, false);
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
        diffCache = new IntOperationCache(CACHE_SIZE);

        stackTargets = new int[INITIAL_STACK_SIZE];
        stackLabels = new int[INITIAL_STACK_SIZE];
        stackTop = 0;

    }

    public static LabelMode getLabelMode() {
        return labelMode;
    }

    public static boolean isZddMode() {
        return !mixedLabelModes
                && (fieldNum < 0 ? labelMode == LabelMode.ZDD
                        : pendingFieldModes.get(0) == LabelMode.ZDD);
    }

    public static boolean isZddMode(int field) {
        validateField(field);
        return fieldMode(field) == LabelMode.ZDD;
    }

    public static LabelMode getFieldLabelMode(int field) {
        validateField(field);
        return pendingFieldModes.get(field);
    }

    public static boolean hasMixedLabelModes() {
        return mixedLabelModes;
    }

    /** Configure one lazily-created label backend's initial node table and cache sizes. */
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

    /**
     * Declare a new field. Stores the bit number and reserves a field index.
     * BDD variable creation is deferred to generateFields() for cross-field sharing.
     *
     * @param bitNum Number of bits in this field.
     * @return The field index (0-based).
     */
    public static int declareField(int bitNum) {
        return declareField(bitNum, labelMode);
    }

    /**
     * Declare a field and its edge-label backend. All fields of the same mode share one backend
     * engine and one right-aligned variable layout.
     */
    public static int declareField(int bitNum, LabelMode mode) {
        if (fieldsGenerated) {
            throw new IllegalStateException("Cannot declare field after generateFields() has been called");
        }
        if (bitNum <= 0) throw new IllegalArgumentException("field width must be positive");
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        BackendContext context = backendContext(mode);
        pendingFieldBitNums.add(bitNum);
        pendingFieldModes.add(mode);
        fieldBackends.add(context.backend);
        fieldNum++;
        if (fieldNum == 0) {
            labelBackend = context.backend;
        } else if (mode != pendingFieldModes.get(0)) {
            mixedLabelModes = true;
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
        else zddEngine = (ZDD) raw;
        return context;
    }

    /**
     * Generate all fields after declaration. Creates shared BDD variables with right-alignment
     * so fields with the same bit-width share identical BDD variables, enabling BDD node reuse.
     * Must be called after all declareField() calls and before any NDD operations.
     */
    public static void generateFields() {
        if (fieldsGenerated) {
            throw new IllegalStateException("generateFields() has already been called");
        }
        if (pendingFieldBitNums.isEmpty()) {
            throw new IllegalStateException("No fields declared before generateFields()");
        }
        fieldsGenerated = true;
        fieldBackendArray = fieldBackends.toArray(new LabelDecisionDiagramBackend[0]);
        fieldModeArray = pendingFieldModes.toArray(new LabelMode[0]);

        for (int f = 0; f < pendingFieldBitNums.size(); f++) {
            BackendContext context = backendContexts[pendingFieldModes.get(f).ordinal()];
            context.maxWidth = Math.max(context.maxWidth, pendingFieldBitNums.get(f));
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

        // Assign shared variables to each field using right-alignment: a width-w field uses the
        // suffix [maxWidth-w, maxWidth) of its backend's variable pool.
        for (int f = 0; f < pendingFieldBitNums.size(); f++) {
            int bitNum = pendingFieldBitNums.get(f);
            LabelMode mode = pendingFieldModes.get(f);
            BackendContext context = backendContexts[mode.ordinal()];
            LabelDecisionDiagramBackend backend = context.backend;
            int offset = context.maxWidth - bitNum;

            nodeTable.declareField();

            int[] bddVars = new int[bitNum];
            int[] bddNotVars = new int[bitNum];
            int[] nddVars = new int[bitNum];
            int[] nddNotVars = new int[bitNum];
            int universe = backend.hasExplicitUniverse()
                    ? backend.ref(backend.buildUniverse(context.sharedVars, offset, bitNum))
                    : TRUE;
            fieldUniverseLabels.add(universe);

            for (int i = 0; i < bitNum; i++) {
                int variable = context.sharedVars[offset + i];
                bddVars[i] = backend.positiveLiteral(universe, variable);
                bddNotVars[i] = backend.negativeLiteral(universe, variable);

                nddVars[i] = nodeTable.mk(f, new int[]{TRUE},
                        new int[]{refLabel(f, bddVars[i])});
                nodeTable.fixNDDNodeRefCount(nddVars[i]);

                nddNotVars[i] = nodeTable.mk(f, new int[]{TRUE},
                        new int[]{refLabel(f, bddNotVars[i])});
                nodeTable.fixNDDNodeRefCount(nddNotVars[i]);
            }

            bddVarsPerField.add(bddVars);
            bddNotVarsPerField.add(bddNotVars);
            nddVarsPerField.add(nddVars);
            nddNotVarsPerField.add(nddNotVars);
            // Keep the legacy cumulative indices used by the homogeneous BDD conversion helpers.
            if (maxVariablePerField.isEmpty()) {
                maxVariablePerField.add(bitNum - 1);
            } else {
                maxVariablePerField.add(maxVariablePerField.get(maxVariablePerField.size() - 1) + bitNum);
            }

            double factor = fieldCardinality(f);
            for (int i = 0; i < satCountDiv.size(); i++) {
                satCountDiv.set(i, satCountDiv.get(i) * factor);
            }
            int totalBitsBefore = 0;
            if (maxVariablePerField.size() > 1) {
                totalBitsBefore = maxVariablePerField.get(maxVariablePerField.size() - 2) + 1;
            }
            satCountDiv.add(Math.pow(2.0, totalBitsBefore));
        }
    }

    /** @return Terminal node id for TRUE. */
    public static int getTrue() { return TRUE; }
    /** @return Terminal node id for FALSE. */
    public static int getFalse() { return FALSE; }
    /** @return Whether the node is TRUE. */
    public static boolean isTrue(int node) { return node == TRUE; }
    /** @return Whether the node is FALSE. */
    public static boolean isFalse(int node) { return node == FALSE; }
    /** @return Whether the node is a terminal (TRUE or FALSE). */
    public static boolean isTerminal(int node) { return node <= 1; }

    /** @return Number of declared fields. */
    public static int getFieldNum() { return fieldNum; }

    /** @return The field index of a node. */
    public static int getField(int nodeId) { return nodeTable.getField(nodeId); }
    /** @return The start index of edges for a node. */
    public static int getEdgeStart(int nodeId) { return nodeTable.getEdgeStart(nodeId); }
    /** @return The number of edges of a node. */
    public static int getEdgeCount(int nodeId) { return nodeTable.getEdgeCount(nodeId); }
    /** @return The target node id of an edge. */
    public static int getEdgeTarget(int edgeIndex) { return nodeTable.getEdgeTarget(edgeIndex); }
    /** @return The target node id of the offset-th edge of a node. */
    public static int getEdgeTarget(int nodeId, int offset) { return nodeTable.getEdgeTarget(nodeId, offset); }
    /** @return The BDD handle of an edge label. */
    public static int getEdgeLabel(int edgeIndex) { return nodeTable.getEdgeLabel(edgeIndex); }
    /** @return The BDD handle of the offset-th edge of a node. */
    public static int getEdgeLabel(int nodeId, int offset) { return nodeTable.getEdgeLabel(nodeId, offset); }

    /** @return NDD node id for positive literal at (field, index). */
    public static int getVar(int field, int index) { return nddVarsPerField.get(field)[index]; }
    /** @return NDD node id for negative literal at (field, index). */
    public static int getNotVar(int field, int index) { return nddNotVarsPerField.get(field)[index]; }
    /** @return BDD variable handles for the field. */
    public static int[] getBDDVars(int field) {
        ensureFieldMode(field, LabelMode.BDD, "BDD variable handles");
        return bddVarsPerField.get(field);
    }
    /** @return BDD negated variable handles for the field. */
    public static int[] getNotBDDVars(int field) {
        ensureFieldMode(field, LabelMode.BDD, "BDD negated variable handles");
        return bddNotVarsPerField.get(field);
    }

    /** @return The internal BDD engine. */
    public static BDD getBDDEngine() { return bddEngine; }

    public static ComplementedBDD getBCDDEngine() { return bcddEngine; }

    public static ZDD getZDDEngine() { return zddEngine; }

    /**
     * Return the raw decision-diagram variable id backing one field bit.
     * This is intended for backend-specific bulk encoders that construct an edge label
     * directly and then install it with {@link #addAtField(int, Map)}.
     */
    public static int getBackendVariableId(int field, int bit) {
        validateField(field);
        int width = pendingFieldBitNums.get(field);
        if (bit < 0 || bit >= width) {
            throw new IndexOutOfBoundsException("bit " + bit + " outside field width " + width);
        }
        BackendContext context = backendContexts[fieldMode(field).ordinal()];
        int variableLabel = context.sharedVars[context.maxWidth - width + bit];
        return context.backend.variableId(variableLabel);
    }

    public static long getLabelNodeCount() {
        long result = 0;
        for (BackendContext context : backendContexts) {
            if (context != null) result += context.backend.nodeCount();
        }
        return result;
    }

    public static long getLabelNodeCount(LabelMode mode) {
        BackendContext context = backendContexts[mode.ordinal()];
        return context == null ? 0 : context.backend.nodeCount();
    }

    public static long getLabelTotalCreated() {
        long result = 0;
        for (BackendContext context : backendContexts) {
            if (context != null) result += context.backend.totalCreated();
        }
        return result;
    }

    public static long getLabelTotalCreated(LabelMode mode) {
        BackendContext context = backendContexts[mode.ordinal()];
        return context == null ? 0 : context.backend.totalCreated();
    }

    public static void gcLabelEngine() {
        for (BackendContext context : backendContexts) {
            if (context != null) context.backend.gc();
        }
    }

    public static void gcLabelEngine(LabelMode mode) {
        BackendContext context = backendContexts[mode.ordinal()];
        if (context != null) context.backend.gc();
    }

    /**
     * Legacy homogeneous-diagram label reference API.
     * @throws IllegalStateException if fields use multiple label backends
     */
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

    public static boolean isUniverseEdgeLabel(int field, int label) {
        return label == getFieldUniverseLabel(field);
    }

    private static int getFieldUniverseLabel(int field) {
        return fieldUniverseLabels.get(field);
    }

    private static void ensureBooleanBddMode(String feature) {
        if (mixedLabelModes || labelBackend == null
                || labelBackend.mode() != LabelMode.BDD) {
            throw new UnsupportedOperationException(feature + " is only supported in BDD mode");
        }
    }

    private static void ensureFieldMode(int field, LabelMode expected, String feature) {
        validateField(field);
        if (fieldMode(field) != expected) {
            throw new UnsupportedOperationException(
                    feature + " requires field " + field + " to use " + expected);
        }
    }

    private static void ensureHomogeneousLabels(String feature) {
        if (mixedLabelModes) {
            throw new IllegalStateException(feature + " requires a field argument in mixed-backend mode");
        }
        if (labelBackend == null) {
            throw new IllegalStateException("No fields have been declared");
        }
    }

    private static LabelMode fieldMode(int field) {
        return mixedLabelModes ? fieldModeArray[field] : labelBackend.mode();
    }

    /**
     * Homogeneous diagrams keep the original single-backend fast path. The branch is stable for
     * the engine lifetime and therefore readily predicted/inlined by the JIT.
     */
    private static LabelDecisionDiagramBackend backendForField(int field) {
        return mixedLabelModes ? fieldBackendArray[field] : labelBackend;
    }

    private static double fieldCardinality(int field) {
        return Math.pow(2.0, pendingFieldBitNums.get(field));
    }

    private static int labelAnd(int field, int a, int b) {
        return backendForField(field).and(a, b);
    }

    private static int labelDiff(int field, int a, int b) {
        return backendForField(field).diff(0, a, b);
    }

    private static int labelNot(int field, int label) {
        return backendForField(field).not(getFieldUniverseLabel(field), label);
    }

    private static int labelOrTo(int current, int add, int field) {
        return backendForField(field).orTo(current, add);
    }

    private static int labelAndTo(int current, int other, int field) {
        return backendForField(field).andTo(current, other);
    }

    /** Consume {@code current} and subtract {@code remove} without materializing a complement. */
    private static int labelDiffTo(int current, int remove, int field) {
        int result = refLabel(field, labelDiff(field, current, remove));
        derefLabel(field, current);
        return result;
    }

    private static double labelSatCount(int field, int label) {
        int fieldBits = pendingFieldBitNums.get(field);
        LabelDecisionDiagramBackend backend = backendForField(field);
        int maxBits = backendContexts[fieldMode(field).ordinal()].maxWidth;
        return backend.satCount(label, fieldBits, maxBits);
    }

    /**
     * Clear not/and/or operation caches (e.g. after gc).
     */
    public static void clearCaches() {
        notCache.clear();
        andCache.clear();
        orCache.clear();
        diffCache.clear();
    }

    /**
     * Run maintenance only after the recursive operation unwinds, when edge-array compaction and
     * retired-slot recycling can no longer invalidate physical positions cached on the call stack.
     */
    private static void runSafePointMaintenance() {
        if (nodeTable != null) {
            nodeTable.compactEdgesIfNeeded();
        }
    }

    /**
     * Apply consumer to each node id in the temporary protect set (used during gc).
     *
     * @param consumer Action to perform for each protected node id.
     */
    public static void forEachTemporarilyProtect(IntConsumer consumer) {
        temporarilyProtect.forEach(consumer);
    }

    /**
     * Increment reference count of a node (protect from gc).
     *
     * @param nodeId The node id.
     * @return The same node id.
     */
    public static int ref(int nodeId) { return nodeTable.ref(nodeId); }

    /**
     * Decrement reference count of a node.
     *
     * @param nodeId The node id.
     */
    public static void deref(int nodeId) { nodeTable.deref(nodeId); }

    /**
     * Collect one edge (target, label) into the current stack frame `[frameStart, stackTop)`.
     * Plain O(1) append: duplicate targets are tolerated here and merged later in {@link #edgeFlush}
     * after sorting. Deferring dedup keeps this hot path branch-free and avoids the O(D^2) linear
     * scan that the previous merge-on-collect implementation paid at every node.
     *
     * @param frameStart Start of current frame in stack (kept for call-site symmetry with edgeFlush).
     * @param target     Target node id.
     * @param label      BDD handle for edge label (caller ref'd).
     */
    private static void edgeCollect(int frameStart, int field, int target, int label) {
        if (target == FALSE) {
            derefLabel(field, label);
            return;
        }
        if (stackTop >= stackTargets.length) growStack();
        stackTargets[stackTop] = target;
        stackLabels[stackTop] = label;
        stackTop++;
    }

    /** Frame size at/above which LSD radix sort beats comparison/insertion sort. */
    private static final int RADIX_THRESHOLD = Integer.getInteger("ndd.radixThreshold", 64);
    /** Scratch for radix sort: (target << 32 | label) packed; second is the ping-pong buffer. */
    private static long[] packScratch = new long[1024];
    private static long[] packScratch2 = new long[1024];
    private static final int[] radixCount = new int[257];

    /**
     * Flush collected edges: sort by target, merge duplicate targets (consuming OR on labels),
     * then create/reuse the node via {@link NodeTable#mk}. mk needs a canonical (sorted, deduped)
     * edge order. Sort is size-adaptive: in-place quicksort/insertion for small frames (the common
     * case — NDD fan-out is typically tiny, so this matches the old insertion-sort cost exactly),
     * and O(n) LSD radix sort for large frames (high fan-out), where it avoids the O(n log n) /
     * O(n^2) blow-up.
     *
     * @param frameStart Start of current frame in stack.
     * @param field      Field index for the new node.
     * @return The created or reused node id, or FALSE if no edges.
     */
    private static int edgeFlush(int frameStart, int field) {
        int size = stackTop - frameStart;

        if (size == 0) {
            stackTop = frameStart;
            return FALSE;
        }
        if (size == 1 && isUniverseEdgeLabel(field, stackLabels[frameStart])) {
            int target = stackTargets[frameStart];
            stackTop = frameStart;
            return target;
        }

        int res = (size >= RADIX_THRESHOLD)
                ? flushRadixMerge(frameStart, field, size)
                : flushQsortMerge(frameStart, field, size);
        stackTop = frameStart;
        return res;
    }

    /**
     * Discard a partially collected edge frame, releasing every caller-owned label reference.
     */
    private static void edgeDiscard(int frameStart, int field) {
        for (int i = frameStart; i < stackTop; i++) {
            derefLabel(field, stackLabels[i]);
        }
        stackTop = frameStart;
    }

    /** Small/medium frames: in-place quicksort (insertion for short runs) by target + merge + mk. */
    private static int flushQsortMerge(int frameStart, int field, int size) {
        qsortPairs(frameStart, frameStart + size - 1);
        return mergeRunsAndMk(frameStart, field, size);
    }

    /** Large frames: pack + O(n) LSD radix sort by target + merge + mk. */
    private static int flushRadixMerge(int frameStart, int field, int size) {
        if (packScratch.length < size) packScratch = new long[Math.max(size, packScratch.length * 2)];
        if (packScratch2.length < size) packScratch2 = new long[Math.max(size, packScratch2.length * 2)];
        long[] pk = packScratch;
        for (int i = 0; i < size; i++) {
            pk[i] = (((long) stackTargets[frameStart + i]) << 32)
                    | (stackLabels[frameStart + i] & 0xffffffffL);
        }
        radixSortByTarget(size);
        pk = packScratch; // radixSortByTarget leaves the sorted result here
        // unpack back into the frame, then merge adjacent equal targets in place
        for (int i = 0; i < size; i++) {
            stackTargets[frameStart + i] = (int) (pk[i] >>> 32);
            stackLabels[frameStart + i] = (int) pk[i];
        }
        return mergeRunsAndMk(frameStart, field, size);
    }

    /**
     * Given the frame `[frameStart, frameStart+size)` sorted by target ascending, merge runs of
     * equal targets via the consuming labelOrTo (each input label consumed once, each output label
     * left with one ref), compact in place, and create the node.
     */
    private static int mergeRunsAndMk(int frameStart, int field, int size) {
        int w = frameStart;
        int curT = stackTargets[frameStart];
        int curL = stackLabels[frameStart];
        for (int i = frameStart + 1; i < frameStart + size; i++) {
            int t = stackTargets[i];
            int l = stackLabels[i];
            if (t == curT) {
                curL = labelOrTo(curL, l, field); // consuming OR
            } else {
                stackTargets[w] = curT;
                stackLabels[w] = curL;
                w++;
                curT = t;
                curL = l;
            }
        }
        stackTargets[w] = curT;
        stackLabels[w] = curL;
        w++;
        return nodeTable.mk(field, stackTargets, stackLabels, frameStart, w - frameStart);
    }

    /** Quicksort over stackTargets/stackLabels[lo..hi] keyed by target; insertion sort for short runs. */
    private static void qsortPairs(int lo, int hi) {
        while (hi - lo > 16) {
            int mid = (lo + hi) >>> 1;
            int a = stackTargets[lo], b = stackTargets[mid], c = stackTargets[hi];
            int pivotIdx = (a < b) ? (b < c ? mid : (a < c ? hi : lo)) : (a < c ? lo : (b < c ? hi : mid));
            swapPair(pivotIdx, hi);
            int pivot = stackTargets[hi];
            int i = lo - 1;
            for (int j = lo; j < hi; j++) {
                if (stackTargets[j] < pivot) {
                    i++;
                    swapPair(i, j);
                }
            }
            swapPair(i + 1, hi);
            int p = i + 1;
            if (p - lo < hi - p) { // recurse on smaller side, loop on larger (bounded stack depth)
                qsortPairs(lo, p - 1);
                lo = p + 1;
            } else {
                qsortPairs(p + 1, hi);
                hi = p - 1;
            }
        }
        for (int i = lo + 1; i <= hi; i++) {
            int t = stackTargets[i], l = stackLabels[i], j = i - 1;
            while (j >= lo && stackTargets[j] > t) {
                stackTargets[j + 1] = stackTargets[j];
                stackLabels[j + 1] = stackLabels[j];
                j--;
            }
            stackTargets[j + 1] = t;
            stackLabels[j + 1] = l;
        }
    }

    private static void swapPair(int i, int j) {
        int t = stackTargets[i]; stackTargets[i] = stackTargets[j]; stackTargets[j] = t;
        int l = stackLabels[i]; stackLabels[i] = stackLabels[j]; stackLabels[j] = l;
    }

    /** Stable LSD radix sort of packScratch[0,size) by the high-32-bit target, 8 bits x 4 passes. */
    private static void radixSortByTarget(int size) {
        long[] src = packScratch, dst = packScratch2;
        int[] count = radixCount;
        for (int shift = 32; shift < 64; shift += 8) {
            Arrays.fill(count, 0);
            for (int i = 0; i < size; i++) count[((int) (src[i] >>> shift) & 0xFF) + 1]++;
            for (int i = 0; i < 256; i++) count[i + 1] += count[i];
            for (int i = 0; i < size; i++) dst[count[(int) (src[i] >>> shift) & 0xFF]++] = src[i];
            long[] t = src; src = dst; dst = t;
        }
        packScratch = src;  // 4 passes (even) -> sorted result is back in the original packScratch
        packScratch2 = dst;
    }

    /**
     * Double the capacity of the edge stack.
     */
    private static void growStack() {
        int newCap = stackTargets.length * 2;
        stackTargets = Arrays.copyOf(stackTargets, newCap);
        stackLabels = Arrays.copyOf(stackLabels, newCap);
    }

    /**
     * Create or reuse an NDD node with the given edges (target -> label map).
     *
     * @param field Field index.
     * @param edges Map from target node id to BDD label handle.
     * @return The node id.
     */
    public static int mk(int field, IntIntMap edges) {
        int frameStart = stackTop;
        edges.forEach((target, label) -> {
            edgeCollect(frameStart, field, target, refLabel(field, label));
        });
        return edgeFlush(frameStart, field);
    }

    /**
     * Create or reuse an NDD node at {@code field} from a plain target-&gt;label edge map.
     * Public counterpart of {@link #mk(int, IntIntMap)} for callers that hold a {@link Map}
     * (e.g. ACL/TC encoders). Each label is ref'd into the new node; the map is not consumed.
     *
     * @param field Field index.
     * @param edges Map from target node id to BDD label handle.
     * @return The node id.
     */
    public static int addAtField(int field, Map<Integer, Integer> edges) {
        int frameStart = stackTop;
        for (Map.Entry<Integer, Integer> e : edges.entrySet()) {
            edgeCollect(frameStart, field, e.getKey(), refLabel(field, e.getValue()));
        }
        return edgeFlush(frameStart, field);
    }

    /**
     * And two NDDs, store result in a (ref result, deref a).
     *
     * @param a First operand (consumed).
     * @param b Second operand.
     * @return The result node id (ref'd).
     */
    public static int andTo(int a, int b) {
        int result = ref(and(a, b));
        deref(a);
        return result;
    }

    /**
     * Or two NDDs, store result in a (ref result, deref a).
     *
     * @param a First operand (consumed).
     * @param b Second operand.
     * @return The result node id (ref'd).
     */
    public static int orTo(int a, int b) {
        int result = ref(or(a, b));
        deref(a);
        return result;
    }

    /**
     * Logical and of two NDDs (result not ref'd).
     *
     * @param a First operand.
     * @param b Second operand.
     * @return The and result node id.
     */
    public static int and(int a, int b) {
        temporarilyProtect.clear();
        int result = andRec(a, b);
        runSafePointMaintenance();
        return result;
    }

    /**
     * Recursive and: same-field nodes combine edges by BDD and on labels; different fields take earlier field.
     */
    private static int andRec(int a, int b) {
        if (isFalse(a) || isTrue(b)) return a;
        if (isTrue(a) || isFalse(b) || a == b) return b;

        if (andCache.getEntry(a, b)) return andCache.result;

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
                    int bTarget = nodeTable.getEdgeTarget(b, j);
                    int bLabel = nodeTable.getEdgeLabel(b, j);
                    int intersect = refLabel(aField, labelAnd(aField, aLabel, bLabel));
                    if (intersect != 0) {
                        int sub = andRec(aTarget, bTarget);
                        edgeCollect(frameStart, aField, sub, intersect);
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
                int aTarget = nodeTable.getEdgeTarget(a, i);
                int aLabel = nodeTable.getEdgeLabel(a, i);
                int sub = andRec(aTarget, b);
                edgeCollect(frameStart, aField, sub, refLabel(aField, aLabel));
            }
        }

        int res = edgeFlush(frameStart, aField);
        temporarilyProtect.add(res);
        andCache.setEntry(andCache.hashValue, a, b, res);
        return res;
    }

    /**
     * Logical or of two NDDs (result not ref'd).
     *
     * @param a First operand.
     * @param b Second operand.
     * @return The or result node id.
     */
    public static int or(int a, int b) {
        temporarilyProtect.clear();
        int res = orRec(a, b);
        runSafePointMaintenance();
        return res;
    }

    /**
     * Recursive or: same-field nodes merge edges and subtract overlaps; different fields take earlier field.
     */
    private static int orRec(int a, int b) {
        if (isTrue(a) || isFalse(b)) return a;
        if (isFalse(a) || isTrue(b) || a == b) return b;

        if (orCache.getEntry(a, b)) return orCache.result;

        int frameStart = stackTop;

        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);

        if (aField == bField) {
            int aCount = nodeTable.getEdgeCount(a);
            int bCount = nodeTable.getEdgeCount(b);

            IntIntMap resA = new IntIntMap(aCount);
            IntIntMap resB = new IntIntMap(bCount);

            for (int i = 0; i < aCount; i++) {
                resA.put(nodeTable.getEdgeTarget(a, i),
                        refLabel(aField, nodeTable.getEdgeLabel(a, i)));
            }
            for (int i = 0; i < bCount; i++) {
                resB.put(nodeTable.getEdgeTarget(b, i),
                        refLabel(aField, nodeTable.getEdgeLabel(b, i)));
            }

            for (int i = 0; i < aCount; i++) {
                int aTarget = nodeTable.getEdgeTarget(a, i);
                int aLabel = nodeTable.getEdgeLabel(a, i);
                for (int j = 0; j < bCount; j++) {
                    int bTarget = nodeTable.getEdgeTarget(b, j);
                    int bLabel = nodeTable.getEdgeLabel(b, j);
                    int intersect = refLabel(aField, labelAnd(aField, aLabel, bLabel));
                    if (intersect != 0) {
                        int ra = resA.get(aTarget);
                        resA.put(aTarget, labelDiffTo(ra, intersect, aField));
                        int rb = resB.get(bTarget);
                        resB.put(bTarget, labelDiffTo(rb, intersect, aField));
                        int sub = orRec(aTarget, bTarget);
                        edgeCollect(frameStart, aField, sub, intersect);
                    }
                }
            }

            final int mergeField = aField;
            resA.forEach((key, value) -> {
                if (value != 0) edgeCollect(frameStart, mergeField, key,
                        refLabel(mergeField, value));
                derefLabel(mergeField, value);
            });
            resB.forEach((key, value) -> {
                if (value != 0) edgeCollect(frameStart, mergeField, key,
                        refLabel(mergeField, value));
                derefLabel(mergeField, value);
            });
            // maps are GC'd
        } else {
            if (aField > bField) {
                int t = a; a = b; b = t;
                int tf = aField; aField = bField; bField = tf;
            }
            int residualB = refLabel(aField, getFieldUniverseLabel(aField));
            int aCount = nodeTable.getEdgeCount(a);
            for (int i = 0; i < aCount; i++) {
                int aTarget = nodeTable.getEdgeTarget(a, i);
                int aLabel = nodeTable.getEdgeLabel(a, i);
                residualB = labelDiffTo(residualB, aLabel, aField);

                int sub = orRec(aTarget, b);
                edgeCollect(frameStart, aField, sub, refLabel(aField, aLabel));
            }
            if (residualB != 0) edgeCollect(frameStart, aField, b, residualB);
        }

        int res = edgeFlush(frameStart, aField);
        temporarilyProtect.add(res);
        orCache.setEntry(orCache.hashValue, a, b, res);
        return res;
    }

    /**
     * Logical not of an NDD (result not ref'd).
     *
     * @param a Operand.
     * @return The not result node id.
     */
    public static int not(int a) {
        temporarilyProtect.clear();
        int res = notRec(a);
        runSafePointMaintenance();
        return res;
    }

    /**
     * Recursive not: complement each edge label and add residual to TRUE.
     */
    private static int notRec(int a) {
        if (isTrue(a)) return FALSE;
        if (isFalse(a)) return TRUE;

        if (notCache.getEntry(a)) return notCache.result;

        int frameStart = stackTop;
        int field = nodeTable.getField(a);
        int residual = refLabel(field, getFieldUniverseLabel(field));

        int aCount = nodeTable.getEdgeCount(a);
        for (int i = 0; i < aCount; i++) {
            int aTarget = nodeTable.getEdgeTarget(a, i);
            int aLabel = nodeTable.getEdgeLabel(a, i);
            residual = labelDiffTo(residual, aLabel, field);

            int sub = notRec(aTarget);
            edgeCollect(frameStart, field, sub, refLabel(field, aLabel));
        }

        if (residual != 0) edgeCollect(frameStart, field, TRUE, residual);

        int result = edgeFlush(frameStart, field);
        temporarilyProtect.add(result);
        notCache.setEntry(notCache.hashValue, a, result);
        return result;
    }

    /**
     * Set difference: a and not(b).
     *
     * @param a First operand.
     * @param b Second operand.
     * @return The result node id.
     */
    public static int diff(int a, int b) {
        temporarilyProtect.clear();
        int res = diffRec(a, b);
        runSafePointMaintenance();
        return res;
    }

    /** Direct ordered set difference that never materializes {@code not(b)} as an intermediate. */
    private static int diffRec(int a, int b) {
        if (isFalse(a) || isTrue(b) || a == b) return FALSE;
        if (isFalse(b)) return a;
        if (isTrue(a)) return notRec(b);

        if (diffCache.getOrderedEntry(a, b)) return diffCache.result;

        int frameStart = stackTop;
        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);

        if (aField == bField) {
            int aCount = nodeTable.getEdgeCount(a);
            int bCount = nodeTable.getEdgeCount(b);
            for (int i = 0; i < aCount; i++) {
                int aTarget = nodeTable.getEdgeTarget(a, i);
                int remaining = refLabel(aField, nodeTable.getEdgeLabel(a, i));
                for (int j = 0; j < bCount && remaining != FALSE; j++) {
                    int bLabel = nodeTable.getEdgeLabel(b, j);
                    int intersection = refLabel(aField, labelAnd(aField, remaining, bLabel));
                    if (intersection != FALSE) {
                        int sub = diffRec(aTarget, nodeTable.getEdgeTarget(b, j));
                        edgeCollect(frameStart, aField, sub, intersection);
                    } else {
                        derefLabel(aField, intersection);
                    }
                    remaining = labelDiffTo(remaining, bLabel, aField);
                }
                if (remaining != FALSE) {
                    edgeCollect(frameStart, aField, aTarget, remaining);
                } else {
                    derefLabel(aField, remaining);
                }
            }
        } else if (aField < bField) {
            int aCount = nodeTable.getEdgeCount(a);
            for (int i = 0; i < aCount; i++) {
                int sub = diffRec(nodeTable.getEdgeTarget(a, i), b);
                edgeCollect(frameStart, aField, sub,
                        refLabel(aField, nodeTable.getEdgeLabel(a, i)));
            }
        } else {
            int residual = refLabel(bField, getFieldUniverseLabel(bField));
            int bCount = nodeTable.getEdgeCount(b);
            for (int i = 0; i < bCount; i++) {
                int bLabel = nodeTable.getEdgeLabel(b, i);
                int sub = diffRec(a, nodeTable.getEdgeTarget(b, i));
                edgeCollect(frameStart, bField, sub, refLabel(bField, bLabel));
                residual = labelDiffTo(residual, bLabel, bField);
            }
            if (residual != FALSE) {
                edgeCollect(frameStart, bField, a, residual);
            } else {
                derefLabel(bField, residual);
            }
        }

        int result = edgeFlush(frameStart, Math.min(aField, bField));
        temporarilyProtect.add(result);
        diffCache.setOrderedEntry(diffCache.hashValue, a, b, result);
        return result;
    }

    /**
     * Implication: not(a) or b.
     *
     * @param a First operand.
     * @param b Second operand.
     * @return The result node id.
     */
    public static int imp(int a, int b) {
        temporarilyProtect.clear();
        int n = notRec(a);
        temporarilyProtect.add(n);
        int res = orRec(n, b);
        runSafePointMaintenance();
        return res;
    }

    /**
     * Apply a named Boolean operation to two NDDs.
     *
     * @param operation Operation to apply.
     * @param a Left operand.
     * @param b Right operand.
     * @return The result node id.
     */
    public static int apply(BinaryOperation operation, int a, int b) {
        if (operation == null) throw new IllegalArgumentException("operation must not be null");
        switch (operation) {
            case AND: return and(a, b);
            case OR: return or(a, b);
            case IMP: return imp(a, b);
            case DIFF: return diff(a, b);
            case XOR: {
                int aOnly = ref(diff(a, b));
                int bOnly = ref(diff(b, a));
                int result = or(aOnly, bOnly);
                deref(aOnly);
                deref(bOnly);
                return result;
            }
            case NAND: {
                int conjunction = ref(and(a, b));
                int result = not(conjunction);
                deref(conjunction);
                return result;
            }
            case NOR: {
                int disjunction = ref(or(a, b));
                int result = not(disjunction);
                deref(disjunction);
                return result;
            }
            case BIIMP: {
                int exclusiveOr = ref(apply(BinaryOperation.XOR, a, b));
                int result = not(exclusiveOr);
                deref(exclusiveOr);
                return result;
            }
            default: throw new AssertionError("Unhandled binary operation: " + operation);
        }
    }

    /**
     * Generalized cofactor of {@code function} under {@code careSet}. The result agrees with
     * {@code function} wherever {@code careSet} is TRUE, while values outside the care set are
     * chosen recursively to eliminate fields and merge equal subgraphs where possible.
     *
     * @param function Function to simplify.
     * @param careSet Assignments whose function value must be preserved.
     * @return A simplified function equivalent to {@code function} on {@code careSet}.
     */
    public static int simplify(int function, int careSet) {
        temporarilyProtect.clear();
        HashMap<Long, Integer> memo = new HashMap<>();
        int result = simplifyRec(function, careSet, memo);
        runSafePointMaintenance();
        return result;
    }

    private static int simplifyRec(int function, int careSet, HashMap<Long, Integer> memo) {
        if (careSet == FALSE) return FALSE;
        if (careSet == TRUE || isTerminal(function)) return function;
        if (function == careSet) return TRUE;

        long key = (((long) function) << 32) | (careSet & 0xffffffffL);
        Integer cached = memo.get(key);
        if (cached != null) return cached;

        int functionField = nodeTable.getField(function);
        int careField = nodeTable.getField(careSet);
        int result;

        if (careField < functionField) {
            /*
             * The care set constrains an earlier field that the function does not inspect.
             * Simplify under each cared-for branch. If every branch yields the same result,
             * the entire care-only field is a don't-care and can be removed.
             */
            int frameStart = stackTop;
            boolean hasRequiredTarget = false;
            boolean uniform = true;
            int commonTarget = FALSE;
            int count = nodeTable.getEdgeCount(careSet);
            for (int i = 0; i < count; i++) {
                int sub = simplifyRec(function, nodeTable.getEdgeTarget(careSet, i), memo);
                if (!hasRequiredTarget) {
                    commonTarget = sub;
                    hasRequiredTarget = true;
                } else if (commonTarget != sub) {
                    uniform = false;
                }
                edgeCollect(frameStart, careField, sub,
                        refLabel(careField, nodeTable.getEdgeLabel(careSet, i)));
            }
            if (!hasRequiredTarget || uniform) {
                edgeDiscard(frameStart, careField);
                result = hasRequiredTarget ? commonTarget : FALSE;
            } else {
                result = edgeFlush(frameStart, careField);
            }
        } else if (functionField < careField) {
            /*
             * The care set does not constrain this function field, so its complete behavior,
             * including the implicit FALSE residual, must be preserved.
             */
            int frameStart = stackTop;
            int count = nodeTable.getEdgeCount(function);
            for (int i = 0; i < count; i++) {
                int sub = simplifyRec(nodeTable.getEdgeTarget(function, i), careSet, memo);
                edgeCollect(frameStart, functionField, sub,
                        refLabel(functionField, nodeTable.getEdgeLabel(function, i)));
            }
            result = edgeFlush(frameStart, functionField);
        } else {
            /*
             * Both operands inspect the same field. Intersections identify the regions where
             * function behavior is required by the care set. Regions outside care are omitted.
             */
            int frameStart = stackTop;
            boolean hasRequiredTarget = false;
            boolean uniform = true;
            int commonTarget = FALSE;
            int careCount = nodeTable.getEdgeCount(careSet);
            int functionCount = nodeTable.getEdgeCount(function);

            for (int i = 0; i < careCount; i++) {
                int careLabel = nodeTable.getEdgeLabel(careSet, i);
                int careTarget = nodeTable.getEdgeTarget(careSet, i);
                int remainingCareLabel = refLabel(functionField, careLabel);

                for (int j = 0; j < functionCount; j++) {
                    int functionLabel = nodeTable.getEdgeLabel(function, j);
                    int intersection = refLabel(functionField,
                            labelAnd(functionField, careLabel, functionLabel));
                    if (intersection != FALSE) {
                        int sub = simplifyRec(nodeTable.getEdgeTarget(function, j), careTarget, memo);
                        if (!hasRequiredTarget) {
                            commonTarget = sub;
                            hasRequiredTarget = true;
                        } else if (commonTarget != sub) {
                            uniform = false;
                        }
                        edgeCollect(frameStart, functionField, sub, intersection);
                    } else {
                        derefLabel(functionField, intersection);
                    }

                    int nextRemaining = refLabel(functionField,
                            labelDiff(functionField, remainingCareLabel, functionLabel));
                    derefLabel(functionField, remainingCareLabel);
                    remainingCareLabel = nextRemaining;
                }

                if (remainingCareLabel != FALSE) {
                    if (!hasRequiredTarget) {
                        commonTarget = FALSE;
                        hasRequiredTarget = true;
                    } else if (commonTarget != FALSE) {
                        uniform = false;
                    }
                }
                derefLabel(functionField, remainingCareLabel);
            }

            if (!hasRequiredTarget || uniform) {
                edgeDiscard(frameStart, functionField);
                result = hasRequiredTarget ? commonTarget : FALSE;
            } else {
                result = edgeFlush(frameStart, functionField);
            }
        }

        memo.put(key, result);
        temporarilyProtect.add(result);
        return result;
    }

    /**
     * Number of satisfying assignments of the NDD (via conversion to BDD).
     *
     * @param ndd Root node id.
     * @return Sat count.
     */
    public static double satCount(int ndd) {
        return satCountRec(ndd, 0, new HashMap<>(), new HashMap<>());
    }

    private static double satCountRec(int ndd, int field, Map<Long, Double> nddMemo,
                                      Map<Long, Double> labelMemo) {
        if (ndd == FALSE) return 0;
        if (ndd == TRUE) {
            if (field > fieldNum) return 1;
            double result = 1;
            for (int f = field; f <= fieldNum; f++) {
                result *= fieldCardinality(f);
            }
            return result;
        }
        long memoKey = (((long) ndd) << 32) ^ (field & 0xffffffffL);
        Double cached = nddMemo.get(memoKey);
        if (cached != null) return cached;
        double result = 0;
        int nddField = nodeTable.getField(ndd);
        if (field == nddField) {
            int count = nodeTable.getEdgeCount(ndd);
            for (int i = 0; i < count; i++) {
                int target = nodeTable.getEdgeTarget(ndd, i);
                int label = nodeTable.getEdgeLabel(ndd, i);
                long labelKey = (((long) field) << 32) ^ (label & 0xffffffffL);
                Double cachedLabelCount = labelMemo.get(labelKey);
                double bddSat = cachedLabelCount != null ? cachedLabelCount : labelSatCount(field, label);
                if (cachedLabelCount == null) labelMemo.put(labelKey, bddSat);
                double nddSat = satCountRec(target, field + 1, nddMemo, labelMemo);
                result += bddSat * nddSat;
            }
        } else {
            // Field is skipped in this NDD branch - all values valid
            result = fieldCardinality(field) * satCountRec(ndd, field + 1, nddMemo, labelMemo);
        }
        nddMemo.put(memoKey, result);
        return result;
    }

    /**
     * Get the current number of allocated NDD nodes.
     * @return Node count stored in the node table.
     */
    public static long getNodeCount() {
        if (nodeTable == null) {
            return 0;
        }
        return nodeTable.getCurrentSize();
    }

    /**
     * Run NDD garbage collection immediately.
     */
    public static void gc() {
        if (nodeTable != null) {
            nodeTable.gc();
            nodeTable.compactEdgesAtSafePoint();
            clearCaches();
        }
    }

    /**
     * Get the total number of NDD nodes ever created.
     * @return Total created count (including garbage collected nodes).
     */
    public static long getTotalCreated() {
        if (nodeTable == null) {
            return 0;
        }
        return nodeTable.getTotalCreated();
    }

    /**
     * Encode a single binary prefix as an NDD (one node with one edge labeled by BDD).
     *
     * @param prefixBinary Binary prefix (e.g. for IP).
     * @param field        Field index.
     * @return NDD node id.
     */
    public static int encodePrefix(int[] prefixBinary, int field) {
        validateField(field);
        if (prefixBinary.length == 0) return TRUE;
        int prefixLabel = encodeBinaryPrefixLabel(prefixBinary, field);
        return nodeTable.mk(field, new int[]{TRUE}, new int[]{prefixLabel});
    }

    /**
     * Encode multiple binary prefixes as union (or) of prefix NDDs.
     *
     * @param prefixsBinary List of binary prefixes.
     * @param field         Field index.
     * @return NDD node id.
     */
    public static int encodePrefixs(ArrayList<int[]> prefixsBinary, int field) {
        validateField(field);
        int prefixsLabel = refLabel(field, FALSE);
        for (int[] prefix : prefixsBinary) {
            int prefixLabel = encodeBinaryPrefixLabel(prefix, field);
            int next = refLabel(field,
                    backendForField(field).or(prefixsLabel, prefixLabel));
            derefLabel(field, prefixsLabel);
            derefLabel(field, prefixLabel);
            prefixsLabel = next;
        }
        return nodeTable.mk(field, new int[]{TRUE}, new int[]{prefixsLabel});
    }

    private static int encodeBinaryPrefixLabel(int[] prefixBinary, int field) {
        if (prefixBinary == null || prefixBinary.length > fieldWidth(field)) {
            throw new IllegalArgumentException("prefix length exceeds field width");
        }
        int label = refLabel(field, getFieldUniverseLabel(field));
        for (int i = 0; i < prefixBinary.length; i++) {
            if (prefixBinary[i] != 0 && prefixBinary[i] != 1) {
                derefLabel(field, label);
                throw new IllegalArgumentException("prefix entries must be 0 or 1");
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

    /**
     * Encode a binary prefix as a BDD using given variable handles.
     *
     * @param prefixBinary Binary prefix.
     * @param vars         BDD positive literal handles.
     * @param notVars      BDD negative literal handles.
     * @return BDD handle for the prefix.
     */
    public static int encodePrefixBDD(int[] prefixBinary, int[] vars, int[] notVars) {
        ensureBooleanBddMode("encodePrefixBDD");
        if (prefixBinary.length == 0) return 1;
        int prefixBDD = 1;
        for (int i = prefixBinary.length - 1; i >= 0; i--) {
            int currentBit = prefixBinary[i] == 1 ? vars[i] : notVars[i];
            if (i == prefixBinary.length - 1) prefixBDD = bddEngine.ref(currentBit);
            else prefixBDD = bddEngine.andTo(prefixBDD, currentBit);
        }
        return prefixBDD;
    }

    /**
     * Encode an ACL (list of per-field BDDs) as a multi-field NDD.
     *
     * @param perFieldBDD List of (field index, BDD handle) pairs.
     * @return Root NDD node id.
     */
    public static int encodeACL(ArrayList<Pair<Integer, Integer>> perFieldBDD) {
        int result = TRUE;
        for (int i = perFieldBDD.size() - 1; i >= 0; i--) {
            int field = perFieldBDD.get(i).getKey();
            ensureFieldMode(field, LabelMode.BDD, "encodeACL");
            if (perFieldBDD.get(i).getValue() != 1) {
                result = nodeTable.mk(field,
                        new int[]{result},
                        new int[]{perFieldBDD.get(i).getValue()});
            }
        }
        return result;
    }

    /**
     * Wrap a BDD handle as a single-field NDD (one node, one edge to TRUE with label a).
     *
     * @param a     BDD handle.
     * @param field Field index.
     * @return NDD node id.
     */
    /**
     * Wrap a BDD handle as a single-field NDD.
     */
    public static int toNDD(int a, int field) {
        ensureBooleanBddMode("toNDD");
        if (a == 0) return FALSE;
        if (a == 1) return TRUE;
        return nodeTable.mk(field, new int[]{TRUE}, new int[]{a});
    }

    /**
     * Convert a (multi-field decomposed) BDD to NDD by rebuilding structure per field.
     *
     * @param a BDD root handle.
     * @return NDD root node id.
     */
    public static int toNDD(int a) {
        ensureBooleanBddMode("toNDD");
        HashMap<Integer, HashMap<Integer, Integer>> decomposed = DecomposeBDD.decompose(a, bddEngine, maxVariablePerField);
        HashMap<Integer, Integer> converted = new HashMap<>();
        converted.put(1, TRUE);

        while (!decomposed.isEmpty()) {
            Set<Integer> finished = converted.keySet();
            Iterator<Map.Entry<Integer, HashMap<Integer, Integer>>> it = decomposed.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, HashMap<Integer, Integer>> entry = it.next();
                if (finished.containsAll(entry.getValue().keySet())) {
                    int field = DecomposeBDD.bddGetField(entry.getKey());
                    HashMap<Integer, Integer> edgeMap = entry.getValue();

                    int frameStart = stackTop;
                    for (Map.Entry<Integer, Integer> e : edgeMap.entrySet()) {
                        edgeCollect(frameStart, field, converted.get(e.getKey()),
                                refLabel(field, e.getValue()));
                    }
                    int n = edgeFlush(frameStart, field);

                    converted.put(entry.getKey(), n);
                    it.remove();
                    break;
                }
            }
        }
        return converted.get(a);
    }

    /**
     * Convert NDD to BDD (recursive: each node's edges OR'd with and(target_BDD, label)).
     *
     * @param root NDD root node id.
     * @return BDD handle.
     */
    public static int toBDD(int root) {
        ensureBooleanBddMode("toBDD");
        int result = toBDDRec(root);
        bddEngine.deref(result);
        return result;
    }

    /**
     * Recursively convert NDD subtree to BDD (returns ref'd BDD).
     */
    private static int toBDDRec(int current) {
        if (isTrue(current)) return 1;
        if (isFalse(current)) return 0;

        int result = 0;
        int count = nodeTable.getEdgeCount(current);
        for (int i = 0; i < count; i++) {
            int target = nodeTable.getEdgeTarget(current, i);
            int label = nodeTable.getEdgeLabel(current, i);
            int temp = bddEngine.andTo(toBDDRec(target), label);
            result = bddEngine.orTo(result, temp);
        }
        return result;
    }

    /**
     * Print NDD structure to stdout (debug).
     *
     * @param root Root node id.
     */
    public static void print(int root) {
        System.out.println("Print " + root + " begin!");
        printRec(root);
        System.out.println("Print " + root + " finish!\n");
    }

    /** Recursively print node and its edges. */
    private static void printRec(int current) {
        if (isTrue(current)) System.out.println("TRUE");
        else if (isFalse(current)) System.out.println("FALSE");
        else {
            System.out.println("field:" + nodeTable.getField(current) + " node:" + current);
            int count = nodeTable.getEdgeCount(current);
            for (int i = 0; i < count; i++) {
                System.out.println("next:" + nodeTable.getEdgeTarget(current, i) + " label:" + nodeTable.getEdgeLabel(current, i));
            }
            for (int i = 0; i < count; i++) printRec(nodeTable.getEdgeTarget(current, i));
        }
    }

    /**
     * Export NDD as a Dot file for graph visualization.
     *
     * @param root     Root node id.
     * @param filename Output file path.
     */
    public static void printDot(int root, String filename) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph NDD_Graph {\n");
        sb.append("  forcelabels=true;\n");
        sb.append("  rankdir=TD;\n");
        sb.append("  compound=true;\n");
        sb.append("  overlap=false;\n");
        sb.append("  splines=true;\n");
        sb.append("  ranksep=0.5;\n");
        sb.append("  nodesep=0.5;\n");

        IntHashSet visitedNDD = new IntHashSet(1024);
        sb.append("  NDD_TRUE [shape=box, style=filled, label=\"NDD TRUE\"];\n");

        // Collect BDD roots per field.
        Map<Integer, Set<Integer>> fieldToBDDRoots = new HashMap<>();
        collectFieldBDDs(root, fieldToBDDRoots, visitedNDD);
        visitedNDD.clear();

        // Draw BDD subgraphs.
        for (Map.Entry<Integer, Set<Integer>> entry : fieldToBDDRoots.entrySet()) {
            int field = entry.getKey();
            Set<Integer> bddRoots = entry.getValue();

            sb.append("  subgraph cluster_field_").append(field).append(" {\n");
            sb.append("    label=\"\";\n");
            sb.append("    style=dashed;\n");
            sb.append("    color=blue;\n");
            sb.append("    bgcolor=lightgrey;\n");
            sb.append("    margin=0;\n");
            sb.append("    pad=0;\n");

            HashSet<Integer> visitedBDD = new HashSet<>();
            for (int bddId : bddRoots) {
                printBDDSubgraph(bddId, field, sb, visitedBDD, bddRoots);
            }

            sb.append("    TRUE_").append(field).append(" [shape=box, style=filled, label=\"TRUE\", fillcolor=lightgrey];\n");

            sb.append("    title_").append(field)
                    .append(" [shape=plaintext, label=\"Field ").append(field + 1)
                    .append("\", fontcolor=black, fontsize=12, group=field").append(field).append("];\n");
            sb.append("    { rank=sink; title_").append(field).append("; }\n");

            sb.append("    TRUE_").append(field).append(" -> title_").append(field)
                    .append(" [style=invis, minlen=1];\n");

            sb.append("  }\n\n");
        }

        visitedNDD.clear();
        printNDDStructure(root, sb, visitedNDD);

        sb.append("}\n");

        try (FileWriter writer = new FileWriter(filename)) {
            writer.write(sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void collectFieldBDDs(int node, Map<Integer, Set<Integer>> fieldToBDDRoots, IntHashSet visited) {
        if (isTerminal(node) || visited.contains(node)) return;
        visited.add(node);
        int field = nodeTable.getField(node);
        int count = nodeTable.getEdgeCount(node);
        for (int i = 0; i < count; i++) {
            int next = nodeTable.getEdgeTarget(node, i);
            int bddId = nodeTable.getEdgeLabel(node, i);
            if (bddId > 1) {
                fieldToBDDRoots.computeIfAbsent(field, k -> new HashSet<>()).add(bddId);
            }
            collectFieldBDDs(next, fieldToBDDRoots, visited);
        }
    }

    private static void printBDDSubgraph(int currentBDD, int field, StringBuilder sb,
                                         HashSet<Integer> visited, Set<Integer> rootSet) {
        if (currentBDD <= 1 || visited.contains(currentBDD)) return;
        visited.add(currentBDD);

        if (fieldMode(field) != LabelMode.BDD) {
            sb.append("    bdd_").append(currentBDD).append("_f").append(field)
                    .append(" [shape=box, label=\"")
                    .append(fieldMode(field)).append(" #").append(currentBDD).append("\"];\n");
            return;
        }

        int var = bddEngine.getVar(currentBDD);
        int high = bddEngine.getHigh(currentBDD);
        int low = bddEngine.getLow(currentBDD);

        int[] fieldVars = bddVarsPerField.get(field);
        int startVar = fieldVars[0];
        int localVar = var - startVar;
        if (localVar < 0 || localVar >= fieldVars.length) {
            localVar = var;
        }

        String nodeName = "bdd_" + currentBDD + "_f" + field;

        if (rootSet.contains(currentBDD)) {
            String blankNodeName = "blank_" + currentBDD + "_f" + field;
            String clusterName = "cluster_root_" + currentBDD + "_f" + field;
            String groupName = "field" + field;

            sb.append("    subgraph ").append(clusterName).append(" {\n");
            sb.append("        label=\"\";\n");
            sb.append("        style=invis;\n");
            sb.append("        rankdir=TB;\n");
            sb.append("        ranksep=0.8;\n");

            sb.append("        ").append(blankNodeName)
                    .append(" [shape=point, width=0, height=0, style=invis, group=").append(groupName).append("];\n");

            sb.append("        ").append(nodeName)
                    .append(" [shape=circle, label=\"x").append(localVar).append("\", group=").append(groupName).append("];\n");

            sb.append("        ").append(blankNodeName).append(" -> ").append(nodeName)
                    .append(" [color=black, style=dashed, arrowhead=normal, arrowsize=1.5, label=\"#").append(currentBDD)
                    .append("\", labelfontcolor=black, fontcolor=black, labeldistance=2.0, labelangle=0, minlen=1];\n");

            sb.append("    }\n");
        } else {
            sb.append("    ").append(nodeName)
                    .append(" [shape=circle, label=\"x").append(localVar).append("\"];\n");
        }

        if (high == 1) {
            sb.append("    ").append(nodeName).append(" -> TRUE_").append(field).append(";\n");
        } else if (high > 1) {
            sb.append("    ").append(nodeName).append(" -> bdd_").append(high).append("_f").append(field).append(";\n");
            printBDDSubgraph(high, field, sb, visited, rootSet);
        }

        if (low == 1) {
            sb.append("    ").append(nodeName).append(" -> TRUE_").append(field).append(" [style=dotted];\n");
        } else if (low > 1) {
            sb.append("    ").append(nodeName).append(" -> bdd_").append(low).append("_f").append(field).append(" [style=dotted];\n");
            printBDDSubgraph(low, field, sb, visited, rootSet);
        }
    }

    /** Recursively append current NDD node and edges to Dot output. */
    private static void printNDDStructure(int current, StringBuilder sb, IntHashSet visited) {
        if (isTerminal(current) || visited.contains(current)) return;
        visited.add(current);

        String nodeId = "NDD_" + current;
        sb.append("  ").append(nodeId)
                .append(" [shape=circle, label=\"f").append(nodeTable.getField(current) + 1).append("\"];\n");

        int count = nodeTable.getEdgeCount(current);
        for (int i = 0; i < count; i++) {
            int next = nodeTable.getEdgeTarget(current, i);
            int bddId = nodeTable.getEdgeLabel(current, i);

            String nextId;
            if (isTrue(next)) nextId = "NDD_TRUE";
            else if (isFalse(next)) nextId = "NDD_FALSE";
            else nextId = "NDD_" + next;

            sb.append("  ").append(nodeId).append(" -> ").append(nextId)
                    .append(" [label=\"#").append(bddId)
                    .append("\", labelfontcolor=black, labeldistance=3, labelangle=15];\n");

            printNDDStructure(next, sb, visited);
        }
    }

    /**
     * Simple key-value pair for encodeACL (field index, BDD handle).
     */
    public static class Pair<K, V> {
        private final K key;
        private final V value;

        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() { return key; }
        public V getValue() { return value; }
    }

    /**
     * Cache for unary/binary NDD operations (op1, op2, result slots by hash).
     */
    private static class IntOperationCache {
        private final int size;
        private final int[] op1;
        private final int[] op2;
        private final int[] res;
        private final int[] gen;   // generation stamp per slot
        private int generation;    // current generation; incremented on clear()
        /** Last result from getEntry (for setEntry). */
        int result;
        /** Last hash index from getEntry (for setEntry). */
        int hashValue;

        IntOperationCache(int cacheSize) {
            this.size = cacheSize;
            this.op1 = new int[cacheSize];
            this.op2 = new int[cacheSize];
            this.res = new int[cacheSize];
            this.gen = new int[cacheSize];
            this.generation = 1; // start at 1 so gen[*]=0 slots are immediately stale
        }

        /** Look up unary cache (e.g. not); return true if hit and result is set. */
        boolean getEntry(int a) {
            int hash = hashUnary(a);
            if (gen[hash] == generation && op1[hash] == a) {
                result = res[hash];
                return true;
            }
            hashValue = hash;
            return false;
        }

        /** Look up binary cache (e.g. and, or); return true if hit and result is set. */
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

        /** Look up an ordered binary operation such as set difference. */
        boolean getOrderedEntry(int a, int b) {
            int hash = hashOrderedBinary(a, b);
            if (gen[hash] == generation && op1[hash] == a && op2[hash] == b) {
                result = res[hash];
                return true;
            }
            hashValue = hash;
            return false;
        }

        /** Store unary result at index. */
        void setEntry(int index, int a, int result) {
            op1[index] = a;
            op2[index] = 0;
            res[index] = result;
            gen[index] = generation;
        }

        /** Store binary result at index. */
        void setEntry(int index, int a, int b, int result) {
            op1[index] = a;
            op2[index] = b;
            res[index] = result;
            gen[index] = generation;
        }

        void setOrderedEntry(int index, int a, int b, int result) {
            setEntry(index, a, b, result);
        }

        /** O(1) clear via generation increment - no array fill needed. */
        void clear() {
            generation++;
        }

        private int hashUnary(int a) {
            int h = a;
            h ^= (h >>> 16);
            h *= 0x45d9f3b;
            h ^= (h >>> 16);
            return (h & 0x7fffffff) % size;
        }

        private int hashBinary(int a, int b) {
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            int h = lo * 0x9e3779b9 + hi * 0x517cc1b7;
            h ^= (h >>> 16);
            h *= 0x45d9f3b;
            h ^= (h >>> 16);
            return (h & 0x7fffffff) % size;
        }

        private int hashOrderedBinary(int a, int b) {
            int h = a * 0x9e3779b9 + b * 0x517cc1b7;
            h ^= (h >>> 16);
            h *= 0x45d9f3b;
            h ^= (h >>> 16);
            return (h & 0x7fffffff) % size;
        }
    }

    /**
     * Int set for temporarily protected node ids (open-addressed hash set).
     */
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

        /** @return Whether the set contains the value. */
        boolean contains(int value) {
            if (value <= 1) return true;
            int pos = mix(value) & mask;
            while (table[pos] != EMPTY) {
                if (table[pos] == value) return true;
                pos = (pos + 1) & mask;
            }
            return false;
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

        /** Apply consumer to each element. */
        void forEach(IntConsumer consumer) {
            for (int value : table) {
                if (value != EMPTY) consumer.accept(value);
            }
        }

        private void rehash() {
            int[] old = table;
            int newCap = old.length << 1;
            table = new int[newCap];
            Arrays.fill(table, EMPTY);
            mask = newCap - 1;
            threshold = (int) (newCap * 0.7);
            size = 0;
            for (int value : old) {
                if (value != EMPTY) add(value);
            }
        }

        private int mix(int x) {
            x ^= (x >>> 16);
            x *= 0x7feb352d;
            x ^= (x >>> 15);
            x *= 0x846ca68b;
            x ^= (x >>> 16);
            return x;
        }
    }

    /**
     * Int-to-int map for edge collection (target -> label), open-addressed.
     */
    private static class IntIntMap {
        private static final int EMPTY = Integer.MIN_VALUE;
        private int[] keys;
        private int[] values;
        private int size;
        private int mask;
        private int threshold;

        IntIntMap(int capacity) {
            int cap = 1;
            while (cap < capacity * 2) cap <<= 1;
            keys = new int[cap];
            values = new int[cap];
            Arrays.fill(keys, EMPTY);
            mask = cap - 1;
            threshold = (int) (cap * 0.7);
        }

        void clearAndResize(int capacity) {
            int cap = 1;
            while (cap < capacity * 2) cap <<= 1;
            if (keys.length >= cap) {
                Arrays.fill(keys, EMPTY);
            } else {
                keys = new int[cap];
                values = new int[cap];
                Arrays.fill(keys, EMPTY);
            }
            size = 0;
            mask = cap - 1;
            threshold = (int) (cap * 0.7);
        }

        /** @return Value for key, or 0 if absent. */
        int get(int key) {
            int pos = mix(key) & mask;
            while (keys[pos] != EMPTY) {
                if (keys[pos] == key) return values[pos];
                pos = (pos + 1) & mask;
            }
            return 0;
        }

        void put(int key, int value) {
            if (size >= threshold) rehash();
            int pos = mix(key) & mask;
            while (keys[pos] != EMPTY) {
                if (keys[pos] == key) {
                    values[pos] = value;
                    return;
                }
                pos = (pos + 1) & mask;
            }
            keys[pos] = key;
            values[pos] = value;
            size++;
        }

        /** Apply consumer to each (key, value) pair. */
        void forEach(IntIntConsumer consumer) {
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != EMPTY) consumer.accept(keys[i], values[i]);
            }
        }

        private void rehash() {
            int[] oldKeys = keys;
            int[] oldValues = values;
            int newCap = oldKeys.length << 1;
            keys = new int[newCap];
            values = new int[newCap];
            Arrays.fill(keys, EMPTY);
            mask = newCap - 1;
            threshold = (int) (newCap * 0.7);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != EMPTY) put(oldKeys[i], oldValues[i]);
            }
        }

        private int mix(int x) {
            x ^= (x >>> 16);
            x *= 0x7feb352d;
            x ^= (x >>> 15);
            x *= 0x846ca68b;
            x ^= (x >>> 16);
            return x;
        }
    }

    /** Callback for IntIntMap.forEach. */
    private interface IntIntConsumer {
        void accept(int key, int value);
    }

    // ==================== Methods ported from ndd variant for benchmark compatibility ====================

    /**
     * Existential quantification: project out (remove) the given field.
     *
     * @param a     NDD node id.
     * @param field Field to quantify out.
     * @return Result node id.
     */
    public static int exist(int a, int field) {
        validateField(field);
        temporarilyProtect.clear();
        int res = existRec(a, field);
        runSafePointMaintenance();
        return res;
    }

    /**
     * Existentially quantify several fields. Fields may be supplied in any order.
     *
     * @param a Root NDD node id.
     * @param fields Fields to project out.
     * @return The projected NDD.
     */
    public static int exist(int a, int... fields) {
        if (fields == null) throw new IllegalArgumentException("fields must not be null");
        int result = ref(a);
        boolean[] seen = new boolean[fieldNum + 1];
        for (int field : fields) {
            validateField(field);
            if (seen[field]) throw new IllegalArgumentException("field specified more than once: " + field);
            seen[field] = true;
            int next = ref(exist(result, field));
            deref(result);
            result = next;
        }
        deref(result);
        return result;
    }

    /**
     * Restrict a field to a concrete bit vector and project that field out.
     * Bit 0 is the most significant bit, matching {@link #getVar(int, int)} and prefix encoders.
     *
     * @param a Root NDD node id.
     * @param field Field to fix.
     * @param valueBits Field value, one 0/1 entry per field bit.
     * @return The cofactor with {@code field} removed.
     */
    public static int restrict(int a, int field, int[] valueBits) {
        validateField(field);
        if (valueBits == null || valueBits.length != fieldWidth(field)) {
            throw new IllegalArgumentException("valueBits must contain exactly " + fieldWidth(field) + " bits");
        }
        for (int bit : valueBits) {
            if (bit != 0 && bit != 1) {
                throw new IllegalArgumentException("valueBits entries must be 0 or 1");
            }
        }
        return restrictWithAssignmentLabel(a, field, buildBooleanFieldAssignmentLabel(field, valueBits));
    }

    /**
     * Restrict a field to a concrete unsigned value and project that field out.
     *
     * @param a Root NDD node id.
     * @param field Field to fix.
     * @param value Unsigned field value.
     * @return The cofactor with {@code field} removed.
     */
    public static int restrict(int a, int field, long value) {
        validateField(field);
        if (value < 0) throw new IllegalArgumentException("value must be non-negative");
        int width = fieldWidth(field);
        if (width > 63) {
            throw new IllegalArgumentException("fields wider than 63 bits require restrict(a, field, int[])");
        }
        if (width < 63 && value >= (1L << width)) {
            throw new IllegalArgumentException("value does not fit in field " + field);
        }
        int[] valueBits = new int[width];
        for (int i = 0; i < width; i++) {
            valueBits[i] = (int) ((value >>> (width - 1 - i)) & 1L);
        }
        return restrict(a, field, valueBits);
    }

    /**
     * Find one complete satisfying assignment, or {@code null} when {@code a} is FALSE.
     * Every field assignment contains one 0/1 entry per field bit, independent of backend.
     */
    public static int[][] anySat(int a) {
        if (satCount(a) == 0) return null;
        int[][] assignment = new int[fieldNum + 1][];
        int working = ref(a);
        for (int field = 0; field <= fieldNum; field++) {
            int width = fieldWidth(field);
            assignment[field] = new int[width];
            for (int bit = 0; bit < width; bit++) {
                int zero = ref(and(working, getNotVar(field, bit)));
                if (satCount(zero) != 0) {
                    assignment[field][bit] = 0;
                    deref(working);
                    working = zero;
                } else {
                    deref(zero);
                    int one = ref(and(working, getVar(field, bit)));
                    if (satCount(one) == 0) {
                        deref(one);
                        throw new IllegalStateException("satisfying assignment disappeared during search");
                    }
                    assignment[field][bit] = 1;
                    deref(working);
                    working = one;
                }
            }
        }
        deref(working);
        return assignment;
    }

    /**
     * Enumerate complete satisfying assignments. The consumer may stop enumeration by returning false.
     * Enumeration is intentionally explicit: a function with wide fields can have exponentially many
     * assignments, so callers should normally stop early or use {@link #anySat(int)}.
     *
     * @param a Root NDD node id.
     * @param consumer Receives a defensive copy of each assignment.
     * @return Number of assignments delivered to the consumer.
     */
    public static long allSat(int a, AssignmentConsumer consumer) {
        if (consumer == null) throw new IllegalArgumentException("consumer must not be null");
        if (satCount(a) == 0) return 0;
        long[] count = new long[]{0};
        int[][] assignment = new int[fieldNum + 1][];
        int working = ref(a);
        allSatFields(working, 0, assignment, consumer, count);
        deref(working);
        return count[0];
    }

    /**
     * Substitute {@code targetField} for {@code sourceField}: the result is equivalent to
     * {@code a[sourceField := targetField]}. The fields must have the same width/domain size.
     */
    public static int substitute(int a, int sourceField, int targetField) {
        validateField(sourceField);
        validateField(targetField);
        if (sourceField == targetField) return a;
        if (fieldMode(sourceField) != fieldMode(targetField)) {
            throw new IllegalArgumentException(
                    "source and target fields must use the same label backend");
        }
        if (fieldWidth(sourceField) != fieldWidth(targetField)) {
            throw new IllegalArgumentException("source and target fields must have the same width/domain size");
        }
        int equality = buildFieldEquality(sourceField, targetField);
        int constrained = ref(and(a, equality));
        deref(equality);
        int result = exist(constrained, sourceField);
        deref(constrained);
        return result;
    }

    /**
     * Complete-field cofactor for every label backend. The assignment is a backend label, not an
     * NDD node, so only the original NDD is traversed and the target field is eliminated directly.
     */
    private static int restrictWithAssignmentLabel(int a, int field, int assignmentLabel) {
        temporarilyProtect.clear();
        try {
            HashMap<Integer, Integer> memo = new HashMap<>();
            int result = restrictRec(a, field, assignmentLabel, memo);
            runSafePointMaintenance();
            return result;
        } finally {
            derefLabel(field, assignmentLabel);
        }
    }

    private static int restrictRec(int a, int field, int assignmentLabel,
            HashMap<Integer, Integer> memo) {
        if (isTerminal(a)) return a;
        Integer cached = memo.get(a);
        if (cached != null) return cached;

        int aField = nodeTable.getField(a);
        if (aField > field) return a;

        int result;
        if (aField == field) {
            result = FALSE;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                int label = nodeTable.getEdgeLabel(a, i);
                if (backendForField(field).matches(label, assignmentLabel)) {
                    result = orRec(result, nodeTable.getEdgeTarget(a, i));
                }
            }
        } else {
            int frameStart = stackTop;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                int target = nodeTable.getEdgeTarget(a, i);
                int restrictedTarget = restrictRec(target, field, assignmentLabel, memo);
                edgeCollect(frameStart, aField, restrictedTarget,
                        refLabel(aField, nodeTable.getEdgeLabel(a, i)));
            }
            result = edgeFlush(frameStart, aField);
        }

        memo.put(a, result);
        temporarilyProtect.add(result);
        return result;
    }

    private static int buildBooleanFieldAssignmentLabel(int field, int[] valueBits) {
        int assignment = refLabel(field, getFieldUniverseLabel(field));
        for (int bit = 0; bit < valueBits.length; bit++) {
            int literal = valueBits[bit] == 0
                    ? bddNotVarsPerField.get(field)[bit]
                    : bddVarsPerField.get(field)[bit];
            int next = refLabel(field, labelAnd(field, assignment, literal));
            derefLabel(field, assignment);
            assignment = next;
        }
        return assignment;
    }

    private static int buildFieldEquality(int leftField, int rightField) {
        int equality = TRUE;
        int width = fieldWidth(leftField);
        for (int index = 0; index < width; index++) {
            int bothTrue = ref(and(getVar(leftField, index), getVar(rightField, index)));
            int bothFalse = ref(and(getNotVar(leftField, index), getNotVar(rightField, index)));
            int equalAtIndex = ref(or(bothTrue, bothFalse));
            deref(bothTrue);
            deref(bothFalse);
            int next = ref(and(equality, equalAtIndex));
            if (!isTerminal(equality)) deref(equality);
            deref(equalAtIndex);
            equality = next;
        }
        return equality;
    }

    private static boolean allSatFields(int working, int field, int[][] assignment,
            AssignmentConsumer consumer, long[] count) {
        if (field > fieldNum) {
            count[0]++;
            return consumer.accept(copyAssignment(assignment));
        }
        int width = fieldWidth(field);
        assignment[field] = new int[width];
        boolean result = allSatBits(working, field, 0, assignment, consumer, count);
        assignment[field] = null;
        return result;
    }

    private static boolean allSatBits(int working, int field, int bit, int[][] assignment,
            AssignmentConsumer consumer, long[] count) {
        if (bit == assignment[field].length) {
            return allSatFields(working, field + 1, assignment, consumer, count);
        }
        for (int value = 0; value <= 1; value++) {
            int literal = value == 0 ? getNotVar(field, bit) : getVar(field, bit);
            int candidate = ref(and(working, literal));
            if (satCount(candidate) != 0) {
                assignment[field][bit] = value;
                boolean continueEnumeration = allSatBits(candidate, field, bit + 1, assignment, consumer, count);
                deref(candidate);
                if (!continueEnumeration) return false;
            } else {
                deref(candidate);
            }
        }
        return true;
    }

    private static int[][] copyAssignment(int[][] assignment) {
        int[][] copy = new int[assignment.length][];
        for (int i = 0; i < assignment.length; i++) {
            copy[i] = assignment[i] == null ? null : Arrays.copyOf(assignment[i], assignment[i].length);
        }
        return copy;
    }

    private static void validateField(int field) {
        if (field < 0 || field > fieldNum) {
            throw new IllegalArgumentException("field out of range: " + field);
        }
    }

    private static int fieldWidth(int field) {
        return pendingFieldBitNums.get(field);
    }

    private static int existRec(int a, int field) {
        if (isTerminal(a)) return a;
        int aField = nodeTable.getField(a);
        if (aField > field) return a;

        int result;
        if (aField == field) {
            result = FALSE;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                result = orRec(result, nodeTable.getEdgeTarget(a, i));
            }
        } else {
            int frameStart = stackTop;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                int sub = existRec(nodeTable.getEdgeTarget(a, i), field);
                edgeCollect(frameStart, aField, sub,
                        refLabel(aField, nodeTable.getEdgeLabel(a, i)));
            }
            result = edgeFlush(frameStart, aField);
        }
        temporarilyProtect.add(result);
        return result;
    }

    // NOTE: toZero() / toZeroRec() ported from SRE-Benchmark are omitted here because they
    // depend on bddEngine.toOne() which is not available in the jdd-111 JAR.

    private static final HashMap<Long, Integer> atMostKCache = new HashMap<>();

    /**
     * Encode "at most k failures" constraint across fields as an NDD.
     *
     * @param bdd        BDD engine.
     * @param vars       BDD variable handles.
     * @param startField First field (failure vars start field).
     * @param endField   Last field.
     * @param k          Maximum failures allowed.
     * @return NDD node id encoding the constraint.
     */
    public static int encodeAtMostKFailureVarsSorted(BDD bdd, int[] vars, int startField, int endField, int k) {
        if (startField > endField) return getTrue();
        for (int field = startField; field <= endField; field++) {
            ensureFieldMode(field, LabelMode.BDD, "encodeAtMostKFailureVarsSorted");
        }
        return encodeAtMostKFailureVarsSortedRec(bdd, vars, endField, startField, k);
    }

    private static int encodeAtMostKFailureVarsSortedRec(BDD bdd, int[] vars, int endField, int currField, int k) {
        if (currField > endField) return getTrue();

        int startIdx = getFieldStartIndex(currField);
        int fieldSize = pendingFieldBitNums.get(currField);
        int[] fieldVars = new int[fieldSize];
        System.arraycopy(vars, startIdx, fieldVars, 0, fieldSize);

        IntIntMap map = new IntIntMap(k + 2);
        for (int i = 0; i <= k; i++) {
            long cacheKey = (((long) currField) << 32) | (i & 0xffffffffL);
            Integer cachedPred = atMostKCache.get(cacheKey);
            int pred = cachedPred != null
                    ? cachedPred
                    : bdd.ref(encodeBDD(bdd, fieldVars, fieldSize - 1, 0, i));
            if (cachedPred == null) {
                atMostKCache.put(cacheKey, pred);
            }
            int next = encodeAtMostKFailureVarsSortedRec(bdd, vars, endField, currField + 1, k - i);
            int nextPred = map.get(next);
            bdd.ref(pred);
            int t = bdd.ref(bdd.or(pred, nextPred));
            bdd.deref(pred);
            bdd.deref(nextPred);
            map.put(next, t);
        }

        int frameStart = stackTop;
        map.forEach((target, label) -> {
            edgeCollect(frameStart, currField, target, refLabel(currField, label));
        });
        return edgeFlush(frameStart, currField);
    }

    private static int getFieldStartIndex(int field) {
        int startIdx = 0;
        for (int i = 0; i < field; i++) {
            startIdx += pendingFieldBitNums.get(i);
        }
        return startIdx;
    }

    private static int encodeBDD(BDD bdd, int[] vars, int endVar, int currVar, int k) {
        if (k < 0) return 0;
        if (currVar > endVar) return k > 0 ? 0 : 1;
        int low = encodeBDD(bdd, vars, endVar, currVar + 1, k - 1);
        int high = encodeBDD(bdd, vars, endVar, currVar + 1, k);
        return bdd.mk(bdd.getVar(vars[endVar - currVar]), low, high);
    }
}
