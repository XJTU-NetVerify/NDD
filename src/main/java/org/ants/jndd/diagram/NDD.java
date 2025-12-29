/**
 * Implement logical operations of NDD.
 * @author Zechun Li & Yichi Zhang - XJTU ANTS NetVerify Lab
 * @version 1.0
 */
package org.ants.jndd.diagram;

import javafx.util.Pair;
import jdd.bdd.BDD;
import org.ants.jndd.cache.OperationCache;
import org.ants.jndd.nodetable.NodeTable;
import org.ants.jndd.utils.DecomposeBDD;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class NDD {
    /**
     * The size of each operation cache.
     */
    private static int CACHE_SIZE = 10000;
    private final static boolean DEBUG_MODEL = false;

    /**
     * The ndd node table.
     */
    private static NodeTable nodeTable;

    /**
     * The internal bdd engine.
     */
    protected static BDD bddEngine;

    /**
     * The number of fields.
     */
    protected static int fieldNum;

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
        nodeTable = new NodeTable(nddTableSize, bddTableSize, bddCacheSize);
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

    /**
     * Initialize the NDD engine with user-defined cache size.
     * @param nddTableSize The max size of ndd node table.
     * @param nddCacheSize The size of ndd cache (default 10000).
     * @param bddTableSize The max size of bdd node table.
     * @param bddCacheSize The max size of bdd operation cache.
     */
    public static void initNDD(int nddTableSize, int nddCacheSize, int bddTableSize, int bddCacheSize) {
        CACHE_SIZE = nddCacheSize;
        initNDD(nddTableSize, bddTableSize, bddCacheSize);
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
        int totalBitsBefore = 0;
        if (maxVariablePerField.size() > 1) {
            totalBitsBefore = maxVariablePerField.get(maxVariablePerField.size() - 2) + 1;
        }
        satCountDiv.add(Math.pow(2.0, totalBitsBefore));
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
            edges.put(getTrue(), bddEngine.ref(bddVars[i]));
            nddVars[i] = mk(fieldNum, edges);
            nodeTable.fixNDDNodeRefCount(nddVars[i]);

            edges = new HashMap<>();
            edges.put(getTrue(), bddEngine.ref(bddNotVars[i]));
            nddNotVars[i] = mk(fieldNum, edges);
            nodeTable.fixNDDNodeRefCount(nddNotVars[i]);
        }
        bddVarsPerField.add(bddVars);
        bddNotVarsPerField.add(bddNotVars);
        nddVarsPerField.add(nddVars);
        nddNotVarsPerField.add(nddNotVars);
        return fieldNum;
    }

    public static int getFieldNum() {
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

    public static int[] getBDDVars(int field) {
        return bddVarsPerField.get(field);
    }

    public static int[] getNotBDDVars(int field) {
        return bddNotVarsPerField.get(field);
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

    public static BDD getBDDEngine() {
        return bddEngine;
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
        NDD result = ref(and(a, b));
        deref(a);
        if (DEBUG_MODEL) {
            int aBDD = bddEngine.ref(toBDD(a));
            int bBDD = bddEngine.ref(toBDD(b));
            int resultBDD = bddEngine.andTo(aBDD, bBDD);
            bddEngine.deref(bBDD);
            if (resultBDD != toBDD(result)) {
                System.out.println("Operation and: result wrong!");
            }
        }
        return result;
    }

    /**
     * The logical operation OR, which automatically ref the result and deref the first operand.
     * @param a The first operand.
     * @param b The second operand.
     * @return The result of the logical operation.
     */
    public static NDD orTo(NDD a, NDD b) {
        NDD result = ref(or(a, b));
        deref(a);
        if (DEBUG_MODEL) {
            int aBDD = bddEngine.ref(toBDD(a));
            int bBDD = bddEngine.ref(toBDD(b));
            int resultBDD = bddEngine.orTo(aBDD, bBDD);
            bddEngine.deref(bBDD);
            if (resultBDD != toBDD(result)) {
                System.out.println("Operation or: result wrong!");
            }
        }
        return result;
    }

    /**
     * Add an edge into a set of edges, may merge some edges.
     * @param edges A set of edges.
     * @param descendant The descendant of the edge to be inserted.
     * @param labelBDD The label of the edge to be inserted.
     */
    protected static void addEdge(HashMap<NDD, Integer> edges, NDD descendant, int labelBDD) {
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
        NDD result = andRec(a, b);
        if (DEBUG_MODEL) {
            int aBDD = bddEngine.ref(toBDD(a));
            int bBDD = bddEngine.ref(toBDD(b));
            int resultBDD = bddEngine.andTo(aBDD, bBDD);
            bddEngine.deref(bBDD);
            if (resultBDD != toBDD(result)) {
                System.out.println("Operation and: result wrong!");
            }
        }
        return result;
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
                NDD subResult = andRec(entryA.getKey(), b);
                addEdge(edges, subResult, bddEngine.ref(entryA.getValue()));
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
        NDD result = orRec(a, b);
        if (DEBUG_MODEL) {
            int aBDD = bddEngine.ref(toBDD(a));
            int bBDD = bddEngine.ref(toBDD(b));
            int resultBDD = bddEngine.orTo(aBDD, bBDD);
            bddEngine.deref(bBDD);
            if (resultBDD != toBDD(result)) {
                System.out.println("Operation or: result wrong!");
            }
        }
        return result;
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
                        int notIntersect = bddEngine.ref(bddEngine.not(intersect));
                        int oldResidual = residualA.get(entryA.getKey());
                        residualA.put(entryA.getKey(), bddEngine.andTo(oldResidual, notIntersect));
                        oldResidual = residualB.get(entryB.getKey());
                        residualB.put(entryB.getKey(), bddEngine.andTo(oldResidual, notIntersect));
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
            for (Map.Entry<NDD, Integer> entryB : residualB.entrySet()) {
                if (entryB.getValue() != 0) {
                    addEdge(edges, entryB.getKey(), bddEngine.ref(entryB.getValue()));
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
        NDD result = notRec(a);
        if (DEBUG_MODEL) {
            int aBDD = bddEngine.ref(toBDD(a));
            int resultBDD = bddEngine.not(aBDD);
            bddEngine.deref(aBDD);
            if (resultBDD != toBDD(result)) {
                System.out.println("Operation not: result wrong!");
            }
        }
        return result;
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
        if (DEBUG_MODEL) {
            int aBDD = bddEngine.ref(toBDD(a));
            int bBDD = bddEngine.ref(toBDD(b));
            int t = bddEngine.ref(bddEngine.not(bBDD));
            bddEngine.deref(bBDD);
            int resultBDD = bddEngine.and(aBDD, t);
            bddEngine.deref(aBDD);
            bddEngine.deref(t);
            if (resultBDD != toBDD(result)) {
                System.out.println("Operation diff: result wrong!");
            }
        }
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
                result = orRec(result, next);
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
//        double result = satCountRec(ndd, 0);
//        if (DEBUG_MODEL) {
//            double bddResult = bddEngine.satCount(toBDD(ndd));
//            if (result != bddResult) {
//                System.out.println("Operation satCount: result wrong!");
//            }
//        }
//        return result;
        return bddEngine.satCount(toBDD(ndd));
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
     * Encode an NDD of a prefix with no temporary NDD nodes created.
     * @param prefixBinary The binary prefix, e.g., [1, 0, 1, 0] for 10.
     * @param field The field of the prefix.
     * @return An ndd node encoding the prefix.
     */
    public static NDD encodePrefix(int[] prefixBinary, int field) {
        if (prefixBinary.length == 0) {
            return TRUE;
        }

        int prefixBDD = encodePrefixBDD(prefixBinary, getBDDVars(field), getNotBDDVars(field));

        HashMap<NDD, Integer> edges = new HashMap<>();
        edges.put(TRUE, prefixBDD);
        return mk(field, edges);
    }

    public static NDD encodePrefixs(ArrayList<int[]> prefixsBinary, int field) {
        int prefixsBDD = 0;
        for (int[] prefix : prefixsBinary) {
            prefixsBDD = bddEngine.orTo(prefixsBDD, encodePrefixBDD(prefix, getBDDVars(field), getNotBDDVars(field)));
        }
        HashMap<NDD, Integer> edges = new HashMap<>();
        edges.put(TRUE, prefixsBDD);
        return mk(field, edges);
    }

    public static int encodePrefixBDD(int[] prefixBinary, int[] vars, int[] notVars) {
        if (prefixBinary.length == 0) {
            return 1;
        }

        int prefixBDD = 1;
        for (int i = prefixBinary.length - 1; i >= 0; i--) {
            int currentBit = prefixBinary[i] == 1 ? vars[i] : notVars[i];
            if (i == prefixBinary.length - 1) {
                prefixBDD = bddEngine.ref(currentBit);
            } else {
                prefixBDD = bddEngine.andTo(prefixBDD, currentBit);
            }
        }
        return prefixBDD;
    }

    // <field, bdd>, entries in perFieldBDD must follow the order with field asc
    public static NDD encodeACL(ArrayList<Pair<Integer, Integer>> perFieldBDD) {
        NDD result = TRUE;
        for (int i = perFieldBDD.size() - 1; i >= 0; i--) {
            if (perFieldBDD.get(i).getValue() != 1) {
                HashMap<NDD, Integer> edges = new HashMap<>();
                edges.put(result, perFieldBDD.get(i).getValue());
                result = mk(perFieldBDD.get(i).getKey(), edges);
            }
        }
        return result;
    }


    public static NDD toNDD(int a, int field) {
        return toNDDFunc(a, field);
    }

    private static NDD toNDDFunc(int a, int field)
    {
        if(a == 1) {
            return TRUE;
        } else {
            HashMap<NDD, Integer> edges = new HashMap<>();
            edges.put(TRUE, a);
            return mk(field, edges);
        }
    }

    public static NDD toNDD(int a) {
        return toNDDFunc(a);
    }

    private static NDD toNDDFunc(int a)
    {
        HashMap<Integer, HashMap<Integer, Integer>> decomposed = DecomposeBDD.decompose(a, bddEngine, maxVariablePerField);
        HashMap<Integer, NDD> converted = new HashMap<>();
        converted.put(1, TRUE);
        while(decomposed.size() != 0)
        {
            Set<Integer> finished = converted.keySet();
            for(Map.Entry<Integer, HashMap<Integer, Integer>> entry : decomposed.entrySet())
            {
                if(finished.containsAll(entry.getValue().keySet()))
                {
                    int field = DecomposeBDD.bddGetField(entry.getKey());
                    HashMap<NDD, Integer> map = new HashMap<>();
                    for(Map.Entry<Integer, Integer> entry1 : entry.getValue().entrySet())
                    {
                        map.put(converted.get(entry1.getKey()), bddEngine.ref(entry1.getValue()));
                    }
                    NDD n = mk(field, map);
                    converted.put(entry.getKey(), n);
                    decomposed.remove(entry.getKey());
                    break;
                }
            }
        }
        for(HashMap<Integer, Integer> map : decomposed.values())
        {
            for(Integer pred : map.values())
            {
                bddEngine.deref(pred);
            }
        }
        return converted.get(a);
    }

    public static ArrayList<int[]> toArray(NDD curr) {
        ArrayList<int[]> array = new ArrayList<>();
        int[] vec = new int[fieldNum + 1];
        toArrayRec(curr, array, vec, 0);
        return array;
    }

    private static void toArrayRec(NDD curr, ArrayList<int[]> array, int[] vec, int currField) {
        if (curr.isFalse()) {
        } else if (curr.isTrue()) {
            for (int i = currField; i <= fieldNum; i++) {
                vec[i] = 1;
            }
            int[] temp = new int[fieldNum + 1];
            for (int i = 0; i <= fieldNum; i++) {
                temp[i] = vec[i];
            }
            array.add(temp);
        } else {
            for (int i = currField; i < curr.field; i++) {
                vec[i] = 1;
            }
            for (Map.Entry<NDD, Integer> entry : curr.edges.entrySet()) {
                vec[curr.field] = entry.getValue();
                toArrayRec(entry.getKey(), array, vec, curr.field + 1);
            }
        }
    }

    public static int toBDD(NDD root) {
        int result = toBDDRec(root);
        bddEngine.deref(result);
        return result;
    }

    private static int toBDDRec(NDD current) {
        if (current.isTrue()) {
            return 1;
        } else if (current.isFalse()) {
            return 0;
        } else {
            int result = 0;
            for (Map.Entry<NDD, Integer> entry : current.edges.entrySet()) {
                int temp = bddEngine.andTo(toBDDRec(entry.getKey()), entry.getValue());
                result = bddEngine.orTo(result, temp);
                bddEngine.deref(temp);
            }
            return result;
        }
    }

    public static void print(NDD root) {
        System.out.println("Print " + root + " begin!");
        printRec(root);
        System.out.println("Print " + root + " finish!\n");
    }

    private static void printRec(NDD current) {
        if (current.isTrue()) System.out.println("TRUE\n");
        else if (current.isFalse()) System.out.println("FALSE\n");
        else {
            System.out.println("field:" + current.field + " node:" + current);
            for (Map.Entry<NDD, Integer> entry : current.getEdges().entrySet()) {
                System.out.println("next:" + entry.getKey() + " label:" + entry.getValue());
            }
            System.out.println();
            for (NDD next : current.getEdges().keySet()) {
                printRec(next);
            }
        }
    }
    
    public static void printDot(NDD root, String filename) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph NDD_Graph {\n");
        sb.append("  rankdir=TD;\n");
        sb.append("  compound=true;\n");
        
        HashSet<NDD> visitedNDD = new HashSet<>();
        
        sb.append("  NDD_TRUE [shape=box, style=filled, label=\"NDD TRUE\"];\n");
        sb.append("  NDD_FALSE [shape=box, style=filled, label=\"NDD FALSE\"];\n");
        
        try {
            FileWriter writer = new FileWriter(filename);
            
            HashMap<Integer, Integer> bddRoots = new HashMap<>();
            collectBDDRoots(root, bddRoots, new HashSet<NDD>());
            
            for (Integer bddId : bddRoots.keySet()) {
                if (bddId <= 1) continue;
                
                sb.append("  subgraph cluster_").append(bddId).append(" {\n");
                sb.append("    label=\"BDD ").append(bddId).append("\";\n");
                sb.append("    style=dashed;\n");
                sb.append("    color=blue;\n");
                sb.append("    bgcolor=lightgrey;\n");
                
                sb.append("    true_").append(bddId).append(" [shape=box, label=\"true#").append(bddId).append("\", style=filled];\n");
                sb.append("    false_").append(bddId).append(" [shape=box, label=\"false#").append(bddId).append("\", style=filled];\n");
                printBDDSubgraph(bddId, bddId, sb, new HashSet<Integer>());
                
                sb.append("  }\n\n");
            }
            
            visitedNDD.clear();
            printNDDStructure(root, sb, visitedNDD);
            
            sb.append("}\n");
            writer.write(sb.toString());
            writer.close();
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void printNDDStructure(NDD current, StringBuilder sb, 
                                        HashSet<NDD> visited) {
        if (current.isTerminal() || visited.contains(current)) {
            return;
        }
        visited.add(current);
        
        String nodeId = "NDD_" + System.identityHashCode(current);
        sb.append("  ").append(nodeId)
        .append(" [shape=circle, label=\"Field ").append(current.field).append("\"];\n");
        
        for (Map.Entry<NDD, Integer> entry : current.getEdges().entrySet()) {
            NDD next = entry.getKey();
            int bddId = entry.getValue();
            
            String nextId;
            if (next.isTrue()) {
                nextId = "NDD_TRUE";
            } else if (next.isFalse()) {
                nextId = "NDD_FALSE";
            } else {
                nextId = "NDD_" + System.identityHashCode(next);
            }
            sb.append("  ").append(nodeId).append(" -> ").append(nextId)
            .append(" [label=\"").append(bddId).append("\"];\n");
            
            printNDDStructure(next, sb, visited);
        }
    }
    private static void collectBDDRoots(NDD ndd, HashMap<Integer, Integer> bddRoots, HashSet<NDD> visited) {
        if (ndd.isTerminal() || visited.contains(ndd)) {
            return;
        }
        visited.add(ndd);
        
        for (Map.Entry<NDD, Integer> entry : ndd.getEdges().entrySet()) {
            int bddId = entry.getValue();
            bddRoots.put(bddId, bddId);
            
            collectBDDRoots(entry.getKey(), bddRoots, visited);
        }
    }
    private static void printBDDSubgraph(int currentBDD, int rootBDD, 
                                    StringBuilder sb, HashSet<Integer> visited) {
        if (currentBDD <= 1 || visited.contains(currentBDD)) {
            return;
        }
        visited.add(currentBDD);
        
        int var = bddEngine.getVar(currentBDD);
        int high = bddEngine.getHigh(currentBDD);
        int low = bddEngine.getLow(currentBDD);
        
        sb.append("    node").append(currentBDD).append("_").append(rootBDD)
        .append(" [shape=circle, label=\"x").append(var).append("\"];\n");
        
        if (high == 1) {
            sb.append("    node").append(currentBDD).append("_").append(rootBDD)
            .append(" -> true_").append(rootBDD).append(";\n");
        } else if (high == 0) {
            sb.append("    node").append(currentBDD).append("_").append(rootBDD)
            .append(" -> false_").append(rootBDD).append(";\n");
        } else {
            sb.append("    node").append(currentBDD).append("_").append(rootBDD)
            .append(" -> node").append(high).append("_").append(rootBDD).append(";\n");
            printBDDSubgraph(high, rootBDD, sb, visited);
        }
        
        if (low == 1) {
            sb.append("    node").append(currentBDD).append("_").append(rootBDD)
            .append(" -> true_").append(rootBDD).append(" [style=dotted];\n");
        } else if (low == 0) {
            sb.append("    node").append(currentBDD).append("_").append(rootBDD)
            .append(" -> false_").append(rootBDD).append(" [style=dotted];\n");
        } else {
            sb.append("    node").append(currentBDD).append("_").append(rootBDD)
            .append(" -> node").append(low).append("_").append(rootBDD)
            .append(" [style=dotted];\n");
            printBDDSubgraph(low, rootBDD, sb, visited);
        }
    }

    /**
     * The field of the node.
     */
    protected int field;

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
     * Note that, one should ref all bdd labels in edges before invoking mk.
     * @param field The field of the ndd node.
     * @param edges All the edges of the ndd node.
     * @return The ndd node.
     */
    public static NDD mk(int field, HashMap<NDD, Integer> edges) {
        return nodeTable.mk(field, edges);
    }

    public static int nodeCount() {
        ArrayList<HashMap<HashMap<NDD, Integer>, NDD>> tables = nodeTable.getNodeTable();
        int nodeCount = 0;
        for (HashMap<HashMap<NDD, Integer>, NDD> table : tables) {
            nodeCount += table.size();
        }
        return nodeCount;
    }
}
