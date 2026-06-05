package org.ants.jndd.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LabelDecisionDiagramBackendFactoryTest {
    @Test
    void factoryCreatesBackendsWithCommonOperations() {
        NDD.LabelMode[] modes = {
                NDD.LabelMode.BOOLEAN_BDD,
                NDD.LabelMode.COMPLEMENTED_BDD,
                NDD.LabelMode.FINITE_DOMAIN_ZDD
        };

        for (NDD.LabelMode mode : modes) {
            LabelDecisionDiagramBackend backend =
                    LabelDecisionDiagramBackends.create(mode, 1_000, 100);

            int first = backend.ref(backend.createVariableLabel());
            int second = backend.ref(backend.createVariableLabel());
            int union = backend.ref(backend.or(first, second));
            int intersection = backend.ref(backend.and(first, union));

            assertEquals(mode, backend.mode());
            assertTrue(backend.satCount(intersection, 1, 2) >= 1.0);

            backend.deref(intersection);
            backend.deref(union);
            backend.deref(second);
            backend.deref(first);
            backend.gc();
        }
    }
}
