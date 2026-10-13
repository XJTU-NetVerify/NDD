package org.ants.mtpndd;

import java.util.Objects;

/**
 * Immutable handle to an MTPNDD node exposed to Java callers.
 *
 * <p>Instances are thin wrappers around native pointers. All node-transforming operations create
 * new {@link MTPNDD} instances; original nodes remain valid after the call.</p>
 */
public final class MTPNDD {
    final long nativePtr;
    final long generation;

    MTPNDD(long nativePointer) {
        if (nativePointer == 0L) {
            throw new MTPNDDException("Native runtime returned an empty MTPNDD pointer.");
        }
        this.nativePtr = nativePointer;
        this.generation = MTPNDDEngine.currentGeneration();
    }

    /**
     * Return the node representing the specified positive literal.
     */
    public static MTPNDD getVar(int fieldId, int index) {
        return MTPNDDEngine.getVar(fieldId, index);
    }

    /**
     * Return the node representing the negated literal.
     */
    public static MTPNDD getNotVar(int fieldId, int index) {
        return MTPNDDEngine.getNotVar(fieldId, index);
    }

    /**
     * Global constant for the logical {@code true}.
     */
    public static MTPNDD getTrue() {
        return MTPNDDEngine.getTrue();
    }

    /**
     * Global constant for the logical {@code false}.
     */
    public static MTPNDD getFalse() {
        return MTPNDDEngine.getFalse();
    }

    public static MTPNDD ref(MTPNDD node) {
        if (node == null) {
            return null;
        }
        return node.ref();
    }

    public static void deref(MTPNDD node) {
        if (node != null) {
            node.deref();
        }
    }

    public static MTPNDD and(MTPNDD left, MTPNDD right) {
        return left.and(right);
    }

    public static MTPNDD or(MTPNDD left, MTPNDD right) {
        return left.or(right);
    }

    public static MTPNDD not(MTPNDD value) {
        return value.not();
    }

    public static MTPNDD diff(MTPNDD left, MTPNDD right) {
        return left.diff(right);
    }

    /**
     * Batch AND: compute lefts[i] AND rights[i] for all i in a single native call.
     * Each result has +1 refcount (already ref'd).
     */
    public static MTPNDD[] andBatch(MTPNDD[] lefts, MTPNDD[] rights) {
        return MTPNDDEngine.andBatch(lefts, rights);
    }

    /**
     * Tree-reduce OR: compute OR of all values in O(log N) depth.
     * Result is NOT ref'd (caller must ref if needed).
     */
    public static MTPNDD orReduce(MTPNDD[] values) {
        return MTPNDDEngine.orReduce(values);
    }

    /**
     * Tree-reduce AND: compute AND of all values in O(log N) depth.
     * Result is NOT ref'd (caller must ref if needed).
     */
    public static MTPNDD andReduce(MTPNDD[] values) {
        return MTPNDDEngine.andReduce(values);
    }

    public static MTPNDD andTo(MTPNDD left, MTPNDD right) {
        MTPNDD result = left.and(right).ref();
        left.deref();
        return result;
    }

    public static MTPNDD orTo(MTPNDD left, MTPNDD right) {
        MTPNDD result = left.or(right).ref();
        left.deref();
        return result;
    }

    public static double satCount(MTPNDD node) {
        return node.satCount();
    }

    public static int toZero(MTPNDD node) {
        return node.minZeros();
    }

    public MTPNDD and(MTPNDD other) {
        return MTPNDDEngine.and(this, other);
    }

    public MTPNDD or(MTPNDD other) {
        return MTPNDDEngine.or(this, other);
    }

    public MTPNDD diff(MTPNDD other) {
        return MTPNDDEngine.diff(this, other);
    }

    public MTPNDD not() {
        return MTPNDDEngine.not(this);
    }

    public MTPNDD exist(int fieldId) {
        return MTPNDDEngine.exist(this, fieldId);
    }

    public double satCount() {
        return MTPNDDEngine.satCount(this);
    }

    public int minZeros() {
        return MTPNDDEngine.minZeros(this);
    }

    public boolean isTrue() {
        return MTPNDDEngine.isTrue(this);
    }

    public boolean isFalse() {
        return MTPNDDEngine.isFalse(this);
    }

    public boolean isTerminal() {
        return MTPNDDEngine.isTerminal(this);
    }

    public int getFieldId() {
        return MTPNDDEngine.getFieldId(this);
    }

    public MTPNDDEdges getEdges() {
        return MTPNDDEngine.getEdges(this);
    }

    /**
     * Increment the native reference count for this node.
     */
    public MTPNDD ref() {
        MTPNDDEngine.ref(this);
        return this;
    }

    /**
     * Decrement the native reference count for this node.
     */
    public void deref() {
        MTPNDDEngine.deref(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MTPNDD)) {
            return false;
        }
        MTPNDD mtpndd = (MTPNDD)o;
        return generation == mtpndd.generation && nativePtr == mtpndd.nativePtr;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(generation) + Long.hashCode(nativePtr);
    }

    @Override
    public String toString() {
        return "MTPNDD{nativePtr=0x" + Long.toHexString(nativePtr) + '}';
    }
}
