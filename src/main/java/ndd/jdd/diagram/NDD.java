package ndd.jdd.diagram;

import jdd.bdd.BDD;
import ndd.jdd.cache.OperationCache;
import ndd.jdd.nodetable.NodeTable;

import java.util.*;

/**
 * Implement logical operations of NDD.
 * @author Zechun Li
 * @version 1.0
 */

public class NDD {
    private final static int CACHE_SIZE = 100000;

    // node table
    private static NodeTable<Integer> nodeTable;
    private static BDD bddEngine;
    private static int fieldNum;
    // per field
    private static ArrayList<Integer> maxVariablePerField;
    private static ArrayList<Double> satCountDiv;
    private static ArrayList<int[]> bddVarsPerField;
    private static ArrayList<int[]> bddNotVarsPerField;
    private static ArrayList<NDD[]> nddVarsPerField;
    private static ArrayList<NDD[]> nddNotVarsPerField;
    // protect temporary NDD nodes during garbage collection
    private static HashSet<NDD> temporarilyProtect;

    // operation caches
    // note that: the usage of operation caches must be based on lazy gc
    private static OperationCache<NDD> notCache;
    private static OperationCache<NDD> andCache;
    private static OperationCache<NDD> orCache;

    public static void initNDD(int nddTableSize, int bddTableSize, int bddCacheSize) {
        nodeTable = new NodeTable<>(nddTableSize, bddTableSize, bddCacheSize);
        bddEngine = nodeTable.getBddEngine();
        fieldNum = -1;
        maxVariablePerField = new ArrayList<>();
        satCountDiv = new ArrayList<>();
        bddVarsPerField = new ArrayList<>();
        bddNotVarsPerField = new ArrayList<>();
        nddVarsPerField = new ArrayList<>();
        nddNotVarsPerField = new ArrayList<>();
        temporarilyProtect = new HashSet<>();
        notCache = new OperationCache<>(CACHE_SIZE, 2);
        andCache = new OperationCache<>(CACHE_SIZE, 3);
        orCache = new OperationCache<>(CACHE_SIZE, 3);
    }

    // declare a field of 'bitNum' bits
    public static int declareField(int bitNum) {
        // 1. update the number of fields
        fieldNum++;
        // 2. update the boundary of each field
        if (maxVariablePerField.isEmpty()) {
            maxVariablePerField.add(bitNum - 1);
        } else {
            maxVariablePerField.add(maxVariablePerField.get(maxVariablePerField.size() - 1) + bitNum);
        }
        // 3. update satCountDiv, which will be used in satCount operation of NDD
        double factor = Math.pow(2.0, bitNum);
        for (int i=0; i < satCountDiv.size(); i++) {
            satCountDiv.set(i, satCountDiv.get(i) * factor);
        }
        int totalBitBefore = 0;
        if (maxVariablePerField.size() > 1) {
            totalBitBefore = maxVariablePerField.get(maxVariablePerField.size() - 2) + 1;
        }
        satCountDiv.add(Math.pow(2.0, totalBitBefore));
        // 4. add node table
        nodeTable.declareField();
        // 5. declare vars
        int[] bddVars = new int[bitNum];
        int[] bddNotVars = new int[bitNum];
        NDD[] nddVars = new NDD[bitNum];
        NDD[] nddNotVars = new NDD[bitNum];

        for (int i = 0;i < bitNum;i++) {
            bddVars[i] = bddEngine.createVar();
            bddNotVars[i] = bddEngine.ref(bddEngine.not(bddVars[i]));
            HashMap<NDD, Integer> edges = new HashMap<>();
            edges.put(NDD.getTrue(), bddEngine.ref(bddVars[i]));
            nddVars[i] = NDD.ref(NDD.mk(fieldNum, edges));
            edges = new HashMap<>();
            edges.put(NDD.getTrue(), bddEngine.ref(bddNotVars[i]));
            nddNotVars[i] = NDD.ref(NDD.mk(fieldNum, edges));
        }
        bddVarsPerField.add(bddVars);
        bddNotVarsPerField.add(bddNotVars);
        nddVarsPerField.add(nddVars);
        nddNotVarsPerField.add(nddNotVars);

        return fieldNum;
    }

