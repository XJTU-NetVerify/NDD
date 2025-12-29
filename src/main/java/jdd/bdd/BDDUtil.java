/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import jdd.bdd.BDD;

public class BDDUtil {
    public static int numberToBDD(BDD jdd, int[] vars, int num) {
        int ret = 1;
        for (int i = 0; i < vars.length; ++i) {
            int next = ((long)num & 1L << i) == 0L ? jdd.not(vars[i]) : vars[i];
            jdd.ref(next);
            ret = jdd.andTo(ret, next);
            jdd.deref(next);
        }
        return ret;
    }

    public static void numberToMinterm(int num, int length, int index, boolean[] output) {
        for (int i = 0; i < length; ++i) {
            output[index++] = ((long)num & 1L << i) != 0L;
        }
    }
}

