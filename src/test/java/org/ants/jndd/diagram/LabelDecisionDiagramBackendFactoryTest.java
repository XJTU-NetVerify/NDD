package org.ants.jndd.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LabelDecisionDiagramBackendFactoryTest {
    @Test
    void factoryCreatesBackendsWithCommonOperations() {
        NDD.LabelMode[] modes = {
                NDD.LabelMode.BDD,
                NDD.LabelMode.COMPLEMENTED_BDD,
                NDD.LabelMode.ZDD
        };

        for (NDD.LabelMode mode : modes) {
            LabelDecisionDiagramBackend backend =
                    LabelDecisionDiagramBackends.create(mode, 1_000, 100);

            int first = backend.ref(backend.createVariableLabel());
            int second = backend.ref(backend.createVariableLabel());
            int[] variables = {first, second};
            int universe = backend.ref(backend.buildUniverse(variables, 0, variables.length));
            int firstLiteral = backend.ref(backend.positiveLiteral(universe, first));
            int secondLiteral = backend.ref(backend.positiveLiteral(universe, second));
            int union = backend.ref(backend.or(firstLiteral, secondLiteral));
            int intersection = backend.ref(backend.and(firstLiteral, union));

            assertEquals(mode, backend.mode());
            assertTrue(backend.satCount(intersection, 2, 2) >= 1.0);

            backend.deref(intersection);
            backend.deref(union);
            backend.deref(secondLiteral);
            backend.deref(firstLiteral);
            backend.deref(universe);
            backend.deref(second);
            backend.deref(first);
            backend.gc();
        }
    }
}