    public static NDD getVar(int field, int index) {
        return nddVarsPerField.get(field)[index];
    }

    public static NDD getNotVar(int field, int index) {
        return nddNotVarsPerField.get(field)[index];
    }

    public static void clearCaches() {
        notCache.clearCache();
        andCache.clearCache();
        orCache.clearCache();
    }

    public static NDD ref(NDD ndd) {
        return nodeTable.ref(ndd);
    }

    public static void deref(NDD ndd) {
        nodeTable.deref(ndd);
    }

    public static HashSet<NDD> getTemporarilyProtect() {
        return temporarilyProtect;
    }

    public static NDD andTo(NDD a, NDD b) {
        NDD t = ref(and(a, b));
        deref(a);
        return t;
    }

    public static NDD orTo(NDD a, NDD b) {
        NDD t = ref(or(a, b));
        deref(a);
        return t;
    }

    private static void addEdge(HashMap<NDD, Integer> edges, NDD descendant, int labelBDD) {
        // omit the edge pointing to terminal node FALSE
        if (descendant.isFalse()) {
            bddEngine.deref(labelBDD);
            return;
        }
        // try to find the edge pointing to the same descendant
        Integer oldLabel = edges.get(descendant);
        if (oldLabel == null) {
            oldLabel = 0;
        }
        // merge the bdd label
        int newLabel = bddEngine.orTo(oldLabel, labelBDD);
        bddEngine.deref(labelBDD);
        edges.put(descendant, newLabel);
    }

    public static NDD and(NDD a, NDD b) {
        temporarilyProtect.clear();
        return andRec(a, b);
    }

    private static NDD andRec(NDD a, NDD b) {
        // terminal condition
        if (a.isFalse() || b.isTrue()) {
            return a;
        } else if (a.isTrue() || b.isFalse() || a == b){
            return b;
        }

        // check the cache
        if (andCache.getEntry(a, b))
            return andCache.result;

        NDD result = null;
        HashMap<NDD, Integer> edges = new HashMap<>();
        if (a.field == b.field) {
            for (Map.Entry<NDD, Integer> entryA : a.edges.entrySet()) {
                for (Map.Entry<NDD, Integer> entryB : b.edges.entrySet()) {
                    // the bdd label on the new edge
                    int intersect = bddEngine.ref(bddEngine.and(entryA.getValue(), entryB.getValue()));
                    if (intersect != 0) {
                        // the descendant of the new edge
                        NDD subResult = andRec(entryA.getKey(), entryB.getKey());
                        // try to merge edges
                        addEdge(edges, subResult, intersect);
                    }
                }
            }
        } else {
            if (a.field > b.field) {
                NDD t = a;
                a = b;
                b = t;
            }
            for (Map.Entry<NDD, Integer> entryA : a.edges.entrySet()) {
                /*
                 * if A branches on a higher field than B,
                 * we can let A operate with a pseudo node
                 * with only edge labelled by true and pointing to B
                 */
                NDD subRet = andRec(entryA.getKey(), b);
                addEdge(edges, subRet, entryA.getValue());
            }
        }
        // try to create or reuse node
        result = mk(a.field, edges);
        // protect the node during the operation
        temporarilyProtect.add(result);
        // store the result into cache
        andCache.setEntry(andCache.hashValue, a, b, result);
        return result;
    }

    public static NDD or(NDD a, NDD b) {
        temporarilyProtect.clear();
        return orRec(a, b);
    }

