package org.ants.jndd.diagram;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NDDBitsetBackendTest {
    @Test
    void homogeneousBitsetSupportsBooleanOperationsAndQueries() {
        NDD.initNDD(10_000, 10_000, 1_000, NDD.LabelMode.BITSET);
        int first = NDD.declareField(3);
        int second = NDD.declareField(2);
        NDD.generateFields();

        int selected = NDD.and(value(first, 3, 5), value(second, 2, 2));
        assertEquals(1.0, NDD.satCount(selected));
        assertArrayEquals(new int[]{1, 0, 1}, NDD.anySat(selected)[first]);
        assertArrayEquals(new int[]{1, 0}, NDD.anySat(selected)[second]);
        assertEquals(1, NDD.allSat(selected, assignment -> true));
        assertEquals(32.0, NDD.satCount(NDD.exist(selected, first, second)));
        assertEquals(0.0, NDD.satCount(NDD.restrict(selected, first, 4L)));
        // Restriction removes the first field, so all eight of its values become free.
        assertEquals(8.0, NDD.satCount(NDD.restrict(selected, first, 5L)));
        assertTrue(NDD.getLabelNodeCount(NDD.LabelMode.BITSET) > 2);
    }

    @Test
    void bitsetCanBeMixedWithDecisionDiagramBackends() {
        NDD.initNDD(10_000, 10_000, 1_000);
        int bdd = NDD.declareField(2);
        int bitset = NDD.declareField(3, NDD.LabelMode.BITSET);
        int bitsetTarget = NDD.declareField(3, NDD.LabelMode.BITSET);
        int zdd = NDD.declareField(1, NDD.LabelMode.ZDD);
        NDD.generateFields();

        int root = NDD.and(value(bdd, 2, 1), value(bitset, 3, 6));
        root = NDD.and(root, value(zdd, 1, 1));
        assertTrue(NDD.hasMixedLabelModes());
        assertEquals(8.0, NDD.satCount(root));

        int replaced = NDD.substitute(root, bitset, bitsetTarget);
        assertEquals(8.0, NDD.satCount(replaced));
        assertEquals(64.0, NDD.satCount(NDD.restrict(replaced, bitsetTarget, 6L)));
        assertEquals(0.0, NDD.satCount(NDD.restrict(replaced, bitsetTarget, 5L)));
    }

    @Test
    void bitsetRejectsFieldsWiderThanItsTruthTable() {
        NDD.initNDD(10_000, 10_000, 1_000, NDD.LabelMode.BITSET);
        NDD.declareField(8);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, NDD::generateFields);
        assertTrue(error.getMessage().contains("1-7"));
    }

    @Test
    void sevenBitBoundaryUsesBothTruthTableWords() {
        NDD.initNDD(10_000, 10_000, 1_000, NDD.LabelMode.BITSET);
        int field = NDD.declareField(7);
        NDD.generateFields();

        assertEquals(64.0, NDD.satCount(NDD.getVar(field, 0)));
        assertEquals(64.0, NDD.satCount(NDD.getVar(field, 6)));
        assertEquals(32.0,
                NDD.satCount(NDD.and(NDD.getVar(field, 0), NDD.getVar(field, 6))));
        assertEquals(1.0, NDD.satCount(value(field, 7, 127)));
    }

    private static int value(int field, int width, int value) {
        int[] bits = new int[width];
        for (int bit = 0; bit < width; bit++) {
            bits[bit] = (value >>> (width - 1 - bit)) & 1;
        }
        return NDD.encodePrefix(bits, field);
    }
}
