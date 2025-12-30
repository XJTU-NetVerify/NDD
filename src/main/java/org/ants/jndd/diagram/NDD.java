/**
 * Implement logical operations of NDD.
 * @author Zechun Li & Yichi Zhang - XJTU ANTS NetVerify Lab
 * @version 1.0
 */
package org.ants.jndd.diagram;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.ants.jndd.cache.OperationCache;
import org.ants.jndd.nodetable.NodeTable;

import javafx.util.Pair;
import jdd.bdd.BDD;

public class NDD {
    /**
     * The size of each operation cache.
     */
    private static int CACHE_SIZE = 10000;

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
     * Whether generateFields() has been called.
     */
    private static boolean fieldsGenerated;

    /**
     * Pending field bit numbers (before generateFields).
     */
    private static ArrayList<Integer> pendingFieldBitNums;

    /**
     * The maximum bit number across all fields.
     */
    private static int maxBitNum;

    /**
     * Shared BDD variables for all fields.
     */
    private static int[] sharedBddVars;

    /**
     * Shared negated BDD variables for all fields.
     */
    private static int[] sharedBddNotVars;


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
        fieldsGenerated = false;
        pendingFieldBitNums = new ArrayList<>();
        maxBitNum = 0;
        sharedBddVars = null;
        sharedBddNotVars = null;
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
     * Declare a new field. This method only stores the bit number.
     * Call generateFields() after all fields are declared to create BDD variables.
     * @param bitNum The number of bits in the field.
     * @return The id of the field.
     */
    public static int declareField(int bitNum) {
        if (fieldsGenerated) {
            throw new IllegalStateException("Cannot declare field after generateFields() has been called");
        }
        pendingFieldBitNums.add(bitNum);
        return pendingFieldBitNums.size() - 1;
    }