    private static NDD orRec(NDD a, NDD b) {
        // terminal condition
        if (a.isTrue() || b.isFalse()) {
            return a;
        } else if (a.isFalse() || b.isTrue() || a == b) {
            return b;
        }

        //check the cache
        if (orCache.getEntry(a, b))
            return orCache.result;

        NDD result = null;
        HashMap<NDD, Integer> edges = new HashMap<>();
        if (a.field == b.field) {
            // record edges of each node, which will 'or' with the edge pointing to FALSE of another node
            HashMap<NDD, Integer> residualA = new HashMap<>(a.edges);
            HashMap<NDD, Integer> residualB = new HashMap<>(b.edges);
            for (int oneBDD : a.edges.values()) {
                bddEngine.ref(oneBDD);
            }
            for (int oneBDD : b.edges.values()) {
                bddEngine.ref(oneBDD);
            }
            for (Map.Entry<NDD, Integer> entryA : a.edges.entrySet()) {
                for (Map.Entry<NDD, Integer> entryB : b.edges.entrySet()) {
                    // the bdd label on the new edge
                    int intersect = bddEngine.ref(bddEngine.and(entryA.getValue(), entryB.getValue()));
                    if (intersect != 0) {
                        // update residual
                        int oldResidual = residualA.get(entryA.getKey());
                        int notIntersect = bddEngine.ref(bddEngine.not(intersect));
                        residualA.put(entryA.getKey(), bddEngine.andTo(oldResidual, notIntersect));
                        oldResidual = residualB.get(entryB.getKey());
                        residualB.put(entryB.getKey(),
                                bddEngine.andTo(oldResidual, notIntersect));
                        bddEngine.deref(notIntersect);
                        // the descendant of the new edge
                        NDD subResult = orRec(entryA.getKey(), entryB.getKey());
                        // try to merge edges
                        addEdge(edges, subResult, intersect);
                    }
                }
            }
            /*
             * Each residual of A doesn't match with any explicit edge of B,
             * and will match with the edge pointing to FALSE of B, which is omitted.
             * The situation is the same for B.
             */
            for (Map.Entry<NDD, Integer> entryA : residualA.entrySet()) {
                if (entryA.getValue() != 0) {
                    addEdge(edges, entryA.getKey(), entryA.getValue());
                }
            }
            for (Map.Entry<NDD, Integer> entry_b : residualB.entrySet()) {
                if (entry_b.getValue() != 0) {
                    addEdge(edges, entry_b.getKey(), entry_b.getValue());
                }
            }
        } else {
            if (a.field > b.field) {
                NDD t = a;
                a = b;
                b = t;
            }
            int residualB = 1;
            for (Map.Entry<NDD, Integer> entryA : a.edges.entrySet()) {
                /*
                 * if A branches on a higher field than B,
                 * we can let A operate with a pseudo node
                 * with only edge labelled by true and pointing to B
                 */
                int notIntersect = bddEngine.ref(bddEngine.not(entryA.getValue()));
                residualB = bddEngine.andTo(residualB, notIntersect);
                bddEngine.deref(notIntersect);
                NDD subResult = orRec(entryA.getKey(), b);
                addEdge(edges, subResult, entryA.getValue());
            }
            if (residualB != 0) {
                addEdge(edges, b, residualB);
            }
        }
        // try to create or reuse node
        result = mk(a.field, edges);
        // protect the node during the operation
        temporarilyProtect.add(result);
        // store the result into cache
        orCache.setEntry(orCache.hashValue, a, b, result);
        return result;
    }

    public static NDD not(NDD a) {
        temporarilyProtect.clear();
        return notRec(a);
    }

    private static NDD notRec(NDD a) {
        if (a.isTrue()) {
            return FALSE;
        } else if (a.isFalse()) {
            return TRUE;
        }


        if (notCache.getEntry(a))
            return notCache.result;

        HashMap<NDD, Integer> edges = new HashMap<>();
        Integer residual = 1;
        for (Map.Entry<NDD, Integer> entryA : a.edges.entrySet()) {
            int notIntersect = bddEngine.ref(bddEngine.not(entryA.getValue()));
            residual = bddEngine.andTo(residual, notIntersect);
            bddEngine.deref(notIntersect);
            NDD subResult = notRec(entryA.getKey());
            addEdge(edges, subResult, entryA.getValue());
        }
        if (residual != 0) {
            addEdge(edges, TRUE, residual);
        }
        NDD result = mk(a.field, edges);
        temporarilyProtect.add(result);
        notCache.setEntry(notCache.hashValue, a, result);
        return result;
    }

