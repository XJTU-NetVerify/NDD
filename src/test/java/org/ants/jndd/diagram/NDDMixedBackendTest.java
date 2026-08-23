package org.ants.jndd.diagram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.ants.jndd.utils.Rational;

public class NDDMixedBackendTest {
    private static final double EPS = 1e-9;

    @Test
    public void homogeneousBackendsShareBooleanFieldSemantics() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            NDD.initNDD(10_000, 10_000, 1_000, mode);
            int field = NDD.declareField(3, mode);
            NDD.generateFields();

            NDD x = NDD.getVar(field, 0);
            NDD nx = NDD.getNotVar(field, 0);
            assertEquals(4.0, NDD.satCount(x), EPS);
            assertEquals(4.0, NDD.satCount(nx), EPS);
            assertEquals(8.0, NDD.satCount(x.or(nx)), EPS);
            assertEquals(0.0, NDD.satCount(x.and(nx)), EPS);
            assertEquals(4.0, NDD.satCount(NDD.diff(x, nx)), EPS);
            assertTrue(NDD.getLabelNodeCount(mode) > 0);
        }
    }

    @Test
    public void mixedBackendsPreserveBooleanAndMultiTerminalAlgebra() {
        NDD.initNDD(20_000, 20_000, 2_000);
        int header = NDD.declareField(2, NDD.LabelMode.BDD);
        int links = NDD.declareField(2, NDD.LabelMode.ZDD);
        int flag = NDD.declareField(1, NDD.LabelMode.COMPLEMENTED_BDD);
        NDD.generateFields();

        assertTrue(NDD.hasMixedLabelModes());
        NDD x = NDD.getVar(header, 0);
        NDD y = NDD.getVar(links, 0);
        NDD z = NDD.getVar(flag, 0);

        NDD conjunction = x.and(y).and(z);
        assertEquals(4.0, NDD.satCount(conjunction), EPS);
        assertEquals(28.0, NDD.satCount(conjunction.cmpl()), EPS);
        assertEquals(NDD.satCount(NDD.diff(x, y)),
                NDD.satCount(x.and(y.cmpl())), EPS);

        NDD twoWhenX = x.times(NDD.createTerminal(2));
        NDD threeWhenNotX = x.cmpl().times(NDD.createTerminal(3));
        NDD piecewise = twoWhenX.plus(threeWhenNotX).withRef();
        assertEquals(16.0, NDD.satCount(piecewise, 0, 2), EPS);

        NDD.gc();
        NDD.gcLabelEngines();
        assertTrue(NDD.getGcCount() > 0);
        assertTrue(NDD.getLabelGcCount(NDD.LabelMode.BDD) > 0);
        assertTrue(NDD.getLabelGcCount(NDD.LabelMode.ZDD) > 0);
        assertTrue(NDD.getLabelGcCount(NDD.LabelMode.COMPLEMENTED_BDD) > 0);
        assertEquals(16.0, NDD.satCount(piecewise, 0, 2), EPS);
        piecewise.recursiveDeref();
    }

    @Test
    public void terminalGarbageCollectionKeepsLiveValuesCanonical() {
        NDD.initNDD(1_000, 128, 1_000, 128, NDD.TerminalMode.DOUBLE);
        NDD live = NDD.createTerminal(2.0).withRef();
        NDD.createTerminal(3.0);

        NDD.gc();

        assertEquals(3, NDD.gettersize()); // zero, one, and the referenced value
        assertTrue(live == NDD.createTerminal(2.0));
        NDD recreated = NDD.createTerminal(3.0);
        assertEquals(3.0, recreated.getTerminalVal(), 0.0);
        assertEquals(4, NDD.gettersize());
        live.recursiveDeref();
    }

    @Test
    public void sumAbstractUsesTheOwningBackend() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            NDD.initNDD(10_000, 10_000, 1_000);
            int retained = NDD.declareField(1, NDD.LabelMode.BDD);
            int abstracted = NDD.declareField(1, mode);
            NDD.generateFields();

            NDD bit = NDD.getVar(abstracted, 0);
            NDD function = bit.times(NDD.createTerminal(2))
                    .plus(bit.cmpl().times(NDD.createTerminal(3)));
            NDD sum = NDD.sumAbstract(function, abstracted);
            assertEquals(5.0, NDD.evaluate(sum, new int[] {0, 0}), EPS);
            assertEquals(5.0, NDD.evaluate(sum, new int[] {1, 1}), EPS);
            assertEquals(4.0, NDD.satCount(sum, retained, 5), EPS);
        }
    }

    @Test
    public void fusedMultiplySumAbstractMatchesMaterializedProduct() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            NDD.initNDD(20_000, 2_000, 10_000, 1_000, NDD.TerminalMode.DOUBLE);
            int retained = NDD.declareField(1, mode);
            int abstracted = NDD.declareField(2, mode);
            NDD.generateFields();

            NDD retainedBit = NDD.getVar(retained, 0);
            NDD retainedWeight = retainedBit.times(NDD.createTerminal(2.0))
                    .plus(retainedBit.cmpl().times(NDD.createTerminal(1.0)));
            NDD x = NDD.getVar(abstracted, 0);
            NDD y = NDD.getVar(abstracted, 1);
            NDD left = retainedWeight.times(
                    x.times(NDD.createTerminal(2.0))
                        .plus(x.cmpl().times(NDD.createTerminal(3.0)))).withRef();
            NDD right = y.times(NDD.createTerminal(5.0))
                    .plus(y.cmpl().times(NDD.createTerminal(7.0))).withRef();

            NDD materialized = NDD.sumAbstract(left.times(right), abstracted).withRef();
            NDD fused = NDD.multiplySumAbstract(left, right, abstracted).withRef();
            NDD chunked = NDD.multiplySumAbstract(left, right, abstracted, 2).withRef();

            assertEquals(60.0, NDD.evaluateDouble(fused, new int[][] {{0}, {0, 0}}), EPS);
            assertEquals(120.0, NDD.evaluateDouble(fused, new int[][] {{1}, {0, 0}}), EPS);
            assertEquals(NDD.evaluateDouble(materialized, new int[][] {{0}, {0, 0}}),
                    NDD.evaluateDouble(fused, new int[][] {{0}, {0, 0}}), EPS);
            assertEquals(NDD.evaluateDouble(materialized, new int[][] {{1}, {0, 0}}),
                    NDD.evaluateDouble(fused, new int[][] {{1}, {0, 0}}), EPS);
            assertEquals(NDD.evaluateDouble(materialized, new int[][] {{0}, {0, 0}}),
                    NDD.evaluateDouble(chunked, new int[][] {{0}, {0, 0}}), EPS);
            assertEquals(NDD.evaluateDouble(materialized, new int[][] {{1}, {0, 0}}),
                    NDD.evaluateDouble(chunked, new int[][] {{1}, {0, 0}}), EPS);
            chunked.recursiveDeref();
            fused.recursiveDeref();
            materialized.recursiveDeref();
            right.recursiveDeref();
            left.recursiveDeref();
        }
    }

    @Test
    public void naryBucketContractionMatchesMaterializedProduct() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            NDD.initNDD(30_000, 4_000, 10_000, 1_000, NDD.TerminalMode.DOUBLE);
            int abstracted = NDD.declareField(2, mode);
            int retained = NDD.declareField(1, mode);
            NDD.generateFields();

            NDD x = NDD.getVar(abstracted, 0);
            NDD y = NDD.getVar(abstracted, 1);
            NDD z = NDD.getVar(retained, 0);
            NDD first = x.times(NDD.createTerminal(2.0))
                    .plus(x.cmpl().times(NDD.createTerminal(3.0)));
            NDD second = y.times(NDD.createTerminal(5.0))
                    .plus(y.cmpl().times(NDD.createTerminal(7.0)));
            NDD third = z.times(x.times(NDD.createTerminal(11.0))
                    .plus(x.cmpl().times(NDD.createTerminal(13.0))))
                    .plus(z.cmpl());

            NDD materialized = NDD.sumAbstract(
                    first.times(second).times(third), abstracted);
            NDD contracted = NDD.contractAndSumAbstract(
                    new NDD[] {first, second, third}, abstracted);

            assertEquals(NDD.evaluateDouble(materialized, new int[][] {{0, 0}, {0}}),
                    NDD.evaluateDouble(contracted, new int[][] {{0, 0}, {0}}), EPS);
            assertEquals(NDD.evaluateDouble(materialized, new int[][] {{0, 0}, {1}}),
                    NDD.evaluateDouble(contracted, new int[][] {{0, 0}, {1}}), EPS);
            assertEquals(3, NDD.getLastNaryContractionStats().factorCount);
            assertTrue(NDD.getLastNaryContractionStats().distinctTargetTuples > 0);
        }
    }

    @Test
    public void lazyScalarContractionMatchesMaterializedElimination() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            NDD.initNDD(30_000, 4_000, 10_000, 1_000, NDD.TerminalMode.DOUBLE);
            int firstField = NDD.declareField(2, mode);
            int secondField = NDD.declareField(1, mode);
            NDD.generateFields();

            NDD x = NDD.getVar(firstField, 0);
            NDD y = NDD.getVar(secondField, 0);
            NDD first = x.times(NDD.createTerminal(2.0))
                    .plus(x.cmpl().times(NDD.createTerminal(3.0))).withRef();
            NDD second = y.times(NDD.createTerminal(5.0))
                    .plus(y.cmpl().times(NDD.createTerminal(7.0))).withRef();

            NDD product = first.times(second).withRef();
            NDD afterFirst = NDD.sumAbstract(product, firstField).withRef();
            NDD materialized = NDD.sumAbstract(afterFirst, secondField).withRef();
            double lazy = NDD.contractAllSumProductDouble(
                    new NDD[] {first, second},
                    new int[] {firstField, secondField}, 1_000);
            NDD partial = NDD.contractAndSumAbstractFields(
                    new NDD[] {first, second}, new int[] {firstField}, 1_000).withRef();

            assertEquals(materialized.getTerminalVal(), lazy, EPS);
            assertEquals(120.0, lazy, EPS);
            assertEquals(NDD.evaluateDouble(afterFirst, new int[][] {{0, 0}, {0}}),
                    NDD.evaluateDouble(partial, new int[][] {{0, 0}, {0}}), EPS);
            assertEquals(NDD.evaluateDouble(afterFirst, new int[][] {{0, 0}, {1}}),
                    NDD.evaluateDouble(partial, new int[][] {{0, 0}, {1}}), EPS);
            assertTrue(NDD.getLastScalarContractionStats().recursiveCalls > 0);
            partial.recursiveDeref();
            materialized.recursiveDeref();
            afterFirst.recursiveDeref();
            product.recursiveDeref();
            second.recursiveDeref();
            first.recursiveDeref();
        }
    }

    @Test
    public void factorizedContractionCrossesInterleavedRetainedFields() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            NDD.initNDD(30_000, 4_000, 10_000, 1_000, NDD.TerminalMode.DOUBLE);
            int current0 = NDD.declareField(1, mode);
            int next0 = NDD.declareField(1, mode);
            int current1 = NDD.declareField(1, mode);
            int next1 = NDD.declareField(1, mode);
            NDD.generateFields();

            NDD c0 = NDD.getVar(current0, 0);
            NDD n0 = NDD.getVar(next0, 0);
            NDD c1 = NDD.getVar(current1, 0);
            NDD n1 = NDD.getVar(next1, 0);
            NDD relation = c0.times(n0).times(NDD.createTerminal(2.0))
                    .plus(c0.cmpl().times(n0.cmpl()).times(NDD.createTerminal(3.0)))
                    .times(c1.times(n1).plus(c1.cmpl().times(n1.cmpl()))).withRef();
            NDD vector = c0.times(NDD.createTerminal(5.0))
                    .plus(c0.cmpl().times(NDD.createTerminal(7.0)))
                    .times(c1.times(NDD.createTerminal(11.0))
                            .plus(c1.cmpl().times(NDD.createTerminal(13.0)))).withRef();

            NDD product = relation.times(vector).withRef();
            NDD after0 = NDD.sumAbstract(product, current0).withRef();
            NDD materialized = NDD.sumAbstract(after0, current1).withRef();
            NDD fused = NDD.contractAndSumAbstractFields(
                    new NDD[] {relation, vector}, new int[] {current0, current1},
                    10_000).withRef();
            NDD singlePass = NDD.sumAbstractFields(
                    product, new int[] {current0, current1}).withRef();
            NDD renamed = NDD.renameFields(singlePass,
                    new int[] {next0, next1}, new int[] {current0, current1}).withRef();

            for (int value0 = 0; value0 < 2; value0++) {
                for (int value1 = 0; value1 < 2; value1++) {
                    int[] assignment = new int[] {0, value0, 0, value1};
                    assertEquals(NDD.evaluate(materialized, assignment),
                            NDD.evaluate(fused, assignment), EPS);
                    assertEquals(NDD.evaluate(materialized, assignment),
                            NDD.evaluate(singlePass, assignment), EPS);
                }
            }
            assertEquals(NDD.evaluate(singlePass, new int[] {0, 1, 0, 1}),
                    NDD.evaluate(renamed, new int[] {1, 0, 1, 0}), EPS);
            renamed.recursiveDeref();
            singlePass.recursiveDeref();
            fused.recursiveDeref();
            materialized.recursiveDeref();
            after0.recursiveDeref();
            product.recursiveDeref();
            vector.recursiveDeref();
            relation.recursiveDeref();
        }
    }

    @Test
    public void zddUsesAnExplicitPowersetUniverse() {
        NDD.initNDD(10_000, 10_000, 1_000, NDD.LabelMode.ZDD);
        int field = NDD.declareField(4, NDD.LabelMode.ZDD);
        NDD.generateFields();

        NDD positive = NDD.getVar(field, 2);
        NDD negative = NDD.getNotVar(field, 2);
        assertEquals(8.0, NDD.getLabelSatCount(field, positive.edgeLabel(0)), EPS);
        assertEquals(8.0, NDD.getLabelSatCount(field, negative.edgeLabel(0)), EPS);
        assertEquals(16.0, NDD.satCount(positive.or(negative)), EPS);
    }

    @Test
    public void rationalFallsBackOnlyWhenLongIntermediatesOverflow() {
        Rational left = new Rational(4_000_000_000L, 4_000_000_001L);
        Rational right = new Rational(4_000_000_000L, 4_000_000_003L);
        assertEquals(1.0, left.multiply(right).doubleValue(), 1e-6);
        assertEquals(new Rational(5, 6), new Rational(1, 2).add(new Rational(1, 3)));
    }

    @Test
    public void concreteValueLabelsWorkForEveryBackend() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            NDD.initNDD(10_000, 10_000, 1_000, mode);
            int field = NDD.declareField(3, mode);
            NDD.generateFields();

            int label = NDD.encodeValueLabel(5, field);
            NDD value = NDD.mk(field, new NDD[] {NDD.getTrue()}, new int[] {label});
            NDD.derefLabel(field, label);
            assertEquals(1.0, NDD.satCount(value), EPS);
            assertEquals(1.0, NDD.evaluate(value, new int[] {5}), EPS);
            assertEquals(0.0, NDD.evaluate(value, new int[] {4}), EPS);
        }
    }

    @Test
    public void restrictingConcreteFieldValuesRemovesThoseFields() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            NDD.initNDD(10_000, 2_000, 10_000, 1_000, NDD.TerminalMode.DOUBLE);
            int first = NDD.declareField(1, mode);
            int second = NDD.declareField(1, mode);
            NDD.generateFields();
            NDD x = NDD.getVar(first, 0);
            NDD y = NDD.getVar(second, 0);
            NDD function = x.times(NDD.createTerminal(2.0))
                    .plus(x.cmpl().times(NDD.createTerminal(3.0)))
                    .times(y.times(NDD.createTerminal(5.0))
                            .plus(y.cmpl().times(NDD.createTerminal(7.0))));

            NDD restricted = NDD.restrictFieldValues(
                    function, new int[] {first}, new int[] {1}).withRef();

            assertEquals(14.0, NDD.evaluate(restricted, new int[] {0, 0}), EPS);
            assertEquals(14.0, NDD.evaluate(restricted, new int[] {1, 0}), EPS);
            assertEquals(10.0, NDD.evaluate(restricted, new int[] {0, 1}), EPS);
            assertEquals(10.0, NDD.evaluate(restricted, new int[] {1, 1}), EPS);
            restricted.recursiveDeref();
        }
    }

    @Test
    public void maximumNonnegativeTerminalIncludesImplicitZero() {
        NDD.initNDD(10_000, 10_000, 1_000);
        int field = NDD.declareField(2);
        NDD.generateFields();
        NDD positive = NDD.getVar(field, 0).times(NDD.createTerminal(7));
        assertEquals(7.0, NDD.maxNonnegativeTerminalValue(positive), EPS);
    }

    @Test
    public void doubleTerminalsAreCanonicalAndSupportArithmetic() {
        NDD.initNDD(10_000, 2_000, 10_000, 1_000, NDD.TerminalMode.DOUBLE);
        int field = NDD.declareField(1);
        NDD.generateFields();

        NDD tenth = NDD.createTerminal(0.1);
        assertTrue(tenth == NDD.createTerminal(0.1));
        NDD bit = NDD.getVar(field, 0);
        NDD weighted = bit.times(tenth).plus(bit.cmpl().times(NDD.createTerminal(0.2)));
        assertEquals(0.2, NDD.evaluate(weighted, new int[] {0}), EPS);
        assertEquals(0.1, NDD.evaluate(weighted, new int[] {1}), EPS);
        assertEquals(0.3, NDD.sumAbstract(weighted, field).getTerminalVal(), EPS);
        assertEquals(NDD.TerminalMode.DOUBLE, NDD.getTerminalMode());
    }

    @Test
    public void doubleTerminalMantissaCanBeQuantized() {
        NDD.initNDD(10_000, 2_000, 10_000, 1_000, NDD.TerminalMode.DOUBLE, 32);

        NDD first = NDD.createTerminal(0.1);
        NDD adjacent = NDD.createTerminal(Math.nextUp(0.1));

        assertTrue(first == adjacent);
        assertEquals(32, NDD.getDoubleTerminalMantissaBits());
        assertEquals(first.getTerminalVal(), adjacent.getTerminalVal(), 0.0);
    }

    @Test
    public void doubleTerminalCutoffCreatesExplicitSparsity() {
        NDD.initNDD(10_000, 2_000, 10_000, 1_000,
                NDD.TerminalMode.DOUBLE, 52, 1e-12);

        assertTrue(NDD.createTerminal(1e-13).isFalse());
        assertTrue(!NDD.createTerminal(1e-11).isFalse());
        assertEquals(1e-12, NDD.getDoubleTerminalZeroCutoff(), 0.0);
    }

    @Test
    public void relativePruningReportsRemovedTerminalMass() {
        NDD.initNDD(10_000, 2_000, 10_000, 1_000, NDD.TerminalMode.DOUBLE);
        int field = NDD.declareField(1);
        NDD.generateFields();
        NDD bit = NDD.getVar(field, 0);
        NDD weighted = bit.times(NDD.createTerminal(1e-4))
                .plus(bit.cmpl().times(NDD.createTerminal(0.5)));

        NDD.PruneResult result = NDD.pruneNonnegativeAtMost(weighted, 1e-3);

        assertEquals(1, result.prunedTerminalCount);
        assertEquals(1e-4, result.maximumPrunedValue, 0.0);
        assertEquals(0.5, NDD.evaluate(result.diagram, new int[] {0}), EPS);
        assertEquals(0.0, NDD.evaluate(result.diagram, new int[] {1}), EPS);
    }

    @Test
    public void doubleTerminalGridClustersNearbyValues() {
        NDD.initNDD(10_000, 2_000, 10_000, 1_000,
                NDD.TerminalMode.DOUBLE, 52, 0.0, 0.01);

        NDD first = NDD.createTerminal(0.103);
        NDD nearby = NDD.createTerminal(0.104);
        NDD nextBucket = NDD.createTerminal(0.106);

        assertTrue(first == nearby);
        assertTrue(first != nextBucket);
        assertEquals(0.10, first.getTerminalVal(), EPS);
        assertEquals(0.11, nextBucket.getTerminalVal(), EPS);
        assertEquals(0.01, NDD.getDoubleTerminalQuantizationStep(), 0.0);
        assertEquals(0.01, NDD.createTerminal(0.14).times(first).getTerminalVal(), EPS);
    }

    @Test
    public void intervalTerminalsOutwardRoundAndPropagateBounds() {
        NDD.initNDD(10_000, 2_000, 10_000, 1_000,
                NDD.TerminalMode.INTERVAL, 3, 0.0, 0.0);

        NDD first = NDD.createTerminal(0.097);
        NDD nearby = NDD.createTerminal(0.098);
        NDD second = NDD.createTerminal(0.2);
        NDD product = first.times(second);
        NDD sum = first.plus(second);

        assertTrue(first == nearby);
        assertTrue(first.getTerminalLowerBound() <= 0.097);
        assertTrue(first.getTerminalUpperBound() >= 0.098);
        assertTrue(product.getTerminalLowerBound() <= 0.097 * 0.2);
        assertTrue(product.getTerminalUpperBound() >= 0.098 * 0.2);
        assertTrue(sum.getTerminalLowerBound() <= 0.297);
        assertTrue(sum.getTerminalUpperBound() >= 0.298);
        NDD exact = NDD.createExactIntervalTerminal(0.123456789);
        assertEquals(0.123456789, exact.getTerminalLowerBound(), 0.0);
        assertEquals(0.123456789, exact.getTerminalUpperBound(), 0.0);
        assertEquals(NDD.TerminalMode.INTERVAL, NDD.getTerminalMode());
    }

    @Test
    public void intervalDirectedRoundingDoesNotWidenExactBinaryOperations() {
        NDD.initNDD(10_000, 2_000, 10_000, 1_000,
                NDD.TerminalMode.INTERVAL, 3, 0.0, 0.0);
        NDD.setIntervalRoundingMode(NDD.IntervalRoundingMode.TIGHT);

        NDD half = NDD.createTerminal(0.5);
        NDD quarter = NDD.createTerminal(0.25);
        assertExactInterval(half.plus(quarter), 0.75);
        assertExactInterval(half.times(quarter), 0.125);
        assertExactInterval(half.divide(quarter), 2.0);
        assertExactInterval(half.minus(quarter), 0.25);
    }

    @Test
    public void nominalRadiusIntervalsShareBucketAndPreserveEnclosure() {
        NDD.initNDD(10_000, 2_000, 10_000, 1_000,
                NDD.TerminalMode.INTERVAL, 3, 0.0, 0.0,
                NDD.IntervalEncoding.NOMINAL_RADIUS);
        NDD.setIntervalRoundingMode(NDD.IntervalRoundingMode.TIGHT);

        NDD first = NDD.createTerminal(0.97);
        NDD nearby = NDD.createTerminal(0.98);
        NDD fifth = NDD.createTerminal(0.2);
        assertTrue(first == nearby);
        assertTrue(first != NDD.getTrue());
        assertEquals(1.0, first.getTerminalVal(), 0.0);
        assertTrue(first.getTerminalLowerBound() <= 0.97);
        assertTrue(first.getTerminalUpperBound() >= 0.98);

        NDD product = first.times(fifth);
        assertTrue(product.getTerminalLowerBound() <= 0.97 * 0.2);
        assertTrue(product.getTerminalUpperBound() >= 0.98 * 0.2);
        assertTrue(product.getTerminalLowerBound() > 0.0);
        NDD broad = NDD.createIntervalTerminal(1e-100, 1e100);
        assertTrue(broad.getTerminalLowerBound() > 0.0);
        assertTrue(broad.getTerminalLowerBound() <= 1e-100);
        assertTrue(broad.getTerminalUpperBound() >= 1e100);
        assertTrue(NDD.createTerminal(0.0) == NDD.getFalse());
        assertTrue(NDD.createTerminal(1.0) == NDD.getTrue());
        assertEquals(NDD.IntervalEncoding.NOMINAL_RADIUS, NDD.getIntervalEncoding());
    }

    private static void assertExactInterval(NDD value, double expected) {
        assertEquals(expected, value.getTerminalLowerBound(), 0.0);
        assertEquals(expected, value.getTerminalUpperBound(), 0.0);
    }
}
