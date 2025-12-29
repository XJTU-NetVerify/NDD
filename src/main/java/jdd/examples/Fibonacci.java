/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.bdd.DoubleCache;

public class Fibonacci {
    private static DoubleCache dc;

    public static double fibonacci(int n) {
        if (n < 0) {
            return -1.0;
        }
        dc = new DoubleCache("fibonacci", n + 3);
        double ret = Fibonacci.fibonacci_rec(n);
        dc.showStats();
        dc = null;
        return ret;
    }

    private static double fibonacci_rec(int n) {
        if (n < 2) {
            return n;
        }
        if (dc.lookup(n)) {
            return Fibonacci.dc.answer;
        }
        int hash = Fibonacci.dc.hash_value;
        double ret = Fibonacci.fibonacci_rec(n - 1) + Fibonacci.fibonacci_rec(n - 2);
        dc.insert(hash, n, ret);
        return ret;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: Java jdd.examples.Fibonacci n");
            System.err.println("      n must be a positive integer.");
            System.err.println("      if n is too large, you will see a java.lang.StackOverflowError :(");
        } else {
            int n = Integer.parseInt(args[0]);
            long t = System.currentTimeMillis();
            double f = Fibonacci.fibonacci(n);
            t = System.currentTimeMillis() - t;
            System.out.println("In " + t + " ms:  F(" + n + ") = " + f);
        }
    }
}

