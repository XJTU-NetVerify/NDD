package ndd.jdd.diagram;

import javafx.util.Pair;
import ndd.jdd.cache.OperationCache;
import ndd.jdd.nodetable.AtomizedNodeTable;
import ndd.jdd.nodetable.NodeTable;

import java.util.*;

public class AtomizedNDD extends NDD {
    public static AtomizedNodeTable atomizedNodeTable;
    private static HashSet<AtomizedNDD> atomizedTemporarilyProtect;
    private static ArrayList<HashSet<Integer>> atomsPerField;
    private static boolean cacheEnable = false;
    private final static int CACHE_SIZE = 100000;
    private static OperationCache<AtomizedNDD> andCache;

    public static void initAtomizedNDD(int atomizedNDDTableSize, int nddTableSize, int bddTableSize, int bddCacheSize) {
        initNDD(nddTableSize, bddTableSize, bddCacheSize);
        atomizedNodeTable = new AtomizedNodeTable(atomizedNDDTableSize, bddEngine);
        atomizedTemporarilyProtect = new HashSet<>();
        atomsPerField = new ArrayList<>();
        andCache = new OperationCache<>(CACHE_SIZE, 3);
    }

    public static int declareField(int bitNum) {
        atomizedNodeTable.declareField();
        HashSet<Integer> atomsOfNewField = new HashSet<>();
        atomsOfNewField.add(1);
        atomsPerField.add(atomsOfNewField);
        return NDD.declareField(bitNum);
    }

    public static void enableCaches() {
        cacheEnable = true;
        clearCaches();
    }

    public static void disableCaches() {
        cacheEnable = false;
    }

    public static void clearCaches() {
        andCache.clearCache();
    }

    public static AtomizedNDD ref(AtomizedNDD ndd) {
        return atomizedNodeTable.ref(ndd);
    }

    public static void deref(AtomizedNDD ndd) {
        atomizedNodeTable.deref(ndd);
    }

    public static AtomizedNDD mkAtomized(int field, HashMap<AtomizedNDD, HashSet<Integer>> edges) {
        return atomizedNodeTable.mk(field, edges);
    }

    public static HashSet<Integer> getAllAtoms(int field) {
        return atomsPerField.get(field);
    }

    public static HashSet<AtomizedNDD> getAtomizedTemporarilyProtect() {
        return atomizedTemporarilyProtect;
    }

    // logical operations

    private static void addEdge(HashMap<AtomizedNDD, HashSet<Integer>> edges, AtomizedNDD descendant, HashSet<Integer> labelAtoms) {
        // omit the edge pointing to terminal node FALSE
        if (descendant.isFalse()) {
            return;
        }
        // try to find the edge pointing to the same descendant
        HashSet<Integer> label = edges.get(descendant);
        if (label == null) {
            label = new HashSet<>();
        }
        // merge the bdd label
        label.addAll(labelAtoms);
        edges.put(descendant, label);
    }

    public static AtomizedNDD andTo(AtomizedNDD a, AtomizedNDD b) {
        AtomizedNDD t = ref(and(a, b));
        deref(a);
        return t;
    }

    public static AtomizedNDD and(AtomizedNDD a, AtomizedNDD b) {
        atomizedTemporarilyProtect.clear();
        return andRec(a, b);
    }