    /**
     * Generate all fields after declaration. This method creates shared BDD variables
     * and assigns them to each field using right-alignment for maximum node reuse.
     * Must be called after all declareField() calls and before any NDD operations.
     */
    public static void generateFields() {
        if (fieldsGenerated) {
            throw new IllegalStateException("generateFields() has already been called");
        }
        if (pendingFieldBitNums.isEmpty()) {
            throw new IllegalStateException("No fields declared before generateFields()");
        }
        
        // update state first
        fieldsGenerated = true;

        // 1. Find the maximum bit number
        maxBitNum = 0;
        for (int bitNum : pendingFieldBitNums) {
            if (bitNum > maxBitNum) {
                maxBitNum = bitNum;
            }
        }

        // 2. Create shared BDD variables
        sharedBddVars = new int[maxBitNum];
        sharedBddNotVars = new int[maxBitNum];
        for (int i = 0; i < maxBitNum; i++) {
            sharedBddVars[i] = bddEngine.ref(bddEngine.createVar());
            sharedBddNotVars[i] = bddEngine.ref(bddEngine.not(sharedBddVars[i]));
        }

        // 3. Create fields with right-aligned shared variables
        for (int field = 0; field < pendingFieldBitNums.size(); field++) {
            int bitNum = pendingFieldBitNums.get(field);
            int offset = maxBitNum - bitNum;  // Right-align offset

            // Update fieldNum
            fieldNum++;

            // Add node table for this field
            nodeTable.declareField();

            // Assign shared variables to this field (right-aligned)
            int[] bddVars = new int[bitNum];
            int[] bddNotVars = new int[bitNum];
            NDD[] nddVars = new NDD[bitNum];
            NDD[] nddNotVars = new NDD[bitNum];

            for (int i = 0; i < bitNum; i++) {
                // Right-align: field's bit i uses shared variable at (offset + i)
                bddVars[i] = sharedBddVars[offset + i];
                bddNotVars[i] = sharedBddNotVars[offset + i];

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
        }
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
        NDD result = mk(a.field, edges);
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
        NDD result = mk(a.field, edges);
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
        return andRec(a, n);
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
        return satCountRec(ndd, 0);
        // return bddEngine.satCount(toBDD(ndd));
    }

    /**
     * The recursive implementation of satCount.
     * With shared BDD variables, each field has pendingFieldBitNums.get(field) bits.
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
                // Count remaining fields' bits
                double result = 1;
                for (int f = field; f <= fieldNum; f++) {
                    int bits = pendingFieldBitNums.get(f);
                    result *= Math.pow(2.0, bits);
                }
                return result;
            }
        } else {
            double result = 0;
            if (field == curr.field) {
                int fieldBits = pendingFieldBitNums.get(field);
                double satDivisor = Math.pow(2.0, maxBitNum - fieldBits);
                for (Map.Entry<NDD, Integer> entry : curr.edges.entrySet()) {
                    // Use our own BDD satCount with fixed variable count (maxBitNum)
                    // instead of bddEngine.satCount which uses all variables
                    double bddSat = bddSatCountWithVars(entry.getValue(), maxBitNum) / satDivisor;
                    double nddSat = satCountRec(entry.getKey(), field + 1);
                    result += bddSat * nddSat;
                }
            } else {
                // Field is skipped, all values are valid
                int bits = pendingFieldBitNums.get(field);
                result = Math.pow(2.0, bits) * satCountRec(curr, field + 1);
            }
            return result;
        }
    }

    /**
     * Calculate satCount for a BDD using a fixed number of variables.
     * This avoids issues when additional variables have been created
     * for toBDD/toNDD conversion.
     * @param bdd The BDD node.
     * @param numVars The number of variables to consider.
     * @return The number of satisfying assignments.
     */
    private static double bddSatCountWithVars(int bdd, int numVars) {
        if (bdd == 0) return 0;
        if (bdd == 1) return Math.pow(2.0, numVars);

        int rootVar = bddEngine.getVar(bdd);
        return Math.pow(2.0, rootVar) * bddSatCountRec(bdd, numVars);
    }

    /**
     * Recursive helper for bddSatCountWithVars.
     */
    private static double bddSatCountRec(int bdd, int numVars) {
        if (bdd == 0) return 0;
        if (bdd == 1) return 1;

        int low = bddEngine.getLow(bdd);
        int high = bddEngine.getHigh(bdd);
        int bddVar = bddEngine.getVar(bdd);

        // For terminal nodes, use numVars as their virtual variable
        int lowVar = (low < 2) ? numVars : bddEngine.getVar(low);
        int highVar = (high < 2) ? numVars : bddEngine.getVar(high);

        double lowCount = bddSatCountRec(low, numVars) * Math.pow(2.0, lowVar - bddVar - 1);
        double highCount = bddSatCountRec(high, numVars) * Math.pow(2.0, highVar - bddVar - 1);

        return lowCount + highCount;
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

    /**
     * Convert a BDD (using shared variables) to NDD for a specific field.
     * The BDD uses shared variables [offset, offset+bitNum) where offset = maxBitNum - bitNum.
     * @param a The BDD with shared variables.
     * @param field The field this BDD belongs to.
     * @return The NDD representation.
     */
    public static NDD toNDD(int a, int field) {
        switch (a) {
            case 0:
                return FALSE;
            case 1:
                return TRUE;
            default:
                HashMap<NDD, Integer> edges = new HashMap<>();
                edges.put(TRUE, bddEngine.ref(a));
                return mk(field, edges);
        }
    }

    /**
     * Convert an expanded BDD (with independent variable space per field) back to NDD.
     *
     * The input BDD uses variables in expanded space:
     * - Field 0: vars [0, bitNum0)
     * - Field 1: vars [bitNum0, bitNum0+bitNum1)
     * - etc.
     *
     * This method recursively decomposes the BDD by field boundaries and replaces
     * variables back to shared variable space.
     *
     * @param a The BDD with expanded (independent) variable space.
     * @return The NDD representation with shared variables.
     */
    public static NDD toNDD(int a) {
        if (a == 0) {
            return FALSE;
        } else if (a == 1) {
            return TRUE;
        }

        ensureExpandedVars();

        // Decompose the BDD by field boundaries
        return toNDDRec(a, 0);
    }

    /**
     * Recursive helper for toNDD.
     * @param bdd Current BDD node (in expanded variable space).
     * @param field Current field being processed.
     * @return NDD with shared variables.
     */
    private static NDD toNDDRec(int bdd, int field) {
        if (bdd == 0) {
            return FALSE;
        }
        if (bdd == 1) {
            return TRUE;
        }
        if (field > fieldNum) {
            return TRUE;
        }

        int bddVar = bddEngine.getVar(bdd);

        // Calculate variable range for current field in expanded space
        int cumulativeOffset = 0;
        for (int f = 0; f < field; f++) {
            cumulativeOffset += pendingFieldBitNums.get(f);
        }
        int fieldBitNum = pendingFieldBitNums.get(field);
        int fieldVarStart = cumulativeOffset;
        int fieldVarEnd = cumulativeOffset + fieldBitNum - 1;

        // If BDD's top variable is beyond current field, skip this field
        if (bddVar > fieldVarEnd) {
            return toNDDRec(bdd, field + 1);
        }

        // If BDD's top variable is before current field, something is wrong
        if (bddVar < fieldVarStart) {
            // This shouldn't happen with proper input, but handle gracefully
            return toNDDRec(bdd, field + 1);
        }

        // Extract per-field BDD and replace variables back to shared space
        // Use existential quantification to separate field constraints
        HashMap<Integer, Integer> fieldBDDs = decomposeByField(bdd, field);

        if (fieldBDDs.isEmpty()) {
            return toNDDRec(bdd, field + 1);
        }

        HashMap<NDD, Integer> edges = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : fieldBDDs.entrySet()) {
            int nextBDD = entry.getKey();
            int edgeBDD = entry.getValue();

            // Replace expanded variables back to shared variables for this field
            int sharedEdgeBDD = replaceVarsToShared(edgeBDD, field);

            // Recursively process the rest
            NDD child = toNDDRec(nextBDD, field + 1);

            addEdge(edges, child, sharedEdgeBDD);
        }

        if (edges.isEmpty()) {
            return toNDDRec(bdd, field + 1);
        }

        return mk(field, edges);
    }

    /**
     * Decompose a BDD by extracting constraints for a specific field.
     * Returns a map from "next BDD" (constraints for later fields) to "edge BDD" (constraints for this field).
     */
    private static HashMap<Integer, Integer> decomposeByField(int bdd, int field) {
        HashMap<Integer, Integer> result = new HashMap<>();

        if (bdd == 0 || bdd == 1) {
            result.put(bdd, 1);
            return result;
        }

        // Calculate variable range for this field in the output BDD space
        int cumulativeOffset = 0;
        for (int f = 0; f < field; f++) {
            cumulativeOffset += pendingFieldBitNums.get(f);
        }
        int fieldBitNum = pendingFieldBitNums.get(field);
        int fieldVarEnd = cumulativeOffset + fieldBitNum - 1;

        // Build the set of variables for this field (for quantification)
        // Note: For field 0 (cumulativeOffset < maxBitNum), use sharedBddVars
        //       For other fields, use expandedBddVars with proper indexing
        int fieldVarSet = 1;
        for (int i = 0; i < fieldBitNum; i++) {
            int varNode;
            if (cumulativeOffset < maxBitNum) {
                // Use shared vars
                int sharedOffset = maxBitNum - fieldBitNum;
                varNode = sharedBddVars[sharedOffset + i];
            } else {
                // Use expanded vars
                int expandedIndex = cumulativeOffset - maxBitNum;
                varNode = expandedBddVars.get(expandedIndex + i);
            }
            fieldVarSet = bddEngine.andTo(fieldVarSet, varNode);
        }

        // Use decomposition similar to DecomposeBDD
        decomposeRec(bdd, field, cumulativeOffset, fieldVarEnd, result, new HashMap<>());

        bddEngine.deref(fieldVarSet);
        return result;
    }

    /**
     * Recursive decomposition helper.
     */
    private static void decomposeRec(int bdd, int field, int fieldVarStart, int fieldVarEnd,
                                      HashMap<Integer, Integer> result, HashMap<Integer, int[]> cache) {
        if (bdd == 0) {
            return;
        }
        if (bdd == 1) {
            result.merge(1, 1, (old, n) -> bddEngine.orTo(old, n));
            return;
        }

        if (cache.containsKey(bdd)) {
            int[] cached = cache.get(bdd);
            result.merge(cached[0], bddEngine.ref(cached[1]), (old, n) -> {
                int merged = bddEngine.orTo(old, n);
                bddEngine.deref(n);
                return merged;
            });
            return;
        }

        int bddVar = bddEngine.getVar(bdd);

        // If we've passed this field's variables, this is the "next" BDD
        if (bddVar > fieldVarEnd) {
            result.merge(bdd, 1, (old, n) -> bddEngine.orTo(old, n));
            cache.put(bdd, new int[]{bdd, 1});
            return;
        }

        // If variable is before this field (shouldn't happen), skip
        if (bddVar < fieldVarStart) {
            decomposeRec(bddEngine.getLow(bdd), field, fieldVarStart, fieldVarEnd, result, cache);
            decomposeRec(bddEngine.getHigh(bdd), field, fieldVarStart, fieldVarEnd, result, cache);
            return;
        }

        // Variable is in this field - build per-field BDD
        int low = bddEngine.getLow(bdd);
        int high = bddEngine.getHigh(bdd);

        // Process low branch (variable = 0)
        HashMap<Integer, Integer> lowResult = new HashMap<>();
        decomposeRec(low, field, fieldVarStart, fieldVarEnd, lowResult, cache);

        // Process high branch (variable = 1)
        HashMap<Integer, Integer> highResult = new HashMap<>();
        decomposeRec(high, field, fieldVarStart, fieldVarEnd, highResult, cache);

        // Get the variable node for this BDD variable
        // bddVar is in the output BDD variable space (0..totalBits-1)
        // For vars 0..maxBitNum-1, use sharedBddVars
        // For vars maxBitNum..totalBits-1, use expandedBddVars
        int varNode;
        if (bddVar < maxBitNum) {
            varNode = sharedBddVars[bddVar];
        } else {
            varNode = expandedBddVars.get(bddVar - maxBitNum);
        }
        int notVarNode = bddEngine.not(varNode);

        // Combine: for each next BDD, combine the edge BDDs
        for (Map.Entry<Integer, Integer> entry : lowResult.entrySet()) {
            int nextBDD = entry.getKey();
            int edgeBDD = bddEngine.ref(bddEngine.and(notVarNode, entry.getValue()));
            bddEngine.deref(entry.getValue());
            result.merge(nextBDD, edgeBDD, (old, n) -> {
                int merged = bddEngine.orTo(old, n);
                bddEngine.deref(n);
                return merged;
            });
        }

        for (Map.Entry<Integer, Integer> entry : highResult.entrySet()) {
            int nextBDD = entry.getKey();
            int edgeBDD = bddEngine.ref(bddEngine.and(varNode, entry.getValue()));
            bddEngine.deref(entry.getValue());
            result.merge(nextBDD, edgeBDD, (old, n) -> {
                int merged = bddEngine.orTo(old, n);
                bddEngine.deref(n);
                return merged;
            });
        }
    }

    /**
     * Replace expanded variables back to shared variables for a field.
     * Expanded var (expandedIndex + i) -> shared var (sharedOffset + i)
     *
     * Note: expandedBddVars starts from var maxBitNum, so:
     * - expandedBddVars[0] = var maxBitNum
     * - expandedIndex = cumulativeOffset - maxBitNum
     */
    private static int replaceVarsToShared(int bdd, int field) {
        if (bdd == 0 || bdd == 1) {
            return bdd;
        }

        int bitNum = pendingFieldBitNums.get(field);
        int sharedOffset = maxBitNum - bitNum;

        int cumulativeOffset = 0;
        for (int f = 0; f < field; f++) {
            cumulativeOffset += pendingFieldBitNums.get(f);
        }

        // For field 0 (or early fields where cumulative < maxBitNum), vars are already in shared space
        if (cumulativeOffset < maxBitNum) {
            // If sharedOffset == cumulativeOffset, the vars are already correct
            if (sharedOffset == cumulativeOffset) {
                return bddEngine.ref(bdd);
            }
            // Otherwise, we'd need to remap within shared space, but for same-size fields this shouldn't happen
            return bddEngine.ref(bdd);
        }

        ensureExpandedVars();

        // Calculate index in expandedBddVars
        int expandedIndex = cumulativeOffset - maxBitNum;

        int[] fromVars = new int[bitNum];
        int[] toVars = new int[bitNum];
        for (int i = 0; i < bitNum; i++) {
            fromVars[i] = expandedBddVars.get(expandedIndex + i);
            toVars[i] = sharedBddVars[sharedOffset + i];
        }

        jdd.bdd.Permutation perm = bddEngine.createPermutation(fromVars, toVars);
        return bddEngine.ref(bddEngine.replace(bdd, perm));
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

    /**
     * Convert NDD to BDD.
     * In shared variable mode, each field's edge BDD needs to be replaced
     * to use independent variable space before AND-ing together.
     *
     * Variable mapping:
     * - Field i uses shared vars [offset_i, offset_i + bitNum_i) where offset_i = maxBitNum - bitNum_i
     * - In the output BDD, field i uses vars [cumulative_i, cumulative_i + bitNum_i)
     *   where cumulative_i = sum of bitNums for fields 0 to i-1
     *
     * @param root The NDD root node.
     * @return The BDD representation with independent variable space per field.
     */
    public static int toBDD(NDD root) {
        // Ensure we have BDD variables for the expanded space
        ensureExpandedVars();

        // Note: result is already ref'd by toBDDRec, caller is responsible for deref
        return toBDDRec(root, 0);
    }

    /**
     * Recursive helper for toBDD.
     * @param current Current NDD node.
     * @param expectedField The expected field at this level.
     * @return BDD with replaced variables.
     */
    private static int toBDDRec(NDD current, int expectedField) {
        if (current.isTrue()) {
            return 1;
        } else if (current.isFalse()) {
            return 0;
        }

        // Handle skipped fields (all values valid)
        if (current.field > expectedField) {
            // Skip this field, continue with current node
            return toBDDRec(current, expectedField + 1);
        }

        int result = 0;
        for (Map.Entry<NDD, Integer> entry : current.edges.entrySet()) {
            // Get the edge BDD and replace its variables
            int edgeBDD = entry.getValue();
            int replacedBDD = replaceVarsForField(edgeBDD, current.field);

            // Recursively process child
            int childBDD = toBDDRec(entry.getKey(), current.field + 1);

            // AND them together
            int temp = bddEngine.ref(bddEngine.and(replacedBDD, childBDD));
            bddEngine.deref(replacedBDD);
            bddEngine.deref(childBDD);

            // OR with result
            result = bddEngine.orTo(result, temp);
            bddEngine.deref(temp);
        }
        return result;
    }

    /**
     * Cache for expanded BDD variables (beyond shared variables).
     * Index i contains the BDD node for variable i.
     */
    private static ArrayList<Integer> expandedBddVars;

    /**
     * Ensure we have BDD variable nodes for the expanded variable space.
     */
    private static void ensureExpandedVars() {
        if (expandedBddVars == null) {
            expandedBddVars = new ArrayList<>();
        }

        // Total expanded vars needed = sum of bits for all fields - maxBitNum
        // (field 0's bits can reuse shared vars)
        int totalBits = 0;
        for (int i = 0; i <= fieldNum; i++) {
            totalBits += pendingFieldBitNums.get(i);
        }
        int expandedVarsNeeded = Math.max(0, totalBits - maxBitNum);

        while (expandedBddVars.size() < expandedVarsNeeded) {
            expandedBddVars.add(bddEngine.createVar());
        }
    }

    /**
     * Replace shared BDD variables to expanded variables for a specific field.
     *
     * Variable layout in output BDD:
     * - Field 0: vars [0, bitNum0)  - uses shared vars directly
     * - Field 1: vars [bitNum0, bitNum0+bitNum1)  - uses expandedBddVars
     * - Field i: vars [cumulativeOffset, cumulativeOffset+bitNum_i)
     *
     * @param bdd The BDD to transform.
     * @param field The field index.
     * @return BDD with variables replaced to the correct position.
     */
    private static int replaceVarsForField(int bdd, int field) {
        if (bdd == 0 || bdd == 1) {
            return bdd;
        }

        int bitNum = pendingFieldBitNums.get(field);
        int sharedOffset = maxBitNum - bitNum;

        // Calculate cumulative offset for this field in the expanded space
        int cumulativeOffset = 0;
        for (int f = 0; f < field; f++) {
            cumulativeOffset += pendingFieldBitNums.get(f);
        }

        // For early fields (cumulativeOffset < maxBitNum), use shared vars directly
        if (cumulativeOffset < maxBitNum) {
            return bddEngine.ref(bdd);
        }

        ensureExpandedVars();

        // Build replacement: shared var node -> expanded var node
        int[] fromVars = new int[bitNum];
        int[] toVars = new int[bitNum];
        int expandedIndex = cumulativeOffset - maxBitNum;
        for (int i = 0; i < bitNum; i++) {
            fromVars[i] = sharedBddVars[sharedOffset + i];
            toVars[i] = expandedBddVars.get(expandedIndex + i);
        }

        jdd.bdd.Permutation perm = bddEngine.createPermutation(fromVars, toVars);
        return bddEngine.ref(bddEngine.replace(bdd, perm));
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
            collectBDDRoots(root, bddRoots, new HashSet<>());
            
            for (Integer bddId : bddRoots.keySet()) {
                if (bddId <= 1) continue;
                
                sb.append("  subgraph cluster_").append(bddId).append(" {\n");
                sb.append("    label=\"BDD ").append(bddId).append("\";\n");
                sb.append("    style=dashed;\n");
                sb.append("    color=blue;\n");
                sb.append("    bgcolor=lightgrey;\n");
                
                sb.append("    true_").append(bddId).append(" [shape=box, label=\"true#").append(bddId).append("\", style=filled];\n");
                sb.append("    false_").append(bddId).append(" [shape=box, label=\"false#").append(bddId).append("\", style=filled];\n");
                printBDDSubgraph(bddId, bddId, sb, new HashSet<>());
                
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
