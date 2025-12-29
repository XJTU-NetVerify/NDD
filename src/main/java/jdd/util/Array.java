/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

import jdd.util.Allocator;
import jdd.util.math.FastRandom;

public final class Array {
    public static final int[] resize(int[] old, int old_size, int new_size) {
        int[] ret = Allocator.allocateIntArray(new_size);
        if (old_size > new_size) {
            old_size = new_size;
        }
        Array.fast_copy(old, 0, ret, 0, old_size);
        return ret;
    }

    public static final short[] resize(short[] old, int old_size, int new_size) {
        short[] ret = Allocator.allocateShortArray(new_size);
        if (old_size > new_size) {
            old_size = new_size;
        }
        Array.fast_copy(old, 0, ret, 0, old_size);
        return ret;
    }

    public static final void copy(int[] from, int[] to, int len, int from_offset, int to_offset) {
        if (from == to && from_offset < to_offset && from_offset + len >= to_offset) {
            Array.fast_copy_backward(from, from_offset, to, to_offset, len);
            return;
        }
        Array.fast_copy(from, from_offset, to, to_offset, len);
    }

    private static final void fast_copy(int[] y, int o1, int[] x, int o2, int len) {
        System.arraycopy(y, o1, x, o2, len);
    }

    private static final void fast_copy(short[] y, int o1, short[] x, int o2, int len) {
        System.arraycopy(y, o1, x, o2, len);
    }

    private static final void fast_copy_forward(int[] y, int o1, int[] x, int o2, int len) {
        for (int i = 0; i < len; ++i) {
            x[o2 + i] = y[o1 + i];
        }
    }

    private static final void fast_copy_forward(short[] y, int o1, short[] x, int o2, int len) {
        for (int i = 0; i < len; ++i) {
            x[o2 + i] = y[o1 + i];
        }
    }

    private static final void fast_copy_backward(int[] y, int o1, int[] x, int o2, int len) {
        while (len != 0) {
            x[o2 + --len] = y[o1 + len];
        }
    }

    public static final int[] clone(int[] old) {
        int[] ret = Allocator.allocateIntArray(old.length);
        Array.fast_copy(old, 0, ret, 0, old.length);
        return ret;
    }

    public static final boolean[] clone(boolean[] old) {
        boolean[] ret = new boolean[old.length];
        System.arraycopy(old, 0, ret, 0, old.length);
        return ret;
    }

    public static final void set(int[] x, int val) {
        Array.set(x, val, x.length);
    }

    public static final void set(int[] x, int val, int length) {
        int i = length;
        while (i != 0) {
            x[--i] = val;
        }
    }

    public static final void set(boolean[] x, boolean val) {
        int i = x.length;
        while (i != 0) {
            x[--i] = val;
        }
    }

    public static final int count(int[] x, int val) {
        int ret = 0;
        int i = x.length;
        while (i != 0) {
            if (x[--i] != val) continue;
            ++ret;
        }
        return ret;
    }

    public static final int count(boolean[] x, boolean val) {
        int len = x.length;
        int ret = 0;
        for (int i = 0; i < len; ++i) {
            if (x[i] != val) continue;
            ++ret;
        }
        return ret;
    }

    public static void reverse(Object[] variables, int size) {
        int len = size / 2;
        --size;
        for (int j = 0; j < len; ++j) {
            int i = size - j;
            Object tmp = variables[i];
            variables[i] = variables[j];
            variables[j] = tmp;
        }
    }

    public static void reverse(int[] variables, int size) {
        int len = size / 2;
        --size;
        for (int j = 0; j < len; ++j) {
            int i = size - j;
            int tmp = variables[i];
            variables[i] = variables[j];
            variables[j] = tmp;
        }
    }

    public static void reverse(double[] variables, int size) {
        int len = size / 2;
        --size;
        for (int j = 0; j < len; ++j) {
            int i = size - j;
            double tmp = variables[i];
            variables[i] = variables[j];
            variables[j] = tmp;
        }
    }

    public static final void shuffle(int[] x) {
        Array.shuffle(x, x.length);
    }

    public static final void shuffle(int[] x, int len) {
        for (int i = 0; i < len; ++i) {
            int j = FastRandom.mtrand() % len;
            int tmp = x[i];
            x[i] = x[j];
            x[j] = tmp;
        }
    }

    public static final void disturb(int[] x, int len) {
        if (len < 16) {
            Array.shuffle(x, len);
        } else {
            int times = Math.max(4, len / 20);
            while (times-- > 0) {
                int j = FastRandom.mtrand() % len;
                int i = FastRandom.mtrand() % len;
                int tmp = x[i];
                x[i] = x[j];
                x[j] = tmp;
            }
        }
    }

    public static final int[] permutation(int size) {
        int[] ret = new int[size];
        for (int i = 0; i < size; ++i) {
            ret[i] = i;
        }
        Array.shuffle(ret);
        return ret;
    }

    public static final boolean equals(boolean[] v1, boolean[] v2, int len) {
        for (int i = 0; i < len; ++i) {
            if (v1[i] == v2[i]) continue;
            return false;
        }
        return true;
    }

    public static final boolean equals(short[] v1, short[] v2, int len) {
        for (int i = 0; i < len; ++i) {
            if (v1[i] == v2[i]) continue;
            return false;
        }
        return true;
    }

    public static final boolean equals(byte[] v1, byte[] v2, int len) {
        for (int i = 0; i < len; ++i) {
            if (v1[i] == v2[i]) continue;
            return false;
        }
        return true;
    }

    public static final boolean equals(int[] v1, int[] v2, int len) {
        for (int i = 0; i < len; ++i) {
            if (v1[i] == v2[i]) continue;
            return false;
        }
        return true;
    }
}

