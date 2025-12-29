/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

import jdd.util.JDDConsole;
import jdd.util.jre.JREInfo;
import jdd.util.math.Digits;

public class Allocator {
    public static final int TYPE_INT = 0;
    public static final int TYPE_SHORT = 1;
    public static final int TYPE_BYTE = 2;
    public static final int TYPE_DOUBLE = 3;
    public static final int TYPE_CHAR = 4;
    public static final int TYPE_BOOLEAN = 5;
    public static final int TYPE_COUNT = 6;
    public static final String[] TYPE_NAMES = new String[]{"int", "short", "byte", "double", "char", "boolean"};
    public static final int[] TYPE_SIZES = new int[]{4, 2, 1, 8, 2, 1};
    private static long[] stats_count = new long[6];
    private static long[] stats_total = new long[6];
    private static long[] stats_max = new long[6];
    private static long stats_total_bytes = 0L;

    public static long getStatsCount(int type) {
        return stats_count[type];
    }

    public static long getStatsTotal(int type) {
        return stats_total[type];
    }

    public static long getStatsMax(int type) {
        return stats_max[type];
    }

    public static long getStatsTotalBytes() {
        return stats_total_bytes;
    }

    private static void register(int type, long size) {
        int n = type;
        stats_count[n] = stats_count[n] + 1L;
        int n2 = type;
        stats_total[n2] = stats_total[n2] + size;
        Allocator.stats_max[type] = Math.max(stats_max[type], size);
        stats_total_bytes += size * (long)TYPE_SIZES[type];
    }

    private static void fail(long size, int type, OutOfMemoryError ex) {
        long size_total = size * (long)TYPE_SIZES[type];
        String typeName = TYPE_NAMES[type];
        JDDConsole.out.printf("FAILED to allocate %s[%d] (%d bytes)\n", typeName, size, size_total);
        Allocator.showStats();
        throw ex;
    }

    public static int[] allocateIntArray(int size) {
        try {
            int[] ret = new int[size];
            Allocator.register(0, size);
            return ret;
        }
        catch (OutOfMemoryError ex) {
            Allocator.fail(size, 0, ex);
            return null;
        }
    }

    public static double[] allocateDoubleArray(int size) {
        try {
            double[] ret = new double[size];
            Allocator.register(3, size);
            return ret;
        }
        catch (OutOfMemoryError ex) {
            Allocator.fail(size, 3, ex);
            return null;
        }
    }

    public static short[] allocateShortArray(int size) {
        try {
            short[] ret = new short[size];
            Allocator.register(1, size);
            return ret;
        }
        catch (OutOfMemoryError ex) {
            Allocator.fail(size, 1, ex);
            return null;
        }
    }

    public static char[] allocateCharArray(int size) {
        try {
            char[] ret = new char[size];
            Allocator.register(4, size);
            return ret;
        }
        catch (OutOfMemoryError ex) {
            Allocator.fail(size, 4, ex);
            return null;
        }
    }

    public static byte[] allocateByteArray(int size) {
        try {
            byte[] ret = new byte[size];
            Allocator.register(2, size);
            return ret;
        }
        catch (OutOfMemoryError ex) {
            Allocator.fail(size, 2, ex);
            return null;
        }
    }

    public static boolean[] allocateBooleanArray(int size) {
        try {
            boolean[] ret = new boolean[size];
            Allocator.register(5, size);
            return ret;
        }
        catch (OutOfMemoryError ex) {
            Allocator.fail(size, 5, ex);
            return null;
        }
    }

    public static void showStats() {
        JDDConsole.out.printf("Allocator total memory: %d, stats (type,count,max,total):\n", stats_total_bytes);
        for (int i = 0; i < 6; ++i) {
            if (stats_count[i] <= 0L) continue;
            JDDConsole.out.printf("(%s, %d, %d, %d)\n", TYPE_NAMES[i], stats_count[i], stats_max[i], stats_total[i]);
        }
        JDDConsole.out.printf("\n", new Object[0]);
        JDDConsole.out.printf("Total=%s max=%s used=%s free=%s\n", Digits.prettify1024(JREInfo.totalMemory()), Digits.prettify1024(JREInfo.maxMemory()), Digits.prettify1024(JREInfo.usedMemory()), Digits.prettify1024(JREInfo.freeMemory()));
    }

    public static void resetStats() {
        stats_total_bytes = 0L;
        for (int i = 0; i < 6; ++i) {
            Allocator.stats_total[i] = 0L;
            Allocator.stats_max[i] = 0L;
            Allocator.stats_count[i] = 0L;
        }
    }
}

