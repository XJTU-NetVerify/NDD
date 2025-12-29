/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.bdd.Permutation;
import jdd.bdd.debug.ProfiledBDD2;
import jdd.util.JDDConsole;

public class Milner
extends ProfiledBDD2 {
    private int N;
    private int[] normvar;
    private int[] primvar;
    private int[] c;
    private int[] cp;
    private int[] t;
    private int[] tp;
    private int[] h;
    private int[] hp;
    private int I;
    private int T;
    private int normvarset;
    private Permutation pairs;

    public Milner(int N) {
        super(100000, 30000);
        int n;
        this.N = N;
        this.normvar = new int[N * 3];
        this.primvar = new int[N * 3];
        this.c = new int[N];
        this.cp = new int[N];
        this.h = new int[N];
        this.hp = new int[N];
        this.t = new int[N];
        this.tp = new int[N];
        for (n = 0; n < N * 3; ++n) {
            this.normvar[n] = this.createVar();
            this.primvar[n] = this.createVar();
        }
        this.pairs = this.createPermutation(this.primvar, this.normvar);
        this.normvarset = 1;
        for (int i = 0; i < this.normvar.length; ++i) {
            this.normvarset = this.andTo(this.normvarset, this.normvar[i]);
        }
        for (n = 0; n < N; ++n) {
            this.c[n] = this.normvar[n * 3];
            this.t[n] = this.normvar[n * 3 + 1];
            this.h[n] = this.normvar[n * 3 + 2];
            this.cp[n] = this.primvar[n * 3];
            this.tp[n] = this.primvar[n * 3 + 1];
            this.hp[n] = this.primvar[n * 3 + 2];
        }
        this.I = this.initial_state(this.t, this.h, this.c);
        this.T = this.transitions(this.t, this.tp, this.h, this.hp, this.c, this.cp);
    }

    private int andA(int res, int[] x, int[] y, int z) {
        for (int i = 0; i < this.N; ++i) {
            if (i == z) continue;
            int tmp1 = this.ref(this.biimp(x[i], y[i]));
            res = this.andTo(res, tmp1);
            this.deref(tmp1);
        }
        return res;
    }

    private int diff(int bdd1, int bdd2) {
        int tmp = this.ref(this.not(bdd2));
        int ret = this.and(tmp, bdd1);
        this.deref(tmp);
        return ret;
    }

    private int transitions(int[] t, int[] tp, int[] h, int[] hp, int[] c, int[] cp) {
        int tran = 0;
        for (int i = 0; i < this.N; ++i) {
            int tmp1 = this.ref(this.diff(c[i], cp[i]));
            int tmp2 = this.ref(this.diff(tp[i], t[i]));
            tmp1 = this.andTo(tmp1, tmp2);
            this.deref(tmp2);
            tmp1 = this.andTo(tmp1, hp[i]);
            tmp1 = this.andA(tmp1, c, cp, i);
            tmp1 = this.andA(tmp1, t, tp, i);
            int P = tmp1 = this.andA(tmp1, h, hp, i);
            tmp1 = this.ref(this.diff(h[i], hp[i]));
            tmp1 = this.andTo(tmp1, cp[(i + 1) % this.N]);
            tmp1 = this.andA(tmp1, c, cp, (i + 1) % this.N);
            tmp1 = this.andA(tmp1, h, hp, i);
            tmp1 = this.andA(tmp1, t, tp, this.N);
            P = this.orTo(P, tmp1);
            this.deref(tmp1);
            tmp1 = this.ref(this.not(tp[i]));
            tmp1 = this.andTo(tmp1, t[i]);
            tmp1 = this.andA(tmp1, t, tp, i);
            tmp1 = this.andA(tmp1, h, hp, this.N);
            int E = this.andA(tmp1, c, cp, this.N);
            tmp2 = this.ref(this.or(P, E));
            this.deref(P);
            this.deref(E);
            tran = this.orTo(tran, tmp2);
            this.deref(tmp2);
        }
        return tran;
    }

    private int initial_state(int[] t, int[] h, int[] c) {
        this.I = 1;
        for (int i = 0; i < this.N; ++i) {
            int tmp1 = this.ref(i == 0 ? c[i] : this.not(c[i]));
            tmp1 = this.andTo(tmp1, this.not(h[i]));
            tmp1 = this.andTo(tmp1, this.not(t[i]));
            this.I = this.andTo(this.I, tmp1);
            this.deref(tmp1);
        }
        return this.I;
    }

    public int reachable_states() {
        int C;
        int by;
        int bx = 0;
        do {
            by = bx;
            int tmp1 = this.ref(this.relProd(bx, this.T, this.normvarset));
            this.deref(bx);
            C = this.ref(this.replace(tmp1, this.pairs));
            this.deref(tmp1);
        } while (by != (bx = this.orTo(C, this.I)));
        return bx;
    }

    public static void main(String[] args) {
        if (args.length >= 1) {
            int n = -1;
            boolean verbose = false;
            for (int i = 0; i < args.length; ++i) {
                if (args[i].equals("-v")) {
                    verbose = true;
                    continue;
                }
                n = Integer.parseInt(args[i]);
            }
            if (n > 0) {
                long c1 = System.currentTimeMillis();
                Milner milner = new Milner(n);
                int R = milner.reachable_states();
                long c2 = System.currentTimeMillis();
                if (verbose) {
                    milner.showStats();
                    JDDConsole.out.println("Simulation of " + n + " milner cyclers");
                    JDDConsole.out.println("SatCount(R) = " + milner.satCount(R));
                    JDDConsole.out.println("Calc        = " + (double)n * Math.pow(2.0, 1 + n) * Math.pow(2.0, 3 * n));
                    JDDConsole.out.println("Time: " + (c2 - c1) + " [ms]");
                } else {
                    JDDConsole.out.println("Milner\tN=" + n + "\ttime=" + (c2 - c1));
                }
                milner.cleanup();
                return;
            }
        }
        JDDConsole.out.println("Usage: java jdd.examples.Milner [-v] <number of cyclers>");
    }
}

