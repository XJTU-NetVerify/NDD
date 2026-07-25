package org.ants.jndd.diagram;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NDDManipulationApiTest {
    @BeforeEach
    void initialiseTwoTwoBitFields() {
        NDD.initNDD(10_000, 10_000, 1_000);
        NDD.declareField(2);
        NDD.declareField(2);
        NDD.generateFields();
    }

    @Test
    void restrictFixesAndProjectsAField() {
        int root = conjunction(value(0, 1), value(1, 2));

        int restricted = NDD.restrict(root, 0, 1);
        assertEquals(4.0, NDD.satCount(restricted)); // field 0 is now unconstrained
        assertEquals(0.0, NDD.satCount(NDD.restrict(root, 0, 2)));

        int fullyRestricted = NDD.restrict(restricted, 1, new int[]{1, 0});
        assertEquals(16.0, NDD.satCount(fullyRestricted));
    }

    @Test
    void anySatAndAllSatReturnCompleteAssignments() {
        int root = conjunction(value(0, 1), value(1, 2));

        assertArrayEquals(new int[]{0, 1}, NDD.anySat(root)[0]);
        assertArrayEquals(new int[]{1, 0}, NDD.anySat(root)[1]);

        List<int[][]> assignments = new ArrayList<>();
        assertEquals(1, NDD.allSat(root, assignment -> {
            assignments.add(assignment);
            return true;
        }));
        assertEquals(1, assignments.size());
        assertArrayEquals(new int[]{0, 1}, assignments.get(0)[0]);
        assertArrayEquals(new int[]{1, 0}, assignments.get(0)[1]);
        assertNull(NDD.anySat(NDD.getFalse()));
    }

    @Test
    void existsMultipleFieldsAndSubstitutionHaveExpectedSemantics() {
        int root = conjunction(value(0, 1), value(1, 2));

        assertEquals(4.0, NDD.satCount(NDD.exist(root, 0)));
        assertEquals(16.0, NDD.satCount(NDD.exist(root, 0, 1)));

        int sourceOnly = value(0, 1);
        int replaced = NDD.substitute(sourceOnly, 0, 1);
        assertEquals(4.0, NDD.satCount(replaced));
        assertEquals(16.0, NDD.satCount(NDD.restrict(replaced, 1, 1)));
        assertEquals(0.0, NDD.satCount(NDD.restrict(replaced, 1, 2)));
    }

    @Test
    void genericApplyAndSimplifyUseTheNddBooleanSemantics() {
        int one = value(0, 1);
        int two = value(0, 2);

        assertEquals(8.0, NDD.satCount(NDD.apply(NDD.BinaryOperation.XOR, one, two)));
        assertEquals(0.0, NDD.satCount(NDD.simplify(one, two)));
    }

    @Test
    void finiteDomainRestrictionEnumerationAndSubstitutionUseValueIndices() {
        NDD.initNDD(10_000, 10_000, 1_000, NDD.LabelMode.FINITE_DOMAIN_ZDD);
        NDD.declareField(2);
        NDD.declareField(2);
        NDD.generateFields();

        int sourceValueOne = NDD.getVar(0, 1);
        assertEquals(4.0, NDD.satCount(NDD.restrict(sourceValueOne, 0, 1L)));
        assertEquals(0.0, NDD.satCount(NDD.restrict(sourceValueOne, 0, 0L)));
        assertArrayEquals(new int[]{1}, NDD.anySat(sourceValueOne)[0]);
        assertEquals(2, NDD.allSat(sourceValueOne, assignment -> true));

        int replaced = NDD.substitute(sourceValueOne, 0, 1);
        assertEquals(2.0, NDD.satCount(replaced));
    }

    private static int value(int field, int value) {
        int result = NDD.getTrue();
        for (int bit = 0; bit < 2; bit++) {
            int bitValue = (value >>> (1 - bit)) & 1;
            result = NDD.and(result, bitValue == 0 ? NDD.getNotVar(field, bit) : NDD.getVar(field, bit));
        }
        return result;
    }

    private static int conjunction(int left, int right) {
        return NDD.and(left, right);
    }
}
