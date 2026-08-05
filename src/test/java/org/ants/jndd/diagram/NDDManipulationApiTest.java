package org.ants.jndd.diagram;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    void restrictOrsEveryMatchingTargetAndReusesSkippedSubtrees() {
        int targetOne = value(1, 1);
        int targetTwo = value(1, 2);
        Map<Integer, Integer> overlappingEdges = new HashMap<>();
        overlappingEdges.put(targetOne, NDD.getTrue());
        overlappingEdges.put(targetTwo, NDD.getTrue());
        int root = NDD.addAtField(0, overlappingEdges);

        assertEquals(8.0, NDD.satCount(NDD.restrict(root, 0, 3)));
        assertEquals(targetOne, NDD.restrict(targetOne, 0, 0));
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
        assertEquals(NDD.getTrue(), NDD.simplify(one, one));
    }

    @Test
    void simplifyDropsCareOnlyFieldsAndPreservesBehaviorInsideCareSet() {
        int care = value(0, 1);
        int downstream = value(1, 2);
        int function = conjunction(care, downstream);

        int simplified = NDD.simplify(function, care);
        assertEquals(downstream, simplified);
        assertEquals(NDD.and(function, care), NDD.and(simplified, care));
    }

    @Test
    void simplifyRetainsDistinctionsThatAreVisibleInsideCareSet() {
        int valueOne = value(0, 1);
        int valueTwo = value(0, 2);
        int function = NDD.or(valueOne, valueTwo);
        int care = NDD.or(valueOne, value(0, 3));

        int simplified = NDD.simplify(function, care);
        assertEquals(NDD.and(function, care), NDD.and(simplified, care));
        assertEquals(4.0, NDD.satCount(NDD.and(simplified, care)));
    }

    @Test
    void simplifyAgreesWithFunctionOnCareSetForEveryTwoVariableTruthTable() {
        NDD.initNDD(10_000, 10_000, 1_000);
        NDD.declareField(1);
        NDD.declareField(1);
        NDD.generateFields();

        int[] functions = new int[16];
        for (int mask = 0; mask < functions.length; mask++) {
            functions[mask] = NDD.ref(booleanFunction(mask));
        }
        for (int functionMask = 0; functionMask < functions.length; functionMask++) {
            for (int careMask = 0; careMask < functions.length; careMask++) {
                int simplified = NDD.simplify(functions[functionMask], functions[careMask]);
                int expected = NDD.and(functions[functionMask], functions[careMask]);
                int actual = NDD.and(simplified, functions[careMask]);
                assertEquals(expected, actual,
                        "function mask=" + functionMask + ", care mask=" + careMask);
            }
        }
        for (int function : functions) {
            NDD.deref(function);
        }
    }

    @Test
    void zddRestrictionEnumerationAndSubstitutionUseBooleanBitVectors() {
        NDD.initNDD(10_000, 10_000, 1_000, NDD.LabelMode.ZDD);
        NDD.declareField(2);
        NDD.declareField(2);
        NDD.generateFields();

        int sourceValueOne = NDD.getVar(0, 1);
        assertEquals(8.0, NDD.satCount(sourceValueOne));
        assertEquals(16.0, NDD.satCount(NDD.restrict(sourceValueOne, 0, 1L)));
        assertEquals(0.0, NDD.satCount(NDD.restrict(sourceValueOne, 0, 0L)));
        assertArrayEquals(new int[]{0, 1}, NDD.anySat(sourceValueOne)[0]);
        assertEquals(8, NDD.allSat(sourceValueOne, assignment -> true));

        int replaced = NDD.substitute(sourceValueOne, 0, 1);
        assertEquals(8.0, NDD.satCount(replaced));
    }

    @Test
    void simplifyPreservesTheCareSetForEveryLabelBackend() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            NDD.initNDD(10_000, 10_000, 1_000, mode);
            NDD.declareField(2);
            NDD.declareField(2);
            NDD.generateFields();

            int first = NDD.getVar(0, 0);
            int second = NDD.getVar(1, 1);
            int function = NDD.or(first, second);
            int careSet = NDD.and(first, second);
            int simplified = NDD.simplify(function, careSet);

            assertEquals(NDD.and(function, careSet), NDD.and(simplified, careSet),
                    "mode=" + mode);
            assertEquals(16.0, NDD.satCount(NDD.restrict(NDD.getVar(0, 0), 0, new int[]{1, 0})));
            assertEquals(0.0, NDD.satCount(NDD.restrict(NDD.getVar(0, 0), 0, new int[]{0, 0})));
        }
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

    private static int booleanFunction(int truthMask) {
        int result = NDD.getFalse();
        for (int assignment = 0; assignment < 4; assignment++) {
            if ((truthMask & (1 << assignment)) == 0) continue;
            int fieldZero = (assignment & 2) == 0 ? NDD.getNotVar(0, 0) : NDD.getVar(0, 0);
            int fieldOne = (assignment & 1) == 0 ? NDD.getNotVar(1, 0) : NDD.getVar(1, 0);
            result = NDD.or(result, NDD.and(fieldZero, fieldOne));
        }
        return result;
    }
}