    private static AtomizedNDD andRec(AtomizedNDD a, AtomizedNDD b) {
        // terminal condition
        if (a.isFalse() || b.isTrue()) {
            return a;
        } else if (a.isTrue() || b.isFalse() || a == b){
            return b;
        }

        if (cacheEnable && andCache.getEntry(a, b)) {
            return andCache.result;
        }

        AtomizedNDD result = null;
        HashMap<AtomizedNDD, HashSet<Integer>> edges = new HashMap<>();
        if (a.field == b.field) {
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryA : a.getAtomizedEdges().entrySet()) {
                for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryB : b.getAtomizedEdges().entrySet()) {
                    // the bdd label on the new edge
                    HashSet<Integer> intersect;
                    if (entryA.getValue().size() < entryB.getValue().size()) {
                        intersect = new HashSet<Integer>(entryA.getValue());
                        intersect.retainAll(entryB.getValue());
                    } else {
                        intersect = new HashSet<Integer>(entryB.getValue());
                        intersect.retainAll(entryA.getValue());
                    }
                    if (!intersect.isEmpty()) {
                        // the descendant of the new edge
                        AtomizedNDD subResult = andRec(entryA.getKey(), entryB.getKey());
                        // try to merge edges
                        addEdge(edges, subResult, intersect);
                    }
                }
            }
        } else {
            if (a.field > b.field) {
                AtomizedNDD t = a;
                a = b;
                b = t;
            }
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryA : a.getAtomizedEdges().entrySet()) {
                /*
                 * if A branches on a higher field than B,
                 * we can let A operate with a pseudo node
                 * with only edge labelled by true and pointing to B
                 */
                AtomizedNDD subResult = andRec(entryA.getKey(), b);
                addEdge(edges, subResult, entryA.getValue());
            }
        }
        // try to create or reuse node
        result = mkAtomized(a.field, edges);
        // protect the node during the operation
        atomizedTemporarilyProtect.add(result);
        if (cacheEnable) {
            andCache.setEntry(andCache.hashValue, a, b, result);
        }
        return result;
    }

    public static AtomizedNDD orTo(AtomizedNDD a, AtomizedNDD b) {
        AtomizedNDD t = ref(or(a, b));
        deref(a);
        return t;
    }

    public static AtomizedNDD or(AtomizedNDD a, AtomizedNDD b) {
        atomizedTemporarilyProtect.clear();
        return orRec(a, b);
    }

    private static AtomizedNDD orRec(AtomizedNDD a, AtomizedNDD b) {
        // terminal condition
        if (a.isTrue() || b.isFalse()) {
            return a;
        } else if (a.isFalse() || b.isTrue() || a == b) {
            return b;
        }

        AtomizedNDD result = null;
        HashMap<AtomizedNDD, HashSet<Integer>> edges = new HashMap<>();
        if (a.field == b.field) {
            // record edges of each node, which will 'or' with the edge pointing to FALSE of another node
            HashMap<AtomizedNDD, HashSet<Integer>> residualA = new HashMap<>(a.getAtomizedEdges());
            HashMap<AtomizedNDD, HashSet<Integer>> residualB = new HashMap<>(b.getAtomizedEdges());
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryA : a.getAtomizedEdges().entrySet()) {
                for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryB : b.getAtomizedEdges().entrySet()) {
                    // the bdd label on the new edge
                    HashSet<Integer> intersect;
                    if (entryA.getValue().size() < entryB.getValue().size()) {
                        intersect = new HashSet<>(entryA.getValue());
                        intersect.retainAll(entryB.getValue());
                    } else {
                        intersect = new HashSet<>(entryB.getValue());
                        intersect.retainAll(entryA.getValue());
                    }
                    if (!intersect.isEmpty()) {
                        // update residual
                        entryA.getValue().removeAll(intersect);
                        entryB.getValue().removeAll(intersect);
                        // the descendant of the new edge
                        AtomizedNDD subResult = orRec(entryA.getKey(), entryB.getKey());
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
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryA : residualA.entrySet()) {
                if (!entryA.getValue().isEmpty()) {
                    addEdge(edges, entryA.getKey(), entryA.getValue());
                }
            }
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryB : residualB.entrySet()) {
                if (!entryB.getValue().isEmpty()) {
                    addEdge(edges, entryB.getKey(), entryB.getValue());
                }
            }
        } else {
            if (a.field > b.field) {
                AtomizedNDD t = a;
                a = b;
                b = t;
            }
            HashSet<Integer> residualB = new HashSet<>(getAllAtoms(b.field));
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryA : a.getAtomizedEdges().entrySet()) {
                /*
                 * if A branches on a higher field than B,
                 * we can let A operate with a pseudo node
                 * with only edge labelled by true and pointing to B
                 */
                residualB.removeAll(entryA.getValue());
                AtomizedNDD subResult = orRec(entryA.getKey(), b);
                addEdge(edges, subResult, entryA.getValue());
            }
            if (!residualB.isEmpty()) {
                addEdge(edges, b, residualB);
            }
        }
        // try to create or reuse node
        result = mkAtomized(a.field, edges);
        // protect the node during the operation
        atomizedTemporarilyProtect.add(result);
        return result;
    }

    public static AtomizedNDD not(AtomizedNDD a) {
        atomizedTemporarilyProtect.clear();
        return notRec(a);
    }

    private static AtomizedNDD notRec(AtomizedNDD a) {
        if (a.isTrue()) {
            return FALSE;
        } else if (a.isFalse()) {
            return TRUE;
        }

        HashMap<AtomizedNDD, HashSet<Integer>> edges = new HashMap<>();
        HashSet<Integer> residual = new HashSet<>(getAllAtoms(a.field));
        for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryA : a.getAtomizedEdges().entrySet()) {
            residual.removeAll(entryA.getValue());
            AtomizedNDD subResult = notRec(entryA.getKey());
            addEdge(edges, subResult, entryA.getValue());
        }
        if (!residual.isEmpty()) {
            addEdge(edges, TRUE, residual);
        }
        AtomizedNDD result = mkAtomized(a.field, edges);
        atomizedTemporarilyProtect.add(result);
        return result;
    }

    public static AtomizedNDD diff(AtomizedNDD a, AtomizedNDD b) {
        atomizedTemporarilyProtect.clear();
        AtomizedNDD n = notRec(b);
        atomizedTemporarilyProtect.add(n);
        AtomizedNDD result = andRec(a, n);
        return result;
    }

    public static AtomizedNDD exist(AtomizedNDD a, int field) {
        atomizedTemporarilyProtect.clear();
        return existRec(a, field);
    }

    private static AtomizedNDD existRec(AtomizedNDD a, int field) {
        if (a.isTerminal() || a.field > field) {
            return a;
        }

        AtomizedNDD result = FALSE;
        if (a.field == field) {
            for (AtomizedNDD next : a.getAtomizedEdges().keySet()) {
                result = orRec(result, next);
            }
        } else {
            HashMap<AtomizedNDD, HashSet<Integer>> edges = new HashMap<>();
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entryA : a.getAtomizedEdges().entrySet()) {
                AtomizedNDD subResult = existRec(entryA.getKey(), field);
                addEdge(edges, subResult, entryA.getValue());
            }
            result = mkAtomized(a.field, edges);
        }
        atomizedTemporarilyProtect.add(result);
        return result;
    }

    private static NDD toNDD(AtomizedNDD a) {
        NDD.getTemporarilyProtect().clear();
        return toNDDRec(a);
    }

    private static NDD toNDDRec(AtomizedNDD a) {
        if (a.isTrue()) {
            return NDD.getTrue();
        } else if (a.isFalse()) {
            return NDD.getFalse();
        }

        HashMap<NDD, Integer> edges = new HashMap<>();
        for (Map.Entry<AtomizedNDD, HashSet<Integer>> entry : a.getAtomizedEdges().entrySet()) {
            int bddLabel = 0;
            for (int atom : entry.getValue()) {
                bddLabel = bddEngine.orTo(bddLabel, atom);
            }
            NDD subResult = toNDDRec(entry.getKey());
            NDD.addEdge(edges, subResult, bddLabel);
        }

        NDD result = NDD.mk(a.field, edges);
        NDD.getTemporarilyProtect().add(result);
        return result;
    }

    // atomization

    public static HashMap<NDD, AtomizedNDD> atomization(HashSet<NDD> nddPredicates, HashMap<NDD, HashSet<Integer>[]> nddToAtoms) {
        //collect preds
        HashSet<Integer>[] bddPredicatesPerField = new HashSet[fieldNum];
        for(int i = 0; i < fieldNum; i++) {
            bddPredicatesPerField[i] = new HashSet<>();
        }
        for(NDD nddPredicate : nddPredicates) {
            collectFieldPreds(nddPredicate, bddPredicatesPerField);
        }

        //update atoms
        for(int field = 0; field < fieldNum; field++) {
            HashSet<Integer> atoms = new HashSet<>();
            atoms.add(1);
            HashSet<Integer> newAtoms = new HashSet<>();
            for (Integer predicate : bddPredicatesPerField[field]) {
                newAtoms.clear();
                for (Integer oldAtom : atoms) {
                    int intersect = bddEngine.and(predicate, oldAtom);
                    if (intersect != 0) {
                        if(!newAtoms.contains(intersect)) {
                            bddEngine.ref(intersect);
                            newAtoms.add(intersect);
                        }
                    }
                    int notIntersect = bddEngine.ref(bddEngine.not(predicate));
                    intersect = bddEngine.ref(bddEngine.and(notIntersect, oldAtom));
                    bddEngine.deref(notIntersect);
                    if (intersect != 0) {
                        if(!newAtoms.contains(intersect)) {
                            bddEngine.ref(intersect);
                            newAtoms.add(intersect);
                        }
                    }
                    bddEngine.deref(oldAtom);
                }
                atoms = new HashSet<>(newAtoms);
            }
            atomsPerField.set(field, atoms);
        }

        //atomize bdd pred
        HashMap<Integer, HashSet<Integer>> bddToAtoms = new HashMap<>();
        for(int field = 0; field < fieldNum; field++) {
            for(int bddPredicate : bddPredicatesPerField[field]) {
                HashSet<Integer> atomsOfPredicate = new HashSet<>();
                for(int atom : atomsPerField.get(field)) {
                    if(bddEngine.and(bddPredicate, atom) != 0) {
                        atomsOfPredicate.add(atom);
                    }
                }
                bddToAtoms.put(bddPredicate, atomsOfPredicate);
            }
        }

        //atomize ndd pred
        HashMap<NDD, AtomizedNDD> nddToAtomizationNDD = new HashMap<>();
        for(NDD nddPredicate : nddPredicates) {
            HashSet<Integer>[] atoms = new HashSet[fieldNum];
            for(int field = 0; field < fieldNum; field++) {
                atoms[field] = new HashSet<>();
            }
            AtomizedNDD atomizedNDD = atomizeNDD(nddPredicate, bddToAtoms);
            nddToAtomizationNDD.put(nddPredicate, atomizedNDD);
            collectAtoms(atomizedNDD, atoms);
            nddToAtoms.put(nddPredicate, atoms);
        }
        return nddToAtomizationNDD;
    }

    private static AtomizedNDD atomizeNDD(NDD current, HashMap<Integer, HashSet<Integer>> bddToAtoms) {
        if (current.isTrue()) {
            return TRUE;
        } else if (current.isFalse()){
            return FALSE;
        }
        HashMap<AtomizedNDD, HashSet<Integer>> edges = new HashMap<>();
        for (Map.Entry<NDD, Integer> entry : current.getEdges().entrySet()) {
            AtomizedNDD subResult = atomizeNDD(entry.getKey(), bddToAtoms);
            edges.put(subResult, new HashSet<>(bddToAtoms.get(entry.getValue())));
        }
        return mkAtomized(current.field, edges);
    }

    private static void collectFieldPreds(NDD current, HashSet<Integer>[] predicates) {
        if(current.isTerminal()) {
        } else {
            for(Map.Entry<NDD, Integer> entry : current.getEdges().entrySet()) {
                predicates[current.field].add(entry.getValue());
                collectFieldPreds(entry.getKey(), predicates);
            }
        }
    }

    private static void collectAtoms(AtomizedNDD current, HashSet<Integer>[] atoms) {
        if(current.isTerminal()) {
        } else {
            for(Map.Entry<AtomizedNDD, HashSet<Integer>> entry : current.getAtomizedEdges().entrySet()) {
                atoms[current.field].addAll(entry.getValue());
                collectAtoms(entry.getKey(), atoms);
            }
        }
    }

    private static void collectAtomsSingleField(AtomizedNDD pred, HashSet<Integer> aps) {
        if (!pred.isTerminal()) {
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entry : pred.getAtomizedEdges().entrySet()) {
                aps.addAll(entry.getValue());
            }
        }
    }

    public static void changeAtoms(AtomizedNDD oldPredicate, AtomizedNDD newPredicate, HashSet<Integer>[] remove,
                                  HashSet<Integer>[] add) {
        HashSet<Integer>[] array1 = new HashSet[fieldNum];
        HashSet<Integer>[] array2 = new HashSet[fieldNum];
        for (int i = 0; i < fieldNum; i++) {
            array1[i] = new HashSet<>();
            array2[i] = new HashSet<>();
        }
        collectAtoms(oldPredicate, array1);
        collectAtoms(newPredicate, array2);
        for (int i = 0; i < fieldNum; i++) {
            remove[i] = new HashSet<>(array1[i]);
            add[i] = new HashSet<>(array2[i]);
            remove[i].removeAll(array2[i]);
            add[i].removeAll(array1[i]);
        }
    }

    public static void changeAtomsSingleField(AtomizedNDD oldPredicate, AtomizedNDD newPredicate, HashSet<Integer> remove,
                                             HashSet<Integer> add) {
        HashSet<Integer> set1 = new HashSet<>();
        HashSet<Integer> set2 = new HashSet<>();
        collectAtomsSingleField(oldPredicate, set1);
        collectAtomsSingleField(newPredicate, set2);
        remove.addAll(set1);
        add.addAll(set2);
        remove.removeAll(set2);
        add.removeAll(set1);
    }

    // incrementally split atoms

    private static int splitDeltaSingleField(HashSet<Integer> atoms, int deltaBDD, HashSet<Integer> deltaToAtoms,
                                              HashMap<Integer, HashSet<Integer>> atomsToSplit) {
        bddEngine.ref(deltaBDD);
        for (int atom : atoms) {
            if (!deltaToAtoms.contains(atom) && !atomsToSplit.containsKey(atom)) {
                int intersect = bddEngine.and(deltaBDD, atom);
                if (intersect != 0) {
                    deltaToAtoms.add(intersect);
                    bddEngine.ref(intersect);
                    int notIntersect = bddEngine.ref(bddEngine.not(intersect));
                    deltaBDD = bddEngine.andTo(deltaBDD, notIntersect);
                    if (intersect != atom) {
                        int diff = bddEngine.ref(bddEngine.and(notIntersect, atom));
                        HashSet<Integer> newAtoms = new HashSet<>();
                        newAtoms.add(intersect);
                        newAtoms.add(diff);
                        atomsToSplit.put(atom, newAtoms);
                    } else {
                        bddEngine.deref(intersect);
                    }
                    bddEngine.deref(notIntersect);
                    if (deltaBDD == 0) {
                        break;
                    }
                }
            }
        }
        bddEngine.deref(deltaBDD);
        return deltaBDD;
    }

    public static void getAtomsToSplitSingleField(AtomizedNDD atomizedNDD, int deltaBDD, HashSet<Integer> deltaToAtoms,
                                              HashMap<Integer, HashSet<Integer>> atomsToSplit, int field) {
        if (atomizedNDD.isTrue()) {
            splitDeltaSingleField(atomsPerField.get(field), deltaBDD, deltaToAtoms, atomsToSplit);
        } else {
            for (HashSet<Integer> atomsOnEdge : atomizedNDD.getAtomizedEdges().values()) {
                deltaBDD = splitDeltaSingleField(atomsOnEdge, deltaBDD, deltaToAtoms, atomsToSplit);
                if (deltaBDD == 0) {
                    break;
                }
            }
        }
    }

    public static void getAtomsToSplitSingleField(NDD deltaNDD, HashSet<Integer> deltaToAtoms,
                                              HashMap<Integer, HashSet<Integer>> atomsToSplit, int field) {
        int deltaBDD = deltaNDD.getEdges().values().iterator().next();
        if (atomsPerField.get(field).contains(deltaBDD)) {
            deltaToAtoms.add(deltaBDD);
        } else {
            splitDeltaSingleField(atomsPerField.get(field), deltaBDD, deltaToAtoms, atomsToSplit);
        }
    }

    public static void getAtomsToSplitMultipleFields(AtomizedNDD atomizedNDD, int[] deltaVector, ArrayList<HashSet<Integer>> deltaToAtoms,
                                                ArrayList<HashMap<Integer, HashSet<Integer>>> atomsToSplit, int field) {
        if (field == fieldNum) {
        } else if ((atomizedNDD.isTrue() && field < fieldNum) || atomizedNDD.field > field) {
            if (deltaVector[field] == 1) {
                deltaToAtoms.get(field).addAll(getAllAtoms(field));
            } else {
                splitDeltaSingleField(getAllAtoms(field), deltaVector[field], deltaToAtoms.get(field), atomsToSplit.get(field));
            }
            getAtomsToSplitMultipleFields(atomizedNDD, deltaVector, deltaToAtoms, atomsToSplit, field + 1);
        } else if (atomizedNDD.field == field) {
            int deltaBDD = deltaVector[field];
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entry : atomizedNDD.getAtomizedEdges().entrySet()) {
                if (deltaBDD == 1) {
                    deltaToAtoms.get(field).addAll(getAllAtoms(field));
                    getAtomsToSplitMultipleFields(entry.getKey(), deltaVector, deltaToAtoms, atomsToSplit, field + 1);
                } else {
                    int newDeltaBDD = splitDeltaSingleField(entry.getValue(), deltaBDD, deltaToAtoms.get(field), atomsToSplit.get(field));
                    if ((newDeltaBDD != 0) && (deltaBDD != newDeltaBDD)) {
                        deltaBDD = newDeltaBDD;
                        getAtomsToSplitMultipleFields(entry.getKey(), deltaVector, deltaToAtoms, atomsToSplit, field + 1);
                    }
                }
            }
        }
    }

    // todo: when should we deref atomsToSplit
    public static AtomizedNDD splitSingleFieldAtomsWithSingleFieldPredicate(HashMap<Integer, HashSet<Integer>> atomsToSplit, AtomizedNDD atomizedNDD) {
        if (!atomizedNDD.isTerminal()) {
            boolean change = false;
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entry : atomizedNDD.getAtomizedEdges().entrySet()) {
                HashSet<Integer> atomsOnEdge = new HashSet<>(entry.getValue());
                for (Map.Entry<Integer, HashSet<Integer>> entry1 : atomsToSplit.entrySet()) {
                    if (atomsOnEdge.contains(entry1.getKey())) {
                        change = true;
                        atomsOnEdge.remove(entry1.getKey());
                        atomsOnEdge.addAll(entry1.getValue());
                    }
                }
                if (change) {
                    HashMap<AtomizedNDD, HashSet<Integer>> edges = new HashMap<>();
                    edges.put(TRUE, atomsOnEdge);
                    return mkAtomized(atomizedNDD.field, edges);
                }
            }
        }
        return FALSE;
    }

    public static Pair<Boolean, AtomizedNDD> splitSingleFieldAtomsWithMultipleFieldsPredicate(HashMap<Integer, HashSet<Integer>> atomsToSplit, AtomizedNDD atomizedNDD,
                                                            int field) {
        if (atomizedNDD.isTerminal() || atomizedNDD.field > field) {
            return new Pair<Boolean, AtomizedNDD>(false, atomizedNDD);
        } else {
            Boolean change = false;
            HashMap<AtomizedNDD, HashSet<Integer>> edges = new HashMap<>();
            if (atomizedNDD.field == field) {
                for (Map.Entry<AtomizedNDD, HashSet<Integer>> entry : atomizedNDD.getAtomizedEdges().entrySet()) {
                    Boolean subChange = false;
                    HashSet<Integer> atomsOnEdge = new HashSet<>(entry.getValue());
                    for (Map.Entry<Integer, HashSet<Integer>> entry1 : atomsToSplit.entrySet()) {
                        if (atomsOnEdge.contains(entry1.getKey())) {
                            subChange = true;
                            atomsOnEdge.remove(entry1.getKey());
                            atomsOnEdge.addAll(entry1.getValue());
                        }
                    }
                    if (subChange) {
                        change = true;
                        edges.put(entry.getKey(), atomsOnEdge);
                    } else {
                        edges.put(entry.getKey(), entry.getValue());
                    }
                }
                if (change) {
                    return new Pair<Boolean, AtomizedNDD>(true, mkAtomized(field, edges));
                } else {
                    return new Pair<Boolean, AtomizedNDD>(false, atomizedNDD);
                }
            } else {
                for (Map.Entry<AtomizedNDD, HashSet<Integer>> entry : atomizedNDD.getAtomizedEdges().entrySet()) {
                    Pair<Boolean, AtomizedNDD> ret = splitSingleFieldAtomsWithMultipleFieldsPredicate(atomsToSplit, entry.getKey(), field);
                    if (ret.getKey()) {
                        change = true;
                    }
                    edges.put(entry.getKey(), entry.getValue());
                }
                if (change) {
                    return new Pair<Boolean, AtomizedNDD>(true, mkAtomized(atomizedNDD.field, edges));
                } else {
                    return new Pair<Boolean, AtomizedNDD>(false, atomizedNDD);
                }
            }
        }
    }

    public static Pair<Boolean, AtomizedNDD> splitMultipleFieldsAtomsWithMultipleFieldsPredicate(ArrayList<HashMap<Integer, HashSet<Integer>>> atomsToSplit,
                                                            AtomizedNDD atomizedNDD) {
        if (atomizedNDD.isTerminal()) {
            return new Pair<Boolean, AtomizedNDD>(false, atomizedNDD);
        } else {
            boolean change = false;
            HashMap<AtomizedNDD, HashSet<Integer>> edges = new HashMap<>();
            for (Map.Entry<AtomizedNDD, HashSet<Integer>> entry : atomizedNDD.getAtomizedEdges().entrySet()) {
                boolean subChange = false;
                Pair<Boolean, AtomizedNDD> result = splitMultipleFieldsAtomsWithMultipleFieldsPredicate(atomsToSplit, entry.getKey());
                if (result.getKey()) {
                    subChange = true;
                }
                HashSet<Integer> atomsOnEdge = new HashSet<>(entry.getValue());
                for (Map.Entry<Integer, HashSet<Integer>> entry1 : atomsToSplit.get(atomizedNDD.field).entrySet()) {
                    if (atomsOnEdge.contains(entry1.getKey())) {
                        subChange = true;
                        atomsOnEdge.remove(entry1.getKey());
                        atomsOnEdge.addAll(entry1.getValue());
                    }
                }
                if (subChange) {
                    change = true;
                    edges.put(result.getValue(), atomsOnEdge);
                } else {
                    edges.put(entry.getKey(), entry.getValue());
                }
            }
            if (change) {
                return new Pair<Boolean, AtomizedNDD>(true, mkAtomized(atomizedNDD.field, edges));
            } else {
                return new Pair<Boolean, AtomizedNDD>(false, atomizedNDD);
            }
        }
    }

    public static int getAtomsCount() {
        int atomsNumber = 0;
        for (int field = 0; field < fieldNum; field++) {
            atomsNumber += atomsPerField.get(field).size();
        }
        return atomsNumber;
    }

    // per node content

    private HashMap<AtomizedNDD, HashSet<Integer>> atomizedEdges;

    public AtomizedNDD() {
        super();
    }

    public AtomizedNDD(int field, HashMap<AtomizedNDD, HashSet<Integer>> atomizedEdges) {
        this.field = field;
        this.atomizedEdges = atomizedEdges;
    }

    private final static AtomizedNDD TRUE = new AtomizedNDD();

    private final static AtomizedNDD FALSE = new AtomizedNDD();

    public static AtomizedNDD getTrue() {
        return TRUE;
    }

    public static AtomizedNDD getFalse() {
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

    public HashMap<AtomizedNDD, HashSet<Integer>> getAtomizedEdges() {
        return atomizedEdges;
    }

    public static int nodeCount() {
        ArrayList<HashMap<HashMap<AtomizedNDD, HashSet<Integer>>, AtomizedNDD>> tables = atomizedNodeTable.getNodeTable();
        int nodeCount = 0;
        for (HashMap<HashMap<AtomizedNDD, HashSet<Integer>>, AtomizedNDD> table : tables) {
            nodeCount += table.size();
        }
        return nodeCount;
    }

    public static void main(String[] args) {
    }
}
