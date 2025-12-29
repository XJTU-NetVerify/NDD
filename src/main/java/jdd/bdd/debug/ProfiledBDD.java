/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd.debug;

import java.util.Collection;
import jdd.bdd.BDD;
import jdd.bdd.Permutation;
import jdd.bdd.CacheBase;
import jdd.bdd.debug.BDDDebugFrame;
import jdd.bdd.debug.BDDDebuger;
import jdd.util.JDDConsole;
import jdd.util.Options;

public class ProfiledBDD
extends BDD {
    private long p_and = 0L;
    private long p_or = 0L;
    private long p_xor = 0L;
    private long p_biimp = 0L;
    private long p_imp = 0L;
    private long p_not = 0L;
    private long p_nand;
    private long p_nor;
    private long p_replace = 0L;
    private long p_exists = 0L;
    private long p_forall = 0L;
    private long p_relprod = 0L;
    private long p_support = 0L;
    private long p_restrict = 0L;
    private long p_simplify = 0L;
    private long p_ite = 0L;
    private long p_satcount = 0L;

    public ProfiledBDD(int nodesize) {
        this(nodesize, 1000);
    }

    public ProfiledBDD(int nodesize, int cache_size) {
        super(nodesize, cache_size);
        if (Options.profile_cache) {
            new BDDDebugFrame(this);
        }
    }

    @Override
    public Collection<CacheBase> addDebugger(BDDDebuger d) {
        Collection<CacheBase> v = super.addDebugger(d);
        v.add(this.quant_cache);
        v.add(this.ite_cache);
        v.add(this.not_cache);
        v.add(this.op_cache);
        v.add(this.replace_cache);
        v.add(this.sat_cache);
        return v;
    }

    @Override
    public int and(int a, int b) {
        ++this.p_and;
        return super.and(a, b);
    }

    @Override
    public int or(int a, int b) {
        ++this.p_or;
        return super.or(a, b);
    }

    @Override
    public int xor(int a, int b) {
        ++this.p_xor;
        return super.xor(a, b);
    }

    @Override
    public int biimp(int a, int b) {
        ++this.p_biimp;
        return super.biimp(a, b);
    }

    @Override
    public int imp(int a, int b) {
        ++this.p_imp;
        return super.imp(a, b);
    }

    @Override
    public int nor(int a, int b) {
        ++this.p_nor;
        return super.nor(a, b);
    }

    @Override
    public int nand(int a, int b) {
        ++this.p_nand;
        return super.nand(a, b);
    }

    @Override
    public int not(int a) {
        ++this.p_not;
        return super.not(a);
    }

    @Override
    public int replace(int a, Permutation b) {
        ++this.p_replace;
        return super.replace(a, b);
    }

    @Override
    public int exists(int a, int b) {
        ++this.p_exists;
        return super.exists(a, b);
    }

    @Override
    public int forall(int a, int b) {
        ++this.p_forall;
        return super.forall(a, b);
    }

    @Override
    public int relProd(int a, int b, int c) {
        ++this.p_relprod;
        return super.relProd(a, b, c);
    }

    @Override
    public int ite(int a, int b, int c) {
        ++this.p_ite;
        return super.ite(a, b, c);
    }

    @Override
    public double satCount(int a) {
        ++this.p_satcount;
        return super.satCount(a);
    }

    @Override
    public int support(int a) {
        ++this.p_support;
        return super.support(a);
    }

    @Override
    public int restrict(int a, int b) {
        ++this.p_restrict;
        return super.restrict(a, b);
    }

    @Override
    public int simplify(int a, int b) {
        ++this.p_simplify;
        return super.simplify(a, b);
    }

    @Override
    public void showStats() {
        if (this.p_and > 0L || this.p_or > 0L || this.p_not > 0L) {
            JDDConsole.out.printf("# calls to and/or/not:                    %d/%d/%d\n", this.p_and, this.p_or, this.p_not);
        }
        if (this.p_biimp > 0L || this.p_imp > 0L || this.p_xor > 0L) {
            JDDConsole.out.printf("# calls to biimp/imp/xor:                 %d/%d/%d\n", this.p_biimp, this.p_imp, this.p_xor);
        }
        if (this.p_nand > 0L || this.p_nor > 0L || this.p_ite > 0L) {
            JDDConsole.out.printf("# calls to nand/nor/ite:                  %d/%d/%d\n", this.p_nand, this.p_nor, this.p_ite);
        }
        if (this.p_replace > 0L || this.p_exists > 0L || this.p_forall > 0L || this.p_relprod > 0L) {
            JDDConsole.out.printf("# calls to replace/exists/forall/relProd: %d/%d/%d/%d\n", this.p_replace, this.p_exists, this.p_forall, this.p_relprod);
        }
        if (this.p_support > 0L || this.p_restrict > 0L || this.p_simplify > 0L) {
            JDDConsole.out.printf("# calls to support/restrict/simplify:     %d/%d/%d\n", this.p_support, this.p_restrict, this.p_simplify);
        }
        if (this.p_satcount > 0L) {
            JDDConsole.out.printf("# calls to satCount:     %d\n", this.p_satcount);
        }
        super.showStats();
    }
}
