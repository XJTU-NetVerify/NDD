package org.ants.mtpndd;

import java.util.Iterator;
import java.util.NoSuchElementException;

public final class MTPNDDEdges implements Iterable<MTPNDDEdge> {
    private final long[] pairs;

    private MTPNDDEdges(long[] pairs) {
        this.pairs = pairs == null ? new long[0] : pairs;
    }

    static MTPNDDEdges fromPairs(long[] pairs) {
        return new MTPNDDEdges(pairs);
    }

    public int size() {
        return pairs.length / 2;
    }

    @Override
    public Iterator<MTPNDDEdge> iterator() {
        return new Iterator<MTPNDDEdge>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index + 1 < pairs.length;
            }

            @Override
            public MTPNDDEdge next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more edges");
                }
                long childPtr = pairs[index++];
                long predicate = pairs[index++];
                return new MTPNDDEdge(new MTPNDD(childPtr), predicate);
            }
        };
    }
}
