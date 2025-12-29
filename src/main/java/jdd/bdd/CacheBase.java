/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

public abstract class CacheBase {
    private String cache_name;

    protected CacheBase(String name) {
        this.cache_name = name;
    }

    public String getName() {
        return this.cache_name;
    }

    public abstract double computeLoadFactor();

    public abstract double computeHitRate();

    public abstract long getAccessCount();

    public abstract int getCacheSize();

    public abstract int getNumberOfClears();

    public abstract int getNumberOfPartialClears();

    public abstract int getNumberOfGrows();
}