    // a / b <==> a ∩ (not b)
    public static NDD diff(NDD a, NDD b) {
        temporarilyProtect.clear();
        NDD n = notRec(b);
        temporarilyProtect.add(n);
        NDD result = andRec(a, n);
        return result;
    }

    public static NDD exist(NDD a, int field) {
        temporarilyProtect.clear();
        return existRec(a, field);
    }

    // existential quantification
    // not updated with lazyGC
    private static NDD existRec(NDD a, int field) {
        if (a.isTerminal() || a.field > field) {
            return a;
        }

        NDD result = FALSE;
        if (a.field == field) {
            for (NDD next : a.edges.keySet()) {
                result = NDD.orRec(result, next);
            }
        } else {
            HashMap<NDD, Integer> edges = new HashMap<>();
            for (Map.Entry<NDD, Integer> entryA : a.edges.entrySet()) {
                NDD subResult = existRec(entryA.getKey(), field);
                addEdge(edges, subResult, entryA.getValue());
            }
            result = mk(a.field, edges);
        }
        temporarilyProtect.add(result);
        return result;
    }

    // a => b <==> (not a) ∪ b
    public static NDD imp(NDD a, NDD b) {
        temporarilyProtect.clear();
        NDD n = notRec(a);
        temporarilyProtect.add(n);
        NDD result = orRec(n, b);
        return result;
    }

    // calculate the number of solutions of an NDD
    public static double satCount(NDD ndd) {
        return satCountRec(ndd, 0);
    }

    private static double satCountRec(NDD curr, int field) {
        if (curr.isFalse()) {
            return 0;
        } else if (curr.isTrue()) {
            if (field > fieldNum) {
                return 1;
            } else {
                int len = maxVariablePerField.get(maxVariablePerField.size() - 1);
                if (field == 0) {
                    len++;
                } else {
                    len -= maxVariablePerField.get(field - 1);
                }
                return Math.pow(2.0, len);
            }
        } else {
            double result = 0;
            if (field == curr.field) {
                for (Map.Entry<NDD, Integer> entry : curr.edges.entrySet()) {
                    double bddSat = bddEngine.satCount(entry.getValue()) / satCountDiv.get(curr.field);
                    double nddSat = satCountRec(entry.getKey(), field + 1);
                    result += bddSat * nddSat;
                }
            } else {
                int len = maxVariablePerField.get(field);
                if (field == 0) {
                    len++;
                } else {
                    len -= maxVariablePerField.get(field - 1);
                }
                result = Math.pow(2.0, len) * satCountRec(curr, field + 1);
            }
            return result;
        }
    }

    // per node content
    private int field;
    private HashMap<NDD, Integer> edges;

    public NDD() {

    }

    public NDD(int field, HashMap<NDD, Integer> edges) {
        this.field = field;
        this.edges = edges;
    }

    public int getField() {
        return field;
    }

    public HashMap<NDD, Integer> getEdges() {
        return edges;
    }

    // terminal node true and false
    private final static NDD TRUE = new NDD();
    private final static NDD FALSE = new NDD();

    public static NDD getTrue() {
        return TRUE;
    }

    public static NDD getFalse() {
        return FALSE;
    }

    public boolean isTrue() {
        return this == getTrue();
    }

    public boolean isFalse() {
        return this == getFalse();
    }

    public boolean isTerminal() {
        return this == getTrue() || this == getFalse();
    }

    @Override
    public boolean equals(Object ndd) {
        return this == ndd;
    }

    //create or reuse a new NDD node
    public static NDD mk(int field, HashMap<NDD, Integer> edges) {
        if (edges.size() == 0) {
            // Since NDD omits all edges pointing to FALSE, the empty edge represents FALSE.
            return getFalse();
        } else if (edges.size() == 1 && edges.values().iterator().next() == 1) {
            // Omit nodes with the only edge labeled by BDD TRUE.
            return edges.keySet().iterator().next();
        } else {
            return nodeTable.mk(field, edges);
        }
    }
}
