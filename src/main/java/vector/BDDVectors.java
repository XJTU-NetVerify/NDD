package vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdd.bdd.BDD;

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
    private static int maxFieldLength;
    private static int[] bddVarReuse;
    private static int[] bddNotVarReuse;

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
            declareVarsToReuse();
        }
        fieldNum = -1;
        maxVariablePerField = new ArrayList<>();
        satCountDiv = new ArrayList<>();
        bddVarsPerField = new ArrayList<>();
        bddNotVarsPerField = new ArrayList<>();
    }

    private static void declareVarsToReuse() {
        bddVarReuse = new int[maxFieldLength];
        bddNotVarReuse = new int[maxFieldLength];
        for (int i = 0; i < maxFieldLength; i++) {
            bddVarReuse[i] = bddEngine.ref(bddEngine.createVar());
            bddNotVarReuse[i] = bddEngine.ref(bddEngine.not(bddNotVarReuse[i]));
        }
    }

    public static int declareField(int bitNum) {
        fieldNum++;
        if (reuseBDDVar) {
            // todo: should maintain maxVariablePerField?
            satCountDiv.add(Math.pow(2.0, maxFieldLength - bitNum));
            int[] bddVars = new int[bitNum];
            int[] bddNotVars = new int[bitNum];
            for (int i = 0; i < bitNum; i++) {
                bddVars[i] = bddVarReuse[maxFieldLength - bitNum + i];
                bddNotVars[i] = bddNotVarReuse[maxFieldLength - bitNum + i];
            }
        } else {
            if (maxVariablePerField.isEmpty()) {
                maxVariablePerField.add(bitNum - 1);
            } else {
                maxVariablePerField.add(maxVariablePerField.get(maxVariablePerField.size() - 1) + bitNum);
            }

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
                System.err.println("Illegal NEGATION_METHOD");
                return null;
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

//    public static double satCount(BDDVectors curr) {
//        int idx = bdd.ref(toBDD(curr));
//        double ret = bdd.satCount(idx);
//        if (reuse)
//            ret = ret / Math.pow(2.0, maxVarsLength);
//        bdd.deref(idx);
//        return ret;
//        // return satCountRec(curr);
//    }

    // can not deal with [[3, 1, 1], [1, 1, 2]] which has overlay
    // private static double satCountRec(BDDArray curr) {
    // double ret = 0.0;
    // for (ArrayList<Integer> vector : curr.vectors) {
    // double subRet = 1.0;
    // for (int i = fieldNum - 1; i >= 0; i--) {
    // subRet = subRet * (bdd.satCount(vector.get(i)) / div[i]);
    // }
    // ret += subRet;
    // }
    // return ret;
    // }

    // todo: should we use this method?
//    private static BDDVectors notRecBackBDD(BDDVectors a) {
//        ref(a);
//        int bddidx = bdd.ref(toBDD(a));
//        int idx = bdd.ref(bdd.not(bddidx));
//        try {
//            BDDVectors ret = toBDDArray(idx);
//            bdd.deref(bddidx);
//            bdd.deref(idx);
//            deref(a);
//            return ref(ret);
//        } catch (Exception e) { // random bug but cannot catch now
//            System.out.println("====== catch ======");
//            System.out.println(a.vectors.toString());
//            boolean flag = false;
//            for (ArrayList<Integer> vector : a.vectors) {
//                for (int i = 0; i < fieldNum; i++) {
//                    if (bdd.getVar(vector.get(i)) == -1) {
//                        flag = true;
//                    }
//                }
//            }
//            if (flag) {
//                System.out.println("-1 vectors");
//            }
//        }
//        return VectorTrue;
//    }

//    public static int[] createVar(int maxNum) {
//        maxVarsLength = maxNum;
//
//        // bdd reuse so use max field num
//        vars = new int[maxNum];
//        if (reuse) {
//            otherVars = new int[varNumTotal];
//
//            // first <- last for reuse
//            for (int i = maxNum - 1; i >= 0; i--) {
//                vars[i] = bdd.createVar();
//            }
//            // create maxVarsLength + varNumTotal bdd vars
//            for (int i = 0; i < varNumTotal; i++) {
//                otherVars[i] = bdd.createVar();
//            }
//        } else {
//            for (int i = 0; i < maxNum; i++) {
//                vars[i] = bdd.createVar();
//            }
//        }
//        return vars;
//    }
//
//    public static void SetFieldNum(int num) {
//        fieldNum = num;
//
//        // lazy init for NDD True [1, 1, 1] and False [0, 0, 0]
//        for (ArrayList<Integer> vector : VectorTrue.vectors) {
//            for (int i = 0; i < fieldNum; i++) {
//                vector.add(1);
//            }
//        }
//        for (ArrayList<Integer> vector : VectorFalse.vectors) {
//            for (int i = 0; i < fieldNum; i++) {
//                vector.add(0);
//            }
//        }
//    }
//
//    public static void SetUpperBound(int[] upper) {
//        upperBound = upper;
//
//        varNumTotal = upper[upper.length - 1] + 1;
//
//        varNumList = new int[fieldNum];
//        varNumList[0] = upper[0] + 1;
//        for (int i = 1; i < fieldNum; i++) {
//            varNumList[i] = upper[i] - upper[i - 1];
//        }
//    }

//    private static int bdd_getField(int a) {
//        if (reuse)
//            return bdd_getField_reuse(a);
//        return bdd_getField_not_reuse(a);
//    }
//
//    // not reuse
//    private static int bdd_getField_not_reuse(int a) {
//        int va = bdd.getVar(a);
//        if (a == 1 || a == 0)
//            return fieldNum;
//        int curr = 0;
//        while (curr < fieldNum) {
//            if (va <= upperBound[curr]) {
//                break;
//            }
//            curr++;
//        }
//        return curr;
//    }
//
//    // reuse
//    private static int bdd_getField_reuse(int a) {
//        int va = bdd.getVar(a);
//        if (a == 1 || a == 0)
//            return fieldNum;
//        int curr = 0;
//        while (curr < fieldNum) {
//            if (va - maxVarsLength <= upperBound[curr]) {
//                break;
//            }
//            curr++;
//        }
//        return curr;
//    }
//
//    public static int toBDD(BDDVectors n) {
//        if (reuse)
//            return toBDDReuse(n);
//        return toBDDNotReuse(n);
//    }
//
//    // bdd not reuse
//    public static int toBDDNotReuse(BDDVectors n) {
//        ArrayList<Integer> bdds = new ArrayList<>();
//        for (ArrayList<Integer> vector : n.vectors) {
//            int lastIdx = 1;
//            for (int i = vector.size() - 1; i >= 0; i--) {
//                int idx = vector.get(i);
//                int temp = lastIdx;
//                lastIdx = bdd.and(idx, lastIdx);
//                bdd.ref(lastIdx);
//                bdd.deref(temp);
//                bdd.deref(idx);
//            }
//            bdds.add(lastIdx);
//        }
//
//        int ret = 0;
//        for (int i = 0; i < bdds.size(); i++) {
//            int temp = ret;
//            int idx = bdds.get(i);
//            ret = bdd.ref(bdd.or(ret, idx));
//            bdd.deref(idx);
//            bdd.deref(temp);
//        }
//        return ret;
//    }
//
//    // bdd reuse
//    public static int toBDDReuse(BDDVectors n) {
//        if (n.is_True())
//            return 1;
//
//        ArrayList<Integer> bdds = new ArrayList<>();
//        for (ArrayList<Integer> vector : n.vectors) {
//            int lastIdx = 1;
//            for (int i = 0; i < vector.size(); i++) {
//                int idx = vector.get(i);
//                if (idx == 1)
//                    continue;
//
//                int length = varNumList[i];
//                int[] from = Arrays.copyOfRange(vars, 0, length);
//                // reverse from for its initialize from last to first
//                for (int j = 0; j < length / 2; j++) {
//                    int temp = from[j];
//                    from[j] = from[length - j - 1];
//                    from[length - j - 1] = temp;
//                }
//                int[] to = Arrays.copyOfRange(otherVars, upperBound[i] + 1 - length, upperBound[i] + 1);
//
//                jdd.bdd.Permutation perm = bdd.createPermutation(from, to);
//                idx = bdd.ref(bdd.replace(idx, perm));
//
//                int temp = lastIdx;
//                lastIdx = bdd.ref(bdd.and(idx, lastIdx));
//                bdd.deref(temp);
//                // bdd.deref(idx);
//            }
//            bdds.add(lastIdx);
//        }
//
//        int ret = 0;
//        for (int i = 0; i < bdds.size(); i++) {
//            int temp = ret;
//            int idx = bdds.get(i);
//            ret = bdd.ref(bdd.or(ret, idx));
//            bdd.deref(idx);
//            bdd.deref(temp);
//        }
//        return ret;
//    }
//
//    public static BDDVectors toBDDArray(int a) {
//        try {
//            BDDVectors ret = toBDDArrayRec(a);
//            return ret;
//        } catch (Exception e) {
//            throw e;
//        }
//        // return toBDDArrayRec(a);
//    }
//
//    private static BDDVectors toBDDArrayRec(int a) {
//        // decomposed: from idx -> {to idx -> bdd}
//        HashMap<Integer, HashMap<Integer, Integer>> decomposed = decompose(a);
//
//        ArrayList<Integer> temp = new ArrayList<>();
//        Set<ArrayList<Integer>> vectors = new HashSet<>();
//
//        for (int i = 0; i < bdd_getField(a); i++) {
//            temp.add(1);
//        }
//
//        toBDDvectorDFS(vectors, decomposed, a, temp);
//
//        // replace
//        if (reuse) {
//            for (ArrayList<Integer> vector : vectors) {
//                for (int field = 0; field < fieldNum; field++) {
//                    if (vector.get(field) == 1 || vector.get(field) == 0) {
//                        continue;
//                    }
//
//                    int length = varNumList[field];
//                    int[] from = Arrays.copyOfRange(otherVars, upperBound[field] + 1 - length,
//                            upperBound[field] + 1);
//                    // reverse from for its initialize from last to first
//                    for (int j = 0; j < length / 2; j++) {
//                        int t = from[j];
//                        from[j] = from[length - j - 1];
//                        from[length - j - 1] = t;
//                    }
//                    int[] to = Arrays.copyOfRange(vars, 0, length);
//
//                    jdd.bdd.Permutation perm = bdd.createPermutation(from, to);
//                    int idx = 0;
//                    try {
//                        idx = bdd.ref(bdd.replace(vector.get(field), perm));
//                    } catch (Exception e) { // random bug but cannot catch now
//                        System.out.println();
//                        System.out.println("field num " + fieldNum);
//                        System.out.println("wrong at " + field + " bdd " + vector.get(field) + " var "
//                                + bdd.getVar(vector.get(field)));
//                        System.out.print("whole vector field ");
//                        for (int i = 0; i < fieldNum; i++) {
//                            System.out.print(bdd.getVar(vector.get(i)) + " ");
//                        }
//                        System.out.println();
//                        System.out.println("vectors " + vectors.toString());
//                        System.out.println("vector " + vector.toString());
//                        System.out.print("from ");
//                        for (int i = 0; i < length; i++) {
//                            System.out.print(from[i] + " ");
//                        }
//                        System.out.println();
//                        System.out.print("to ");
//                        for (int i = 0; i < length; i++) {
//                            System.out.print(to[i] + " ");
//                        }
//                        System.out.println();
//                        throw new IndexOutOfBoundsException();
//                    }
//                    // bdd.deref(vector.get(field));
//
//                    vector.set(field, idx);
//                }
//            }
//        }
//
//        // deref idx in decompose
//        for (Map.Entry<Integer, HashMap<Integer, Integer>> en : decomposed.entrySet()) {
//            for (Map.Entry<Integer, Integer> entry : en.getValue().entrySet()) {
//                int idx = entry.getValue();
//                bdd.deref(idx);
//            }
//        }
//
//        return BDDVectors.ref(new BDDVectors(vectors));
//    }
//
//    private static void toBDDvectorDFS(Set<ArrayList<Integer>> vectors,
//                                       HashMap<Integer, HashMap<Integer, Integer>> data, int root, ArrayList<Integer> vector) {
//        // in case of root == 1 in the first iterator
//        if (root == 1) {
//            ArrayList<Integer> vectorRet = new ArrayList<>(vector);
//            vectors.add(vectorRet);
//            return;
//        }
//        HashMap<Integer, Integer> map = data.get(root);
//        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
//            int end = entry.getKey();
//
//            int idx = entry.getValue();
//            int fieldDiff = bdd_getField(end) - bdd_getField(root) - 1;
//
//            // iterator end here
//            if (end == 1) {
//                ArrayList<Integer> vectorRet = new ArrayList<>(vector);
//                vectorRet.add(idx);
//                for (int i = 0; i < fieldDiff; i++) {
//                    vectorRet.add(1);
//                }
//                vectors.add(vectorRet);
//                continue;
//            }
//
//            vector.add(idx);
//
//            for (int i = 0; i < fieldDiff; i++) {
//                vector.add(1);
//            }
//
//            toBDDvectorDFS(vectors, data, end, vector);
//
//            for (int i = 0; i <= fieldDiff; i++) {
//                vector.remove(vector.size() - 1);
//            }
//        }
//    }
//
//    private static HashMap<Integer, HashMap<Integer, Integer>> decompose(int a) {
//        HashMap<Integer, HashMap<Integer, Integer>> decomposed_bdd = new HashMap<Integer, HashMap<Integer, Integer>>();
//        if (a == 0)
//            return decomposed_bdd;
//        if (a == 1) {
//            HashMap<Integer, Integer> map = new HashMap<>();
//            map.put(1, 1);
//            decomposed_bdd.put(1, map);
//            return decomposed_bdd;
//        }
//        HashMap<Integer, HashSet<Integer>> boundary_tree = new HashMap<Integer, HashSet<Integer>>();
//        ArrayList<HashSet<Integer>> boundary_points = new ArrayList<HashSet<Integer>>();
//
//        // boundary points: field -> idx list
//        // boundary tree: idx -> child idx list
//        get_boundary_tree(a, boundary_tree, boundary_points);
//
//        for (int curr_level = 0; curr_level < fieldNum - 1; curr_level++) {
//            for (int root : boundary_points.get(curr_level)) {
//                for (int end_point : boundary_tree.get(root)) {
//                    int res = bdd.ref(construct_decomposed_bdd(root, end_point, root));
//                    if (!decomposed_bdd.containsKey(root)) {
//                        decomposed_bdd.put(root, new HashMap<Integer, Integer>());
//                    }
//                    decomposed_bdd.get(root).put(end_point, res);
//                }
//            }
//        }
//
//        for (int abdd : boundary_points.get(fieldNum - 1)) {
//            if (!decomposed_bdd.containsKey(abdd)) {
//                decomposed_bdd.put(abdd, new HashMap<Integer, Integer>());
//            }
//            decomposed_bdd.get(abdd).put(1, bdd.ref(abdd));
//        }
//
//        return decomposed_bdd;
//    }
//
//    private static void get_boundary_tree(int a, HashMap<Integer, HashSet<Integer>> boundary_tree,
//                                          ArrayList<HashSet<Integer>> boundary_points) {
//        int start_level = bdd_getField(a);
//        for (int curr = 0; curr < fieldNum; curr++) {
//            boundary_points.add(new HashSet<Integer>());
//        }
//        boundary_points.get(start_level).add(a);
//        if (start_level == fieldNum - 1) {
//            boundary_tree.put(a, new HashSet<Integer>());
//            boundary_tree.get(a).add(1);
//            return;
//        }
//
//        for (int curr_level = start_level; curr_level < fieldNum; curr_level++) {
//            for (int abdd : boundary_points.get(curr_level)) {
//                detect_boundary_point(abdd, abdd, boundary_tree, boundary_points);
//            }
//        }
//    }
//
//    private static void detect_boundary_point(int root, int curr, HashMap<Integer, HashSet<Integer>> boundary_tree,
//                                              ArrayList<HashSet<Integer>> boundary_points) {
//        if (curr == 0)
//            return;
//        if (curr == 1) {
//            if (!boundary_tree.containsKey(root)) {
//                boundary_tree.put(root, new HashSet<Integer>());
//            }
//            boundary_tree.get(root).add(1);
//            return;
//        }
//
//        if (bdd_getField(root) != bdd_getField(curr)) {
//            if (!boundary_tree.containsKey(root)) {
//                boundary_tree.put(root, new HashSet<Integer>());
//            }
//            boundary_tree.get(root).add(curr);
//            boundary_points.get(bdd_getField(curr)).add(curr);
//            return;
//        }
//
//        detect_boundary_point(root, bdd.getLow(curr), boundary_tree, boundary_points);
//        detect_boundary_point(root, bdd.getHigh(curr), boundary_tree, boundary_points);
//    }
//
//    private static int construct_decomposed_bdd(int root, int end_point, int curr) {
//        if (curr == 0) {
//            return curr;
//        } else if (curr == 1) {
//            if (end_point == 1)
//                return 1;
//            else
//                return 0;
//        } else if (bdd_getField(root) != bdd_getField(curr)) {
//            if (end_point == curr)
//                return 1;
//            else
//                return 0;
//        }
//
//        int new_low = bdd.ref(construct_decomposed_bdd(root, end_point, bdd.getLow(curr)));
//        int new_high = bdd.ref(construct_decomposed_bdd(root, end_point, bdd.getHigh(curr)));
//
//        // int field = bdd_getField(curr);
//        // int result = 0;
//        // if (field == 0) {
//        // result = bdd.mk(bdd.getVar(curr), new_low, new_high);
//        // } else {
//        // result = bdd.mk(bdd.getVar(curr) - upperBound[field - 1] - 1, new_low,
//        // new_high);
//        // }
//        int result = bdd.mk(bdd.getVar(curr), new_low, new_high);
//        bdd.deref(new_low);
//        bdd.deref(new_high);
//        return result;
//    }

//    public static int toZero(BDDVectors n) {
//        ref(n);
//        int idx = bdd.ref(toBDD(n));
//        int ret = bdd.toZero(idx);
//        bdd.deref(idx);
//        deref(n);
//        return ret;
//    }
//
//    public static BDDVectors encodeAtMostKFailureVarsSorted(BDD bdd, int[] vars, int startField, int endField, int k) {
//        return encodeAtMostKFailureVarsSortedRec(bdd, vars, endField, startField, k);
//    }
//
//    private static BDDVectors encodeAtMostKFailureVarsSortedRec(BDD bdd, int[] vars, int endField, int currField, int k) {
//        if (currField > endField)
//            return VectorTrue;
//        int fieldSize = upperBound[0] + 1;
//        if (currField > 0)
//            fieldSize = upperBound[currField] - upperBound[currField - 1];
//        HashMap<BDDVectors, Integer> map = new HashMap<BDDVectors, Integer>();
//        for (int i = 0; i <= k; i++) {
//            // bdd with k and only k failures
//            int pred = bdd.ref(encodeBDD(bdd, vars, fieldSize - 1, 0, i));
//            BDDVectors next = encodeAtMostKFailureVarsSortedRec(bdd, vars, endField, currField + 1, k - i);
//            int nextPred = 0;
//            if (map.containsKey(next))
//                nextPred = map.get(next);
//            bdd.ref(pred);
//            int t = bdd.ref(bdd.or(pred, nextPred));
//            bdd.deref(pred);
//            bdd.deref(nextPred);
//            nextPred = t;
//            map.put(next, nextPred);
//        }
//        return BDDVectors.addAtField(currField, map);
//        // return BDDArray.table.mk(currField, map);
//    }

//    // replacement of BDDArray.table.mk(currField, map)
//    public static BDDVectors addAtField(int field, HashMap<NDD, Integer> map) {
//        BDDVectors ret = new BDDVectors();
//        for (Map.Entry<BDDVectors, Integer> entry : map.entrySet()) {
//            BDDVectors n = entry.getKey();
//            int b = entry.getValue();
//            for (ArrayList<Integer> vector : n.vectors) {
//                ArrayList<Integer> temp = new ArrayList<>(vector);
//                temp.set(field, b);
//                ret.vectors.add(temp);
//            }
//        }
//        return ret;
//    }

//    private static int encodeBDD(BDD bdd, int[] vars, int endVar, int currVar, int k) {
//        // cache? link num, k -> bdd
//        if (k < 0)
//            return 0;
//        if (currVar > endVar) {
//            if (k > 0)
//                return 0;
//            else
//                return 1;
//        }
//        int low = encodeBDD(bdd, vars, endVar, currVar + 1, k - 1);
//        int high = encodeBDD(bdd, vars, endVar, currVar + 1, k);
//
//        // return bdd.mk(currVar, low, high);
//        return bdd.mk(bdd.getVar(vars[endVar - currVar]), low, high);
//    }

//    public static void printDot(String path, BDDVectors curr) {
//        for (ArrayList<Integer> vector : curr.vectors) {
//            for (int i = 0; i < fieldNum; i++) {
//                int idx = vector.get(i);
//                if (idx != 1 && idx != 0) {
//                    bdd.printDot(path + "/" + idx, idx);
//                }
//            }
//        }
//    }
//
//    public static void nodeCount(BDDVectors node) {
//        HashSet<Integer> BDDRootSet = new HashSet<Integer>();
//        HashSet<Integer> BDDSet = new HashSet<Integer>();
//        for (ArrayList<Integer> vector : node.vectors) {
//            for (int i = 0; i < vector.size(); i++) {
//                BDDRootSet.add(vector.get(i));
//            }
//        }
//        for (int BDDRoot : BDDRootSet) {
//            detectBDD(BDDRoot, BDDSet);
//        }
//        System.out.println("NDD node:" + node.vectors.size() + " BDD node:" +
//                BDDSet.size());
//    }
//
//    private static void detectBDD(int node, HashSet<Integer> BDDSet) {
//        if (node == 1 || node == 0)
//            return;
//        else {
//            if (!BDDSet.contains(node)) {
//                BDDSet.add(node);
//                detectBDD(bdd.getHigh(node), BDDSet);
//                detectBDD(bdd.getLow(node), BDDSet);
//            }
//        }
//    }
}