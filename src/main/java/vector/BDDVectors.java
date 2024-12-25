package vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdd.bdd.BDD;
import jdd.bdd.Permutation;
import ndd.jdd.utils.DecomposeBDD;

// todo: recheck the logic of ref and deref
public class BDDVectors {
    private final static int NEGATION_METHOD = 1;
    public final static BDDVectors TRUE = new BDDVectors(true);
    public final static BDDVectors FALSE = new BDDVectors(false);
    public final static int BDD_TRUE = 1;
    public final static int BDD_FALSE = 0;

    /*
     * reuse flag (default true)
     *   reuse bdd elements in vector
     *   initialize with reverse order
     */
    public static boolean reuseBDDVar;
    public static BDD bddEngine;
    public static int fieldNum;

    // used for reuse variables
    private static int totalVarNum;
    private static int maxFieldLength;
    private static int[] bddVarsReuse;
    private static int[] bddNotVarsReuse;
    private static ArrayList<Integer> backupBDDVars; // used by toBDDReuse

    private static ArrayList<Integer> maxVariablePerField;
    private static ArrayList<Double> satCountDiv;
    private static ArrayList<int[]> bddVarsPerField;
    private static ArrayList<int[]> bddNotVarsPerField;

//    public static int[] varNumList;
//    public static int varNumTotal;
//    public static int maxVarsLength; // = varNumTotal if not reuse
//    public static int[] vars;
//    public static int[] otherVars; // convert between bdd and vector

    public Set<ArrayList<Integer>> bddVectors;

    // if reuse bdd variables, must set maxFieldLength
    public static void initBDDArray(boolean reuse, int maxLen, int bddTableSize, int bddCacheSize) {
        bddEngine = new BDD(bddTableSize, bddCacheSize);
        reuseBDDVar = reuse;
        if (reuseBDDVar) {
            maxFieldLength = maxLen;
            totalVarNum = 0;
            backupBDDVars = new ArrayList<>();
            declareVarsToReuse();
        }
        fieldNum = -1;
        maxVariablePerField = new ArrayList<>();
        satCountDiv = new ArrayList<>();
        bddVarsPerField = new ArrayList<>();
        bddNotVarsPerField = new ArrayList<>();
    }

    private static void declareVarsToReuse() {
        bddVarsReuse = new int[maxFieldLength];
        bddNotVarsReuse = new int[maxFieldLength];
        for (int i = 0; i < maxFieldLength; i++) {
            bddVarsReuse[i] = bddEngine.ref(bddEngine.createVar());
            bddNotVarsReuse[i] = bddEngine.ref(bddEngine.not(bddNotVarsReuse[i]));
        }
    }

    public static int declareField(int bitNum) {
        fieldNum++;
        if (maxVariablePerField.isEmpty()) {
            maxVariablePerField.add(bitNum - 1);
        } else {
            maxVariablePerField.add(maxVariablePerField.get(maxVariablePerField.size() - 1) + bitNum);
        }
        if (reuseBDDVar) {
            // todo: should maintain maxVariablePerField?
            satCountDiv.add(Math.pow(2.0, maxFieldLength - bitNum));
            int[] bddVars = new int[bitNum];
            int[] bddNotVars = new int[bitNum];
            for (int i = 0; i < bitNum; i++) {
                bddVars[i] = bddVarsReuse[maxFieldLength - bitNum + i];
                bddNotVars[i] = bddNotVarsReuse[maxFieldLength - bitNum + i];
            }
            bddVarsPerField.add(bddVars);
            bddNotVarsPerField.add(bddNotVars);

            totalVarNum += bitNum;

            for (int i = 0; i < bitNum; i++) {
                if (backupBDDVars.size() < bddVarsReuse.length) {
                    backupBDDVars.add(bddVarsReuse[backupBDDVars.size()]);
                } else {
                    backupBDDVars.add(bddEngine.ref(bddEngine.createVar()));
                }
            }
        } else {
            double factor = Math.pow(2.0, bitNum);
            for (int i = 0; i < satCountDiv.size(); i++) {
                satCountDiv.set(i, satCountDiv.get(i) * factor);
            }
            int totalBitsBefore = 0;
            if (maxVariablePerField.size() > 1) {
                totalBitsBefore = maxVariablePerField.get(maxVariablePerField.size() - 2) + 1;
            }
            satCountDiv.add(Math.pow(2.0, totalBitsBefore));

            int[] bddVars = new int[bitNum];
            int[] bddNotVars = new int[bitNum];
            for (int i = 0; i < bitNum; i++) {
                bddVars[i] = bddEngine.ref(bddEngine.createVar());
                bddNotVars[i] = bddEngine.ref(bddEngine.not(bddVars[i]));
            }
            bddVarsPerField.add(bddVars);
            bddNotVarsPerField.add(bddNotVars);
        }
        TRUE.bddVectors.iterator().next().add(BDD_TRUE);
        FALSE.bddVectors.iterator().next().add(BDD_FALSE);
        return fieldNum;
    }

