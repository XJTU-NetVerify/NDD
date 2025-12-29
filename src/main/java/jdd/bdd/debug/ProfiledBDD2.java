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

public class ProfiledBDD2
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
    private long p_permutation = 0L;
    private long t_and = 0L;
    private long t_or = 0L;
    private long t_xor = 0L;
    private long t_biimp = 0L;
    private long t_imp = 0L;
    private long t_not = 0L;
    private long t_nand;
    private long t_nor;
    private long t_replace = 0L;
    private long t_exists = 0L;
    private long t_forall = 0L;
    private long t_relprod = 0L;
    private long t_support = 0L;
    private long t_restrict = 0L;
    private long t_simplify = 0L;
    private long t_ite = 0L;
    private long t_satcount = 0L;
    private long t_permutation = 0L;

    public ProfiledBDD2(int nodesize) {
        this(nodesize, 1000);
    }

    public ProfiledBDD2(int nodesize, int cache_size) {
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
        long t = System.currentTimeMillis();
        ++this.p_and;
        int ret = super.and(a, b);
        this.t_and += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int or(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_or;
        int ret = super.or(a, b);
        this.t_or += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int xor(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_xor;
        int ret = super.xor(a, b);
        this.t_xor += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int biimp(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_biimp;
        int ret = super.biimp(a, b);
        this.t_biimp += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int imp(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_imp;
        int ret = super.imp(a, b);
        this.t_imp += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int nor(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_nor;
        int ret = super.nor(a, b);
        this.t_nor += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int nand(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_nand;
        int ret = super.nand(a, b);
        this.t_nand += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int not(int a) {
        long t = System.currentTimeMillis();
        ++this.p_not;
        int ret = super.not(a);
        this.t_not += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int replace(int a, Permutation b) {
        long t = System.currentTimeMillis();
        ++this.p_replace;
        int ret = super.replace(a, b);
        this.t_replace += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int exists(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_exists;
        int ret = super.exists(a, b);
        this.t_exists += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int forall(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_forall;
        int ret = super.forall(a, b);
        this.t_forall += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int relProd(int a, int b, int c) {
        long t = System.currentTimeMillis();
        ++this.p_relprod;
        int ret = super.relProd(a, b, c);
        this.t_relprod += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int support(int a) {
        long t = System.currentTimeMillis();
        ++this.p_support;
        int ret = super.support(a);
        this.t_support += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int restrict(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_restrict;
        int ret = super.restrict(a, b);
        this.t_restrict += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int simplify(int a, int b) {
        long t = System.currentTimeMillis();
        ++this.p_simplify;
        int ret = super.simplify(a, b);
        this.t_simplify += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public int ite(int a, int b, int c) {
        long t = System.currentTimeMillis();
        ++this.p_ite;
        int ret = super.ite(a, b, c);
        this.t_ite += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public double satCount(int a) {
        long t = System.currentTimeMillis();
        ++this.p_satcount;
        double ret = super.satCount(a);
        this.t_satcount += System.currentTimeMillis() - t;
        return ret;
    }

    @Override
    public Permutation createPermutation(int[] cube_from, int[] cube_to) {
        long t = System.currentTimeMillis();
        ++this.p_permutation;
        Permutation ret = super.createPermutation(cube_from, cube_to);
        this.t_permutation += System.currentTimeMillis() - t;
        return ret;
    }

    public void report(String what, long count, long time) {
        if (count > 0L) {
            StringBuffer sb = new StringBuffer(256);
            sb.append("calls to " + what);
            while (sb.length() < 28) {
                sb.append(' ');
            }
            sb.append(" : " + count + " times");
            while (sb.length() < 48) {
                sb.append(' ');
            }
            sb.append(" " + time + " [ms]");
            JDDConsole.out.printf("%s\n", sb.toString());
        }
    }

    @Override
    public void showStats() {
        this.report("AND", this.p_and, this.t_and);
        this.report("OR", this.p_or, this.t_or);
        this.report("NAND", this.p_nand, this.t_nand);
        this.report("NOR", this.p_nor, this.t_nor);
        this.report("XOR", this.p_xor, this.t_xor);
        this.report("BI-IMP", this.p_biimp, this.t_biimp);
        this.report("IMP", this.p_imp, this.t_imp);
        this.report("NOT", this.p_not, this.t_not);
        this.report("ITE", this.p_ite, this.t_ite);
        this.report("REPLACE", this.p_replace, this.t_replace);
        this.report("EXISTS", this.p_exists, this.t_exists);
        this.report("FORALL", this.p_forall, this.t_forall);
        this.report("REL-PROD", this.p_relprod, this.t_relprod);
        this.report("SUPPORT", this.p_support, this.t_support);
        this.report("RESTRICT", this.p_restrict, this.t_restrict);
        this.report("SIMPLIFY", this.p_simplify, this.t_simplify);
        this.report("SAT-COUNT", this.p_satcount, this.t_satcount);
        this.report("CREATE-PERMUTATION", this.p_permutation, this.t_permutation);
        super.showStats();
    }
}
