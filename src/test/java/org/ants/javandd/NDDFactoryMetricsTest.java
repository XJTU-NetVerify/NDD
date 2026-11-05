package org.ants.javandd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NDDFactoryMetricsTest {
    @Test
    void collectionPreservesOwnedRootsAndCreationCounters() {
        NDDFactory factory = new NDDFactory(1000, 128);
        factory.setVarNum(new int[]{2, 2}, 128);
        BDD x = factory.ithVar(0);
        BDD y = factory.ithVar(2);
        BDD root = x.and(y);
        x.free();
        y.free();
        long created = factory.getTotalCreated();
        long labelCreated = factory.getLabelTotalCreated();
        factory.runGC();
        assertEquals(created, factory.getTotalCreated());
        assertEquals(labelCreated, factory.getLabelTotalCreated());
        assertEquals(4, root.satCount());
        root.free();
        assertTrue(factory.runGC() > 0);
        assertEquals(8, factory.getNodeTableSize());
        assertEquals(0, factory.runGC());
        factory.free();
    }
}
