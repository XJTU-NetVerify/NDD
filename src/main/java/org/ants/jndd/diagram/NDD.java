package org.ants.jndd.diagram;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.IntConsumer;

import javafx.util.Pair;
import jdd.bdd.BDD;
import org.ants.jndd.nodetable.NodeTable;
import org.ants.jndd.utils.DecomposeBDD;
import org.ants.jndd.utils.Rational;

public class NDD {
    public enum LabelMode { BOOLEAN_BDD, COMPLEMENTED_BDD, FINITE_DOMAIN_ZDD }
    private static int CACHE_SIZE = 10000;
    private static final int INITIAL_STACK_SIZE = 100000;
    private static final int FALSE_ID = 0;
    private static final int TRUE_ID = 1;

    private static NodeTable nodeTable;
    protected static BDD bddEngine;
    protected static int fieldNum;
    private static boolean fieldsGenerated;
    private static ArrayList<Integer> pendingFieldBitNums;
    private static ArrayList<Integer> maxVariablePerField;
    private static ArrayList<Double> satCountDiv;
    private static ArrayList<int[]> bddVarsPerField;
    private static ArrayList<int[]> bddNotVarsPerField;
    private static ArrayList<int[]> nddVarsPerField;
    private static ArrayList<int[]> nddNotVarsPerField;
    private static int[] sharedBddVars;
    private static int[] sharedBddNotVars;
    private static IntHashSet temporarilyProtect;
    private static HashSet<NDD> temporarilyProtectObjects;
    private static IntOperationCache notCache;
    private static IntOperationCache andCache;
    private static IntOperationCache orCache;
    private static IntOperationCache addCache;
    private static IntOperationCache subCache;
    private static IntOperationCache mulCache;
    private static IntOperationCache divCache;
    private static IntOperationCache sumAbstractCache;
    private static int[] stackTargets;
    private static int[] stackLabels;
    private static int stackTop;
    private static NDD[] wrappers;
    private static Runnable externalCacheCleaner = () -> {};
    private static double eps = 0.000001;

    protected final int id;
    protected int field;

    protected NDD() {
        this.id = -1;
        this.field = NodeTable.TERMINAL_FIELD;
    }

    protected NDD(int nodeId) {
        this.id = nodeId;
        this.field = nodeTable == null ? NodeTable.TERMINAL_FIELD : nodeTable.getField(nodeId);
    }

    public static void initNDD(int nddTableSize, int bddTableSize, int bddCacheSize) {
        nodeTable = new NodeTable(nddTableSize, bddTableSize, bddCacheSize);
        bddEngine = nodeTable.getBddEngine();
        fieldNum = -1;
        fieldsGenerated = false;
        pendingFieldBitNums = new ArrayList<>();
        maxVariablePerField = new ArrayList<>();
        satCountDiv = new ArrayList<>();
        bddVarsPerField = new ArrayList<>();
        bddNotVarsPerField = new ArrayList<>();
        nddVarsPerField = new ArrayList<>();
        nddNotVarsPerField = new ArrayList<>();
        temporarilyProtect = new IntHashSet(1024);
        temporarilyProtectObjects = new HashSet<>();
        notCache = new IntOperationCache(CACHE_SIZE);
        andCache = new IntOperationCache(CACHE_SIZE);
        orCache = new IntOperationCache(CACHE_SIZE);
        addCache = new IntOperationCache(CACHE_SIZE);
        subCache = new IntOperationCache(CACHE_SIZE);
        mulCache = new IntOperationCache(CACHE_SIZE);
        divCache = new IntOperationCache(CACHE_SIZE);
        sumAbstractCache = new IntOperationCache(CACHE_SIZE);
        stackTargets = new int[INITIAL_STACK_SIZE];
        stackLabels = new int[INITIAL_STACK_SIZE];
        stackTop = 0;
        wrappers = new NDD[16];
        wrap(FALSE_ID);
        wrap(TRUE_ID);
    }

