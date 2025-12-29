/*
 * Decompiled with CFR 0.152.
 */
package jdd.zdd;

import jdd.bdd.OptimizedCache;
import jdd.util.Configuration;
import jdd.zdd.ZDD2;

public class ZDDCSP
extends ZDD2 {
    protected static final int CACHE_RESTRICT = 0;
    protected static final int CACHE_NOSUPSET = 1;
    protected OptimizedCache csp_cache;

    public ZDDCSP(int nodesize, int cachesize) {
        super(nodesize, cachesize);
        this.csp_cache = new OptimizedCache("csp", cachesize / Configuration.zddCSPCacheDiv, 3, 2);
    }

    @Override
    public void cleanup() {
        super.cleanup();
        this.csp_cache = null;
    }

    @Override
    protected void post_removal_callbak() {
        super.post_removal_callbak();
        this.csp_cache.free_or_grow(this);
    }

    public final int restrict(int F, int C) {
        if (F == 0 || C == 0) {
            return 0;
        }
        if (F == C) {
            return F;
        }
        if (this.csp_cache.lookup(F, C, 0)) {
            return this.csp_cache.answer;
        }
        int hash = this.csp_cache.hash_value;
        int ret = 0;
        int v = this.getVar(F);
        if (v < this.getVar(C)) {
            int tmp = this.nstack.push(this.restrict(F, this.getLow(C)));
            ret = this.mk(this.getVar(C), tmp, 0);
            this.nstack.pop();
        } else if (v > this.getVar(C)) {
            int tmp = this.nstack.push(this.restrict(this.getHigh(F), C));
            int tmp2 = this.nstack.push(this.restrict(this.getLow(F), C));
            ret = this.mk(v, tmp2, tmp);
            this.nstack.drop(2);
        } else {
            int tmp1 = this.nstack.push(this.restrict(this.getHigh(F), this.getHigh(C)));
            int tmp2 = this.nstack.push(this.restrict(this.getHigh(F), this.getLow(C)));
            tmp1 = this.union(tmp1, tmp2);
            this.nstack.drop(2);
            this.nstack.push(tmp1);
            tmp2 = this.nstack.push(this.restrict(this.getLow(F), this.getLow(C)));
            ret = this.mk(v, tmp2, tmp1);
            this.nstack.drop(2);
        }
        this.csp_cache.insert(hash, F, C, 0, ret);
        return ret;
    }

    private final int exclude_slow(int F, int C) {
        int tmp = this.nstack.push(this.restrict(F, C));
        tmp = this.diff(F, tmp);
        this.nstack.pop();
        return tmp;
    }

    private final int exclude_fast(int F, int C) {
        if (this.emptyIn(C)) {
            return 0;
        }
        return this.noSupset_rec(F, C);
    }

    private final int noSupset_rec(int F, int C) {
        int ret;
        int cvar;
        if (F == 0 || C == 1 || F == C) {
            return 0;
        }
        if (F == 1 || C == 0) {
            return F;
        }
        if (this.csp_cache.lookup(F, C, 1)) {
            return this.csp_cache.answer;
        }
        int hash = this.csp_cache.hash_value;
        int fvar = this.getVar(F);
        if (fvar < (cvar = this.getVar(C))) {
            ret = this.noSupset_rec(F, this.getLow(C));
        } else if (fvar > cvar) {
            int tmp1 = this.nstack.push(this.noSupset_rec(this.getHigh(F), C));
            int tmp2 = this.nstack.push(this.noSupset_rec(this.getLow(F), C));
            ret = this.mk(fvar, tmp2, tmp1);
            this.nstack.drop(2);
        } else {
            int tmp2;
            int tmp1;
            int C1 = this.getHigh(C);
            if (this.emptyIn(C1)) {
                tmp1 = this.nstack.push(0);
            } else {
                int F1 = this.getHigh(F);
                tmp1 = this.nstack.push(this.noSupset_rec(F1, this.getLow(C)));
                tmp2 = this.nstack.push(this.noSupset_rec(F1, C1));
                tmp1 = this.intersect(tmp1, tmp2);
                this.nstack.drop(2);
                this.nstack.push(tmp1);
            }
            tmp2 = this.nstack.push(this.noSupset_rec(this.getLow(F), this.getLow(C)));
            ret = this.mk(fvar, tmp2, tmp1);
            this.nstack.drop(2);
        }
        this.csp_cache.insert(hash, F, C, 1, ret);
        return ret;
    }

    public final int exclude(int F, int C) {
        return this.exclude_fast(F, C);
    }

    @Override
    public void showStats() {
        super.showStats();
        this.csp_cache.showStats();
    }

    @Override
    public long getMemoryUsage() {
        long ret = super.getMemoryUsage();
        if (this.csp_cache != null) {
            ret += this.csp_cache.getMemoryUsage();
        }
        return ret;
    }
}

