/*
 * Decompiled with CFR 0.152.
 */
package jdd.util.math;

import java.util.Random;
import jdd.util.Array;
import jdd.util.math.Prime;

public class Chi2Test {
    private int n;
    private int samples_needed;
    private int samples_have;
    private int[] distibution;
    private boolean has_chi2;
    private double the_chi2;
    private double the_stddev;

    public Chi2Test(int n) {
        this.n = n;
        this.samples_needed = 25 * n + 3;
        this.distibution = new int[n];
        this.reset();
    }

    public void reset() {
        this.samples_have = 0;
        Array.set(this.distibution, 0);
        this.has_chi2 = false;
    }

    public boolean more() {
        return this.samples_have < this.samples_needed;
    }

    public void add(int x) {
        int n = x;
        this.distibution[n] = this.distibution[n] + 1;
        ++this.samples_have;
        this.has_chi2 = false;
    }

    public double getChi2() {
        if (!this.has_chi2) {
            this.computeChi2();
        }
        return this.the_chi2;
    }

    public double getStandardDeviation() {
        if (!this.has_chi2) {
            this.computeChi2();
        }
        return this.the_stddev;
    }

    private void computeChi2() {
        this.has_chi2 = true;
        double p = (double)this.samples_have / (double)this.n;
        this.the_chi2 = 0.0;
        for (int i = 0; i < this.n; ++i) {
            double t = (double)this.distibution[i] - p;
            this.the_chi2 += t * t;
        }
        this.the_chi2 /= p;
        this.the_stddev = (this.the_chi2 - (double)this.n) / Math.sqrt(this.n);
    }

    public boolean isChi2Acceptable() {
        double c2 = this.getChi2();
        return Math.abs(c2 - (double)this.n) < 3.5 * Math.sqrt(this.n);
    }

    public boolean isStandardDeviationAcceptable() {
        double stddev = this.getStandardDeviation();
        return Math.abs(stddev) < 3.5;
    }

    public int[] getDistibution() {
        return this.distibution;
    }

    public static void main(String[] args) {
        int max = Prime.nextPrime(1000);
        Chi2Test c2t = new Chi2Test(max);
        while (c2t.more()) {
            c2t.add((int)(Math.random() * (double)max));
        }
        System.out.println("testing Math.random() * " + max);
        System.out.println("chi2 ==> " + c2t.getChi2());
        System.out.println("stddev==> " + c2t.getStandardDeviation());
        Random rnd = new Random();
        c2t.reset();
        while (c2t.more()) {
            c2t.add(rnd.nextInt(max));
        }
        System.out.println("\nesting java.util.Random.nextInt(" + max + ")");
        System.out.println("chi2 ==> " + c2t.getChi2());
        System.out.println("stddev==> " + c2t.getStandardDeviation());
    }
}