    public static BDDVectors getVar(int field, int index) {
        Set<ArrayList<Integer>> bddVectors = new HashSet<>();
        ArrayList<Integer> bddVector = new ArrayList<>();
        for (int i = 0; i < fieldNum; i++) {
            bddVector.add(BDD_TRUE);
        }
        bddVector.set(field, bddEngine.ref(bddVarsPerField.get(field)[index]));
        return new BDDVectors(bddVectors);
    }

    public static BDDVectors getNotVar(int field, int index) {
        Set<ArrayList<Integer>> bddVectors = new HashSet<>();
        ArrayList<Integer> bddVector = new ArrayList<>();
        for (int i = 0; i < fieldNum; i++) {
            bddVector.add(BDD_TRUE);
        }
        bddVector.set(field, bddEngine.ref(bddNotVarsPerField.get(field)[index]));
        bddVectors.add(bddVector);
        return new BDDVectors(bddVectors);
    }

    public BDDVectors() {
        bddVectors = new HashSet<>();
    }

    public BDDVectors(boolean flag) {
        // lazy init BDDArray True and False
        bddVectors = new HashSet<>();
        ArrayList<Integer> vector = new ArrayList<Integer>();
        bddVectors.add(vector);
    }

    public BDDVectors(Set<ArrayList<Integer>> bddVectors) {
        this.bddVectors = bddVectors;
    }

    public BDDVectors(BDDVectors another) {
        // deep copy
        this.bddVectors = new HashSet<>();
        for (ArrayList<Integer> vector : another.bddVectors) {
            ArrayList<Integer> newVector = new ArrayList<>(vector);
            this.bddVectors.add(newVector);
        }
        // ref each bdd in bdd vectors
         ref(this);
    }

    public boolean equals(BDDVectors obj) {
        return this.bddVectors.equals(obj.bddVectors);
    }

    public boolean isTrue() {
        // return this == VectorTrue;
        if (this == TRUE) {
            return true;
        }

        for (ArrayList<Integer> bddVector : bddVectors) {
            int i = 0;
            for (; i < bddVector.size(); i++) {
                if (bddVector.get(i) != 1) {
                    break;
                }
            }
            if (i == fieldNum) {
                return true;
            }
        }
        return false;
    }

    public boolean isFalse() {
        // do not only use pointer compare to judge VectorFalse
        if (this == FALSE) {
            return true;
        }

        // only if every vector has 0 can be regarded as False
        for (ArrayList<Integer> bddVector : bddVectors) {
            boolean flag = false;
            for (int i = 0; i < bddVector.size(); i++) {
                if (bddVector.get(i) == 0) {
                    flag = true;
                    break;
                }
            }
            if (!flag)
                return false;
        }
        return true;
    }

    public boolean isTerminal() {
        return this.isTrue() || this.isFalse();
    }

    public static BDDVectors ref(BDDVectors bddVectors) {
        for (ArrayList<Integer> bddVector : bddVectors.bddVectors) {
            for (int i = 0; i < bddVector.size(); i++) {
                bddEngine.ref(bddVector.get(i));
            }
        }
        return bddVectors;
    }

    public static void deref(BDDVectors bddVectors) {
        for (ArrayList<Integer> bddVector : bddVectors.bddVectors) {
            for (int i = 0; i < bddVector.size(); i++) {
                bddEngine.deref(bddVector.get(i));
            }
        }
    }

    public static BDDVectors andTo(BDDVectors a, BDDVectors b) {
        BDDVectors result = ref(andRec(a, b));
        deref(a);
        return result;
    }

    public static BDDVectors and(BDDVectors a, BDDVectors b) {
        BDDVectors result = andRec(a, b);
        return result;
    }

