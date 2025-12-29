/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.examples.Queens;
import jdd.util.Array;
import jdd.util.JDDConsole;
import jdd.util.Options;
import jdd.util.math.Digits;
import jdd.zdd.ZDD2;

public class ZDDQueens
extends ZDD2
implements Queens {
    private int n;
    private int sols;
    private int[] pos_x;
    private int[] pos_xv;
    private boolean[] solvec;
    private long time = System.currentTimeMillis();
    private long memory;

    private int get(int i, int j) {
        return this.pos_x[i + j * this.n];
    }

    private int getVar(int i, int j) {
        return this.pos_xv[i + j * this.n];
    }

    public ZDDQueens(int n) {
        super(1 + Math.max(1000, (int)Math.pow(3.5, n - 6) * 1000), 10000);
        this.n = n;
        this.pos_x = new int[n * n];
        this.pos_xv = new int[n * n];
        boolean[] mark = new boolean[n * n];
        for (int i = 0; i < n * n; ++i) {
            this.pos_xv[i] = this.createVar();
            this.pos_x[i] = this.ref(this.change(1, this.pos_xv[i]));
        }
        int S1 = 0;
        for (int i = 0; i < n; ++i) {
            S1 = this.unionWith(S1, this.get(0, i));
        }
        int last_S = S1;
        for (int i = 1; i < n; ++i) {
            int new_S = 0;
            for (int j = 0; j < n; ++j) {
                int bld = this.build(i, j, last_S, mark);
                new_S = this.unionWith(new_S, bld);
                this.deref(bld);
            }
            this.deref(last_S);
            last_S = new_S;
        }
        this.solvec = this.satOne(last_S, null);
        this.sols = this.count(last_S);
        this.deref(last_S);
        this.time = System.currentTimeMillis() - this.time;
        if (Options.verbose) {
            this.showStats();
        }
        this.memory = this.getMemoryUsage();
        this.cleanup();
    }

    @Override
    public int getN() {
        return this.n;
    }

    @Override
    public double numberOfSolutions() {
        return this.sols;
    }

    @Override
    public long getTime() {
        return this.time;
    }

    public long getMemory() {
        return this.memory;
    }

    @Override
    public boolean[] getOneSolution() {
        return this.solvec;
    }

    private boolean valid(int a, int b) {
        return a >= 0 && a < this.n && b >= 0 && b < this.n;
    }

    private int build(int i, int j, int S, boolean[] mark) {
        int a;
        int b;
        int k;
        this.ref(S);
        Array.set(mark, false);
        for (k = 0; k < i; ++k) {
            mark[k + this.n * j] = true;
        }
        for (k = 1; k <= i; ++k) {
            b = i - k;
            a = j - k;
            if (this.valid(b, a)) {
                mark[b + this.n * a] = true;
            }
            if (!this.valid(b, a = j + k)) continue;
            mark[b + this.n * a] = true;
        }
        for (k = 0; k < this.n * this.n; ++k) {
            if (!mark[k]) continue;
            a = k / this.n;
            b = k % this.n;
            S = this.offsetWith(S, this.getVar(b, a));
        }
        int ret = this.ref(this.mul(S, this.get(i, j)));
        this.deref(S);
        return ret;
    }

    private int unionWith(int a, int b) {
        int tmp = this.ref(this.union(a, b));
        this.deref(a);
        return tmp;
    }

    private int offsetWith(int a, int b) {
        int tmp = this.ref(this.subset0(a, b));
        this.deref(a);
        return tmp;
    }

    public static void main(String[] args) {
        for (String str : args) {
            int n = Integer.parseInt(str);
            ZDDQueens q = new ZDDQueens(n);
            JDDConsole.out.printf("ZDD-Queen\tSolutions=%.0f\tN=%d\tmem=%s\ttime=%d\n", q.numberOfSolutions(), n, Digits.prettify1024(q.getMemory()), q.getTime());
        }
    }
}

