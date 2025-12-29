/*
 * Decompiled with CFR 0.152.
 */
package jdd.zdd;

import java.util.Collection;
import java.util.StringTokenizer;
import jdd.bdd.CacheBase;
import jdd.bdd.NodeTable;
import jdd.bdd.OptimizedCache;
import jdd.bdd.debug.BDDDebugFrame;
import jdd.bdd.debug.BDDDebuger;
import jdd.util.Array;
import jdd.util.Configuration;
import jdd.util.NodeName;
import jdd.util.Options;
import jdd.zdd.ZDDNames;
import jdd.zdd.ZDDPrinter;

public class ZDD
extends NodeTable {
    private static final int CACHE_SUBSET0 = 0;
    private static final int CACHE_SUBSET1 = 1;
    private static final int CACHE_CHANGE = 2;
    private static final int CACHE_UNION = 3;
    private static final int CACHE_INTERSECT = 4;
    private static final int CACHE_DIFF = 5;
    protected int num_vars;
    private int node_count_int;
    private OptimizedCache unary_cache;
    private OptimizedCache binary_cache;
    protected NodeName nodeNames = new ZDDNames();

    public ZDD(int nodesize) {
        this(nodesize, 1000);
    }

    public ZDD(int nodesize, int cachesize) {
        super(nodesize);
        this.unary_cache = new OptimizedCache("unary", cachesize / Configuration.zddUnaryCacheDiv, 3, 1);
        this.binary_cache = new OptimizedCache("binary", cachesize / Configuration.zddBinaryCacheDiv, 3, 2);
        if (Options.profile_cache) {
            new BDDDebugFrame(this);
        }
        this.enableStackMarking();
    }

    @Override
    public void cleanup() {
        super.cleanup();
        this.binary_cache = null;
        this.unary_cache = null;
    }

    @Override
    public Collection<CacheBase> addDebugger(BDDDebuger d) {
        Collection<CacheBase> v = super.addDebugger(d);
        v.add(this.unary_cache);
        v.add(this.binary_cache);
        return v;
    }

    @Override
    protected void post_removal_callbak() {
        this.binary_cache.free_or_grow(this);
        this.unary_cache.free_or_grow(this);
    }

    protected final int mk(int i, int l, int h) {
        if (h == 0) {
            return l;
        }
        return this.add(i, l, h);
    }

    public int createVar() {
        int ret = this.num_vars++;
        this.nstack.grow(5 * this.num_vars + 3);
        this.tree_depth_changed(this.num_vars);
        return ret;
    }

    public final int empty() {
        return 0;
    }

    public final int base() {
        return 1;
    }

    public final int single(int var) {
        return this.mk(var, 0, 1);
    }

    public final int universe() {
        int last = 1;
        for (int i = 0; i < this.num_vars; ++i) {
            this.nstack.push(last);
            last = this.mk(i, last, last);
            this.nstack.pop();
        }
        return last;
    }

    public final int cube(int v) {
        return this.mk(v, 0, 1);
    }

    public final int cube(boolean[] v) {
        int last = 1;
        for (int i = 0; i < v.length; ++i) {
            if (!v[i]) continue;
            this.nstack.push(last);
            last = this.mk(i, 0, last);
            this.nstack.pop();
        }
        return last;
    }

    public final int cube(String s) {
        int len = s.length();
        int last = 1;
        for (int i = 0; i < len; ++i) {
            if (s.charAt(len - i - 1) != '1') continue;
            this.nstack.push(last);
            last = this.mk(i, 0, last);
            this.nstack.pop();
        }
        return last;
    }

    public int cubes_union(String s) {
        return this.do_cubes_op(s, true);
    }

    public int cubes_intersect(String s) {
        return this.do_cubes_op(s, false);
    }

    private int do_cubes_op(String s, boolean do_union) {
        StringTokenizer st = new StringTokenizer(s, " \t\n,;");
        int ret = -1;
        while (st.hasMoreTokens()) {
            String str = st.nextToken();
            int c = this.cube(str);
            if (ret == -1) {
                ret = c;
                continue;
            }
            this.ref(ret);
            this.ref(c);
            int tmp1 = do_union ? this.union(ret, c) : this.intersect(ret, c);
            this.deref(ret);
            this.deref(c);
            ret = tmp1;
        }
        return ret;
    }

    public int subsets(boolean[] v) {
        int last = 1;
        for (int i = 0; i < v.length; ++i) {
            if (!v[i]) continue;
            this.nstack.push(last);
            last = this.mk(i, last, last);
            this.nstack.pop();
        }
        return last;
    }

    public final int subset1(int zdd, int var) {
        if (var < 0 || var >= this.num_vars) {
            return -1;
        }
        if (this.getVar(zdd) < var) {
            return 0;
        }
        if (this.getVar(zdd) == var) {
            return this.getHigh(zdd);
        }
        if (this.unary_cache.lookup(zdd, var, 1)) {
            return this.unary_cache.answer;
        }
        int hash = this.unary_cache.hash_value;
        int l = this.nstack.push(this.subset1(this.getLow(zdd), var));
        int h = this.nstack.push(this.subset1(this.getHigh(zdd), var));
        l = this.mk(this.getVar(zdd), l, h);
        this.nstack.drop(2);
        this.unary_cache.insert(hash, zdd, var, 1, l);
        return l;
    }

    public final int subset0(int zdd, int var) {
        if (var < 0 || var >= this.num_vars) {
            return -1;
        }
        if (this.getVar(zdd) < var) {
            return zdd;
        }
        if (this.getVar(zdd) == var) {
            return this.getLow(zdd);
        }
        if (this.unary_cache.lookup(zdd, var, 0)) {
            return this.unary_cache.answer;
        }
        int hash = this.unary_cache.hash_value;
        int l = this.nstack.push(this.subset0(this.getLow(zdd), var));
        int h = this.nstack.push(this.subset0(this.getHigh(zdd), var));
        l = this.mk(this.getVar(zdd), l, h);
        this.nstack.drop(2);
        this.unary_cache.insert(hash, zdd, var, 0, l);
        return l;
    }

    public final int change(int zdd, int var) {
        if (var < 0 || var >= this.num_vars) {
            return -1;
        }
        if (this.getVar(zdd) < var) {
            return this.mk(var, 0, zdd);
        }
        if (this.getVar(zdd) == var) {
            return this.mk(var, this.getHigh(zdd), this.getLow(zdd));
        }
        if (this.unary_cache.lookup(zdd, var, 2)) {
            return this.unary_cache.answer;
        }
        int hash = this.unary_cache.hash_value;
        int l = this.nstack.push(this.change(this.getLow(zdd), var));
        int h = this.nstack.push(this.change(this.getHigh(zdd), var));
        l = this.mk(this.getVar(zdd), l, h);
        this.nstack.drop(2);
        this.unary_cache.insert(hash, zdd, var, 2, l);
        return l;
    }

    public final int union(int p, int q) {
        int l;
        if (this.getVar(p) > this.getVar(q)) {
            return this.union(q, p);
        }
        if (p == 0) {
            return q;
        }
        if (q == 0 || q == p) {
            return p;
        }
        if (this.binary_cache.lookup(p, q, 3)) {
            return this.binary_cache.answer;
        }
        int hash = this.binary_cache.hash_value;
        if (this.getVar(p) < this.getVar(q)) {
            l = this.nstack.push(this.union(p, this.getLow(q)));
            l = this.mk(this.getVar(q), l, this.getHigh(q));
            this.nstack.pop();
        } else {
            l = this.nstack.push(this.union(this.getLow(p), this.getLow(q)));
            int h = this.nstack.push(this.union(this.getHigh(p), this.getHigh(q)));
            l = this.mk(this.getVar(p), l, h);
            this.nstack.drop(2);
        }
        this.binary_cache.insert(hash, p, q, 3, l);
        return l;
    }

    public final int intersect(int p, int q) {
        if (p == 0 || q == 0) {
            return 0;
        }
        if (q == p) {
            return p;
        }
        if (p == 1) {
            return this.follow_low(q);
        }
        if (q == 1) {
            return this.follow_low(p);
        }
        if (this.binary_cache.lookup(p, q, 4)) {
            return this.binary_cache.answer;
        }
        int hash = this.binary_cache.hash_value;
        int l = 0;
        if (this.getVar(p) > this.getVar(q)) {
            l = this.intersect(this.getLow(p), q);
        } else if (this.getVar(p) < this.getVar(q)) {
            l = this.intersect(p, this.getLow(q));
        } else {
            l = this.nstack.push(this.intersect(this.getLow(p), this.getLow(q)));
            int h = this.nstack.push(this.intersect(this.getHigh(p), this.getHigh(q)));
            l = this.mk(this.getVar(p), l, h);
            this.nstack.drop(2);
        }
        this.binary_cache.insert(hash, p, q, 4, l);
        return l;
    }

    public final int diff(int p, int q) {
        if (p == 0 || p == q) {
            return 0;
        }
        if (q == 0) {
            return p;
        }
        if (this.binary_cache.lookup(p, q, 5)) {
            return this.binary_cache.answer;
        }
        int hash = this.binary_cache.hash_value;
        int l = 0;
        if (this.getVar(p) < this.getVar(q)) {
            l = this.diff(p, this.getLow(q));
        } else if (this.getVar(p) > this.getVar(q)) {
            l = this.nstack.push(this.diff(this.getLow(p), q));
            l = this.mk(this.getVar(p), l, this.getHigh(p));
            this.nstack.pop();
        } else {
            l = this.nstack.push(this.diff(this.getLow(p), this.getLow(q)));
            int h = this.nstack.push(this.diff(this.getHigh(p), this.getHigh(q)));
            l = this.mk(this.getVar(p), l, h);
            this.nstack.drop(2);
        }
        this.binary_cache.insert(hash, p, q, 5, l);
        return l;
    }

    public final int follow_low(int zdd) {
        while (zdd >= 2) {
            zdd = this.getLow(zdd);
        }
        return zdd;
    }

    public final int follow_high(int zdd) {
        while (zdd >= 2) {
            zdd = this.getHigh(zdd);
        }
        return zdd;
    }

    public final boolean emptyIn(int X) {
        return this.follow_low(X) == 1;
    }

    private final int insert_base(int set) {
        if (set < 2) {
            return 1;
        }
        int l = this.nstack.push(this.insert_base(this.getLow(set)));
        l = this.getLow(set) == l ? set : this.mk(this.getVar(set), l, this.getHigh(set));
        this.nstack.pop();
        return l;
    }

    public boolean[] satOne(int zdd, boolean[] vec) {
        if (zdd == 0) {
            return null;
        }
        if (vec == null) {
            vec = new boolean[this.num_vars];
        }
        Array.set(vec, false);
        if (zdd != 1) {
            this.satOne_rec(zdd, vec);
        }
        return vec;
    }

    private void satOne_rec(int zdd, boolean[] vec) {
        if (zdd < 2) {
            return;
        }
        int next = this.getLow(zdd);
        if (next == 0) {
            vec[this.getVar((int)zdd)] = true;
            next = this.getHigh(zdd);
        }
        this.satOne_rec(next, vec);
    }

    public final int count(int zdd) {
        if (zdd < 2) {
            return zdd;
        }
        return this.count(this.getLow(zdd)) + this.count(this.getHigh(zdd));
    }

    public int nodeCount(int zdd) {
        this.node_count_int = 0;
        this.nodeCount_mark(zdd);
        this.unmark_tree(zdd);
        return this.node_count_int;
    }

    private final void nodeCount_mark(int zdd) {
        if (zdd < 2) {
            return;
        }
        if (this.isNodeMarked(zdd)) {
            return;
        }
        this.mark_node(zdd);
        ++this.node_count_int;
        this.nodeCount_mark(this.getLow(zdd));
        this.nodeCount_mark(this.getHigh(zdd));
    }

    public int unionTo(int set, int add) {
        int tmp = this.ref(this.union(set, add));
        this.deref(set);
        return tmp;
    }

    public int diffTo(int set, int add) {
        int tmp = this.ref(this.diff(set, add));
        this.deref(set);
        return tmp;
    }

    @Override
    public void showStats() {
        super.showStats();
        this.unary_cache.showStats();
        this.binary_cache.showStats();
    }

    @Override
    public long getMemoryUsage() {
        long ret = super.getMemoryUsage();
        if (this.unary_cache != null) {
            ret += this.unary_cache.getMemoryUsage();
        }
        if (this.binary_cache != null) {
            ret += this.binary_cache.getMemoryUsage();
        }
        return ret;
    }

    public void setNodeNames(NodeName nn) {
        this.nodeNames = nn;
    }

    public void print(int zdd) {
        ZDDPrinter.print(zdd, this, this.nodeNames);
    }

    public void printDot(String fil, int bdd) {
        ZDDPrinter.printDot(fil, bdd, this, this.nodeNames);
    }

    public void printSet(int bdd) {
        ZDDPrinter.printSet(bdd, this, this.nodeNames);
    }

    public void printCubes(int bdd) {
        ZDDPrinter.printSet(bdd, this, null);
    }
}