    private static BDDVectors andRec(BDDVectors a, BDDVectors b) {
        if (a.isFalse() || b.isFalse()) {
            return FALSE;
        } else if (a.isTrue()) {
            // todo: why create new vectors?
            return ref(new BDDVectors(b));
        } else if (b.isTrue() || a.equals(b)){
            return ref(new BDDVectors(a));
        }

        BDDVectors result = new BDDVectors();
        for (ArrayList<Integer> vectorA : a.bddVectors) {
            for (ArrayList<Integer> vectorB : b.bddVectors) {
                ArrayList<Integer> vectorC = new ArrayList<>();
                // remove vector with any idx pointed to bdd false
                boolean isFalse = false;
                for (int i = 0; i < vectorA.size(); i++) {
                    int bdd = bddEngine.ref(bddEngine.and(vectorA.get(i), vectorB.get(i)));
                    if (bdd == BDD_FALSE) {
                        isFalse = true;
                    }
                    vectorC.add(bdd);
                }
                if (!isFalse) {
                    result.bddVectors.add(vectorC);
                } else {
                    for (int bdd : vectorC) {
                        bddEngine.deref(bdd);
                    }
                }
            }
        }
        // already ref in process
        if (result.bddVectors.size() == 0) {
            return FALSE;
        } else {
            return result;
        }
    }

    public static BDDVectors orTo(BDDVectors a, BDDVectors b) {
        BDDVectors result = ref(orRec(a, b));
        deref(a);
        return result;
    }

    public static BDDVectors or(BDDVectors a, BDDVectors b) {
        BDDVectors result = orRec(a, b);
        return result;
    }

    private static BDDVectors orRec(BDDVectors a, BDDVectors b) {
        if (a.isTrue() || b.isTrue()) {
            return TRUE;
        } else if (a.isFalse()) {
            return ref(new BDDVectors(b));
        } else if (b.isFalse() || a.equals(b)) {
            return ref(new BDDVectors(a));
        }

        BDDVectors result = new BDDVectors();
        result.bddVectors.addAll(a.bddVectors);
        result.bddVectors.addAll(b.bddVectors);
        return ref(result);
    }

    /*
     * Three different implementation of Not operation,
     * but only recommend NotRecBackBDD for its fastest speed.
     */
    public static BDDVectors not(BDDVectors a) {
        switch (NEGATION_METHOD) {
            case 1:
                return notRec(a);
            case 2:
                return notRecDirectly(a);
            default:
                return notBackToBDD(a);
        }
    }

    /*
     * Implement negation of bdd vectors by De Morgan's laws.
     */
    private static BDDVectors notRec(BDDVectors a) {
        if (a.isTrue()) {
            return FALSE;
        } else if (a.isFalse()) {
            return TRUE;
        }

        BDDVectors result = TRUE;

        for (ArrayList<Integer> bddVector : a.bddVectors) {
            BDDVectors resultPerVector = new BDDVectors();
            for (int i = 0; i < bddVector.size(); i++) {
                if (bddVector.get(i) == BDD_TRUE)
                    continue;
                ArrayList<Integer> temp = new ArrayList<>();
                for (int j = 0; j < bddVector.size(); j++) {
                    temp.add(BDD_TRUE);
                }
                temp.set(i, bddEngine.ref(bddEngine.not(bddVector.get(i))));
                resultPerVector.bddVectors.add(temp);
            }
            result = andTo(result, resultPerVector);
            deref(resultPerVector);
        }
        return result;
    }

    private static BDDVectors notRecDirectly(BDDVectors a) {
        if (a.isTrue()) {
            return FALSE;
        } else if (a.isFalse()) {
            return TRUE;
        }

        if (a.bddVectors.size() == 1)
            return new BDDVectors(notRecSingleVector(a.bddVectors.iterator().next()));

        HashSet<ArrayList<Integer>> result = null;
        for (ArrayList<Integer> bddVector : a.bddVectors) {
            if (result == null) {
                result = notRecSingleVector(bddVector);
            } else {
                HashSet<ArrayList<Integer>> newResult = new HashSet<>();
                for (int i = 0; i < fieldNum; i++) {
                    if (bddVector.get(i) == BDD_TRUE) {
                        continue;
                    }
                    int elementNot = bddEngine.ref(bddEngine.not(bddVector.get(i)));
                    for (ArrayList<Integer> resultVector : result) {
                        int intersect = bddEngine.and(resultVector.get(i), elementNot);
                        if (intersect == BDD_FALSE) {
                            continue;
                        }
                        ArrayList<Integer> temp = new ArrayList<>(resultVector);
                        temp.set(i, intersect);
                        for (int j = 0; j < fieldNum; j++) {
                            bddEngine.ref(temp.get(j));
                        }
                        newResult.add(temp);
                    }
                    bddEngine.deref(elementNot);
                }
                if (newResult.isEmpty()) {
                    // todo: should directly return FALSE?
//                    continue;
                    return FALSE;
                }

                for (ArrayList<Integer> oldArray : result) {
                    for (int oldBDD : oldArray) {
                        bddEngine.deref(oldBDD);
                    }
                }
                result = newResult;
            }
        }
        return new BDDVectors(result);
    }

