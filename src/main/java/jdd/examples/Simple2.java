/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.bdd.BDD;

public class Simple2 {
    public static void main(String[] args) {
        BDD bdd = new BDD(1000, 100);
        int v1 = bdd.createVar();
        int v2 = bdd.createVar();
        int v3 = bdd.createVar();
        int tmp = bdd.ref(bdd.or(v1, v2));
        int f = bdd.ref(bdd.or(tmp, v3));
        bdd.deref(tmp);
        int cube = bdd.ref(bdd.and(v1, v2));
        int b = bdd.ref(bdd.forall(f, cube));
        System.out.print("'b = ");
        bdd.printCubes(b);
        int a = bdd.ref(bdd.exists(f, cube));
        System.out.print("'a' = ");
        bdd.printCubes(a);
        if (a == 0) {
            System.out.println("sorry man, 'a' is FALSE");
        } else if (a == 1) {
            System.out.println("hurray, 'a' is TRUE!");
        }
        bdd.deref(a);
        bdd.deref(b);
        bdd.deref(f);
        bdd.ref(cube);
        bdd.cleanup();
    }
}

