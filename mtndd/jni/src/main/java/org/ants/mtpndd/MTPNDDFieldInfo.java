package org.ants.mtpndd;

/**
 * Description of a declared MTPNDD field.
 */
public final class MTPNDDFieldInfo {
    private final int fieldId;
    private final int bitWidth;
    private final int startVar;

    public MTPNDDFieldInfo(int fieldId, int bitWidth, int startVar) {
        this.fieldId = fieldId;
        this.bitWidth = bitWidth;
        this.startVar = startVar;
    }

    public int fieldId() {
        return fieldId;
    }

    public int bitWidth() {
        return bitWidth;
    }

    public int startVar() {
        return startVar;
    }

    @Override
    public String toString() {
        return "MTPNDDFieldInfo{" +
                "fieldId=" + fieldId +
                ", bitWidth=" + bitWidth +
                ", startVar=" + startVar +
                '}';
    }
}