    private static HashSet<ArrayList<Integer>> notRecSingleVector(ArrayList<Integer> bddVector) {
        HashSet<ArrayList<Integer>> result = new HashSet<>();

        for (int i = 0; i < fieldNum; i++) {
            // ignore 1 for its not will be 0 which cannot be in vectors
            if (bddVector.get(i) == BDD_TRUE) {
                continue;
            }

            ArrayList<Integer> newBDDVector = new ArrayList<>();
            for (int j = 0; j < fieldNum; j++) {
                newBDDVector.add(BDD_TRUE);
            }
            newBDDVector.set(i, bddEngine.ref(bddEngine.not(bddVector.get(i))));
            result.add(newBDDVector);
        }

        if (result.isEmpty()) {
            ArrayList<Integer> newBDDVector = new ArrayList<>();
            for (int i = 0; i < fieldNum; i++) {
                newBDDVector.add(BDD_FALSE);
            }
        }
        return result;
    }

    private static BDDVectors notBackToBDD(BDDVectors a) {
        int equivalentBDD = bddEngine.ref(toBDD(a));
        int equivalentNotBDD = bddEngine.ref(bddEngine.not(equivalentBDD));
        BDDVectors result = toBDDVectors(equivalentNotBDD);
        bddEngine.deref(equivalentBDD);
        bddEngine.deref(equivalentNotBDD);
        return result;
    }

    public static BDDVectors diff(BDDVectors a, BDDVectors b) {
        BDDVectors temp = not(b);
        BDDVectors result = and(a, temp);
        deref(temp);
        return result;
    }

    public static BDDVectors exist(BDDVectors a, int field) {
        return existRec(a, field);
    }

    private static BDDVectors existRec(BDDVectors a, int field) {
        if (a.isTrue() || a.isFalse())
            return a;

        BDDVectors result = new BDDVectors();
        for (ArrayList<Integer> bddVector : a.bddVectors) {
            ArrayList<Integer> newBDDVector = new ArrayList<>(bddVector);
            newBDDVector.set(field, BDD_TRUE);
            result.bddVectors.add(newBDDVector);
        }
        return ref(result);
    }

    // convert bdd vectors to equivalent bdd
    public static int toBDD(BDDVectors a) {
        if (reuseBDDVar) {
            return toBDDReuse(a);
        } else {
            return toBDDNotReuse(a);
        }
    }

    private static int toBDDNotReuse(BDDVectors a) {
        // convert each vector to equivalent bdd
        ArrayList<Integer> bdds = new ArrayList<>();
        for (ArrayList<Integer> bddVector : a.bddVectors) {
            int bddForVector = BDD_TRUE;
            for (int i = bddVector.size() - 1; i >= 0; i--) {
                int bddForField = bddVector.get(i);
                bddForVector = bddEngine.andTo(bddForVector, bddForField);
            }
            bdds.add(bddForVector);
        }

        // merge bdd for each vector
        int result = BDD_FALSE;
        for (int i = 0; i < bdds.size(); i++) {
            int bddForVector = bdds.get(i);
            result = bddEngine.orTo(result, bddForVector);
            bddEngine.deref(bddForVector);
        }
        return result;
    }

