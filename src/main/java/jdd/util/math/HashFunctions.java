/*
 * Decompiled with CFR 0.152.
 */
package jdd.util.math;

public final class HashFunctions {
    private static final int DD_P1 = 0xC00005;
    private static final int DD_P2 = 4256249;
    private static final int DD_P3 = 741457;
    private static final int DD_P4 = 1618033999;
    private static final int FNV_PRIME = 16777619;
    private static final int FNV_OFFSET = -2128830935;

    public static final int mix(int i) {
        return i ^ i >>> 8;
    }

    public static final int mix_wang(int i) {
        i += ~(i << 15);
        i ^= i >>> 10;
        i += ~(i << 3);
        i ^= i >>> 6;
        i += ~(i << 11);
        i ^= i >>> 16;
        return i;
    }

    public static final int mix_jenkins(int i) {
        i += i << 12;
        i ^= i >> 22;
        i += i << 4;
        i ^= i >> 9;
        i += i << 10;
        i ^= i >> 2;
        i += i << 7;
        i ^= i >> 12;
        return i;
    }

    private static final long pair(long i, long j) {
        return ((i + j) * (i + j + 1L) >>> 1) + 1L;
    }

    public static final int hash_pair(int a, int b) {
        return (int)HashFunctions.pair(a, b);
    }

    public static final int hash_pair(int a, int b, int c) {
        return (int)HashFunctions.pair(a, HashFunctions.pair(b, c));
    }

    public static final int hash_prime(int a, int b) {
        return a * 0xC00005 + b * 4256249;
    }

    public static final int hash_prime(int a, int b, int c) {
        return a * 0xC00005 + b * 4256249 + c * 741457;
    }

    public static final int hash_jenkins(int a, int b, int c) {
        a -= b;
        a -= c;
        b -= c;
        b -= (a ^= c >>> 13);
        c -= a;
        c -= (b ^= a << 8);
        a -= b;
        a -= (c ^= b >>> 13);
        b -= c;
        b -= (a ^= c >>> 12);
        c -= a;
        c -= (b ^= a << 16);
        a -= b;
        a -= (c ^= b >>> 5);
        b -= c;
        b -= (a ^= c >>> 3);
        c -= a;
        c -= (b ^= a << 10);
        return c ^= b >>> 15;
    }

    private static final int hash_FNV_round(int init, int word) {
        init = init * 16777619 ^ word & 0xFF;
        init = init * 16777619 ^ word >> 8 & 0xFF;
        init = init * 16777619 ^ word >> 16 & 0xFF;
        init = init * 16777619 ^ word >> 24 & 0xFF;
        return init;
    }

    public static final int hash_FNV(int a, int b, int c) {
        int hash = -2128830935;
        hash = HashFunctions.hash_FNV_round(hash, a);
        hash = HashFunctions.hash_FNV_round(hash, b);
        hash = HashFunctions.hash_FNV_round(hash, c);
        return hash;
    }

    public static final int hash_FNV(int[] data, int offset, int len) {
        int hash = -2128830935;
        for (int i = 0; i < len; ++i) {
            hash = HashFunctions.hash_FNV_round(hash, data[offset + i]);
        }
        return hash;
    }
}

