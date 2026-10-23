package org.ants.jndd.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NDDNodeCountTest {

    @Test
    void terminalsHaveNoNodes() {
        NDD.initNDD(1_000, 1_000, 1_000);
        NDD.declareField(2);
        NDD.generateFields();

        assertEquals(0, NDD.nodeCount(NDD.getTrue()));
        assertEquals(0, NDD.nodeCount(NDD.getFalse()));
        assertEquals(0, NDD.labelNodeCount(NDD.getTrue()));
        assertEquals(0, NDD.labelNodeCount(NDD.getFalse()));
    }

    @Test
    void singleVariableHasOneNddNodeAndOneLabelNode() {
        NDD.initNDD(1_000, 1_000, 1_000);
        int field = NDD.declareField(2);
        NDD.generateFields();

        int x0 = NDD.getVar(field, 0);
        assertEquals(1, NDD.nodeCount(x0));
        assertEquals(1, NDD.labelNodeCount(x0));
    }

    @Test
    void countsOnlyTheGivenDiagramNotTheWholeTable() {
        for (int n : new int[]{2, 8}) {
            int root = hadamard(n, NDD.LabelMode.BDD);
            // 2 parity nodes per field, 1 for the first field.
            assertEquals(2 * n - 1, NDD.nodeCount(root));
            // x ∧ y and ¬(x ∧ y), 2 BDD nodes each, shared by every field.
            assertEquals(4, NDD.labelNodeCount(root));
            // The table also holds the variable nodes and intermediate results.
            assertTrue(NDD.getNodeCount() > NDD.nodeCount(root));
        }
    }

    @Test
    void everyLabelBackendGivesTheSameNddNodesAndItsOwnLabelNodes() {
        int n = 3;
        // BCDD: x ∧ y and its complement share both nodes. BITSET: one truth table per label.
        int[] expectedLabelNodes = {4, 2, 4, 2};
        NDD.LabelMode[] modes = {NDD.LabelMode.BDD, NDD.LabelMode.COMPLEMENTED_BDD,
                NDD.LabelMode.ZDD, NDD.LabelMode.BITSET};
        for (int m = 0; m < modes.length; m++) {
            int root = hadamard(n, modes[m]);
            assertEquals(2 * n - 1, NDD.nodeCount(root), modes[m].toString());
            assertEquals(expectedLabelNodes[m], NDD.labelNodeCount(root), modes[m].toString());
        }
    }

    @Test
    void mixedBackendsAreCountedSeparately() {
        NDD.initNDD(1_000, 1_000, 1_000);
        int bdd = NDD.declareField(1, NDD.LabelMode.BDD);
        int zdd = NDD.declareField(1, NDD.LabelMode.ZDD);
        int bcdd = NDD.declareField(1, NDD.LabelMode.COMPLEMENTED_BDD);
        NDD.generateFields();

        int root = NDD.and(NDD.and(NDD.getVar(bdd, 0), NDD.getVar(zdd, 0)), NDD.getVar(bcdd, 0));
        assertEquals(3, NDD.nodeCount(root));
        // One label node per backend, even if two backends use the same node id.
        assertEquals(3, NDD.labelNodeCount(root));
    }

    /** Build the Hadamard matrix H_{2^n} with one 2-bit field per bit pair. */
    private static int hadamard(int n, NDD.LabelMode mode) {
        NDD.initNDD(10_000, 10_000, 1_000);
        for (int i = 0; i < n; i++) {
            NDD.declareField(2, mode);
        }
        NDD.generateFields();
        int root = NDD.getFalse();
        for (int i = 0; i < n; i++) {
            int pair = NDD.and(NDD.getVar(i, 0), NDD.getVar(i, 1));
            root = NDD.apply(NDD.BinaryOperation.XOR, root, pair);
        }
        return root;
    }
}
