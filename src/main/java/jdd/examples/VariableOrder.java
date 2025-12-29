/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.bdd.BDD;
import jdd.bdd.BDDPrinter;
import jdd.util.JDDConsole;
import jdd.util.math.Digits;

public class VariableOrder {
    static final int COUNT = 32;

    private static int numToBDD(BDD bdd, int[] vars, int n) {
        int ret = 1;
        for (int i = 0; i < vars.length; ++i) {
            int v = vars[i];
            if ((n & 1 << i) == 0) {
                v = bdd.ref(bdd.not(v));
            }
            int old = ret;
            ret = bdd.ref(bdd.and(ret, v));
            bdd.deref(v);
            bdd.deref(old);
        }
        return ret;
    }

    private static int createR(BDD bdd, int[] a, int[] b) {
        int N = a.length;
        int ret = 0;
        for (int i = 0; i < 32; ++i) {
            int left = bdd.ref(VariableOrder.numToBDD(bdd, a, i));
            int right = bdd.ref(VariableOrder.numToBDD(bdd, b, i));
            int entry = bdd.ref(bdd.and(left, right));
            bdd.deref(left);
            bdd.deref(right);
            int old = ret;
            ret = bdd.ref(bdd.or(ret, entry));
            bdd.deref(old);
            bdd.deref(entry);
        }
        return ret;
    }

    public static void main(String[] args) {
        int bits = Digits.log2_ceil(32);
        JDDConsole.out.printf("Note: A and B have %d elements, encoded with %d bits\n", 32, bits);
        BDD bdd1 = new BDD(1000);
        int[] bdd1_a = bdd1.createVars(bits);
        int[] bdd1_b = bdd1.createVars(bits);
        int bdd1_r = VariableOrder.createR(bdd1, bdd1_a, bdd1_b);
        JDDConsole.out.printf("DISJOINT ordering: satcount=%.0f BDD-size=%d\n", bdd1.satCount(bdd1_r), bdd1.nodeCount(bdd1_r));
        BDDPrinter.printDot("order_disjoint", bdd1_r, bdd1, null);
        BDD bdd2 = new BDD(1000);
        int[] bdd2_ab = bdd2.createVars(bits * 2);
        int[] bdd2_a = new int[bits];
        int[] bdd2_b = new int[bits];
        for (int i = 0; i < bits; ++i) {
            bdd2_a[i] = bdd2_ab[i * 2 + 0];
            bdd2_b[i] = bdd2_ab[i * 2 + 1];
        }
        int bdd2_r = VariableOrder.createR(bdd2, bdd2_a, bdd2_b);
        JDDConsole.out.printf("INTERLEAVED ordering: satcount=%.0f BDD-size=%d\n", bdd2.satCount(bdd2_r), bdd2.nodeCount(bdd2_r));
        BDDPrinter.printDot("order_interleaved", bdd2_r, bdd2, null);
    }
}