    private static int toBDDReuse(BDDVectors a) {
        if (a.isTrue()) {
            return BDD_TRUE;
        } else if (a.isFalse()){
            return BDD_FALSE;
        }

        ArrayList<Integer> bdds = new ArrayList<>();
        for (ArrayList<Integer> bddVector : a.bddVectors) {
            int bddForVector = BDD_TRUE;
            for (int i = 0; i < bddVector.size(); i++) {
                int bddForField = bddVector.get(i);
                if (bddForField == BDD_TRUE) continue;
                int[] from = bddVarsPerField.get(i);
                int bitNum = maxVariablePerField.get(0) + 1;
                int startBit = 0;
                if (i > 0) {
                    bitNum = maxVariablePerField.get(i) - maxVariablePerField.get(i - 1);
                    startBit = maxVariablePerField.get(i - 1) + 1;
                }
                int[] to = new int[bitNum];
                for (int j = 0; j < bitNum; j++) {
                    to[j] = backupBDDVars.get(startBit + j);
                }
                Permutation perm = bddEngine.createPermutation(from, to);
                bddForField = bddEngine.ref(bddEngine.replace(bddForField, perm));

                bddForVector = bddEngine.andTo(bddForVector, bddForField);
                bddEngine.deref(bddForVector);
            }
            bdds.add(bddForVector);
        }

        int result = BDD_FALSE;
        for (int i = 0; i < bdds.size(); i++) {
            int bddForVector = bdds.get(i);
            result = bddEngine.orTo(result, bddForVector);
            bddEngine.deref(bddForVector);
        }
        return result;
    }

    public static double satCount(BDDVectors a) {
        int equivalentBDD = toBDD(a);
        double satCount = bddEngine.satCount(equivalentBDD);
        bddEngine.deref(equivalentBDD);
        return satCount;
    }

    // convert a bdd to equivalent bdd vectors
    public static BDDVectors toBDDVectors(int a) {
        BDDVectors result = toBDDVectorsRec(a);
        return result;
    }

    private static BDDVectors toBDDVectorsRec(int a) {
        // decomposed: from -> {to -> bdd}
        HashMap<Integer, HashMap<Integer, Integer>> decomposed = DecomposeBDD.decompose(a, bddEngine, maxVariablePerField);

        ArrayList<Integer> tempBDDVector = new ArrayList<>();
        Set<ArrayList<Integer>> bddVectors = new HashSet<>();

        for (int i = 0; i < DecomposeBDD.bddGetField(a); i++) {
            tempBDDVector.add(BDD_TRUE);
        }

        toBDDVectorsDFS(bddVectors, decomposed, a, tempBDDVector);

        if (reuseBDDVar) {
            for (ArrayList<Integer> bddVector : bddVectors) {
                for (int currentField = 0; currentField < fieldNum; currentField++) {
                    if (bddVector.get(currentField) == BDD_TRUE || bddVector.get(currentField) == BDD_FALSE) {
                        continue;
                    }

                    int bitNum = maxVariablePerField.get(0) + 1;
                    int startBit = 0;
                    if (currentField > 0) {
                        bitNum = maxVariablePerField.get(currentField) - maxVariablePerField.get(currentField - 1);
                        startBit = maxVariablePerField.get(currentField - 1) + 1;
                    }
                    int[] from = new int[bitNum];
                    for (int j = 0; j < bitNum; j++) {
                        from[j] = backupBDDVars.get(startBit + j);
                    }

                    int[] to = bddVarsPerField.get(currentField);

                    Permutation perm = bddEngine.createPermutation(from, to);
                    int newBDD = bddEngine.ref(bddEngine.replace(bddVector.get(currentField), perm));
                    bddEngine.deref(bddVector.get(currentField));
                    bddVector.set(currentField, newBDD);
                }
            }
        }

        for (HashMap<Integer, Integer> map : decomposed.values()) {
            for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
                bddEngine.deref(entry.getValue());
            }
        }

