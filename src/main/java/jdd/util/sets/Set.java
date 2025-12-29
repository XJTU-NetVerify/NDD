/*
 * Decompiled with CFR 0.152.
 */
package jdd.util.sets;

import jdd.util.sets.SetEnumeration;

public interface Set {
    public void free();

    public double cardinality();

    public boolean insert(int[] var1);

    public boolean remove(int[] var1);

    public boolean member(int[] var1);

    public int compare(Set var1);

    public boolean equals(Set var1);

    public boolean isEmpty();

    public Set invert();

    public Set copy();

    public void clear();

    public Set union(Set var1);

    public Set intersection(Set var1);

    public Set diff(Set var1);

    public SetEnumeration elements();
}

