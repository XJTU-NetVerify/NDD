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
import java.lang.reflect.Field;
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
        BOOLEAN_BDD,
        COMPLEMENTED_BDD,
        FINITE_DOMAIN_ZDD
    }

    /**
     * Size of operation caches (not, and, or).
     */
    private static int CACHE_SIZE = 10000;

    /**
     * The node table (node storage and unique table).
     */
    private static NodeTable nodeTable;

    /**
     * The internal BDD engine (shared with node table).
     */
    protected static BDD bddEngine;

    /**
     * Experimental complemented-edge BDD engine for Boolean edge labels.
     */
    private static ComplementedBDD bcddEngine;

    /**
     * Experimental ZDD engine for finite-domain value-set labels.
     */
    private static ZDD zddEngine;

    /**
     * Active edge-label representation.
     */
    private static LabelMode labelMode = LabelMode.BOOLEAN_BDD;

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

    /**
     * Shared BDD variable handles (right-aligned): sharedBddVars[maxBitNum-1] has lowest BDD var ID.
     */
    private static int[] sharedBddVars;

    /**
     * Shared BDD negated variable handles (right-aligned).
     */
    private static int[] sharedBddNotVars;

    /**
     * Shared ZDD variable ids for finite-domain label mode.
     */
    private static int[] sharedZddVarIds;

    /**
     * Shared singleton ZDD labels for each possible field value.
     */
    private static int[] sharedZddSingletons;

    /**
     * Universe label per field in finite-domain ZDD mode.
     */
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

    /**
     * Initial capacity of edge-collection stack.
     */
    private static final int INITIAL_STACK_SIZE = 100000;

    /**
     * Stack of edge targets during edge collection.
     */
    private static int[] stackTargets;

    /**
     * Stack of edge labels (BDD handles) during edge collection.
     */
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
        initNDD(nddTableSize, nddCacheSize, bddTableSize, bddCacheSize, LabelMode.BOOLEAN_BDD);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize, int bddCacheSize, LabelMode mode) {
        CACHE_SIZE = nddCacheSize;
        nodeTable = new NodeTable(nddTableSize, bddTableSize, bddCacheSize);
        bddEngine = nodeTable.getBddEngine();
        labelMode = mode;
        bcddEngine = (mode == LabelMode.COMPLEMENTED_BDD) ? new ComplementedBDD(bddTableSize, bddCacheSize) : null;
        zddEngine = (mode == LabelMode.FINITE_DOMAIN_ZDD) ? new ZDD(bddTableSize, bddCacheSize) : null;

        fieldNum = -1;
        fieldsGenerated = false;
        pendingFieldBitNums = new ArrayList<>();
        maxVariablePerField = new ArrayList<>();
        satCountDiv = new ArrayList<>();

        bddVarsPerField = new ArrayList<>();
        bddNotVarsPerField = new ArrayList<>();
        nddVarsPerField = new ArrayList<>();
        nddNotVarsPerField = new ArrayList<>();
        sharedBddVars = null;
        sharedBddNotVars = null;
        sharedZddVarIds = null;
        sharedZddSingletons = null;
        fieldUniverseLabels = new ArrayList<>();

        temporarilyProtect = new IntHashSet(1024);
        notCache = new IntOperationCache(CACHE_SIZE);
        andCache = new IntOperationCache(CACHE_SIZE);
        orCache = new IntOperationCache(CACHE_SIZE);

        stackTargets = new int[INITIAL_STACK_SIZE];
        stackLabels = new int[INITIAL_STACK_SIZE];
        stackTop = 0;

    }

    public static LabelMode getLabelMode() {
        return labelMode;
    }

    public static boolean isFiniteDomainZddMode() {
        return labelMode == LabelMode.FINITE_DOMAIN_ZDD;
    }

    /**
     * Declare a new field. Stores the bit number and reserves a field index.
     * BDD variable creation is deferred to generateFields() for cross-field sharing.
     *
     * @param bitNum Number of bits in this field.
     * @return The field index (0-based).
     */
    public static int declareField(int bitNum) {
        if (fieldsGenerated) {
            throw new IllegalStateException("Cannot declare field after generateFields() has been called");
        }
        pendingFieldBitNums.add(bitNum);
        fieldNum++;
        return fieldNum;
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

        // Find the maximum bit width across all fields
        int maxBitNum = 0;
        for (int bitNum : pendingFieldBitNums) {
            if (bitNum > maxBitNum) maxBitNum = bitNum;
        }

        if (labelMode == LabelMode.BOOLEAN_BDD) {
            // Create shared BDD variables in reverse order:
            // sharedBddVars[maxBitNum-1] gets the lowest BDD var ID,
            // sharedBddVars[0] gets the highest BDD var ID.
            sharedBddVars = new int[maxBitNum];
            sharedBddNotVars = new int[maxBitNum];
            for (int i = maxBitNum - 1; i >= 0; i--) {
                sharedBddVars[i] = bddEngine.ref(bddEngine.createVar());
                sharedBddNotVars[i] = bddEngine.ref(bddEngine.not(sharedBddVars[i]));
            }
        } else if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            sharedBddVars = new int[maxBitNum];
            sharedBddNotVars = new int[maxBitNum];
            for (int i = maxBitNum - 1; i >= 0; i--) {
                sharedBddVars[i] = bcddEngine.ref(bcddEngine.createVar());
                sharedBddNotVars[i] = bcddEngine.ref(bcddEngine.not(sharedBddVars[i]));
            }
        } else {
            sharedZddVarIds = new int[maxBitNum];
            sharedZddSingletons = new int[maxBitNum];
            for (int i = 0; i < maxBitNum; i++) {
                sharedZddVarIds[i] = zddEngine.createVar();
                sharedZddSingletons[i] = zddEngine.ref(zddEngine.single(sharedZddVarIds[i]));
            }
        }

        // Assign shared vars to each field using right-alignment:
        // a field with bitNum bits uses sharedBddVars[maxBitNum-bitNum .. maxBitNum-1]
        for (int f = 0; f < pendingFieldBitNums.size(); f++) {
            int bitNum = pendingFieldBitNums.get(f);
            int offset = maxBitNum - bitNum;

            nodeTable.declareField();

            int[] bddVars = new int[bitNum];
            int[] bddNotVars = new int[bitNum];
            int[] nddVars = new int[bitNum];
            int[] nddNotVars = new int[bitNum];
            int universe = 0;

            for (int i = 0; i < bitNum; i++) {
                if (labelMode != LabelMode.FINITE_DOMAIN_ZDD) {
                    bddVars[i] = sharedBddVars[offset + i];
                    bddNotVars[i] = sharedBddNotVars[offset + i];

                    nddVars[i] = nodeTable.mk(f, new int[]{TRUE}, new int[]{refLabel(bddVars[i])});
                    nodeTable.fixNDDNodeRefCount(nddVars[i]);

                    nddNotVars[i] = nodeTable.mk(f, new int[]{TRUE}, new int[]{refLabel(bddNotVars[i])});
                    nodeTable.fixNDDNodeRefCount(nddNotVars[i]);
                } else {
                    int singleton = sharedZddSingletons[i];
                    universe = labelOrTo(universe, refLabel(singleton), f);
                    nddVars[i] = nodeTable.mk(f, new int[]{TRUE}, new int[]{refLabel(singleton)});
                    nodeTable.fixNDDNodeRefCount(nddVars[i]);
                }
            }

            if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
                for (int i = 0; i < bitNum; i++) {
                    int negative = refLabel(zddEngine.diff(universe, sharedZddSingletons[i]));
                    nddNotVars[i] = nodeTable.mk(f, new int[]{TRUE}, new int[]{negative});
                    nodeTable.fixNDDNodeRefCount(nddNotVars[i]);
                }
            }

            bddVarsPerField.add(bddVars);
            bddNotVarsPerField.add(bddNotVars);
            nddVarsPerField.add(nddVars);
            nddNotVarsPerField.add(nddNotVars);
            fieldUniverseLabels.add(labelMode == LabelMode.FINITE_DOMAIN_ZDD ? universe : 1);

            // maxVariablePerField: max BDD var ID in this field = sharedBddVars[maxBitNum-1]'s var ID
            // With right-alignment, the max BDD var for any field is always sharedBddVars[maxBitNum-1]
            // which has var ID = 0 (lowest). sharedBddVars[0] has var ID = maxBitNum-1 (highest).
            // The BDD var ID for sharedBddVars[i] is (maxBitNum - 1 - i).
            // For field f with bitNum bits, vars span indices [offset..maxBitNum-1] in sharedBddVars,
            // which correspond to BDD var IDs [maxBitNum-1-offset .. 0] = [bitNum-1 .. 0].
            // Max BDD var ID for this field = bitNum - 1.
            // But for decompose to work correctly with sequential fields, we need cumulative ordering.
            // Since toNDD(int a) multi-field decompose is not used in application code,
            // we set these to keep the data structure consistent (same as sequential allocation):
            if (maxVariablePerField.isEmpty()) {
                maxVariablePerField.add(bitNum - 1);
            } else {
                maxVariablePerField.add(maxVariablePerField.get(maxVariablePerField.size() - 1) + bitNum);
            }

            double factor = fieldCardinality(bitNum);
            for (int i = 0; i < satCountDiv.size(); i++) {
                satCountDiv.set(i, satCountDiv.get(i) * factor);
            }
            int totalBitsBefore = 0;
            if (maxVariablePerField.size() > 1) {
                totalBitsBefore = maxVariablePerField.get(maxVariablePerField.size() - 2) + 1;
            }
            satCountDiv.add(labelMode == LabelMode.FINITE_DOMAIN_ZDD ? 1.0 : Math.pow(2.0, totalBitsBefore));
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
        ensureBooleanBddMode("BDD variable handles");
        return bddVarsPerField.get(field);
    }
    /** @return BDD negated variable handles for the field. */
    public static int[] getNotBDDVars(int field) {
        ensureBooleanBddMode("BDD negated variable handles");
        return bddNotVarsPerField.get(field);
    }

    /** @return The internal BDD engine. */
    public static BDD getBDDEngine() { return bddEngine; }

    public static long getLabelNodeCount() {
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            return bcddEngine.getNodeCount();
        }
        if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            return reflectActiveNodeCount(zddEngine);
        }
        return reflectActiveNodeCount(bddEngine);
    }

    public static long getLabelTotalCreated() {
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            return bcddEngine.getTotalCreated();
        }
        return jdd.bdd.NodeTable.mkCount;
    }

    public static void gcLabelEngine() {
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            return;
        }
        if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            zddEngine.gc();
            return;
        }
        bddEngine.gc();
    }

    private static long reflectActiveNodeCount(Object engine) {
        try {
            long tableSize = readLongField(engine, "table_size");
            long freeNodes = readLongField(engine, "free_nodes_count");
            return tableSize - freeNodes;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to read active label-node count", e);
        }
    }

    private static long readLongField(Object target, String fieldName) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return ((Number) field.get(target)).longValue();
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    public static int refLabel(int label) {
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            return bcddEngine.ref(label);
        }
        if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            return zddEngine.ref(label);
        }
        return bddEngine.ref(label);
    }

    public static void derefLabel(int label) {
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            bcddEngine.deref(label);
        } else if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            zddEngine.deref(label);
        } else {
            bddEngine.deref(label);
        }
    }

    public static boolean isUniverseEdgeLabel(int field, int label) {
        if (labelMode != LabelMode.FINITE_DOMAIN_ZDD) {
            return label == 1;
        }
        return field >= 0 && field < fieldUniverseLabels.size() && label == fieldUniverseLabels.get(field);
    }

    private static int getFieldUniverseLabel(int field) {
        if (labelMode != LabelMode.FINITE_DOMAIN_ZDD) {
            return 1;
        }
        return fieldUniverseLabels.get(field);
    }

    private static void ensureBooleanBddMode(String feature) {
        if (labelMode != LabelMode.BOOLEAN_BDD) {
            throw new UnsupportedOperationException(feature + " is only supported in BOOLEAN_BDD mode");
        }
    }

    private static double fieldCardinality(int fieldSize) {
        return labelMode == LabelMode.FINITE_DOMAIN_ZDD ? fieldSize : Math.pow(2.0, fieldSize);
    }

    private static int labelAnd(int a, int b) {
        if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            return zddEngine.intersect(a, b);
        }
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            return bcddEngine.and(a, b);
        }
        return bddEngine.and(a, b);
    }

    private static int labelDiff(int a, int b) {
        if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            return zddEngine.diff(a, b);
        }
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            return bcddEngine.and(a, bcddEngine.not(b));
        }
        return bddEngine.and(a, bddEngine.not(b));
    }

    private static int labelNot(int field, int label) {
        if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            return zddEngine.diff(getFieldUniverseLabel(field), label);
        }
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            return bcddEngine.not(label);
        }
        return bddEngine.not(label);
    }

    private static int labelOrTo(int current, int add, int field) {
        if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            if (current == 0) {
                return add;
            }
            int result = zddEngine.ref(zddEngine.union(current, add));
            zddEngine.deref(current);
            zddEngine.deref(add);
            return result;
        }
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            int result = bcddEngine.ref(bcddEngine.or(current, add));
            bcddEngine.deref(current);
            bcddEngine.deref(add);
            return result;
        }
        return bddEngine.orTo(current, add);
    }

    private static int labelAndTo(int current, int other, int field) {
        if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            int result = zddEngine.ref(zddEngine.intersect(current, other));
            zddEngine.deref(current);
            return result;
        }
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            int result = bcddEngine.ref(bcddEngine.and(current, other));
            bcddEngine.deref(current);
            return result;
        }
        return bddEngine.andTo(current, other);
    }

    private static double labelSatCount(int field, int label) {
        if (labelMode == LabelMode.FINITE_DOMAIN_ZDD) {
            return zddEngine.count(label);
        }
        int fieldBits = pendingFieldBitNums.get(field);
        double satDivisor = Math.pow(2.0, sharedBddVars.length - fieldBits);
        if (labelMode == LabelMode.COMPLEMENTED_BDD) {
            return bcddEngine.satCount(label) / satDivisor;
        }
        return bddEngine.satCount(label) / satDivisor;
    }

    /**
     * Clear not/and/or operation caches (e.g. after gc).
     */
    public static void clearCaches() {
        notCache.clear();
        andCache.clear();
        orCache.clear();
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
     * Collect one edge (target, label) into the stack; merge with same target by OR-ing labels.
     * Each recursive operation owns a stack frame `[frameStart, stackTop)`, which lets us reuse
     * one global edge buffer instead of allocating a fresh per-node map or list on the hot path.
     *
     * @param frameStart Start of current frame in stack.
     * @param target     Target node id.
     * @param label      BDD handle for edge label (caller ref'd).
     */
    private static void edgeCollect(int frameStart, int target, int label) {
        if (target == FALSE) {
            derefLabel(label);
            return;
        }

        for (int i = frameStart; i < stackTop; i++) {
            if (stackTargets[i] == target) {
                int oldLabel = stackLabels[i];
                stackLabels[i] = labelOrTo(oldLabel, label, nodeTable.getField(target));
                return;
            }
        }

        if (stackTop >= stackTargets.length) growStack();
        stackTargets[stackTop] = target;
        stackLabels[stackTop] = label;
        stackTop++;
    }

    /**
     * Flush collected edges: sort by target, then create/reuse node via nodeTable.mk.
     * Sorting gives the unique table a canonical edge order even though edgeCollect appends in
     * traversal order while opportunistically merging duplicate targets.
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

        for (int i = frameStart + 1; i < stackTop; i++) {
            int t = stackTargets[i];
            int l = stackLabels[i];
            int j = i - 1;
            while (j >= frameStart && stackTargets[j] > t) {
                stackTargets[j + 1] = stackTargets[j];
                stackLabels[j + 1] = stackLabels[j];
                j--;
            }
            stackTargets[j + 1] = t;
            stackLabels[j + 1] = l;
        }

        int res = nodeTable.mk(field, stackTargets, stackLabels, frameStart, size);

        stackTop = frameStart;
        return res;
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
            edgeCollect(frameStart, target, refLabel(label));
        });
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
                    int intersect = refLabel(labelAnd(aLabel, bLabel));
                    if (intersect != 0) {
                        int sub = andRec(aTarget, bTarget);
                        edgeCollect(frameStart, sub, intersect);
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
                edgeCollect(frameStart, sub, refLabel(aLabel));
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
                resA.put(nodeTable.getEdgeTarget(a, i), refLabel(nodeTable.getEdgeLabel(a, i)));
            }
            for (int i = 0; i < bCount; i++) {
                resB.put(nodeTable.getEdgeTarget(b, i), refLabel(nodeTable.getEdgeLabel(b, i)));
            }

            for (int i = 0; i < aCount; i++) {
                int aTarget = nodeTable.getEdgeTarget(a, i);
                int aLabel = nodeTable.getEdgeLabel(a, i);
                for (int j = 0; j < bCount; j++) {
                    int bTarget = nodeTable.getEdgeTarget(b, j);
                    int bLabel = nodeTable.getEdgeLabel(b, j);
                    int intersect = refLabel(labelAnd(aLabel, bLabel));
                    if (intersect != 0) {
                        int notIntersect = refLabel(labelNot(aField, intersect));
                        int ra = resA.get(aTarget);
                        resA.put(aTarget, labelAndTo(ra, notIntersect, aField));
                        int rb = resB.get(bTarget);
                        resB.put(bTarget, labelAndTo(rb, notIntersect, aField));
                        derefLabel(notIntersect);
                        int sub = orRec(aTarget, bTarget);
                        edgeCollect(frameStart, sub, intersect);
                    }
                }
            }

            resA.forEach((key, value) -> {
                if (value != 0) edgeCollect(frameStart, key, refLabel(value));
                derefLabel(value);
            });
            resB.forEach((key, value) -> {
                if (value != 0) edgeCollect(frameStart, key, refLabel(value));
                derefLabel(value);
            });
            // maps are GC'd
        } else {
            if (aField > bField) {
                int t = a; a = b; b = t;
                int tf = aField; aField = bField; bField = tf;
            }
            int residualB = refLabel(getFieldUniverseLabel(aField));
            int aCount = nodeTable.getEdgeCount(a);
            for (int i = 0; i < aCount; i++) {
                int aTarget = nodeTable.getEdgeTarget(a, i);
                int aLabel = nodeTable.getEdgeLabel(a, i);
                int notInt = refLabel(labelNot(aField, aLabel));
                residualB = labelAndTo(residualB, notInt, aField);
                derefLabel(notInt);

                int sub = orRec(aTarget, b);
                edgeCollect(frameStart, sub, refLabel(aLabel));
            }
            if (residualB != 0) edgeCollect(frameStart, b, residualB);
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
        int residual = refLabel(getFieldUniverseLabel(field));

        int aCount = nodeTable.getEdgeCount(a);
        for (int i = 0; i < aCount; i++) {
            int aTarget = nodeTable.getEdgeTarget(a, i);
            int aLabel = nodeTable.getEdgeLabel(a, i);
            int notIntersect = refLabel(labelNot(field, aLabel));
            residual = labelAndTo(residual, notIntersect, field);
            derefLabel(notIntersect);

            int sub = notRec(aTarget);
            edgeCollect(frameStart, sub, refLabel(aLabel));
        }

        if (residual != 0) edgeCollect(frameStart, TRUE, residual);

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
        int n = notRec(b);
        temporarilyProtect.add(n);
        int res = andRec(a, n);
        runSafePointMaintenance();
        return res;
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
     * Number of satisfying assignments of the NDD (via conversion to BDD).
     *
     * @param ndd Root node id.
     * @return Sat count.
     */
    public static double satCount(int ndd) {
        return satCountRec(ndd, 0);
    }

    private static double satCountRec(int ndd, int field) {
        if (ndd == FALSE) return 0;
        if (ndd == TRUE) {
            if (field > fieldNum) return 1;
            double result = 1;
            for (int f = field; f <= fieldNum; f++) {
                result *= fieldCardinality(pendingFieldBitNums.get(f));
            }
            return result;
        }
        double result = 0;
        int nddField = nodeTable.getField(ndd);
        if (field == nddField) {
            int count = nodeTable.getEdgeCount(ndd);
            for (int i = 0; i < count; i++) {
                int target = nodeTable.getEdgeTarget(ndd, i);
                int label = nodeTable.getEdgeLabel(ndd, i);
                double bddSat = labelSatCount(field, label);
                double nddSat = satCountRec(target, field + 1);
                result += bddSat * nddSat;
            }
        } else {
            // Field is skipped in this NDD branch - all values valid
            int fieldSize = pendingFieldBitNums.get(field);
            result = fieldCardinality(fieldSize) * satCountRec(ndd, field + 1);
        }
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
        ensureBooleanBddMode("encodePrefix");
        if (prefixBinary.length == 0) return TRUE;
        int prefixBDD = encodePrefixBDD(prefixBinary, getBDDVars(field), getNotBDDVars(field));
        return nodeTable.mk(field, new int[]{TRUE}, new int[]{prefixBDD});
    }

    /**
     * Encode multiple binary prefixes as union (or) of prefix NDDs.
     *
     * @param prefixsBinary List of binary prefixes.
     * @param field         Field index.
     * @return NDD node id.
     */
    public static int encodePrefixs(ArrayList<int[]> prefixsBinary, int field) {
        ensureBooleanBddMode("encodePrefixs");
        int prefixsBDD = 0;
        for (int[] prefix : prefixsBinary) {
            prefixsBDD = bddEngine.orTo(prefixsBDD, encodePrefixBDD(prefix, getBDDVars(field), getNotBDDVars(field)));
        }
        return nodeTable.mk(field, new int[]{TRUE}, new int[]{prefixsBDD});
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
        ensureBooleanBddMode("encodeACL");
        int result = TRUE;
        for (int i = perFieldBDD.size() - 1; i >= 0; i--) {
            if (perFieldBDD.get(i).getValue() != 1) {
                result = nodeTable.mk(perFieldBDD.get(i).getKey(),
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
                        edgeCollect(frameStart, converted.get(e.getKey()), refLabel(e.getValue()));
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
        temporarilyProtect.clear();
        int res = existRec(a, field);
        runSafePointMaintenance();
        return res;
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
                edgeCollect(frameStart, sub, refLabel(nodeTable.getEdgeLabel(a, i)));
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
            edgeCollect(frameStart, target, refLabel(label));
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