    public static void initNDD(int nddTableSize, int bddTableSize, int bddCacheSize, LabelMode labelMode) {
        initNDD(nddTableSize, bddTableSize, bddCacheSize);
    }

    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize, int bddCacheSize) {
        CACHE_SIZE = nddCacheSize;
        initNDD(nddTableSize, bddTableSize, bddCacheSize);
    }

    public static int declareField(int bitNum) {
        if (fieldsGenerated) {
            throw new IllegalStateException("Cannot declare field after generateFields()");
        }
        fieldNum++;
        pendingFieldBitNums.add(bitNum);
        if (maxVariablePerField.isEmpty()) {
            maxVariablePerField.add(bitNum - 1);
        } else {
            maxVariablePerField.add(maxVariablePerField.get(maxVariablePerField.size() - 1) + bitNum);
        }

        nodeTable.declareField();
        return fieldNum;
    }

    public static void generateFields() {
        if (fieldsGenerated) {
            return;
        }
        fieldsGenerated = true;
        if (pendingFieldBitNums.isEmpty()) {
            return;
        }

        int maxBitNum = 0;
        for (int bitNum : pendingFieldBitNums) {
            maxBitNum = Math.max(maxBitNum, bitNum);
        }
        sharedBddVars = new int[maxBitNum];
        sharedBddNotVars = new int[maxBitNum];
        for (int i = maxBitNum - 1; i >= 0; i--) {
            sharedBddVars[i] = bddEngine.ref(bddEngine.createVar());
            sharedBddNotVars[i] = bddEngine.ref(bddEngine.not(sharedBddVars[i]));
        }

        satCountDiv = new ArrayList<>();
        for (int bitNum : pendingFieldBitNums) {
            satCountDiv.add(Math.pow(2.0, maxBitNum - bitNum));
        }

        for (int field = 0; field < pendingFieldBitNums.size(); field++) {
            generateFieldVars(field, pendingFieldBitNums.get(field), maxBitNum);
        }
    }

    private static void ensureFieldsGenerated() {
        if (!fieldsGenerated) {
            throw new IllegalStateException("Call generateFields() after declaring all fields and before using NDD variables");
        }
    }

    private static void generateFieldVars(int field, int bitNum, int maxBitNum) {
        int[] bddVars = new int[bitNum];
        int[] bddNotVars = new int[bitNum];
        int[] nddVars = new int[bitNum];
        int[] nddNotVars = new int[bitNum];
        int offset = maxBitNum - bitNum;
        for (int i = 0; i < bitNum; i++) {
            bddVars[i] = sharedBddVars[offset + i];
            bddNotVars[i] = sharedBddNotVars[offset + i];
            nddVars[i] = nodeTable.mk(field, new int[] { TRUE_ID }, new int[] { refLabel(bddVars[i]) });
            nddNotVars[i] = nodeTable.mk(field, new int[] { TRUE_ID }, new int[] { refLabel(bddNotVars[i]) });
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
        return bddVarsPerField.get(field);
    }

    public static int[] getNotBDDVars(int field) {
        ensureFieldsGenerated();
        return bddNotVarsPerField.get(field);
    }

    public static BDD getBDDEngine() {
        return bddEngine;
    }

    public static int getFalseId() {
        return FALSE_ID;
    }

    public static boolean isUniverseEdgeLabel(int label) {
        return label == 1;
    }

    public static int refLabel(int label) {
        return bddEngine.ref(label);
    }

    public static void derefLabel(int label) {
        bddEngine.deref(label);
    }

    public static void clearCaches() {
        notCache.clear();
        andCache.clear();
        orCache.clear();
        addCache.clear();
        subCache.clear();
        mulCache.clear();
        divCache.clear();
        sumAbstractCache.clear();
        externalCacheCleaner.run();
    }

    public static void setExternalCacheCleaner(Runnable cleaner) {
        externalCacheCleaner = cleaner == null ? () -> {} : cleaner;
    }

    public static void forEachTemporarilyProtect(IntConsumer consumer) {
        temporarilyProtect.forEach(consumer);
        for (NDD ndd : temporarilyProtectObjects) {
            consumer.accept(ndd.id);
        }
    }

    private static void runSafePointMaintenance() {
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
                ? new Terminal(nodeId, nodeTable.getTerminalValue(nodeId))
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

    public static HashSet<NDD> getTemporarilyProtect() {
        return temporarilyProtectObjects;
    }

    private static Rational value(int nodeId) {
        return nodeTable.getTerminalValue(nodeId);
    }

    private static int terminal(Rational value) {
        return nodeTable.mkTerminal(value);
    }

    private static void edgeCollect(int frameStart, int target, int label) {
        if (target == FALSE_ID) {
            derefLabel(label);
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
        if (size == 1 && isUniverseEdgeLabel(stackLabels[frameStart])) {
            int target = stackTargets[frameStart];
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
                currentLabel = bddEngine.orTo(currentLabel, label);
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

    protected static void addEdge(HashMap<NDD, Integer> edges, NDD descendant, int labelBDD) {
        if (descendant.isFalse()) {
            derefLabel(labelBDD);
            return;
        }
        Integer oldLabel = edges.get(descendant);
        int newLabel = bddEngine.orTo(oldLabel == null ? 0 : oldLabel, labelBDD);
        edges.put(descendant, newLabel);
    }

    public static NDD mk(int field, HashMap<NDD, Integer> edges) {
        int frameStart = stackTop;
        for (Map.Entry<NDD, Integer> edge : edges.entrySet()) {
            edgeCollect(frameStart, id(edge.getKey()), edge.getValue());
        }
        return wrap(edgeFlush(frameStart, field));
    }

    public static NDD mk(int field, NDD[] targets, int[] labels) {
        if (targets.length != labels.length) {
            throw new IllegalArgumentException("targets and labels must have the same length");
        }
        int frameStart = stackTop;
        for (int i = 0; i < targets.length; i++) {
            edgeCollect(frameStart, id(targets[i]), refLabel(labels[i]));
        }
        return wrap(edgeFlush(frameStart, field));
    }

    public static NDD and(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = andRec(id(a), id(b));
        runSafePointMaintenance();
        return wrap(res);
    }

    public static NDD or(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = orRec(id(a), id(b));
        runSafePointMaintenance();
        return wrap(res);
    }

    public static NDD not(NDD a) {
        temporarilyProtect.clear();
        int res = notRec(id(a));
        runSafePointMaintenance();
        return wrap(res);
    }

    public static NDD add(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = addRec(id(a), id(b));
        runSafePointMaintenance();
        return wrap(res);
    }

    public static NDD sub(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = subRec(id(a), id(b));
        runSafePointMaintenance();
        return wrap(res);
    }

    public static NDD mul(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = mulRec(id(a), id(b));
        runSafePointMaintenance();
        return wrap(res);
    }

    public static NDD div(NDD a, NDD b) {
        temporarilyProtect.clear();
        int res = divRec(id(a), id(b));
        runSafePointMaintenance();
        return wrap(res);
    }

    public static NDD diff(NDD a, NDD b) {
        temporarilyProtect.clear();
        int n = notRec(id(b));
        temporarilyProtect.add(n);
        int res = andRec(id(a), n);
        runSafePointMaintenance();
        return wrap(res);
    }

    public static NDD imp(NDD a, NDD b) {
        temporarilyProtect.clear();
        int n = notRec(id(a));
        temporarilyProtect.add(n);
        int res = orRec(n, id(b));
        runSafePointMaintenance();
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
                    int intersect = bddEngine.ref(bddEngine.and(aLabel, nodeTable.getEdgeLabel(b, j)));
                    if (intersect != 0) {
                        edgeCollect(frameStart, andRec(aTarget, nodeTable.getEdgeTarget(b, j)), intersect);
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
                edgeCollect(frameStart, sub, refLabel(nodeTable.getEdgeLabel(a, i)));
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
            int residualB = refLabel(1);
            int aCount = nodeTable.getEdgeCount(a);
            for (int i = 0; i < aCount; i++) {
                int aLabel = nodeTable.getEdgeLabel(a, i);
                int notInt = bddEngine.ref(bddEngine.not(aLabel));
                residualB = bddEngine.andTo(residualB, notInt);
                bddEngine.deref(notInt);
                int sub = orRec(nodeTable.getEdgeTarget(a, i), b);
                edgeCollect(frameStart, sub, refLabel(aLabel));
            }
            if (residualB != 0) edgeCollect(frameStart, b, residualB);
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
            return value(a).doubleValue() == 0.0 ? TRUE_ID : FALSE_ID;
        }
        if (notCache.getEntry(a)) return notCache.result;
        int cacheIndex = notCache.hashValue;

        int frameStart = stackTop;
        int residual = refLabel(1);
        int count = nodeTable.getEdgeCount(a);
        for (int i = 0; i < count; i++) {
            int label = nodeTable.getEdgeLabel(a, i);
            int notIntersect = bddEngine.ref(bddEngine.not(label));
            residual = bddEngine.andTo(residual, notIntersect);
            bddEngine.deref(notIntersect);
            edgeCollect(frameStart, notRec(nodeTable.getEdgeTarget(a, i)), refLabel(label));
        }
        if (residual != 0) edgeCollect(frameStart, TRUE_ID, residual);
        int res = edgeFlush(frameStart, nodeTable.getField(a));
        temporarilyProtect.add(res);
        notCache.setEntry(cacheIndex, a, res);
        return res;
    }

    private enum TerminalOp { ADD, SUB, MUL, DIV, OR }

    private static int terminalOp(int a, int b, TerminalOp op) {
        Rational av = value(a);
        Rational bv = value(b);
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
            int residualB = refLabel(1);
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                int label = nodeTable.getEdgeLabel(a, i);
                int notInt = bddEngine.ref(bddEngine.not(label));
                residualB = bddEngine.andTo(residualB, notInt);
                bddEngine.deref(notInt);
                int sub = arithmeticChild(nodeTable.getEdgeTarget(a, i), b, op);
                edgeCollect(frameStart, sub, refLabel(label));
            }
            if (op != TerminalOp.MUL && residualB != 0) {
                int sub = arithmeticChild(FALSE_ID, b, op);
                edgeCollect(frameStart, sub, residualB);
            } else {
                derefLabel(residualB);
            }
            int res = edgeFlush(frameStart, aField);
            temporarilyProtect.add(res);
            return res;
        }

        int residualA = refLabel(1);
        int count = nodeTable.getEdgeCount(b);
        for (int i = 0; i < count; i++) {
            int label = nodeTable.getEdgeLabel(b, i);
            int notInt = bddEngine.ref(bddEngine.not(label));
            residualA = bddEngine.andTo(residualA, notInt);
            bddEngine.deref(notInt);
            int sub = arithmeticChild(a, nodeTable.getEdgeTarget(b, i), op);
            edgeCollect(frameStart, sub, refLabel(label));
        }
        if (op != TerminalOp.MUL && residualA != 0) {
            int sub = arithmeticChild(a, FALSE_ID, op);
            edgeCollect(frameStart, sub, residualA);
        } else {
            derefLabel(residualA);
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

    public static NDD sumAbstract(NDD a, int field) {
        if (field < 0 || field > fieldNum) {
            throw new IllegalArgumentException("invalid field: " + field);
        }
        temporarilyProtect.clear();
        int result = sumAbstractRec(id(a), field);
        runSafePointMaintenance();
        return wrap(result);
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
                int label = nodeTable.getEdgeLabel(a, i);
                long multiplicity = Math.round(
                        bddEngine.satCount(label) / satCountDiv.get(field));
                if (multiplicity == 0) {
                    continue;
                }
                int target = nodeTable.getEdgeTarget(a, i);
                int weighted = multiplicity == 1
                        ? target
                        : mulRec(target, terminal(new Rational(multiplicity)));
                result = addRec(result, weighted);
                temporarilyProtect.add(result);
            }
        } else {
            int frameStart = stackTop;
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                int target = sumAbstractRec(nodeTable.getEdgeTarget(a, i), field);
                edgeCollect(frameStart, target, refLabel(nodeTable.getEdgeLabel(a, i)));
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
        int aCount = nodeTable.getEdgeCount(a);
        int bCount = nodeTable.getEdgeCount(b);
        int[] residualA = new int[aCount];
        int[] residualB = new int[bCount];
        for (int i = 0; i < aCount; i++) {
            residualA[i] = refLabel(nodeTable.getEdgeLabel(a, i));
        }
        for (int i = 0; i < bCount; i++) {
            residualB[i] = refLabel(nodeTable.getEdgeLabel(b, i));
        }

        int field = nodeTable.getField(a);
        for (int i = 0; i < aCount; i++) {
            int aTarget = nodeTable.getEdgeTarget(a, i);
            int aLabel = nodeTable.getEdgeLabel(a, i);
            for (int j = 0; j < bCount; j++) {
                int bTarget = nodeTable.getEdgeTarget(b, j);
                int bLabel = nodeTable.getEdgeLabel(b, j);
                int intersect = bddEngine.ref(bddEngine.and(aLabel, bLabel));
                if (intersect != 0) {
                    int notIntersect = bddEngine.ref(bddEngine.not(intersect));
                    residualA[i] = bddEngine.andTo(residualA[i], notIntersect);
                    residualB[j] = bddEngine.andTo(residualB[j], notIntersect);
                    bddEngine.deref(notIntersect);
                    int sub = isOr ? orRec(aTarget, bTarget) : arithmeticChild(aTarget, bTarget, op);
                    edgeCollect(frameStart, sub, intersect);
                }
            }
        }

        for (int i = 0; i < aCount; i++) {
            int target = nodeTable.getEdgeTarget(a, i);
            int label = residualA[i];
            if (label != 0) {
                edgeCollect(frameStart,
                        isOr ? target : arithmeticChild(target, FALSE_ID, op), refLabel(label));
            }
            derefLabel(label);
        }
        for (int i = 0; i < bCount; i++) {
            int target = nodeTable.getEdgeTarget(b, i);
            int label = residualB[i];
            if (label != 0) {
                edgeCollect(frameStart,
                        isOr ? target : arithmeticChild(FALSE_ID, target, op), refLabel(label));
            }
            derefLabel(label);
        }
    }

    public static NDD exist(NDD a, int field) {
        temporarilyProtect.clear();
        int res = existRec(id(a), field);
        runSafePointMaintenance();
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
            for (int i = 0; i < count; i++) {
                edgeCollect(frameStart, existRec(nodeTable.getEdgeTarget(a, i), field),
                        refLabel(nodeTable.getEdgeLabel(a, i)));
            }
            result = edgeFlush(frameStart, nodeTable.getField(a));
        }
        temporarilyProtect.add(result);
        return result;
    }

    public static NDD encodePrefix(int[] prefixBinary, int field) {
        if (prefixBinary.length == 0) return getTrue();
        int prefixBDD = encodePrefixBDD(prefixBinary, getBDDVars(field), getNotBDDVars(field));
        return wrap(nodeTable.mk(field, new int[] { TRUE_ID }, new int[] { prefixBDD }));
    }

    public static NDD encodePrefixs(ArrayList<int[]> prefixsBinary, int field) {
        int prefixsBDD = 0;
        for (int[] prefix : prefixsBinary) {
            prefixsBDD = bddEngine.orTo(prefixsBDD, encodePrefixBDD(prefix, getBDDVars(field), getNotBDDVars(field)));
        }
        return wrap(nodeTable.mk(field, new int[] { TRUE_ID }, new int[] { prefixsBDD }));
    }

    public static int encodePrefixBDD(int[] prefixBinary, int[] vars, int[] notVars) {
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
        if (a == 0) return getFalse();
        if (a == 1) return getTrue();
        return wrap(nodeTable.mk(field, new int[] { TRUE_ID }, new int[] { bddEngine.ref(a) }));
    }

    public static NDD toNDD(int a) {
        HashMap<Integer, HashMap<Integer, Integer>> decomposed = DecomposeBDD.decompose(a, bddEngine, maxVariablePerField);
        HashMap<Integer, Integer> converted = new HashMap<>();
        converted.put(1, TRUE_ID);
        while (!decomposed.isEmpty()) {
            boolean progressed = false;
            for (Map.Entry<Integer, HashMap<Integer, Integer>> entry : new ArrayList<>(decomposed.entrySet())) {
                if (converted.keySet().containsAll(entry.getValue().keySet())) {
                    int frameStart = stackTop;
                    for (Map.Entry<Integer, Integer> edge : entry.getValue().entrySet()) {
                        edgeCollect(frameStart, converted.get(edge.getKey()), refLabel(edge.getValue()));
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
        int result = toBDDRec(id(root));
        bddEngine.deref(result);
        return result;
    }

    private static int toBDDRec(int current) {
        if (isTerminalId(current)) return value(current).doubleValue() == 0.0 ? 0 : 1;
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
            if (Math.abs(value(curr).doubleValue() - target) < eps) {
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
                double bddSat = bddEngine.satCount(nodeTable.getEdgeLabel(curr, i)) / satCountDiv.get(currField);
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
            System.out.println("TERMINAL: " + value(current) + " node:" + current);
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
            sb.append("  N").append(current).append(" [shape=box,label=\"").append(value(current)).append("\"];\n");
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
        return value(id).doubleValue();
    }

    public Rational getTerminalRational() {
        return value(id);
    }

    public static NDD createTerminal(int terNum) {
        return wrap(terminal(new Rational(terNum)));
    }

    public static NDD createTerminal(double terNum) {
        return wrap(terminal(new Rational(terNum)));
    }

    public static NDD createTerminal(Rational num) {
        return wrap(terminal(num));
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

    public HashMap<NDD, Integer> getEdges() {
        HashMap<NDD, Integer> edges = new HashMap<>();
        for (int i = 0; i < edgeCount(); i++) {
            edges.put(edgeTarget(i), edgeLabel(i));
        }
        return edges;
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

    public static NDD andTo(NDD a, NDD b) {
        NDD result = ref(and(a, b));
        deref(a);
        return result;
    }

    public static NDD orTo(NDD a, NDD b) {
        NDD result = ref(or(a, b));
        deref(a);
        return result;
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

    public static void gcLabelEngine() {
        bddEngine.gc();
    }

    public static long getTotalCreated() {
        return nodeTable.getTotalCreated();
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

    public static long getLabelTotalCreated() {
        return jdd.bdd.NodeTable.mkCount;
    }

    public static long getLabelNodeCount() {
        return bddEngine.debug_table_size();
    }

    public static void printopcount() {
        nodeTable.showMKCnt();
        bddEngine.showStats();
    }

    public static int gettersize() {
        return nodeTable.getTerminalCount();
    }

    public static double evaluate(NDD root, int[] fieldValues) {
        if (fieldValues.length <= fieldNum) {
            throw new IllegalArgumentException("one value is required for every field");
        }
        int current = id(root);
        boolean[] assignment = new boolean[sharedBddVars.length];
        while (!isTerminalId(current)) {
            int currentField = nodeTable.getField(current);
            int[] variables = bddVarsPerField.get(currentField);
            int fieldValue = fieldValues[currentField];
            Arrays.fill(assignment, false);
            for (int i = 0; i < variables.length; i++) {
                int shift = variables.length - 1 - i;
                assignment[bddEngine.getVar(variables[i])] =
                        ((fieldValue >>> shift) & 1) != 0;
            }
            int next = FALSE_ID;
            int count = nodeTable.getEdgeCount(current);
            for (int i = 0; i < count; i++) {
                if (bddEngine.member(nodeTable.getEdgeLabel(current, i), assignment)) {
                    next = nodeTable.getEdgeTarget(current, i);
                    break;
                }
            }
            current = next;
        }
        return value(current).doubleValue();
    }

    public static final class GraphStats {
        public final long internalNodes;
        public final long physicalEdges;
        public final long labelBddNodes;
        public final long terminals;

        GraphStats(long internalNodes, long physicalEdges, long labelBddNodes, long terminals) {
            this.internalNodes = internalNodes;
            this.physicalEdges = physicalEdges;
            this.labelBddNodes = labelBddNodes;
            this.terminals = terminals;
        }
    }

    public static GraphStats graphStats(NDD root) {
        HashSet<Integer> visitedNodes = new HashSet<>();
        HashSet<Integer> visitedTerminals = new HashSet<>();
        HashSet<Integer> visitedLabels = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.push(id(root));
        long internalNodes = 0;
        long physicalEdges = 0;
        while (!pending.isEmpty()) {
            int current = pending.pop();
            if (!visitedNodes.add(current)) {
                continue;
            }
            if (isTerminalId(current)) {
                visitedTerminals.add(current);
                continue;
            }
            internalNodes++;
            int count = nodeTable.getEdgeCount(current);
            physicalEdges += count;
            int currentField = nodeTable.getField(current);
            double coveredValues = 0;
            for (int i = 0; i < count; i++) {
                int label = nodeTable.getEdgeLabel(current, i);
                collectLabelNodes(label, visitedLabels);
                coveredValues += bddEngine.satCount(label) / satCountDiv.get(currentField);
                pending.push(nodeTable.getEdgeTarget(current, i));
            }
            if (coveredValues + 0.5 < fieldCardinality(currentField)) {
                visitedTerminals.add(FALSE_ID);
            }
        }
        return new GraphStats(
                internalNodes, physicalEdges, visitedLabels.size(), visitedTerminals.size());
    }

    private static void collectLabelNodes(int label, HashSet<Integer> visitedLabels) {
        if (label < 2 || !visitedLabels.add(label)) {
            return;
        }
        collectLabelNodes(bddEngine.getLow(label), visitedLabels);
        collectLabelNodes(bddEngine.getHigh(label), visitedLabels);
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
        return isTerminal() ? "NDD(" + value(id) + ")" : "NDD#" + id;
    }

    private static class IntOperationCache {
        private final int size;
        private final int[] op1;
        private final int[] op2;
        private final int[] res;
        private final int[] gen;
        private int generation;
        int result;
        int hashValue;

        IntOperationCache(int cacheSize) {
            this.size = cacheSize;
            this.op1 = new int[cacheSize];
            this.op2 = new int[cacheSize];
            this.res = new int[cacheSize];
            this.gen = new int[cacheSize];
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
            int h = mix(a);
            return (h & 0x7fffffff) % size;
        }

        private int hashBinary(int a, int b) {
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            return hashOrdered(lo, hi);
        }

        private int hashOrdered(int a, int b) {
            int h = a * 0x9e3779b9 + b * 0x517cc1b7;
            return (mix(h) & 0x7fffffff) % size;
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
