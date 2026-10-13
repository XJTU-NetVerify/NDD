package org.ants.mtpndd;

public final class MTPNDDEdge {
    private final MTPNDD descendant;
    private final long predicateHandle;

    MTPNDDEdge(MTPNDD descendant, long predicateHandle) {
        this.descendant = descendant;
        this.predicateHandle = predicateHandle;
    }

    public MTPNDD descendant() {
        return descendant;
    }

    public long predicateHandle() {
        return predicateHandle;
    }
}
