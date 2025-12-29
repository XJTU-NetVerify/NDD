/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import jdd.bdd.BDDNames;
import jdd.bdd.BDDPrinter;
import jdd.bdd.DoubleCache;
import jdd.bdd.NodeTable;
import jdd.bdd.OptimizedCache;
import jdd.bdd.Permutation;
import jdd.bdd.SimpleCache;
import jdd.util.Allocator;
import jdd.util.Array;
import jdd.util.Configuration;
import jdd.util.NodeName;
import jdd.util.Test;
import jdd.util.math.Prime;

public class BDD
extends NodeTable {
    /**
     * Functional interface for GC prehook callbacks.
     */
    @FunctionalInterface
    public interface GCPrehook {
        void onBeforeGC();
    }

    /**
     * Registered prehook to be called before JDD GC.
     */
    private GCPrehook gcPrehook;

    protected static final int CACHE_AND = 0;
    protected static final int CACHE_OR = 1;
    protected static final int CACHE_XOR = 2;
    protected static final int CACHE_BIIMP = 3;
    protected static final int CACHE_IMP = 4;
    protected static final int CACHE_NAND = 5;
    protected static final int CACHE_NOR = 6;
    protected static final int CACHE_RESTRICT = 7;
    protected static final int CACHE_EXISTS = 0;
    protected static final int CACHE_FORALL = 1;
    protected int num_vars;
    protected int last_sat_vars;
    protected SimpleCache op_cache;
    protected SimpleCache relprod_cache;
    protected SimpleCache not_cache;
    protected SimpleCache ite_cache;
    protected SimpleCache quant_cache;
    protected SimpleCache replace_cache;
    protected DoubleCache sat_cache;
    protected boolean[] varset_vec;
    protected boolean[] sign_vec;
    protected int[] oneSat_buffer;
    private boolean[] support_buffer;
    protected int varset_last;
    protected int quant_id;
    protected int quant_cube;
    protected int restrict_careset;
    protected boolean quant_conj;
    protected NodeName nodeNames = new BDDNames();
    private Permutation firstPermutation;
    private int[] perm_vec;
    private int perm_last;
    private int perm_var;
    private int perm_id;
    private int node_count_int;

    public BDD(int nodesize) {
        this(nodesize, 1000);
    }

    public BDD(int nodesize, int cache_size) {
        super(Prime.prevPrime(nodesize));
        this.op_cache = new OptimizedCache("OP", cache_size / Configuration.bddOpcacheDiv, 3, 2);
        this.not_cache = new OptimizedCache("NOT", cache_size / Configuration.bddNegcacheDiv, 1, 1);
        this.ite_cache = new OptimizedCache("ITE", cache_size / Configuration.bddItecacheDiv, 3, 3);
        this.quant_cache = new OptimizedCache("QUANT", cache_size / Configuration.bddQuantcacheDiv, 3, 2);
        this.relprod_cache = new OptimizedCache("REL-PROD", cache_size / 2, 3, 3);
        this.replace_cache = new OptimizedCache("REPLACE", cache_size / 3, 2, 1);
        this.sat_cache = new DoubleCache("SAT", cache_size / 8);
        this.num_vars = 0;
        this.last_sat_vars = -1;
        this.varset_last = -1;
        this.varset_vec = Allocator.allocateBooleanArray(24);
        this.sign_vec = Allocator.allocateBooleanArray(this.varset_vec.length);
        this.support_buffer = new boolean[24];
        this.firstPermutation = null;
        this.enableStackMarking();
    }

    @Override
    public void cleanup() {
        super.cleanup();
        this.varset_vec = null;
        this.sign_vec = null;
        this.oneSat_buffer = null;
        this.quant_cache = null;
        this.ite_cache = null;
        this.not_cache = null;
        this.op_cache = null;
        this.replace_cache = null;
        this.relprod_cache = null;
        this.sat_cache = null;
    }

    public final int getOne() {
        return 1;
    }

    public final int getZero() {
        return 0;
    }

    public int numberOfVariables() {
        return this.num_vars;
    }

    public int createVar() {
        int var = this.nstack.push(this.mk(this.num_vars, 0, 1));
        int nvar = this.mk(this.num_vars, 1, 0);
        this.nstack.pop();
        ++this.num_vars;
        this.saturate(var);
        this.saturate(nvar);
        this.not_cache.add(var, nvar);
        this.not_cache.add(nvar, var);
        this.nstack.grow(6 * this.num_vars + 1);
        if (this.varset_vec.length < this.num_vars) {
            this.varset_vec = Allocator.allocateBooleanArray(this.num_vars * 3);
            this.sign_vec = Allocator.allocateBooleanArray(this.num_vars * 3);
        }
        if (this.support_buffer.length < this.num_vars) {
            this.support_buffer = new boolean[this.num_vars * 3];
        }
        this.tree_depth_changed(this.num_vars);
        this.setAll(0, this.num_vars, 0, 0);
        this.setAll(1, this.num_vars, 1, 1);
        return var;
    }

    public int[] createVars(int n) {
        int[] ret = new int[n];
        for (int i = 0; i < n; ++i) {
            ret[i] = this.createVar();
        }
        return ret;
    }

    @Override
    protected void post_removal_callbak() {
        this.sat_cache.invalidate_cache();
        this.relprod_cache.free_or_grow(this);
        this.replace_cache.free_or_grow(this);
        this.quant_cache.free_or_grow(this);
        this.ite_cache.free_or_grow(this);
        this.not_cache.free_or_grow(this);
        this.op_cache.free_or_grow(this);
    }

    @Override
    protected void pre_removal_callback() {
        if (this.gcPrehook != null) {
            this.gcPrehook.onBeforeGC();
        }
    }

    /**
     * Register a prehook to be called before JDD GC.
     * This allows NDD to perform its GC before JDD GC.
     * @param prehook The prehook callback to register.
     */
    public void registerGCPrehook(GCPrehook prehook) {
        this.gcPrehook = prehook;
    }

    public int mk(int i, int l, int h) {
        if (l == h) {
            return l;
        }
        return this.add(i, l, h);
    }

    public final int cube(boolean[] v) {
        int last = 1;
        int len = Math.min(v.length, this.num_vars);
        for (int i = 0; i < len; ++i) {
            int var = len - i - 1;
            this.nstack.push(last);
            if (v[var]) {
                last = this.mk(var, 0, last);
            }
            this.nstack.pop();
        }
        return last;
    }

    public final int cube(String s) {
        int len = s.length();
        int last = 1;
        for (int i = 0; i < len; ++i) {
            int var = len - i - 1;
            this.nstack.push(last);
            if (s.charAt(var) == '1') {
                last = this.mk(var, 0, last);
            }
            this.nstack.pop();
        }
        return last;
    }

    public final int minterm(boolean[] v) {
        int last = 1;
        int len = Math.min(v.length, this.num_vars);
        for (int i = 0; i < len; ++i) {
            int var = len - i - 1;
            this.nstack.push(last);
            last = v[var] ? this.mk(var, 0, last) : this.mk(var, last, 0);
            this.nstack.pop();
        }
        return last;
    }

    public final int minterm(String s) {
        int len = s.length();
        int last = 1;
        for (int i = 0; i < len; ++i) {
            int var = len - i - 1;
            this.nstack.push(last);
            last = s.charAt(var) == '1' ? this.mk(var, 0, last) : (s.charAt(var) == '0' ? this.mk(var, last, 0) : last);
            this.nstack.pop();
        }
        return last;
    }

    public int ite(int f, int then_, int else_) {
        if (f == 0) {
            return else_;
        }
        if (f == 1) {
            return then_;
        }
        if (this.getLow(f) < 2 && this.getHigh(f) < 2 && this.getVar(f) < this.getVar(then_) && this.getVar(f) < this.getVar(else_)) {
            if (this.getLow(f) == 0) {
                return this.mk(this.getVar(f), else_, then_);
            }
            if (this.getLow(f) == 1) {
                this.mk(this.getVar(f), then_, else_);
            }
        }
        return this.ite_rec(f, then_, else_);
    }

    private final int ite_rec(int f, int g, int h) {
        if (f == 1) {
            return g;
        }
        if (f == 0) {
            return h;
        }
        if (g == h) {
            return g;
        }
        if (g == 1 && h == 0) {
            return f;
        }
        if (g == 0 && h == 1) {
            return this.not_rec(f);
        }
        if (g == 1) {
            return this.or_rec(f, h);
        }
        if (g == 0) {
            int tmp = this.nstack.push(this.not_rec(h));
            tmp = this.nor_rec(f, tmp);
            this.nstack.pop();
            return tmp;
        }
        if (h == 0) {
            return this.and_rec(f, g);
        }
        if (h == 1) {
            int tmp = this.nstack.push(this.not_rec(g));
            tmp = this.nand_rec(f, tmp);
            this.nstack.pop();
            return tmp;
        }
        if (this.ite_cache.lookup(f, g, h)) {
            return this.ite_cache.answer;
        }
        int hash = this.ite_cache.hash_value;
        int v = Math.min(this.getVar(f), Math.min(this.getVar(g), this.getVar(h)));
        int l = this.nstack.push(this.ite_rec(v == this.getVar(f) ? this.getLow(f) : f, v == this.getVar(g) ? this.getLow(g) : g, v == this.getVar(h) ? this.getLow(h) : h));
        int H = this.nstack.push(this.ite_rec(v == this.getVar(f) ? this.getHigh(f) : f, v == this.getVar(g) ? this.getHigh(g) : g, v == this.getVar(h) ? this.getHigh(h) : h));
        l = this.mk(v, l, H);
        this.nstack.drop(2);
        this.ite_cache.insert(hash, f, g, h, l);
        return l;
    }

    public int and(int u1, int u2) {
        this.nstack.push(u1);
        this.nstack.push(u2);
        int ret = this.and_rec(u1, u2);
        this.nstack.drop(2);
        return ret;
    }

    private final int and_rec(int u1, int u2) {
        int h;
        int l;
        if (u1 == u2 || u2 == 1) {
            return u1;
        }
        if (u1 == 0 || u2 == 0) {
            return 0;
        }
        if (u1 == 1) {
            return u2;
        }
        int v = this.getVar(u1);
        if (v > this.getVar(u2)) {
            v = u1;
            u1 = u2;
            u2 = v;
            v = this.getVar(u1);
        }
        if (this.op_cache.lookup(u1, u2, 0)) {
            return this.op_cache.answer;
        }
        int hash = this.op_cache.hash_value;
        if (v == this.getVar(u2)) {
            l = this.nstack.push(this.and_rec(this.getLow(u1), this.getLow(u2)));
            h = this.nstack.push(this.and_rec(this.getHigh(u1), this.getHigh(u2)));
        } else {
            l = this.nstack.push(this.and_rec(this.getLow(u1), u2));
            h = this.nstack.push(this.and_rec(this.getHigh(u1), u2));
        }
        if (l != h) {
            l = this.mk(v, l, h);
        }
        this.nstack.drop(2);
        this.op_cache.insert(hash, u1, u2, 0, l);
        return l;
    }

    public int nand(int u1, int u2) {
        this.nstack.push(u1);
        this.nstack.push(u2);
        int ret = this.nand_rec(u1, u2);
        this.nstack.drop(2);
        return ret;
    }

    private final int nand_rec(int u1, int u2) {
        int h;
        int l;
        if (u1 == 0 || u2 == 0) {
            return 1;
        }
        if (u1 == 1 || u1 == u2) {
            return this.not_rec(u2);
        }
        if (u2 == 1) {
            return this.not_rec(u1);
        }
        int v = this.getVar(u1);
        if (v > this.getVar(u2)) {
            v = u1;
            u1 = u2;
            u2 = v;
            v = this.getVar(u1);
        }
        if (this.op_cache.lookup(u1, u2, 5)) {
            return this.op_cache.answer;
        }
        int hash = this.op_cache.hash_value;
        if (v == this.getVar(u2)) {
            l = this.nstack.push(this.nand_rec(this.getLow(u1), this.getLow(u2)));
            h = this.nstack.push(this.nand_rec(this.getHigh(u1), this.getHigh(u2)));
        } else {
            l = this.nstack.push(this.nand_rec(this.getLow(u1), u2));
            h = this.nstack.push(this.nand_rec(this.getHigh(u1), u2));
        }
        if (l != h) {
            l = this.mk(v, l, h);
        }
        this.op_cache.insert(hash, u1, u2, 5, l);
        this.nstack.drop(2);
        return l;
    }

    public int or(int u1, int u2) {
        this.nstack.push(u1);
        this.nstack.push(u2);
        int ret = this.or_rec(u1, u2);
        this.nstack.drop(2);
        return ret;
    }

    private final int or_rec(int u1, int u2) {
        int h;
        int l;
        if (u1 == 1 || u2 == 1) {
            return 1;
        }
        if (u1 == 0 || u1 == u2) {
            return u2;
        }
        if (u2 == 0) {
            return u1;
        }
        int v = this.getVar(u1);
        if (v > this.getVar(u2)) {
            v = u1;
            u1 = u2;
            u2 = v;
            v = this.getVar(u1);
        }
        if (this.op_cache.lookup(u1, u2, 1)) {
            return this.op_cache.answer;
        }
        int hash = this.op_cache.hash_value;
        if (v == this.getVar(u2)) {
            l = this.nstack.push(this.or_rec(this.getLow(u1), this.getLow(u2)));
            h = this.nstack.push(this.or_rec(this.getHigh(u1), this.getHigh(u2)));
        } else {
            l = this.nstack.push(this.or_rec(this.getLow(u1), u2));
            h = this.nstack.push(this.or_rec(this.getHigh(u1), u2));
        }
        if (l != h) {
            l = this.mk(v, l, h);
        }
        this.op_cache.insert(hash, u1, u2, 1, l);
        this.nstack.drop(2);
        return l;
    }

    public int nor(int u1, int u2) {
        this.nstack.push(u1);
        this.nstack.push(u2);
        int ret = this.nor_rec(u1, u2);
        this.nstack.drop(2);
        return ret;
    }

    private final int nor_rec(int u1, int u2) {
        int h;
        int l;
        if (u1 == 1 || u2 == 1) {
            return 0;
        }
        if (u1 == 0 || u1 == u2) {
            return this.not_rec(u2);
        }
        if (u2 == 0) {
            return this.not_rec(u1);
        }
        int v = this.getVar(u1);
        if (v > this.getVar(u2)) {
            v = u1;
            u1 = u2;
            u2 = v;
            v = this.getVar(u1);
        }
        if (this.op_cache.lookup(u1, u2, 6)) {
            return this.op_cache.answer;
        }
        int hash = this.op_cache.hash_value;
        if (v == this.getVar(u2)) {
            l = this.nstack.push(this.nor_rec(this.getLow(u1), this.getLow(u2)));
            h = this.nstack.push(this.nor_rec(this.getHigh(u1), this.getHigh(u2)));
        } else {
            l = this.nstack.push(this.nor_rec(this.getLow(u1), u2));
            h = this.nstack.push(this.nor_rec(this.getHigh(u1), u2));
        }
        if (l != h) {
            l = this.mk(v, l, h);
        }
        this.op_cache.insert(hash, u1, u2, 6, l);
        this.nstack.drop(2);
        return l;
    }

    public int xor(int u1, int u2) {
        this.nstack.push(u1);
        this.nstack.push(u2);
        int ret = this.xor_rec(u1, u2);
        this.nstack.drop(2);
        return ret;
    }

    private final int xor_rec(int u1, int u2) {
        int h;
        int l;
        if (u1 == u2) {
            return 0;
        }
        if (u1 == 0) {
            return u2;
        }
        if (u2 == 0) {
            return u1;
        }
        if (u1 == 1) {
            return this.not_rec(u2);
        }
        if (u2 == 1) {
            return this.not_rec(u1);
        }
        int v = this.getVar(u1);
        if (v > this.getVar(u2)) {
            v = u1;
            u1 = u2;
            u2 = v;
            v = this.getVar(u1);
        }
        if (this.op_cache.lookup(u1, u2, 2)) {
            return this.op_cache.answer;
        }
        int hash = this.op_cache.hash_value;
        if (v == this.getVar(u2)) {
            l = this.nstack.push(this.xor_rec(this.getLow(u1), this.getLow(u2)));
            h = this.nstack.push(this.xor_rec(this.getHigh(u1), this.getHigh(u2)));
        } else {
            l = this.nstack.push(this.xor_rec(this.getLow(u1), u2));
            h = this.nstack.push(this.xor_rec(this.getHigh(u1), u2));
        }
        if (l != h) {
            l = this.mk(v, l, h);
        }
        this.op_cache.insert(hash, u1, u2, 2, l);
        this.nstack.drop(2);
        return l;
    }

    public int biimp(int u1, int u2) {
        this.nstack.push(u1);
        this.nstack.push(u2);
        int ret = this.biimp_rec(u1, u2);
        this.nstack.drop(2);
        return ret;
    }

    private final int biimp_rec(int u1, int u2) {
        int h;
        int l;
        if (u1 == u2) {
            return 1;
        }
        if (u1 == 0) {
            return this.not_rec(u2);
        }
        if (u1 == 1) {
            return u2;
        }
        if (u2 == 0) {
            return this.not_rec(u1);
        }
        if (u2 == 1) {
            return u1;
        }
        int v = this.getVar(u1);
        if (v > this.getVar(u2)) {
            v = u1;
            u1 = u2;
            u2 = v;
            v = this.getVar(u1);
        }
        if (this.op_cache.lookup(u1, u2, 3)) {
            return this.op_cache.answer;
        }
        int hash = this.op_cache.hash_value;
        if (v == this.getVar(u2)) {
            l = this.nstack.push(this.biimp_rec(this.getLow(u1), this.getLow(u2)));
            h = this.nstack.push(this.biimp_rec(this.getHigh(u1), this.getHigh(u2)));
        } else {
            l = this.nstack.push(this.biimp_rec(this.getLow(u1), u2));
            h = this.nstack.push(this.biimp_rec(this.getHigh(u1), u2));
        }
        if (l != h) {
            l = this.mk(v, l, h);
        }
        this.op_cache.insert(hash, u1, u2, 3, l);
        this.nstack.drop(2);
        return l;
    }

    public int imp(int u1, int u2) {
        this.nstack.push(u1);
        this.nstack.push(u2);
        int ret = this.imp_rec(u1, u2);
        this.nstack.drop(2);
        return ret;
    }

    private final int imp_rec(int u1, int u2) {
        int h;
        int l;
        if (u1 == 0 || u2 == 1 || u1 == u2) {
            return 1;
        }
        if (u1 == 1) {
            return u2;
        }
        if (u2 == 0) {
            return this.not_rec(u1);
        }
        if (this.op_cache.lookup(u1, u2, 4)) {
            return this.op_cache.answer;
        }
        int hash = this.op_cache.hash_value;
        int v = this.getVar(u1);
        if (this.getVar(u1) == this.getVar(u2)) {
            l = this.nstack.push(this.imp_rec(this.getLow(u1), this.getLow(u2)));
            h = this.nstack.push(this.imp_rec(this.getHigh(u1), this.getHigh(u2)));
        } else if (this.getVar(u1) < this.getVar(u2)) {
            l = this.nstack.push(this.imp_rec(this.getLow(u1), u2));
            h = this.nstack.push(this.imp_rec(this.getHigh(u1), u2));
        } else {
            l = this.nstack.push(this.imp_rec(u1, this.getLow(u2)));
            h = this.nstack.push(this.imp_rec(u1, this.getHigh(u2)));
            v = this.getVar(u2);
        }
        if (l != h) {
            l = this.mk(v, l, h);
        }
        this.op_cache.insert(hash, u1, u2, 4, l);
        this.nstack.drop(2);
        return l;
    }

    public int not(int u1) {
        this.nstack.push(u1);
        int ret = this.not_rec(u1);
        this.nstack.pop();
        return ret;
    }

    private final int not_rec(int bdd) {
        int h;
        if (bdd < 2) {
            return bdd ^ 1;
        }
        if (this.not_cache.lookup(bdd)) {
            return this.not_cache.answer;
        }
        int hash = this.not_cache.hash_value;
        int l = this.nstack.push(this.not_rec(this.getLow(bdd)));
        if (l != (h = this.nstack.push(this.not_rec(this.getHigh(bdd))))) {
            l = this.mk(this.getVar(bdd), l, h);
        }
        this.nstack.drop(2);
        this.not_cache.insert(hash, bdd, l);
        return l;
    }

    private void varset(int bdd) {
        Test.check(bdd > 1, "BAD varset");
        int i = this.num_vars;
        while (i != 0) {
            this.varset_vec[--i] = false;
        }
        while (bdd > 1) {
            this.varset_last = this.getVar(bdd);
            this.varset_vec[this.varset_last] = true;
            bdd = this.getHigh(bdd);
        }
    }

    private void varset_signed(int bdd) {
        Test.check(bdd > 1, "BAD varset");
        for (int i = 0; i < this.num_vars; ++i) {
            this.varset_vec[i] = false;
        }
        while (bdd > 1) {
            this.varset_last = this.getVar(bdd);
            this.varset_vec[this.varset_last] = true;
            this.sign_vec[this.varset_last] = this.getLow(bdd) == 0;
            bdd = this.getHigh(bdd);
        }
    }

    public int exists(int bdd, int cube) {
        if (cube == 1) {
            return bdd;
        }
        Test.check(cube != 0, "Empty cube");
        this.quant_conj = false;
        this.quant_id = 0;
        this.quant_cube = cube;
        this.varset(cube);
        return this.quant_rec(bdd);
    }

    public int forall(int bdd, int cube) {
        if (cube == 1) {
            return bdd;
        }
        Test.check(cube != 0, "Empty cube");
        this.quant_conj = true;
        this.quant_id = 1;
        this.quant_cube = cube;
        this.varset(cube);
        return this.quant_rec(bdd);
    }

    private final int quant_rec(int bdd) {
        int var = this.getVar(bdd);
        if (bdd < 2 || var > this.varset_last) {
            return bdd;
        }
        if (this.quant_cache.lookup(bdd, this.quant_cube, this.quant_id)) {
            return this.quant_cache.answer;
        }
        int hash = this.quant_cache.hash_value;
        int l = 0;
        if (this.varset_vec[var]) {
            l = this.getLow(bdd);
            int h = this.getHigh(bdd);
            if (this.getVar(h) > this.getVar(l)) {
                l = h;
                h = this.getLow(bdd);
            }
            l = this.quant_rec(l);
            if (!(this.quant_conj && l == 0 || !this.quant_conj && l == 1)) {
                this.nstack.push(l);
                h = this.nstack.push(this.quant_rec(h));
                l = this.quant_conj ? this.and_rec(l, h) : this.or_rec(l, h);
                this.nstack.drop(2);
            }
        } else {
            l = this.nstack.push(this.quant_rec(this.getLow(bdd)));
            int h = this.nstack.push(this.quant_rec(this.getHigh(bdd)));
            l = this.mk(var, l, h);
            this.nstack.drop(2);
        }
        this.quant_cache.insert(hash, bdd, this.quant_cube, this.quant_id, l);
        return l;
    }

    public int relProd(int u1, int u2, int c) {
        if (c < 2) {
            return this.and_rec(u1, u2);
        }
        this.varset(c);
        this.quant_conj = false;
        this.quant_id = 0;
        this.quant_cube = c;
        return this.relProd_rec(u1, u2);
    }

    private final int relProd_rec(int u1, int u2) {
        if (u1 == 0 || u2 == 0) {
            return 0;
        }
        if (u1 == u2 || u2 == 1) {
            return this.quant_rec(u1);
        }
        if (u1 == 1) {
            return this.quant_rec(u2);
        }
        if (this.getVar(u1) > this.varset_last && this.getVar(u2) > this.varset_last) {
            return this.and_rec(u1, u2);
        }
        if (this.getVar(u2) < this.getVar(u1)) {
            int tmp = u1;
            u1 = u2;
            u2 = tmp;
        }
        if (this.relprod_cache.lookup(u1, u2, this.quant_cube)) {
            return this.relprod_cache.answer;
        }
        int hash = this.relprod_cache.hash_value;
        int v = this.getVar(u1);
        int l = this.relProd_rec(this.getLow(u1), v == this.getVar(u2) ? this.getLow(u2) : u2);
        if (this.varset_vec[v]) {
            if (l == 1) {
                return l;
            }
            if (l == this.getHigh(u1)) {
                return l;
            }
            if (l == this.getHigh(u2) && this.getVar(u2) == v) {
                return l;
            }
        }
        this.nstack.push(l);
        int h = this.nstack.push(this.relProd_rec(this.getHigh(u1), v == this.getVar(u2) ? this.getHigh(u2) : u2));
        if (l != h) {
            l = this.varset_vec[v] ? this.or_rec(l, h) : this.mk(v, l, h);
        }
        this.relprod_cache.insert(hash, u1, u2, this.quant_cube, l);
        this.nstack.drop(2);
        return l;
    }

    public Permutation createPermutation(int[] cube_from, int[] cube_to) {
        Permutation perm = Permutation.findPermutation(this.firstPermutation, cube_from, cube_to);
        if (perm == null) {
            perm = new Permutation(cube_from, cube_to, this);
            perm.next = this.firstPermutation;
            this.firstPermutation = perm;
        }
        return perm;
    }

    public int replace(int bdd, Permutation perm) {
        this.perm_vec = perm.perm;
        this.perm_last = perm.last;
        this.perm_id = perm.id;
        int ret = this.replace_rec(bdd);
        this.perm_vec = null;
        return ret;
    }

    private final int replace_rec(int bdd) {
        if (bdd < 2 || this.getVar(bdd) > this.perm_last) {
            return bdd;
        }
        if (this.replace_cache.lookup(bdd, this.perm_id)) {
            return this.replace_cache.answer;
        }
        int hash = this.replace_cache.hash_value;
        int l = this.nstack.push(this.replace_rec(this.getLow(bdd)));
        int h = this.nstack.push(this.replace_rec(this.getHigh(bdd)));
        this.perm_var = this.perm_vec[this.getVar(bdd)];
        l = this.mkAndOrder(l, h);
        this.nstack.drop(2);
        this.replace_cache.insert(hash, bdd, this.perm_id, l);
        return l;
    }

    private final int mkAndOrder(int l, int h) {
        int y;
        int x;
        int vl = this.getVar(l);
        int vh = this.getVar(h);
        if (this.perm_var < vl && this.perm_var < vh) {
            return this.mk(this.perm_var, l, h);
        }
        Test.check(this.perm_var != vl && this.perm_var != vh, "Replacing to a variable already in the BDD");
        int v = vl;
        if (vl == vh) {
            x = this.nstack.push(this.mkAndOrder(this.getLow(l), this.getLow(h)));
            y = this.nstack.push(this.mkAndOrder(this.getHigh(l), this.getHigh(h)));
        } else if (vl < vh) {
            x = this.nstack.push(this.mkAndOrder(this.getLow(l), h));
            y = this.nstack.push(this.mkAndOrder(this.getHigh(l), h));
        } else {
            x = this.nstack.push(this.mkAndOrder(l, this.getLow(h)));
            y = this.nstack.push(this.mkAndOrder(l, this.getHigh(h)));
            v = vh;
        }
        x = this.mk(v, x, y);
        this.nstack.drop(2);
        return x;
    }

    public int restrict(int u, int v) {
        if (v == 1) {
            return u;
        }
        this.varset_signed(v);
        this.restrict_careset = v;
        return this.restrict_rec(u);
    }

    private int restrict_rec(int u) {
        if (u < 2 || this.getVar(u) > this.varset_last) {
            return u;
        }
        if (this.op_cache.lookup(u, this.restrict_careset, 7)) {
            return this.op_cache.answer;
        }
        int hash = this.op_cache.hash_value;
        int ret = 0;
        if (this.varset_vec[this.getVar(u)]) {
            ret = this.restrict_rec(this.sign_vec[this.getVar(u)] ? this.getHigh(u) : this.getLow(u));
        } else {
            int l = this.nstack.push(this.restrict_rec(this.getLow(u)));
            int h = this.nstack.push(this.restrict_rec(this.getHigh(u)));
            ret = this.mk(this.getVar(u), l, h);
            this.nstack.drop(2);
        }
        this.op_cache.insert(hash, u, this.restrict_careset, 7, ret);
        return ret;
    }

    public int simplify(int d, int u) {
        if (d == 0) {
            return 0;
        }
        if (u < 2) {
            return u;
        }
        if (d == 1) {
            int l = this.nstack.push(this.simplify(d, this.getLow(u)));
            int h = this.nstack.push(this.simplify(d, this.getHigh(u)));
            h = this.mk(this.getVar(u), l, h);
            this.nstack.drop(2);
            return h;
        }
        if (this.getVar(d) == this.getVar(u)) {
            if (this.getLow(d) == 0) {
                return this.simplify(this.getHigh(d), this.getHigh(u));
            }
            if (this.getHigh(d) == 0) {
                return this.simplify(this.getLow(d), this.getLow(u));
            }
            int l = this.nstack.push(this.simplify(this.getLow(d), this.getLow(u)));
            int h = this.nstack.push(this.simplify(this.getHigh(d), this.getHigh(u)));
            h = this.mk(this.getVar(u), l, h);
            this.nstack.drop(2);
            return h;
        }
        if (this.getVar(d) < this.getVar(u)) {
            int l = this.nstack.push(this.simplify(this.getLow(d), u));
            int h = this.nstack.push(this.simplify(this.getHigh(d), u));
            h = this.mk(this.getVar(d), l, h);
            this.nstack.drop(2);
            return h;
        }
        int l = this.nstack.push(this.simplify(d, this.getLow(u)));
        int h = this.nstack.push(this.simplify(d, this.getHigh(u)));
        h = this.mk(this.getVar(u), l, h);
        this.nstack.drop(2);
        return h;
    }

    public boolean isVariable(int bdd) {
        if (bdd < 2 || bdd > this.table_size || !this.isValid(bdd)) {
            return false;
        }
        return this.getLow(bdd) == 0 && this.getHigh(bdd) == 1;
    }

    public double satCount(int bdd) {
        if (this.last_sat_vars != -1 && this.last_sat_vars != this.num_vars) {
            this.sat_cache.invalidate_cache();
        }
        this.last_sat_vars = this.num_vars;
        return Math.pow(2.0, this.getVar(bdd)) * this.satCount_rec(bdd);
    }

    protected double satCount_rec(int bdd) {
        if (bdd < 2) {
            return bdd;
        }
        if (this.sat_cache.lookup(bdd)) {
            return this.sat_cache.answer;
        }
        int hash = this.sat_cache.hash_value;
        int low = this.getLow(bdd);
        int high = this.getHigh(bdd);
        double ret = this.satCount_rec(low) * Math.pow(2.0, this.getVar(low) - this.getVar(bdd) - 1) + this.satCount_rec(high) * Math.pow(2.0, this.getVar(high) - this.getVar(bdd) - 1);
        this.sat_cache.insert(hash, bdd, ret);
        return ret;
    }

    public int nodeCount(int bdd) {
        this.node_count_int = 0;
        this.nodeCount_mark(bdd);
        this.unmark_tree(bdd);
        return this.node_count_int;
    }

    private final void nodeCount_mark(int bdd) {
        if (bdd < 2) {
            return;
        }
        if (this.isNodeMarked(bdd)) {
            return;
        }
        this.mark_node(bdd);
        ++this.node_count_int;
        this.nodeCount_mark(this.getLow(bdd));
        this.nodeCount_mark(this.getHigh(bdd));
    }

    public final int quasiReducedNodeCount(int bdd) {
        if (bdd < 2) {
            return 0;
        }
        return 1 + this.quasiReducedNodeCount(this.getLow(bdd)) + this.quasiReducedNodeCount(this.getHigh(bdd));
    }

    public BitSet minAssignment(int bdd) {
        BitSet set = new BitSet(this.num_vars);
        this.minAssignment_rec(set, bdd);
        return set;
    }

    private void minAssignment_rec(BitSet set, int bdd) {
        boolean useHi;
        if (bdd < 2) {
            return;
        }
        int lo = this.getLow(bdd);
        int hi = this.getHigh(bdd);
        boolean bl = useHi = lo == 0;
        if (useHi) {
            set.set(this.getVar(bdd));
            this.minAssignment_rec(set, hi);
        } else {
            this.minAssignment_rec(set, lo);
        }
    }

    public int oneSat(int bdd) {
        if (bdd < 2) {
            return bdd;
        }
        if (this.getLow(bdd) == 0) {
            int high = this.nstack.push(this.oneSat(this.getHigh(bdd)));
            int u = this.mk(this.getVar(bdd), 0, high);
            this.nstack.pop();
            return u;
        }
        int low = this.nstack.push(this.oneSat(this.getLow(bdd)));
        int u = this.mk(this.getVar(bdd), low, 0);
        this.nstack.pop();
        return u;
    }

    public int[] oneSat(int bdd, int[] buffer) {
        if (buffer == null) {
            buffer = new int[this.num_vars];
        }
        this.oneSat_buffer = buffer;
        Array.set(buffer, -1);
        this.oneSat_rec(bdd);
        this.oneSat_buffer = null;
        return buffer;
    }

    protected void oneSat_rec(int bdd) {
        if (bdd < 2) {
            return;
        }
        if (this.getLow(bdd) == 0) {
            this.oneSat_buffer[this.getVar((int)bdd)] = 1;
            this.oneSat_rec(this.getHigh(bdd));
        } else {
            this.oneSat_buffer[this.getVar((int)bdd)] = 0;
            this.oneSat_rec(this.getLow(bdd));
        }
    }

    public int support(int bdd) {
        Array.set(this.support_buffer, false);
        this.support_rec(bdd);
        this.unmark_tree(bdd);
        int ret = this.cube(this.support_buffer);
        return ret;
    }

    private final void support_rec(int bdd) {
        if (bdd < 2) {
            return;
        }
        if (this.isNodeMarked(bdd)) {
            return;
        }
        this.support_buffer[this.getVar((int)bdd)] = true;
        this.mark_node(bdd);
        this.support_rec(this.getLow(bdd));
        this.support_rec(this.getHigh(bdd));
    }

    public boolean member(int bdd, boolean[] minterm) {
        while (bdd >= 2) {
            bdd = minterm[this.getVar(bdd)] ? this.getHigh(bdd) : this.getLow(bdd);
        }
        return bdd != 0;
    }

    public int orTo(int bdd1, int bdd2) {
        int tmp = this.ref(this.or(bdd1, bdd2));
        this.deref(bdd1);
        return tmp;
    }

    public int andTo(int bdd1, int bdd2) {
        int tmp = this.ref(this.and(bdd1, bdd2));
        this.deref(bdd1);
        return tmp;
    }

    @Override
    public void showStats() {
        super.showStats();
        this.op_cache.showStats();
        this.not_cache.showStats();
        this.quant_cache.showStats();
        this.replace_cache.showStats();
        this.ite_cache.showStats();
        this.relprod_cache.showStats();
        this.sat_cache.showStats();
    }

    @Override
    public long getMemoryUsage() {
        long ret = super.getMemoryUsage();
        if (this.varset_vec != null) {
            ret += (long)(this.varset_vec.length * 4);
        }
        if (this.oneSat_buffer != null) {
            ret += (long)(this.oneSat_buffer.length * 4);
        }
        if (this.support_buffer != null) {
            ret += (long)(this.support_buffer.length * 1);
        }
        if (this.op_cache != null) {
            ret += this.op_cache.getMemoryUsage();
        }
        if (this.relprod_cache != null) {
            ret += this.relprod_cache.getMemoryUsage();
        }
        if (this.not_cache != null) {
            ret += this.not_cache.getMemoryUsage();
        }
        if (this.ite_cache != null) {
            ret += this.ite_cache.getMemoryUsage();
        }
        if (this.quant_cache != null) {
            ret += this.quant_cache.getMemoryUsage();
        }
        if (this.sat_cache != null) {
            ret += this.sat_cache.getMemoryUsage();
        }
        if (this.replace_cache != null) {
            ret += this.replace_cache.getMemoryUsage();
        }
        Permutation tmp = this.firstPermutation;
        while (tmp != null) {
            ret += tmp.getMemoryUsage();
            tmp = tmp.next;
        }
        return ret;
    }

    public int toZero(int bdd) {
        HashSet<Integer> vi = new HashSet<Integer>();
        HashMap<Integer, Integer> dis = new HashMap<Integer, Integer>();
        dis.put(bdd, 0);
        return this.toZero_rec(bdd, vi, dis, 0);
    }

    private int toZero_rec(int bdd, Set<Integer> vi, Map<Integer, Integer> dis, int d) {
        if (bdd == 0) {
            return d;
        }
        vi.add(bdd);
        int low = this.getLow(bdd);
        int high = this.getHigh(bdd);
        if (!dis.containsKey(low) || dis.get(low) > d + 1) {
            dis.put(low, d + 1);
        }
        if (!dis.containsKey(high) || dis.get(high) > d) {
            dis.put(high, d);
        }
        int nxtV = 0;
        int nxtD = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> entry : dis.entrySet()) {
            if (vi.contains(entry.getKey()) || entry.getValue() >= nxtD) continue;
            nxtD = entry.getValue();
            nxtV = entry.getKey();
        }
        return this.toZero_rec(nxtV, vi, dis, nxtD);
    }

    public void print(int bdd) {
        BDDPrinter.print(bdd, this);
    }

    public void printDot(String fil, int bdd) {
        BDDPrinter.printDot(fil, bdd, this, this.nodeNames);
    }

    public void printSet(int bdd) {
        BDDPrinter.printSet(bdd, this.num_vars, this, null);
    }

    public void printCubes(int bdd) {
        BDDPrinter.printSet(bdd, this.num_vars, this, this.nodeNames);
    }

    public void setNodeNames(NodeName nn) {
        this.nodeNames = nn;
    }
}

