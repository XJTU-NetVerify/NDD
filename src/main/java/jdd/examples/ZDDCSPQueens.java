/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.examples.Queens;
import jdd.util.Array;
import jdd.util.JDDConsole;
import jdd.util.Options;
import jdd.util.math.Digits;
import jdd.zdd.ZDDCSP;

public class ZDDCSPQueens
extends ZDDCSP
implements Queens {
    private int n;
    private int sols;
    private int[] x;
    private int[] xv;
    private boolean[] solvec;
    private long time = System.currentTimeMillis();
    private long memory;

    private int get(int i, int j) {
        return this.x[i + j * this.n];
    }

    private int getVar(int i, int j) {
        return this.xv[i + j * this.n];
    }

    public ZDDCSPQueens(int n) {
        super(1 + Math.max(1000, (int)Math.pow(2.0, n - 5) * 800), 10000);
        this.n = n;
        this.x = new int[n * n];
        this.xv = new int[n * n];
        boolean[] mark = new boolean[n * n];
        for (int i = 0; i < n * n; ++i) {
            this.xv[i] = this.createVar();
            this.x[i] = this.ref(this.change(1, this.xv[i]));
        }
        int G1 = 0;
        for (int i = 0; i < n; ++i) {
            G1 = this.unionWith(G1, this.get(0, i));
        }
        int last_G = G1;
        for (int i = 1; i < n; ++i) {
            int F = 0;
            for (int j = 0; j < n; ++j) {
                int bld = this.build(i, j, last_G, mark);
                F = this.unionWith(F, bld);
                this.deref(bld);
            }
            this.deref(last_G);
            last_G = F;
        }
        this.solvec = this.satOne(last_G, null);
        this.sols = this.count(last_G);
        this.deref(last_G);
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

    private int build(int i, int j, int G, boolean[] mark) {
        int k;
        Array.set(mark, false);
        for (k = 0; k < i; ++k) {
            mark[k + this.n * j] = true;
        }
        for (k = 1; k <= i; ++k) {
            int b = i - k;
            int a = j - k;
            if (this.valid(b, a)) {
                mark[b + this.n * a] = true;
            }
            if (!this.valid(b, a = j + k)) continue;
            mark[b + this.n * a] = true;
        }
        int C = 0;
        for (int k2 = 0; k2 < this.n * this.n; ++k2) {
            if (!mark[k2]) continue;
            int a = k2 / this.n;
            int b = k2 % this.n;
            C = this.unionWith(C, this.get(b, a));
        }
        int tmp = this.ref(this.exclude(G, C));
        this.deref(C);
        int ret = this.ref(this.mul(tmp, this.get(i, j)));
        this.deref(tmp);
        return ret;
    }

    private int unionWith(int a, int b) {
        int tmp = this.ref(this.union(a, b));
        this.deref(a);
        return tmp;
    }

    public static void main(String[] args) {
        for (String str : args) {
            int n = Integer.parseInt(str);
            ZDDCSPQueens q = new ZDDCSPQueens(n);
            JDDConsole.out.printf("ZDDCSP-Queen\tSolutions=%.0f\tN=%d\tmem=%s\ttime=%d\n", q.numberOfSolutions(), n, Digits.prettify1024(q.getMemory()), q.getTime());
        }
    }
}

