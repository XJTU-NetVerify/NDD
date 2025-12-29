/*
 * Decompiled with CFR 0.152.
 */
package jdd.util.sets;

import jdd.util.sets.Set;

public interface Universe {
    public Set createEmptySet();

    public Set createFullSet();

    public double domainSize();

    public int subdomainCount();

    public void free();
}

