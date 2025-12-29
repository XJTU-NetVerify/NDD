/*
 * Decompiled with CFR 0.152.
 */
package jdd.util.sets;

public interface SetEnumeration {
    public void free();

    public boolean hasMoreElements();

    public int[] nextElement();
}

