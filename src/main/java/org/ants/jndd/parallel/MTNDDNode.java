package org.ants.jndd.parallel;

/** One owned native root. Closing a node releases only this reference. */
public final class MTNDDNode implements AutoCloseable {
    final ParallelMTNDD engine;
    final long handle;
    volatile boolean closed;

    MTNDDNode(ParallelMTNDD engine, long handle) {
        this.engine = engine;
        this.handle = handle;
    }

    public boolean isClosed() { return closed || engine.isClosed(); }
    public MTNDDNode retain() { return engine.retain(this); }
    @Override public void close() { engine.release(this); }
    @Override public boolean equals(Object other) {
        if (!(other instanceof MTNDDNode)) return false;
        MTNDDNode node = (MTNDDNode) other;
        return engine == node.engine && handle == node.handle;
    }
    @Override public int hashCode() {
        // Node IDs and allocation cookies often advance together. Folding the
        // two halves directly maps id ^ (id + 1) to only a few hash values.
        long hash = handle;
        hash ^= hash >>> 30; hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27; hash *= 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        return 31 * System.identityHashCode(engine) + (int) (hash ^ (hash >>> 32));
    }
}
