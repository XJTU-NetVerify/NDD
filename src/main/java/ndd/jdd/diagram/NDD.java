package ndd.jdd.diagram;

import jdd.bdd.BDD;
import ndd.jdd.cache.OperationCache;
import ndd.jdd.nodetable.NodeTable;

import java.util.*;

/**
 * Implement logical operations of NDD.
 * @author Zechun Li
 * @version 0.1
 */

public class NDD {
    /**
     * The size of each operation cache.
     */
    private final static int CACHE_SIZE = 100000;

    /**
     * The ndd node table.
     */
    private static NodeTable<Integer> nodeTable;

    /**
     * The internal bdd engine.
     */
    private static BDD bddEngine;

    /**
     * The number of fields.
     */
    private static int fieldNum;

    /**
     * The max id of bits for each field.
     */
    private static ArrayList<Integer> maxVariablePerField;

    /**
     * The correction factor of each field, used by operation of satCount.
     */
    private static ArrayList<Double> satCountDiv;

    /**
     * All bdd variables.
     */
    private static ArrayList<int[]> bddVarsPerField;

    /**
     * The negation of each ndd variable.
     */
    private static ArrayList<int[]> bddNotVarsPerField;

    /**
     * All ndd variables.
     */
    private static ArrayList<NDD[]> nddVarsPerField;

    /**
     * The negation of each ndd variable.
     */
    private static ArrayList<NDD[]> nddNotVarsPerField;

    /**
     * Temporary ndd nodes during a logical operation, which should be protected during garbage collection.
     */
    private static HashSet<NDD> temporarilyProtect;

    /**
     * The cache of operation NOT.
     */
    private static OperationCache<NDD> notCache;
    /**
     * The cache of operation AND.
     */
    private static OperationCache<NDD> andCache;
    /**
     * The cache of operation OR.
     */
    private static OperationCache<NDD> orCache;

    /**
     * Init the NDD engine.
     * @param nddTableSize The max size of ndd node table.
     * @param bddTableSize The max size of bdd node table.
     * @param bddCacheSize The max size of bdd operation cache.
     */
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
    /**
     * Declare a new field.
     * @param bitNum The number of bits in the field.
     * @return The id of the field.
     */
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
            bddVars[i] = bddEngine.ref(bddEngine.createVar());
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

    /**
     * Get the ndd variable of a specific bit.
     * @param field The id of the field.
     * @param index The id of the bit in the field.
     * @return The ndd variable.
     */
    public static NDD getVar(int field, int index) {
        return nddVarsPerField.get(field)[index];
    }

    /**
     * Get the negation the variable for a specific bit.
     * @param field The id of the field.
     * @param index The id of the bit in the field.
     * @return The negation of the ndd variable.
     */
    public static NDD getNotVar(int field, int index) {
        return nddNotVarsPerField.get(field)[index];
    }

    /**
     * Clear all the caches, the api is usually invoked during garbage collection.
     */
    public static void clearCaches() {
        notCache.clearCache();
        andCache.clearCache();
        orCache.clearCache();
    }

    /**
     * Protect a root node from garbage collection.
     * @param ndd The root to be protected.
     * @return The ndd node.
     */
    public static NDD ref(NDD ndd) {
        return nodeTable.ref(ndd);
    }

    /**
     * Unprotect a root node, such that the node can be cleared during garbage collection.
     * @param ndd The ndd node to be unprotected.
     */
    public static void deref(NDD ndd) {
        nodeTable.deref(ndd);
    }

    /**
     * Get all the temporary nodes.
     * @return All the temporary nodes.
     */
    public static HashSet<NDD> getTemporarilyProtect() {
        return temporarilyProtect;
    }

    /**
     * The logical operation AND, which automatically ref the result and deref the first operand.
     * @param a The first operand.
     * @param b The second operand.
     * @return The result of the logical operation.
     */
    public static NDD andTo(NDD a, NDD b) {
        NDD t = ref(and(a, b));
        deref(a);
        return t;
    }

    /**
     * The logical operation OR, which automatically ref the result and deref the first operand.
     * @param a The first operand.
     * @param b The second operand.
     * @return The result of the logical operation.
     */
    public static NDD orTo(NDD a, NDD b) {
        NDD t = ref(or(a, b));
        deref(a);
        return t;
    }

    /**
     * Add an edge into a set of edges, may merge some edges.
     * @param edges A set of edges.
     * @param descendant The descendant of the edge to be inserted.
     * @param labelBDD The label of the edge to be inserted.
     */
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

    /**
     * The logical operation AND.
     * @param a The first operand.
     * @param b The second operand.
     * @return The result of the logical operation.
     */
    public static NDD and(NDD a, NDD b) {
        temporarilyProtect.clear();
        return andRec(a, b);
    }

    /**
     * The recursive implementation of the logical operation AND.
     * @param a The first operand.
     * @param b The second operand.
     * @return The result of the logical operation.
     */
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
                addEdge(edges, subRet, bddEngine.ref(entryA.getValue()));
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

