package org.ants.jndd.diagram;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NDDMixedBackendTest {
    private int bddTwo;
    private int zddThree;
    private int bcddTwo;
    private int bddOne;
    private int bddTwoAgain;
    private int bcddOne;

    @BeforeEach
    void initialiseMixedFields() {
        NDD.initNDD(20_000, 20_000, 2_000);
        bddTwo = NDD.declareField(2);
        zddThree = NDD.declareField(3, NDD.LabelMode.ZDD);
        bcddTwo = NDD.declareField(2, NDD.LabelMode.COMPLEMENTED_BDD);
        bddOne = NDD.declareField(1);
        bddTwoAgain = NDD.declareField(2, NDD.LabelMode.BDD);
        bcddOne = NDD.declareField(1, NDD.LabelMode.COMPLEMENTED_BDD);
        NDD.generateFields();
    }

    @Test
    void declarationKeepsOneRightAlignedLayoutPerBackend() {
        assertTrue(NDD.hasMixedLabelModes());
        assertEquals(NDD.LabelMode.BDD, NDD.getFieldLabelMode(bddTwo));
        assertEquals(NDD.LabelMode.ZDD, NDD.getFieldLabelMode(zddThree));
        assertEquals(NDD.LabelMode.COMPLEMENTED_BDD, NDD.getFieldLabelMode(bcddTwo));

        assertEquals(edgeLabel(NDD.getVar(bddTwo, 1)), edgeLabel(NDD.getVar(bddOne, 0)));
        assertEquals(edgeLabel(NDD.getVar(bddTwo, 0)), edgeLabel(NDD.getVar(bddTwoAgain, 0)));
        assertEquals(edgeLabel(NDD.getVar(bcddTwo, 1)), edgeLabel(NDD.getVar(bcddOne, 0)));

        assertEquals(1024.0, NDD.satCount(NDD.encodePrefix(new int[]{1}, bcddTwo)));
        assertEquals(1024.0, NDD.satCount(NDD.encodePrefix(new int[]{0}, bddTwo)));
        assertEquals(1024.0, NDD.satCount(NDD.encodePrefix(new int[]{1}, zddThree)));
    }

    @Test
    void booleanOperationsCountingAndEnumerationCrossBackendBoundaries() {
        int root = mixedSingleton();
        assertEquals(8.0, NDD.satCount(root));
        assertEquals(2040.0, NDD.satCount(NDD.not(root)));

        int[][] witness = NDD.anySat(root);
        assertArrayEquals(new int[]{0, 1}, witness[bddTwo]);
        assertArrayEquals(new int[]{1, 0, 1}, witness[zddThree]);
        assertArrayEquals(new int[]{1, 1}, witness[bcddTwo]);
        assertArrayEquals(new int[]{1}, witness[bddOne]);
        assertEquals(8, NDD.allSat(root, assignment -> true));

        int care = NDD.or(NDD.getVar(zddThree, 2), NDD.getVar(bddTwoAgain, 0));
        int simplified = NDD.simplify(root, care);
        assertEquals(NDD.and(root, care), NDD.and(simplified, care));
        assertEquals(root, NDD.apply(NDD.BinaryOperation.AND, root, care));
    }

    @Test
    void restrictExistSubstituteAndGcUseTheOwningFieldBackend() {
        int root = NDD.ref(mixedSingleton());
        assertEquals(64.0, NDD.satCount(NDD.restrict(root, zddThree, 5L)));
        assertEquals(0.0, NDD.satCount(NDD.restrict(root, zddThree, 1L)));
        assertEquals(32.0, NDD.satCount(NDD.exist(root, bddTwo)));

        int replaced = NDD.substitute(root, bddTwo, bddTwoAgain);
        assertEquals(0.0, NDD.satCount(NDD.restrict(replaced, bddTwoAgain, 2L)));
        assertEquals(32.0, NDD.satCount(NDD.restrict(replaced, bddTwoAgain, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> NDD.substitute(root, bddTwo, bcddTwo));

        NDD.gc();
        assertEquals(8.0, NDD.satCount(root));
        NDD.deref(root);
    }

    @Test
    void aggregateLabelStatisticsAreTheSumOfPerBackendStatistics() {
        long bdd = NDD.getLabelNodeCount(NDD.LabelMode.BDD);
        long bcdd = NDD.getLabelNodeCount(NDD.LabelMode.COMPLEMENTED_BDD);
        long zdd = NDD.getLabelNodeCount(NDD.LabelMode.ZDD);
        assertTrue(bdd > 0 && bcdd > 0 && zdd > 0);
        assertEquals(bdd + bcdd + zdd, NDD.getLabelNodeCount());

        long created = NDD.getLabelTotalCreated(NDD.LabelMode.BDD)
                + NDD.getLabelTotalCreated(NDD.LabelMode.COMPLEMENTED_BDD)
                + NDD.getLabelTotalCreated(NDD.LabelMode.ZDD);
        assertEquals(created, NDD.getLabelTotalCreated());
    }

    private int mixedSingleton() {
        int result = twoBitValue(bddTwo, 1);
        result = NDD.and(result, fieldValue(zddThree, 3, 5));
        result = NDD.and(result, twoBitValue(bcddTwo, 3));
        result = NDD.and(result, NDD.getVar(bddOne, 0));
        return result;
    }

    private static int twoBitValue(int field, int value) {
        return fieldValue(field, 2, value);
    }

    private static int fieldValue(int field, int width, int value) {
        int result = NDD.getTrue();
        for (int bit = 0; bit < width; bit++) {
            int bitValue = (value >>> (width - 1 - bit)) & 1;
            result = NDD.and(result,
                    bitValue == 0 ? NDD.getNotVar(field, bit) : NDD.getVar(field, bit));
        }
        return result;
    }

    private static int edgeLabel(int node) {
        return NDD.getEdgeLabel(node, 0);
    }
}
