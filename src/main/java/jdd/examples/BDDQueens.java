/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.bdd.BDD;
import jdd.examples.Queens;
import jdd.util.JDDConsole;
import jdd.util.Options;
import jdd.util.math.Digits;

public class BDDQueens
extends BDD
implements Queens {
    private int[] bdds;
    private int[] nbdds;
    private int N;
    private int queen;
    private double sols;
    private double memory_usage;
    private long time;
    private boolean[] solvec;

    private int X(int x, int y) {
        return this.bdds[y + x * this.N];
    }

    private int nX(int x, int y) {
        return this.nbdds[y + x * this.N];
    }

    public BDDQueens(int N) {
        super(1 + Math.max(1000, (int)Math.pow(4.4, N - 6) * 1000), 10000);
        int i;
        this.N = N;
        this.time = System.currentTimeMillis();
        int all = N * N;
        this.bdds = new int[all];
        this.nbdds = new int[all];
        for (i = 0; i < all; ++i) {
            this.bdds[i] = this.createVar();
            this.nbdds[i] = this.ref(this.not(this.bdds[i]));
        }
        this.queen = 1;
        for (i = 0; i < N; ++i) {
            int e = 0;
            for (int j = 0; j < N; ++j) {
                e = this.orTo(e, this.X(i, j));
            }
            this.queen = this.andTo(this.queen, e);
            this.deref(e);
        }
        for (i = 0; i < N; ++i) {
            for (int j = 0; j < N; ++j) {
                this.build(i, j);
            }
        }
        this.sols = this.satCount(this.queen);
        this.time = System.currentTimeMillis() - this.time;
        this.memory_usage = this.getMemoryUsage();
        if (this.queen == 0) {
            this.solvec = null;
        }
        int[] tmp = this.oneSat(this.queen, null);
        this.solvec = new boolean[tmp.length];
        for (int x = 0; x < this.solvec.length; ++x) {
            this.solvec[x] = tmp[x] == 1;
        }
        this.deref(this.queen);
        if (Options.verbose) {
            this.showStats();
        }
        this.cleanup();
    }

    private void build(int i, int j) {
        int mp;
        int ll;
        int k;
        int mp2;
        int d = 1;
        int c = 1;
        int b = 1;
        int a = 1;
        for (int l = 0; l < this.N; ++l) {
            if (l == j) continue;
            mp2 = this.ref(this.imp(this.X(i, j), this.nX(i, l)));
            a = this.andTo(a, mp2);
            this.deref(mp2);
        }
        for (k = 0; k < this.N; ++k) {
            if (k == i) continue;
            mp2 = this.ref(this.imp(this.X(i, j), this.nX(k, j)));
            b = this.andTo(b, mp2);
            this.deref(mp2);
        }
        for (k = 0; k < this.N; ++k) {
            ll = k - i + j;
            if (ll < 0 || ll >= this.N || k == i) continue;
            mp = this.ref(this.imp(this.X(i, j), this.nX(k, ll)));
            c = this.andTo(c, mp);
            this.deref(mp);
        }
        for (k = 0; k < this.N; ++k) {
            ll = i + j - k;
            if (ll < 0 || ll >= this.N || k == i) continue;
            mp = this.ref(this.imp(this.X(i, j), this.nX(k, ll)));
            d = this.andTo(d, mp);
            this.deref(mp);
        }
        c = this.andTo(c, d);
        this.deref(d);
        b = this.andTo(b, c);
        this.deref(c);
        a = this.andTo(a, b);
        this.deref(b);
        this.queen = this.andTo(this.queen, a);
        this.deref(a);
    }

    public void showOneSolution() {
        if (this.solvec == null) {
            return;
        }
        for (int x = 0; x < this.solvec.length; ++x) {
            if (x % this.N == 0) {
                JDDConsole.out.printf("\n", new Object[0]);
            }
            JDDConsole.out.printf("%c|", Character.valueOf(this.solvec[x] ? (char)'*' : '_'));
        }
        JDDConsole.out.printf("\n", new Object[0]);
    }

    @Override
    public int getN() {
        return this.N;
    }

    @Override
    public double numberOfSolutions() {
        return this.sols;
    }

    @Override
    public long getTime() {
        return this.time;
    }

    public double getMemory() {
        return this.memory_usage;
    }

    @Override
    public boolean[] getOneSolution() {
        return this.solvec;
    }

    public static void main(String[] args) {
        for (String str : args) {
            int n = Integer.parseInt(str);
            BDDQueens q = new BDDQueens(n);
            JDDConsole.out.printf("BDD-Queen\tSolutions=%.0f\tN=%d\tmem=%s\ttime=%d\n", q.numberOfSolutions(), n, Digits.prettify1024((long)q.getMemory()), q.getTime());
        }
    }
}

