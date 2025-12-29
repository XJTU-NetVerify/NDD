/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.bdd.BDD;
import jdd.util.JDDConsole;
import jdd.util.Options;

public class Adder
extends BDD {
    private int N;
    private int[] ainp;
    private int[] binp;
    private int[] not_ainp;
    private int[] not_binp;
    private int[] co;
    private int[] xout;

    public Adder(int N) {
        super(505 + N, 2000);
        this.N = N;
        this.ainp = new int[N];
        this.binp = new int[N];
        this.not_ainp = new int[N];
        this.not_binp = new int[N];
        this.co = new int[N];
        this.xout = new int[N];
        for (int n = 0; n < N; ++n) {
            this.ainp[n] = this.createVar();
            this.binp[n] = this.createVar();
            this.not_ainp[n] = this.ref(this.not(this.ainp[n]));
            this.not_binp[n] = this.ref(this.not(this.binp[n]));
        }
        this.build_adder();
    }

    private void build_adder() {
        for (int n = 0; n < this.N; ++n) {
            if (n > 0) {
                int tmp1 = this.ref(this.xor(this.ainp[n], this.binp[n]));
                this.xout[n] = this.ref(this.xor(tmp1, this.co[n - 1]));
                this.deref(tmp1);
                tmp1 = this.ref(this.and(this.ainp[n], this.binp[n]));
                int tmp2 = this.ref(this.and(this.ainp[n], this.co[n - 1]));
                int tmp4 = this.ref(this.or(tmp1, tmp2));
                this.deref(tmp1);
                this.deref(tmp2);
                int tmp3 = this.ref(this.and(this.binp[n], this.co[n - 1]));
                this.co[n] = this.ref(this.or(tmp4, tmp3));
                this.deref(tmp3);
                this.deref(tmp4);
                continue;
            }
            this.xout[n] = this.ref(this.xor(this.ainp[n], this.binp[n]));
            this.co[n] = this.ref(this.and(this.ainp[n], this.binp[n]));
        }
    }

    public void dump() {
        for (int n = 0; n < this.N; ++n) {
            System.out.println("Out[" + n + "]: " + this.nodeCount(this.xout[n]) + " nodes");
        }
    }

    private int setval(int val, boolean use_a) {
        int x = 1;
        for (int n = 0; n < this.N; ++n) {
            x = (val & 1) != 0 ? this.andTo(x, use_a ? this.ainp[n] : this.binp[n]) : this.andTo(x, use_a ? this.not_ainp[n] : this.not_binp[n]);
            val >>>= 1;
        }
        return x;
    }

    private boolean test_vector(int av, int bv, int a, int b) {
        int res = a + b;
        for (int n = 0; n < this.N; ++n) {
            int resv = this.ref(this.and(av, bv));
            boolean fail = (resv = this.andTo(resv, this.xout[n])) == 0 && (res & 1) != 0 || resv != 0 && (res & 1) == 0;
            this.deref(resv);
            if (fail) {
                JDDConsole.out.println("resv = " + resv + ", res = " + res);
                return false;
            }
            res >>>= 1;
        }
        return true;
    }

    public boolean test_adder() {
        int m = 1 << this.N;
        for (int a = 0; a < m; ++a) {
            for (int b = 0; b < m; ++b) {
                int av = this.setval(a, true);
                int bv = this.setval(b, false);
                boolean ret = this.test_vector(av, bv, a, b);
                this.deref(av);
                this.deref(bv);
                if (ret) continue;
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        if (args.length >= 1) {
            boolean test = false;
            boolean dump = false;
            boolean verbose = false;
            int n = -1;
            for (int i = 0; i < args.length; ++i) {
                if (args[i].equals("-t")) {
                    test = true;
                    continue;
                }
                if (args[i].equals("-d")) {
                    dump = true;
                    continue;
                }
                if (args[i].equals("-v")) {
                    verbose = true;
                    continue;
                }
                n = Integer.parseInt(args[i]);
            }
            Options.verbose = verbose;
            if (n > 0) {
                JDDConsole.out.print("Adder\tN=" + n);
                long c1 = System.currentTimeMillis();
                Adder adder = new Adder(n);
                if (dump) {
                    adder.dump();
                }
                if (test) {
                    JDDConsole.out.print("Testing...");
                    JDDConsole.out.println(adder.test_adder() ? " PASSED" : "FAILED!");
                }
                long c2 = System.currentTimeMillis();
                JDDConsole.out.println("\ttime=" + (c2 - c1));
                if (verbose) {
                    adder.showStats();
                }
                adder.cleanup();
                return;
            }
        }
        JDDConsole.out.println("Usage: java jdd.examples.Adder [-t] [-d] [-v] <number of bits>");
        JDDConsole.out.println("\t -t    test adder (slow)");
        JDDConsole.out.println("\t -d    dump BDD size");
        JDDConsole.out.println("\t -v    be verbose");
    }
}

