/**
 * Utility for decomposing NDD.
 * @author Zechun Li & Yichi Zhang - XJTU ANTS NetVerify Lab
 * @version 1.0
 */
package org.ants.jndd.utils;

import jdd.bdd.BDD;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class DecomposeBDD {
    private final static int BDD_FALSE = 0;
    private final static int BDD_TRUE = 1;
    private static BDD bddEngine;
    private static int fieldNum;
    private static ArrayList<Integer> maxVariablePerField;

    public static HashMap<Integer, HashMap<Integer, Integer>> decompose(int a, BDD bdd, ArrayList<Integer> vars) {
        bddEngine = bdd;
        maxVariablePerField = vars;
        fieldNum = maxVariablePerField.size();
        HashMap<Integer, HashMap<Integer, Integer>> decomposedBDD = new HashMap<Integer, HashMap<Integer, Integer>>();
        if (a == BDD_FALSE) {
        } else if (a == BDD_TRUE) {
            HashMap<Integer, Integer> map = new HashMap<>();
            map.put(BDD_TRUE, BDD_TRUE);
            decomposedBDD.put(BDD_TRUE, map);
        } else {
            HashMap<Integer, HashSet<Integer>> boundaryTree = new HashMap<>();
            ArrayList<HashSet<Integer>> boundaryPoints = new ArrayList<>();
            getBoundaryTree(a, boundaryTree, boundaryPoints);

            for (int currentField = 0; currentField < fieldNum - 1; currentField++) {
                for (int from : boundaryPoints.get(currentField)) {
                    decomposedBDD.put(from, new HashMap<>());
                    for (int to : boundaryTree.get(from)) {
                        int perFieldBDD = bddEngine.ref(constructPerFieldBDD(from, to, from));
                        decomposedBDD.get(from).put(to, perFieldBDD);
                    }
                }
            }

            for (int from : boundaryPoints.get(fieldNum - 1)) {
                decomposedBDD.put(from, new HashMap<Integer, Integer>());
                decomposedBDD.get(from).put(BDD_TRUE, bddEngine.ref(from));
            }
        }
        return decomposedBDD;
    }

    public static int bddGetField(int a) {
        if (a == BDD_FALSE || a == BDD_TRUE) {
            return fieldNum;
        }
        int varA = bddEngine.getVar(a);
        int currentField = 0;
        while (currentField < fieldNum) {
            if (varA <= maxVariablePerField.get(currentField)) {
                break;
            }
            currentField++;
        }
        return currentField;
    }

    private static void getBoundaryTree(int a, HashMap<Integer, HashSet<Integer>> boundaryTree,
                                        ArrayList<HashSet<Integer>> boundaryPoints) {
        int startField = bddGetField(a);
        for (int i = 0; i < fieldNum; i++) {
            boundaryPoints.add(new HashSet<Integer>());
        }
        boundaryPoints.get(startField).add(a);
        if (startField == fieldNum - 1) {
            boundaryTree.put(a, new HashSet<Integer>());
            boundaryTree.get(a).add(BDD_TRUE);
        } else {
            for (int currentField = startField; currentField < fieldNum; currentField++) {
                for (int from : boundaryPoints.get(currentField)) {
                    detectBoundaryPoints(from, from, boundaryTree, boundaryPoints);
                }
            }
        }
    }

    private static void detectBoundaryPoints(int from, int current, HashMap<Integer, HashSet<Integer>> boundaryTree,
                                             ArrayList<HashSet<Integer>> boundaryPoints) {
        if (current == BDD_FALSE) {
            return;
        }

        if (bddGetField(from) != bddGetField(current)) {
            if (!boundaryTree.containsKey(from)) {
                boundaryTree.put(from, new HashSet<Integer>());
            }
            boundaryTree.get(from).add(current);
            if (current != BDD_TRUE) {
                boundaryPoints.get(bddGetField(current)).add(current);
            }
            return;
        }

        detectBoundaryPoints(from, bddEngine.getLow(current), boundaryTree, boundaryPoints);
        detectBoundaryPoints(from, bddEngine.getHigh(current), boundaryTree, boundaryPoints);
    }

    // return per field bdd without ref
    private static int constructPerFieldBDD(int from, int to, int current) {
        if (bddGetField(from) != bddGetField(current)) {
            if (to == current)
                return BDD_TRUE;
            else
                return BDD_FALSE;
        }

        int new_low = bddEngine.ref(constructPerFieldBDD(from, to, bddEngine.getLow(current)));
        int new_high = bddEngine.ref(constructPerFieldBDD(from, to, bddEngine.getHigh(current)));
        int result = bddEngine.mk(bddEngine.getVar(current), new_low, new_high);
        bddEngine.deref(new_low);
        bddEngine.deref(new_high);
        return result;
    }
}
