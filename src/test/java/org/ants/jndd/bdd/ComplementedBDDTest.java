package org.ants.jndd.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComplementedBDDTest {
    @Test
    void complementedOperationsMatchTruthTablesAndSatCount() {
        ComplementedBDD bdd = new ComplementedBDD(512, 256);
        int x = bdd.createVar();
        int y = bdd.createVar();
        int z = bdd.createVar();

        int left = bdd.ref(bdd.and(x, bdd.not(y)));
        int right = bdd.ref(bdd.and(bdd.not(x), z));
        int function = bdd.ref(bdd.or(left, right));
        int complement = bdd.not(function);

        int satisfying = 0;
        for (int bits = 0; bits < 8; bits++) {
            boolean xv = (bits & 1) != 0;
            boolean yv = (bits & 2) != 0;
            boolean zv = (bits & 4) != 0;
            boolean expected = (xv && !yv) || (!xv && zv);
            assertEquals(expected, evaluate(bdd, function, bits));
            assertEquals(!expected, evaluate(bdd, complement, bits));
            if (expected) satisfying++;
        }

        assertEquals(satisfying, bdd.satCount(function));
        assertEquals(8 - satisfying, bdd.satCount(complement));
        assertEquals(bdd.getNodeId(function), bdd.getNodeId(complement));
        assertEquals(bdd.nodeCount(function), bdd.nodeCount(complement));

        bdd.deref(function);
        bdd.deref(right);
        bdd.deref(left);
    }

    @Test
    void referencedRootsSurviveCollectionAndConsumedLeftIsReleased() {
        ComplementedBDD bdd = new ComplementedBDD(256, 64);
        int[] vars = new int[12];
        for (int i = 0; i < vars.length; i++) vars[i] = bdd.createVar();

        int root = bdd.ref(bdd.and(vars[0], vars[1]));
        root = bdd.andTo(root, bdd.not(vars[2]));
        for (int i = 3; i < vars.length; i++) {
            int junk = bdd.ref(bdd.or(vars[i - 1], vars[i]));
            bdd.deref(junk);
        }

        int before = bdd.getNodeCount();
        bdd.gc();
        assertTrue(evaluate(bdd, root, 0b0000_0000_0011));
        assertTrue(bdd.getNodeCount() <= before);

        bdd.deref(root);
        bdd.gc();
        assertEquals(vars.length, bdd.getNodeCount(), "only permanent variable nodes remain");
    }

    private static boolean evaluate(ComplementedBDD bdd, int handle, int assignment) {
        while (handle > 1) {
            int variable = bdd.getVar(handle);
            handle = ((assignment >>> variable) & 1) == 0
                    ? bdd.getLow(handle)
                    : bdd.getHigh(handle);
        }
        return handle == 1;
    }
}
