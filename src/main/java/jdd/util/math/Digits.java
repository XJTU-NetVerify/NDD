/*
 * Decompiled with CFR 0.152.
 */
package jdd.util.math;

public class Digits {
    public static int log2_ceil(int x) {
        int ret = 1;
        while (1L << ret < (long)x) {
            ++ret;
        }
        return ret;
    }

    public static int closest_log2(int x) {
        long d2;
        int lg2 = Digits.log2_ceil(x);
        long d1 = (1L << lg2) - (long)x;
        return d1 < (d2 = (long)x - (1L << lg2 - 1)) ? lg2 : lg2 - 1;
    }

    public static int maxUniquePairs(int n) {
        if (n == 0 || n == 1) {
            return 0;
        }
        if (n == 2) {
            return 1;
        }
        return n - 1 + Digits.maxUniquePairs(n - 1);
    }

    public static double getPercent(double x) {
        return Digits.getWithDecimals(100.0 * x, 2);
    }

    public static double getWithDecimals(double x, int n) {
        double dec = Math.pow(10.0, n);
        return (double)Math.round(x * dec) / dec;
    }

    public static String prettify(long n) {
        return Digits.prettifyWith(n, 1000L);
    }

    public static String prettify1024(long n) {
        return Digits.prettifyWith(n, 1024L);
    }

    private static String prettifyWith(long n, long k_) {
        long m_ = k_ * k_;
        long g_ = k_ * m_;
        long t_ = k_ * g_;
        if (n > t_) {
            return String.format("%.2fT", (double)n / (double)t_);
        }
        if (n > g_) {
            return String.format("%.2fG", (double)n / (double)g_);
        }
        if (n > m_) {
            return String.format("%.2fM", (double)n / (double)m_);
        }
        if (n > k_) {
            return String.format("%.2fK", (double)n / (double)k_);
        }
        return n + "";
    }
}

