package org.ants.jndd.parallel;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

public class NDDNodeHashTest {
    @Test public void correlatedIdsAndCookiesDoNotCollapseHashBuckets() {
        HashSet<Integer> hashes = new HashSet<>();
        for (int id = 0; id < 4096; ++id) {
            long handle = ((long) (id + 1) << 32) | id;
            NDDNode node = new NDDNode(null, handle);
            NDDNode same = new NDDNode(null, handle);
            assertEquals(node, same);
            assertEquals(node.hashCode(), same.hashCode());
            hashes.add(node.hashCode());
        }
        assertTrue(hashes.size() > 4000, "Correlated native handles must remain well distributed");
    }
}
