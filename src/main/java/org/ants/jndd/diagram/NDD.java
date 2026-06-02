package org.ants.jndd.diagram;

import java.io.FileWriter;
import java.io.IOException;
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
    private static final int EDGE_INDEX_THRESHOLD = 8;
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
    private static int[] stackTargets;
    private static int[] stackLabels;
    private static int stackTop;
    private static HashMap<Integer, NDD> wrappers;
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

    private static final class EdgeFrame {
        final int start;
        HashMap<Integer, Integer> targetIndex;

        EdgeFrame() {
            this.start = stackTop;
        }
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
        stackTargets = new int[INITIAL_STACK_SIZE];
        stackLabels = new int[INITIAL_STACK_SIZE];
        stackTop = 0;
        wrappers = new HashMap<>();
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
        externalCacheCleaner.run();
    }

    public static void setExternalCacheCleaner(Runnable cleaner) {
        externalCacheCleaner = cleaner == null ? () -> {} : cleaner;
    }

    public static void forEachTemporarilyProtect(IntConsumer consumer) {
        temporarilyProtect.forEach(consumer);
    }

    private static void runSafePointMaintenance() {
        if (nodeTable != null) {
            nodeTable.compactEdgesIfNeeded();
        }
    }

    private static NDD wrap(int nodeId) {
        NDD existing = wrappers.get(nodeId);
        if (existing != null) {
            return existing;
        }
        NDD created = nodeTable.isTerminal(nodeId)
                ? new Terminal(nodeId, nodeTable.getTerminalValue(nodeId))
                : new NDD(nodeId);
        wrappers.put(nodeId, created);
        return created;
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

    private static void edgeCollect(EdgeFrame frame, int target, int label) {
        if (target == FALSE_ID) {
            derefLabel(label);
            return;
        }
        if (frame.targetIndex != null) {
            Integer index = frame.targetIndex.get(target);
            if (index != null) {
                int oldLabel = stackLabels[index];
                stackLabels[index] = bddEngine.orTo(oldLabel, label);
                return;
            }
        } else {
            for (int i = frame.start; i < stackTop; i++) {
                if (stackTargets[i] == target) {
                    int oldLabel = stackLabels[i];
                    stackLabels[i] = bddEngine.orTo(oldLabel, label);
                    return;
                }
            }
            if (stackTop - frame.start >= EDGE_INDEX_THRESHOLD) {
                frame.targetIndex = new HashMap<>((stackTop - frame.start + 1) * 2);
                for (int i = frame.start; i < stackTop; i++) {
                    frame.targetIndex.put(stackTargets[i], i);
                }
            }
        }
        if (stackTop >= stackTargets.length) {
            int newCap = stackTargets.length * 2;
            stackTargets = Arrays.copyOf(stackTargets, newCap);
            stackLabels = Arrays.copyOf(stackLabels, newCap);
        }
        stackTargets[stackTop] = target;
        stackLabels[stackTop] = label;
        if (frame.targetIndex != null) {
            frame.targetIndex.put(target, stackTop);
        }
        stackTop++;
    }

    private static int edgeFlush(EdgeFrame frame, int field) {
        int frameStart = frame.start;
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
        EdgeFrame frame = new EdgeFrame();
        for (Map.Entry<NDD, Integer> edge : edges.entrySet()) {
            edgeCollect(frame, id(edge.getKey()), edge.getValue());
        }
        return wrap(edgeFlush(frame, field));
    }

    public static NDD mk(int field, NDD[] targets, int[] labels) {
        if (targets.length != labels.length) {
            throw new IllegalArgumentException("targets and labels must have the same length");
        }
        EdgeFrame frame = new EdgeFrame();
        for (int i = 0; i < targets.length; i++) {
            edgeCollect(frame, id(targets[i]), refLabel(labels[i]));
        }
        return wrap(edgeFlush(frame, field));
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

        EdgeFrame frame = new EdgeFrame();
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
                        edgeCollect(frame, andRec(aTarget, nodeTable.getEdgeTarget(b, j)), intersect);
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
                edgeCollect(frame, sub, refLabel(nodeTable.getEdgeLabel(a, i)));
            }
        }
        int res = edgeFlush(frame, aField);
        temporarilyProtect.add(res);
        andCache.setEntry(andCache.hashValue, a, b, res);
        return res;
    }

    private static int orRec(int a, int b) {
        if (isOneId(a) || isZeroId(b)) return a;
        if (isZeroId(a) || isOneId(b) || a == b) return b;
        if (orCache.getEntry(a, b)) return orCache.result;

        EdgeFrame frame = new EdgeFrame();
        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);
        if (aField == bField) {
            combineSameField(a, b, frame, true, TerminalOp.OR);
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
                edgeCollect(frame, sub, refLabel(aLabel));
            }
            if (residualB != 0) edgeCollect(frame, b, residualB);
        }
        int res = edgeFlush(frame, aField);
        temporarilyProtect.add(res);
        orCache.setEntry(orCache.hashValue, a, b, res);
        return res;
    }

    private static int notRec(int a) {
        if (isOneId(a)) return FALSE_ID;
        if (isZeroId(a)) return TRUE_ID;
        if (isTerminalId(a)) {
            return value(a).doubleValue() == 0.0 ? TRUE_ID : FALSE_ID;
        }
        if (notCache.getEntry(a)) return notCache.result;

        EdgeFrame frame = new EdgeFrame();
        int residual = refLabel(1);
        int count = nodeTable.getEdgeCount(a);
        for (int i = 0; i < count; i++) {
            int label = nodeTable.getEdgeLabel(a, i);
            int notIntersect = bddEngine.ref(bddEngine.not(label));
            residual = bddEngine.andTo(residual, notIntersect);
            bddEngine.deref(notIntersect);
            edgeCollect(frame, notRec(nodeTable.getEdgeTarget(a, i)), refLabel(label));
        }
        if (residual != 0) edgeCollect(frame, TRUE_ID, residual);
        int res = edgeFlush(frame, nodeTable.getField(a));
        temporarilyProtect.add(res);
        notCache.setEntry(notCache.hashValue, a, res);
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
        if (isTerminalId(a) && isTerminalId(b)) return terminalOp(a, b, TerminalOp.ADD);
        if (addCache.getEntry(a, b)) return addCache.result;
        int res = arithmeticRec(a, b, TerminalOp.ADD);
        addCache.setEntry(addCache.hashValue, a, b, res);
        return res;
    }

    private static int subRec(int a, int b) {
        if (isTerminalId(a) && isTerminalId(b)) return terminalOp(a, b, TerminalOp.SUB);
        if (subCache.getEntryOrdered(a, b)) return subCache.result;
        int res = arithmeticRec(a, b, TerminalOp.SUB);
        subCache.setEntry(subCache.hashValue, a, b, res);
        return res;
    }

    private static int mulRec(int a, int b) {
        if (isTerminalId(a) && isTerminalId(b)) return terminalOp(a, b, TerminalOp.MUL);
        if (mulCache.getEntry(a, b)) return mulCache.result;
        int res = arithmeticRec(a, b, TerminalOp.MUL);
        mulCache.setEntry(mulCache.hashValue, a, b, res);
        return res;
    }

    private static int divRec(int a, int b) {
        if (isTerminalId(a) && isTerminalId(b)) return terminalOp(a, b, TerminalOp.DIV);
        if (divCache.getEntryOrdered(a, b)) return divCache.result;
        int res = arithmeticRec(a, b, TerminalOp.DIV);
        divCache.setEntry(divCache.hashValue, a, b, res);
        return res;
    }

    private static int arithmeticRec(int a, int b, TerminalOp op) {
        EdgeFrame frame = new EdgeFrame();
        int aField = nodeTable.getField(a);
        int bField = nodeTable.getField(b);
        if (aField == bField) {
            combineSameField(a, b, frame, false, op);
            int res = edgeFlush(frame, aField);
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
                edgeCollect(frame, sub, refLabel(label));
            }
            if (op != TerminalOp.MUL && residualB != 0) {
                int sub = arithmeticChild(FALSE_ID, b, op);
                edgeCollect(frame, sub, residualB);
            } else {
                derefLabel(residualB);
            }
            int res = edgeFlush(frame, aField);
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
            edgeCollect(frame, sub, refLabel(label));
        }
        if (op != TerminalOp.MUL && residualA != 0) {
            int sub = arithmeticChild(a, FALSE_ID, op);
            edgeCollect(frame, sub, residualA);
        } else {
            derefLabel(residualA);
        }
        int res = edgeFlush(frame, bField);
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

    private static void combineSameField(int a, int b, EdgeFrame frame, boolean isOr, TerminalOp op) {
        int aCount = nodeTable.getEdgeCount(a);
        int bCount = nodeTable.getEdgeCount(b);
        IntIntMap residualA = new IntIntMap(aCount + 1);
        IntIntMap residualB = new IntIntMap(bCount + 1);
        for (int i = 0; i < aCount; i++) residualA.put(nodeTable.getEdgeTarget(a, i), refLabel(nodeTable.getEdgeLabel(a, i)));
        for (int i = 0; i < bCount; i++) residualB.put(nodeTable.getEdgeTarget(b, i), refLabel(nodeTable.getEdgeLabel(b, i)));

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
                    residualA.put(aTarget, bddEngine.andTo(residualA.get(aTarget), notIntersect));
                    residualB.put(bTarget, bddEngine.andTo(residualB.get(bTarget), notIntersect));
                    bddEngine.deref(notIntersect);
                    int sub = isOr ? orRec(aTarget, bTarget) : arithmeticChild(aTarget, bTarget, op);
                    edgeCollect(frame, sub, intersect);
                }
            }
        }

        residualA.forEach((target, label) -> {
            if (label != 0) edgeCollect(frame, isOr ? target : arithmeticChild(target, FALSE_ID, op), refLabel(label));
            derefLabel(label);
        });
        residualB.forEach((target, label) -> {
            if (label != 0) edgeCollect(frame, isOr ? target : arithmeticChild(FALSE_ID, target, op), refLabel(label));
            derefLabel(label);
        });
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
            EdgeFrame frame = new EdgeFrame();
            int count = nodeTable.getEdgeCount(a);
            for (int i = 0; i < count; i++) {
                edgeCollect(frame, existRec(nodeTable.getEdgeTarget(a, i), field),
                        refLabel(nodeTable.getEdgeLabel(a, i)));
            }
            result = edgeFlush(frame, nodeTable.getField(a));
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
                    EdgeFrame frame = new EdgeFrame();
                    for (Map.Entry<Integer, Integer> edge : entry.getValue().entrySet()) {
                        edgeCollect(frame, converted.get(edge.getKey()), refLabel(edge.getValue()));
                    }
                    int n = edgeFlush(frame, DecomposeBDD.bddGetField(entry.getKey()));
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

    private static class IntIntMap {
        private static final int EMPTY = Integer.MIN_VALUE;
        private int[] keys;
        private int[] values;
        private int size;
        private int mask;
        private int threshold;

        IntIntMap(int capacity) {
            int cap = 1;
            while (cap < Math.max(2, capacity * 2)) cap <<= 1;
            keys = new int[cap];
            values = new int[cap];
            Arrays.fill(keys, EMPTY);
            mask = cap - 1;
            threshold = (int) (cap * 0.7);
        }

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

        void forEach(IntIntConsumer consumer) {
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != EMPTY) consumer.accept(keys[i], values[i]);
            }
        }

        private void rehash() {
            int[] oldKeys = keys;
            int[] oldValues = values;
            keys = new int[oldKeys.length << 1];
            values = new int[keys.length];
            Arrays.fill(keys, EMPTY);
            mask = keys.length - 1;
            threshold = (int) (keys.length * 0.7);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != EMPTY) put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private interface IntIntConsumer {
        void accept(int key, int value);
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
