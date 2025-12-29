/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.bdd.BDD;

public class Simple1 {
    public static void main(String[] args) {
        BDD bdd = new BDD(1000, 100);
        int v1 = bdd.createVar();
        int v2 = bdd.createVar();
        int v3 = bdd.createVar();
        int v4 = bdd.createVar();
        int tmp1 = bdd.and(v1, v2);
        bdd.ref(tmp1);
        int f1 = bdd.or(tmp1, v3);
        bdd.ref(f1);
        bdd.deref(tmp1);
        tmp1 = bdd.ref(bdd.or(v1, v2));
        int tmp2 = bdd.ref(bdd.or(v3, v4));
        int f2 = bdd.ref(bdd.or(tmp1, tmp2));
        bdd.deref(tmp1);
        bdd.deref(tmp2);
        tmp1 = bdd.ref(bdd.not(v4));
        int f3 = bdd.ref(bdd.xor(tmp1, v1));
        bdd.deref(tmp1);
        bdd.print(f1);
        bdd.printSet(f1);
        bdd.printCubes(f1);
        bdd.printDot("f1", f1);
        bdd.cleanup();
    }
}

