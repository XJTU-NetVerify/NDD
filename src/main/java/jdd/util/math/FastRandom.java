/*
 * Decompiled with CFR 0.152.
 */
package jdd.util.math;

public class FastRandom {
    private static final int MT_N = 624;
    private static final int MT_M = 397;
    private static final int MT_A = -1727480561;
    private static final int MT_B = -1658038656;
    private static final int MT_C = -272236544;
    private static final int MT_MAKS_UPPER = Integer.MIN_VALUE;
    private static final int MT_MASK_LOWER = Integer.MAX_VALUE;
    private static int[] mt_mt = new int[624];
    private static int[] mt_mag01 = new int[2];
    private static int mt_mti;

    private static final int MT_SHIFT_U(int y) {
        return y >>> 11;
    }

    private static final int MT_SHIFT_S(int y) {
        return y << 7;
    }

    private static final int MT_SHIFT_T(int y) {
        return y << 15;
    }

    private static final int MT_SHIFT_L(int y) {
        return y >>> 18;
    }

    public static final void mtseed(int n) {
        FastRandom.mt_mt[0] = n;
        for (mt_mti = 1; mt_mti < 624; ++mt_mti) {
            FastRandom.mt_mt[FastRandom.mt_mti] = 1812433253 * (mt_mt[mt_mti - 1] ^ mt_mt[mt_mti - 1] >>> 30) + mt_mti;
        }
    }

    public static final int mtrand() {
        int y;
        if (mt_mti >= 624) {
            int kk;
            FastRandom.mt_mag01[0] = 0;
            FastRandom.mt_mag01[1] = -1727480561;
            for (kk = 0; kk < 227; ++kk) {
                y = mt_mt[kk] & Integer.MIN_VALUE | mt_mt[kk + 1] & Integer.MAX_VALUE;
                FastRandom.mt_mt[kk] = mt_mt[kk + 397] ^ y >>> 1 ^ mt_mag01[y & 1];
            }
            while (kk < 623) {
                y = mt_mt[kk] & Integer.MIN_VALUE | mt_mt[kk + 1] & Integer.MAX_VALUE;
                FastRandom.mt_mt[kk] = mt_mt[kk + -227] ^ y >>> 1 ^ mt_mag01[y & 1];
                ++kk;
            }
            y = mt_mt[623] & Integer.MIN_VALUE | mt_mt[0] & Integer.MAX_VALUE;
            FastRandom.mt_mt[623] = mt_mt[396] ^ y >>> 1 ^ mt_mag01[y & 1];
            mt_mti = 0;
        }
        y = mt_mt[mt_mti++];
        y ^= FastRandom.MT_SHIFT_U(y);
        y ^= FastRandom.MT_SHIFT_S(y) & 0x9D2C5680;
        y ^= FastRandom.MT_SHIFT_T(y) & 0xEFC60000;
        y ^= FastRandom.MT_SHIFT_L(y);
        return y & Integer.MAX_VALUE;
    }

    static {
        FastRandom.mtseed((int)(1.0 + 2.147483646E9 * Math.random()));
    }
}

