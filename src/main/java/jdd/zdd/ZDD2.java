/*
 * Decompiled with CFR 0.152.
 */
package jdd.zdd;

import jdd.bdd.OptimizedCache;
import jdd.util.Configuration;
import jdd.zdd.ZDD;

public class ZDD2
extends ZDD {
    private static final int CACHE_MUL = 0;
    private static final int CACHE_DIV = 1;
    private static final int CACHE_MOD = 2;
    protected OptimizedCache unate_cache;

    public ZDD2(int nodesize) {
        this(nodesize, 1000);
    }

    public ZDD2(int nodesize, int cachesize) {
        super(nodesize, cachesize);
        this.unate_cache = new OptimizedCache("unate", cachesize / Configuration.zddUnateCacheDiv, 3, 2);
    }

    @Override
    public void cleanup() {
        super.cleanup();
        this.unate_cache = null;
    }

    @Override
    protected void post_removal_callbak() {
        super.post_removal_callbak();
        this.unate_cache.free_or_grow(this);
    }

    public final int mul(int p, int q) {
        int ret;
        int qvar;
        if (p == 0 || q == 0) {
            return 0;
        }
        if (p == 1) {
            return q;
        }
        if (q == 1) {
            return p;
        }
        int pvar = this.getVar(p);
        if (pvar > (qvar = this.getVar(q))) {
            int tmp = p;
            p = q;
            q = tmp;
            tmp = pvar;
            pvar = qvar;
            qvar = tmp;
        }
        if (this.unate_cache.lookup(p, q, 0)) {
            return this.unate_cache.answer;
        }
        int hash = this.unate_cache.hash_value;
        if (pvar < qvar) {
            int tmp1 = this.nstack.push(this.mul(p, this.getHigh(q)));
            int tmp2 = this.nstack.push(this.mul(p, this.getLow(q)));
            ret = this.mk(qvar, tmp2, tmp1);
            this.nstack.drop(2);
        } else {
            int tmp1 = this.nstack.push(this.mul(this.getHigh(p), this.getHigh(q)));
            int tmp2 = this.nstack.push(this.mul(this.getHigh(p), this.getLow(q)));
            ret = this.union(tmp1, tmp2);
            this.nstack.drop(2);
            this.nstack.push(ret);
            tmp1 = this.nstack.push(this.mul(this.getLow(p), this.getHigh(q)));
            tmp1 = this.union(ret, tmp1);
            this.nstack.drop(2);
            this.nstack.push(tmp1);
            tmp2 = this.nstack.push(this.mul(this.getLow(p), this.getLow(q)));
            ret = this.mk(pvar, tmp2, tmp1);
            this.nstack.drop(2);
        }
        this.unate_cache.insert(hash, p, q, 0, ret);
        return ret;
    }

    public final int div(int p, int q) {
        int ret;
        int qvar;
        if (p < 2) {
            return 0;
        }
        if (p == q) {
            return 1;
        }
        if (q == 1) {
            return p;
        }
        int pvar = this.getVar(p);
        if (pvar < (qvar = this.getVar(q))) {
            return 0;
        }
        if (this.unate_cache.lookup(p, q, 1)) {
            return this.unate_cache.answer;
        }
        int hash = this.unate_cache.hash_value;
        if (pvar > qvar) {
            int tmp1 = this.nstack.push(this.div(this.getLow(p), q));
            int tmp2 = this.nstack.push(this.div(this.getHigh(p), q));
            ret = this.mk(pvar, tmp1, tmp2);
            this.nstack.drop(2);
        } else {
            ret = this.div(this.getHigh(p), this.getHigh(q));
            int tmp1 = this.getLow(q);
            if (tmp1 != 0 && ret != 0) {
                this.nstack.push(ret);
                tmp1 = this.nstack.push(this.div(this.getLow(p), tmp1));
                ret = this.intersect(tmp1, ret);
                this.nstack.drop(2);
            }
        }
        this.unate_cache.insert(hash, p, q, 1, ret);
        return ret;
    }

    public final int mod(int p, int q) {
        if (this.unate_cache.lookup(p, q, 2)) {
            return this.unate_cache.answer;
        }
        int hash = this.unate_cache.hash_value;
        int tmp = this.nstack.push(this.div(p, q));
        tmp = this.nstack.push(this.mul(q, tmp));
        tmp = this.diff(p, tmp);
        this.nstack.drop(2);
        this.unate_cache.insert(hash, p, q, 2, tmp);
        return tmp;
    }

    @Override
    public void showStats() {
        super.showStats();
        this.unate_cache.showStats();
    }

    @Override
    public long getMemoryUsage() {
        long ret = super.getMemoryUsage();
        if (this.unate_cache != null) {
            ret += this.unate_cache.getMemoryUsage();
        }
        return ret;
    }
}

