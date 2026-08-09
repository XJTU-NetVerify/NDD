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
}
