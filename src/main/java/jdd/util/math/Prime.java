/*
 * Decompiled with CFR 0.152.
 */
package jdd.util.math;

public final class Prime {
    private static final int NUM_TRIALS = 8;

    private static final long witness(long a, long i, long n) {
        if (i == 0L) {
            return 1L;
        }
        long x = Prime.witness(a, i / 2L, n);
        if (x == 0L) {
            return 0L;
        }
        long y = x * x % n;
        if (y == 1L && x != 1L && x != n - 1L) {
            return 0L;
        }
        if (i % 2L == 1L) {
            y = a * y % n;
        }
        return y;
    }

    public static final boolean isPrime(int n) {
        if (n < 20 && (n == 1 || n == 2 || n == 3 || n == 5 || n == 7 || n == 11 || n == 13 || n == 17 || n == 19)) {
            return true;
        }
        if (n % 2 == 0 || n % 3 == 0 || n % 5 == 0 || n % 7 == 0 || n % 11 == 0 || n % 13 == 0 || n % 17 == 0 || n % 19 == 0) {
            return false;
        }
        for (int c = 0; c < 8; ++c) {
            if (Prime.witness(2L + (long)(Math.random() * (double)(n - 2)), n - 1, n) == 1L) continue;
            return false;
        }
        return true;
    }

    public static final int nextPrime(int n) {
        if (n % 2 == 0) {
            ++n;
        }
        while (!Prime.isPrime(n)) {
            n += 2;
        }
        return n;
    }

    public static final int prevPrime(int n) {
        if (n % 2 == 0) {
            --n;
        }
        while (!Prime.isPrime(n)) {
            n -= 2;
        }
        return n;
    }
}

