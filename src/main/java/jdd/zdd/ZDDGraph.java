/*
 * Decompiled with CFR 0.152.
 */
package jdd.zdd;

import jdd.bdd.OptimizedCache;
import jdd.util.Configuration;
import jdd.zdd.ZDD;

public class ZDDGraph
extends ZDD {
    protected static final int CACHE_NOSUBSET = 0;
    protected static final int CACHE_NOSUPSET = 1;
    protected OptimizedCache graph_cache;

    public ZDDGraph(int nodesize, int cachesize) {
        super(nodesize, cachesize);
        this.graph_cache = new OptimizedCache("graph", cachesize / Configuration.zddGraphCacheDiv, 3, 2);
    }

    @Override
    public void cleanup() {
        super.cleanup();
        this.graph_cache = null;
    }

    @Override
    protected void post_removal_callbak() {
        super.post_removal_callbak();
        this.graph_cache.free_or_grow(this);
    }

    public int allEdge() {
        return this.allEdge(0, this.num_vars - 1);
    }

    public int allEdge(int from, int to) {
        if (to < from) {
            return 0;
        }
        int left = 0;
        int right = this.mk(from, 0, 1);
        this.nstack.push(left);
        this.nstack.push(right);
        for (int i = from + 1; i < to; ++i) {
            int tmp1 = this.nstack.push(this.mk(i, left, right));
            int tmp2 = this.nstack.push(this.mk(i, right, 1));
            this.nstack.drop(4);
            left = this.nstack.push(tmp1);
            right = this.nstack.push(tmp2);
        }
        int ret = this.mk(to, left, right);
        this.nstack.drop(2);
        return ret;
    }

    public final int noSubset(int F, int C) {
        int ret;
        int cvar;
        if (F == C || F == 1 || F == 0) {
            return 0;
        }
        if (C == 0) {
            return F;
        }
        if (C == 1) {
            return this.diff(F, 1);
        }
        if (this.graph_cache.lookup(F, C, 0)) {
            return this.graph_cache.answer;
        }
        int hash = this.graph_cache.hash_value;
        int fvar = this.getVar(F);
        if (fvar > (cvar = this.getVar(C))) {
            int tmp1 = this.nstack.push(this.noSubset(this.getLow(F), C));
            ret = this.mk(fvar, tmp1, this.getHigh(F));
            this.nstack.pop();
        } else if (fvar < cvar) {
            int tmp1 = this.nstack.push(this.noSubset(F, this.getLow(C)));
            int tmp2 = this.nstack.push(this.noSubset(F, this.getHigh(C)));
            ret = this.intersect(tmp1, tmp2);
            this.nstack.drop(2);
        } else {
            int tmp1 = this.nstack.push(this.noSubset(this.getLow(F), this.getLow(C)));
            int tmp2 = this.nstack.push(this.noSubset(this.getLow(F), this.getHigh(C)));
            tmp1 = this.intersect(tmp1, tmp2);
            this.nstack.drop(2);
            this.nstack.push(tmp1);
            tmp2 = this.nstack.push(this.noSubset(this.getHigh(F), this.getHigh(C)));
            ret = this.mk(fvar, tmp1, tmp2);
            this.nstack.drop(2);
        }
        this.graph_cache.insert(hash, F, C, 0, ret);
        return ret;
    }

    public int noSupset(int F, int C) {
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
        if (this.graph_cache.lookup(F, C, 1)) {
            return this.graph_cache.answer;
        }
        int hash = this.graph_cache.hash_value;
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
                tmp1 = this.nstack.push(this.noSupset_rec(this.getHigh(F), this.getLow(C)));
                tmp2 = this.nstack.push(this.noSupset_rec(this.getHigh(F), C1));
                tmp1 = this.intersect(tmp1, tmp2);
                this.nstack.drop(2);
                this.nstack.push(tmp1);
            }
            tmp2 = this.nstack.push(this.noSupset_rec(this.getLow(F), this.getLow(C)));
            ret = this.mk(fvar, tmp2, tmp1);
            this.nstack.drop(2);
        }
        this.graph_cache.insert(hash, F, C, 1, ret);
        return ret;
    }

    public int maxSet(int X) {
        if (X < 2) {
            return X;
        }
        int T0 = this.nstack.push(this.maxSet(this.getLow(X)));
        int T1 = this.nstack.push(this.maxSet(this.getHigh(X)));
        int T2 = this.nstack.push(this.noSubset(T0, T1));
        T0 = this.mk(this.getVar(X), T2, T1);
        this.nstack.drop(3);
        return T0;
    }
}

