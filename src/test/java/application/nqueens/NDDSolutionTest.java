package application.nqueens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ants.javandd.NDDFactory;
import org.ants.jndd.diagram.NDD;
import org.junit.jupiter.api.Test;

class NDDSolutionTest {
    private static final int[] COUNTS = {1, 0, 0, 2, 10, 4, 40, 92};

    @Test
    void bothImplementationsAndAllLabelModesMatchKnownCounts() {
        for (NDDSolution.Implementation implementation : NDDSolution.Implementation.values()) {
            for (NDD.LabelMode mode : new NDD.LabelMode[]{
                    NDD.LabelMode.BDD, NDD.LabelMode.COMPLEMENTED_BDD, NDD.LabelMode.ZDD}) {
                if (implementation == NDDSolution.Implementation.JAVANDD && mode != NDD.LabelMode.BDD) continue;
                for (int n = 1; n <= COUNTS.length; n++) {
                    NDDSolution.Result result = NDDSolution.solve(n, implementation, mode, 10000);
                    assertEquals(COUNTS[n - 1], result.solutions, implementation + "/" + mode + "/" + n);
                    assertEquals(implementation, result.implementation);
                    assertEquals(mode, result.mode);
                    assertEquals(result.nddNodesCreated + result.bddNodesCreated, result.nodesCreated);
                    assertEquals(result.nddNodesAlive + result.bddNodesAlive, result.nodesAlive);
                    assertTrue(result.nodesAlive >= 0);
                }
            }
        }
    }

    @Test
    void repeatedJavaNddRunsHaveIndependentCountsAndReleaseIntermediateNodes() {
        NDDSolution.Result first = NDDSolution.solve(6, NDDSolution.Implementation.JAVANDD, NDD.LabelMode.BDD, 10000);
        NDDSolution.Result second = NDDSolution.solve(6, NDDSolution.Implementation.JAVANDD, NDD.LabelMode.BDD, 10000);
        assertEquals(4, first.solutions);
        assertEquals(4, second.solutions);
        // Hash/GC order can change creation counts, but they must not include earlier factories.
        assertTrue(second.nddNodesCreated < NDDFactory.mkCount);
        assertEquals(first.nodesAlive, second.nodesAlive);
        // Only the positive/negative variable nodes remain pinned after GC.
        assertEquals(2 * 6 * 6, second.nddNodesAlive);
        assertTrue(second.nddNodesCreated > second.nddNodesAlive);
    }

    @Test
    void rejectsUnsupportedModesAndInvalidArgumentsBeforeInitialization() {
        assertThrows(IllegalArgumentException.class, () -> NDDSolution.solve(
                4, NDDSolution.Implementation.JAVANDD, NDD.LabelMode.ZDD, 10000));
        for (String[] args : new String[][]{
                {}, {"--implementation"}, {"--label"}, {"--label", "unknown", "4"},
                {"--implementation", "unknown", "4"}, {"0"}, {"-1"}, {"4", "0"},
                {"--implementation", "javandd", "--bcdd", "4"}}) {
            assertThrows(IllegalArgumentException.class, () -> NDDSolution.run(args));
        }
    }
}
