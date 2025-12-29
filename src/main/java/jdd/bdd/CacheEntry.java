/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import jdd.util.Configuration;

public final class CacheEntry {
    public int op1 = -1;
    public int op2;
    public int ret;
    public int found = 0;
    public int overwrite;
    public int type;
    public byte hits = 0;

    public final boolean invalid() {
        return this.op1 == -1;
    }

    public final void clear() {
        this.op1 = -1;
        this.hits = 0;
    }

    public final int hit() {
        ++this.found;
        if (this.hits < 127) {
            this.hits = (byte)(this.hits + 1);
        }
        return this.ret;
    }

    public final boolean save() {
        if (this.op1 != -1) {
            ++this.overwrite;
        }
        if (this.hits > Configuration.cacheentryStickyHits) {
            return false;
        }
        this.hits = 0;
        return true;
    }

    public final void saturate() {
        this.hits = Configuration.cacheentryStickyHits;
    }
}