        return BDDVectors.ref(new BDDVectors(bddVectors));
    }

    private static void toBDDVectorsDFS(Set<ArrayList<Integer>> bddVectors,
                                       HashMap<Integer, HashMap<Integer, Integer>> decomposed, int from, ArrayList<Integer> bddVector) {
        if (from == BDD_TRUE) {
            ArrayList<Integer> newVector = new ArrayList<>(bddVector);
            bddVectors.add(newVector);
            return;
        }
        HashMap<Integer, Integer> map = decomposed.get(from);
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            int to = entry.getKey();
            int perFieldBDD = entry.getValue();
            int middleFieldNum = DecomposeBDD.bddGetField(to) - DecomposeBDD.bddGetField(from) - 1;
            bddVector.add(bddEngine.ref(perFieldBDD));
            for (int i = 0; i < middleFieldNum; i++) {
                bddVector.add(BDD_TRUE);
            }

            toBDDVectorsDFS(bddVectors, decomposed, to, bddVector);

            for (int i = 0; i <= middleFieldNum; i++) {
                bddVector.remove(bddVector.size() - 1);
            }
        }
    }

    public static int toZero(BDDVectors n) {
        int equivalentBDD = bddEngine.ref(toBDD(n));
        int result = bddEngine.toZero(equivalentBDD);
        bddEngine.deref(equivalentBDD);
        return result;
    }

    public static BDDVectors encodeAtMostKFailureVarsSorted(int[] vars, int startField, int endField, int k) {
        return encodeAtMostKFailureVarsSortedRec(vars, startField, endField, k);
    }

    private static BDDVectors encodeAtMostKFailureVarsSortedRec(int[] vars, int currentField, int endField, int k) {
        if (currentField > endField) {
            return TRUE;
        }

        int bitNum = maxVariablePerField.get(0) + 1;
        if (currentField > 0) {
            bitNum = maxVariablePerField.get(currentField) - maxVariablePerField.get(currentField - 1);
        }

        HashMap<BDDVectors, Integer> map = new HashMap<>();
        for (int i = 0; i <= k; i++) {
            // bdd with k and only k failures
            int toAddBDD = bddEngine.ref(encodeBDD(bddEngine, vars, 0, bitNum - 1, i));
            BDDVectors bddVectors = encodeAtMostKFailureVarsSortedRec(vars, endField, currentField + 1, k - i);
            int nextFieldBDD = BDD_FALSE;
            if (map.containsKey(bddVectors)) {
                nextFieldBDD = map.get(bddVectors);
            }
            nextFieldBDD = bddEngine.orTo(nextFieldBDD, toAddBDD);
            bddEngine.deref(toAddBDD);
            map.put(bddVectors, nextFieldBDD);
        }
        return BDDVectors.addAtField(currentField, map);
    }

    private static BDDVectors addAtField(int field, HashMap<BDDVectors, Integer> map) {
        BDDVectors result = new BDDVectors();
        for (Map.Entry<BDDVectors, Integer> entry : map.entrySet()) {
            BDDVectors bddVectors = entry.getKey();
            int oneBDD = entry.getValue();
            for (ArrayList<Integer> bddVector : bddVectors.bddVectors) {
                ArrayList<Integer> temp = new ArrayList<>(bddVector);
                temp.set(field, bddEngine.ref(oneBDD));
                result.bddVectors.add(temp);
                bddEngine.deref(bddVector.get(field));
            }
            bddEngine.deref(oneBDD);
        }
        return result;
    }

    private static int encodeBDD(BDD bdd, int[] vars, int currentVar, int endVar, int k) {
        // cache? link num, k -> bdd
        if (k < 0) {
            return BDD_FALSE;
        } else if (currentVar > endVar) {
            if (k > 0) {
                return BDD_FALSE;
            } else {
                return BDD_TRUE;
            }
        }
        int low = encodeBDD(bdd, vars, currentVar + 1, endVar, k - 1);
        int high = encodeBDD(bdd, vars, currentVar + 1, endVar, k);

        return bdd.mk(bdd.getVar(vars[endVar - currentVar]), low, high);
    }

    public static void printDot(String path, BDDVectors bddVectors) {
        for (ArrayList<Integer> bddVector : bddVectors.bddVectors) {
            for (int i = 0; i < fieldNum; i++) {
                int oneBDD = bddVector.get(i);
                if (oneBDD != BDD_TRUE && oneBDD != BDD_FALSE) {
                    bddEngine.printDot(path + "/" + oneBDD, oneBDD);
                }
            }
        }
    }

    public static void nodeCount(BDDVectors bddVectors) {
        HashSet<Integer> bddRootSet = new HashSet<>();
        for (ArrayList<Integer> bddVector : bddVectors.bddVectors) {
            for (int i = 0; i < bddVector.size(); i++) {
                bddRootSet.add(bddVector.get(i));
            }
        }

        HashSet<Integer> bddNodeSet = new HashSet<>();
        for (int BDDRoot : bddRootSet) {
            detectBDD(BDDRoot, bddNodeSet);
        }

        System.out.println("NDD node:" + bddVectors.bddVectors.size() + " BDD node:" + bddNodeSet.size());
    }

    private static void detectBDD(int node, HashSet<Integer> BDDSet) {
        if (node == BDD_TRUE || node == BDD_FALSE) {
        } else {
            BDDSet.add(node);
            detectBDD(bddEngine.getHigh(node), BDDSet);
            detectBDD(bddEngine.getLow(node), BDDSet);
        }
    }
}