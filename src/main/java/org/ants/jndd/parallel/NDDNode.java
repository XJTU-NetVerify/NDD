package org.ants.jndd.parallel;

/** One owned native root. Closing a node releases only this reference. */
public final class NDDNode implements AutoCloseable {
    final ParallelNDD engine;
    final long handle;
    volatile boolean closed;

    NDDNode(ParallelNDD engine, long handle) {
        this.engine = engine;
        this.handle = handle;
    }

    public boolean isClosed() { return closed || engine.isClosed(); }
    public NDDNode retain() { return engine.retain(this); }
    @Override public void close() { engine.release(this); }
    @Override public boolean equals(Object other) {
        if (!(other instanceof NDDNode)) return false;
        NDDNode node = (NDDNode) other;
        return engine == node.engine && handle == node.handle;
    }
    @Override public int hashCode() {
        return 31 * System.identityHashCode(engine) + (int) (handle ^ (handle >>> 32));
    }
}