    /**
     * The logical operation OR.
     * @param a The first operand.
     * @param b The second operand.
     * @return The result of the logical operation.
     */
    public static NDD or(NDD a, NDD b) {
        temporarilyProtect.clear();
        return orRec(a, b);
    }

    /**
     * The recursive implementation of the logical operation OR.
     * @param a The first operand.
     * @param b The second operand.
     * @return The result of the logical operation.
     */
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
                    addEdge(edges, entryA.getKey(), bddEngine.ref(entryA.getValue()));
                }
            }
            for (Map.Entry<NDD, Integer> entry_b : residualB.entrySet()) {
                if (entry_b.getValue() != 0) {
                    addEdge(edges, entry_b.getKey(), bddEngine.ref(entry_b.getValue()));
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
                addEdge(edges, subResult, bddEngine.ref(entryA.getValue()));
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

    /**
     * The logical operation NOT.
     * @param a The operand.
     * @return The result of the logical operation.
     */
    public static NDD not(NDD a) {
        temporarilyProtect.clear();
        return notRec(a);
    }

    /**
     * The recursive implementation of the logical operation NOT.
     * @param a The operand.
     * @return The result of the logical operation.
     */
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
            addEdge(edges, subResult, bddEngine.ref(entryA.getValue()));
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
    /**
     * The logical operation DIFF, which is equivalent to a AND (NOT b).
     * @param a The operand.
     * @return The result of the logical operation.
     */
    public static NDD diff(NDD a, NDD b) {
        temporarilyProtect.clear();
        NDD n = notRec(b);
        temporarilyProtect.add(n);
        NDD result = andRec(a, n);
        return result;
    }

    /**
     * The existential quantification.
     * @param a The operand.
     * @param field The field to run an existential quantification.
     * @return The result.
     */
    public static NDD exist(NDD a, int field) {
        temporarilyProtect.clear();
        return existRec(a, field);
    }

    /**
     * The recursive implementation of existential quantification.
     * @param a The operand.
     * @param field The field to run an existential quantification.
     * @return The result.
     */
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
                addEdge(edges, subResult, bddEngine.ref(entryA.getValue()));
            }
            result = mk(a.field, edges);
        }
        temporarilyProtect.add(result);
        return result;
    }

    // a => b <==> (not a) ∪ b
    /**
     * The logical implication, which is equivalent to (NOT a) OR b.
     * @param a The first operand.
     * @param b The second operand.
     * @return The result of the logical implication.
     */
    public static NDD imp(NDD a, NDD b) {
        temporarilyProtect.clear();
        NDD n = notRec(a);
        temporarilyProtect.add(n);
        NDD result = orRec(n, b);
        return result;
    }

    /**
     * The number of solutions encoded in the ndd node.
     * @param ndd The ndd node.
     * @return The number of solutions.
     */
    public static double satCount(NDD ndd) {
        return satCountRec(ndd, 0);
    }

    /**
     * The recursive implementation of satCount.
     * @param curr Current ndd node.
     * @param field Current field.
     * @return The number of solutions.
     */
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
//                    System.out.println(bddEngine.satCount(entry.getValue()) + " " + bddSat);
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

    /**
     * The field of the node.
     */
    private int field;

    /**
     * All the edges of the node.
     */
    private HashMap<NDD, Integer> edges;

    /**
     * Construct function, used for terminal nodes.
     */
    public NDD() {

    }

    /**
     * Construct function, used for non-terminal nodes.
     * @param field The field that the node branches on.
     * @param edges Edges of the node.
     */
    public NDD(int field, HashMap<NDD, Integer> edges) {
        this.field = field;
        this.edges = edges;
    }

    /**
     * Get the field of the node.
     * @return The field of the node.
     */
    public int getField() {
        return field;
    }

    /**
     * Get all the edges of the node.
     * @return All the edges.
     */
    public HashMap<NDD, Integer> getEdges() {
        return edges;
    }

    /**
     * The terminal node TRUE.
     */
    private final static NDD TRUE = new NDD();

    /**
     * The terminal node FALSE.
     */
    private final static NDD FALSE = new NDD();

    /**
     * Get the terminal node TRUE.
     * @return The terminal node TRUE.
     */
    public static NDD getTrue() {
        return TRUE;
    }

    /**
     * Get the terminal node FALSE.
     * @return The terminal node FALSE.
     */
    public static NDD getFalse() {
        return FALSE;
    }

    /**
     * Check if the node is the terminal node TRUE.
     * @return If the node is the terminal node TRUE.
     */
    public boolean isTrue() {
        return this == getTrue();
    }

    /**
     * Check if the node is the terminal node FALSE.
     * @return If the node is the terminal node FALSE.
     */
    public boolean isFalse() {
        return this == getFalse();
    }

    /**
     * Check if the node is a terminal node.
     * @return If the node is a terminal node.
     */
    public boolean isTerminal() {
        return this == getTrue() || this == getFalse();
    }

    @Override
    public boolean equals(Object ndd) {
        return this == ndd;
    }

    //create or reuse a new NDD node
    /**
     * Create or reuse an NDD node.
     * @param field The field of the ndd node.
     * @param edges All the edges of the ndd node.
     * @return The ndd node.
     */
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
