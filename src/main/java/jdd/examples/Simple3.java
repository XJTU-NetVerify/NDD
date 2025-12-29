/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.bdd.BDD;
import jdd.bdd.Permutation;

public class Simple3 {
    public static void main(String[] args) {
        BDD bdd = new BDD(1000, 100);
        int v0 = bdd.createVar();
        int v1 = bdd.createVar();
        int cat = bdd.ref(bdd.minterm("00"));
        int dog = bdd.ref(bdd.minterm("01"));
        int man = bdd.ref(bdd.minterm("10"));
        int mouse = bdd.ref(bdd.minterm("11"));
        int s = bdd.ref(bdd.or(cat, mouse));
        bdd.printSet(s);
        int tmp = bdd.and(s, dog);
        if (tmp == 0) {
            System.out.println("As expected, dog is not in { cat, mouse} ");
        } else {
            System.out.println("Something is very wrong. Or just another glitch in the Matrix");
        }
        int v0p = bdd.createVar();
        int v1p = bdd.createVar();
        int catp = bdd.ref(bdd.minterm("--00"));
        int dogp = bdd.ref(bdd.minterm("--01"));
        int manp = bdd.ref(bdd.minterm("--10"));
        int mousep = bdd.ref(bdd.minterm("--11"));
        int friend = bdd.ref(bdd.and(man, dogp));
        int enemy = bdd.ref(bdd.and(mouse, catp));
        enemy = bdd.orTo(enemy, bdd.ref(bdd.and(cat, dogp)));
        System.out.println("Friend = ");
        bdd.printSet(friend);
        System.out.println("Enemy = ");
        bdd.printSet(enemy);
        int X = bdd.ref(bdd.and(friend, man));
        System.out.println("X = ");
        bdd.printSet(X);
        int cube = bdd.cube("11--");
        int Y = bdd.ref(bdd.exists(X, cube));
        System.out.println("Y = ");
        bdd.printSet(Y);
        Permutation perm = bdd.createPermutation(new int[]{v0p, v1p}, new int[]{v0, v1});
        int Z = bdd.ref(bdd.replace(Y, perm));
        System.out.println("Z = ");
        bdd.printSet(Z);
        if (Z == dog) {
            System.out.println("Don't worry, dog is still mans best friend!");
        } else {
            System.out.println("Dude, your dog just abandoned you...");
        }
        int not_cat = bdd.ref(bdd.not(cat));
        int Wp = bdd.ref(bdd.relProd(enemy, not_cat, cube));
        int W = bdd.ref(bdd.replace(Wp, perm));
        System.out.println("W = ");
        bdd.printSet(W);
        if (W == cat) {
            System.out.println("Good news everyone! We have proof that someone hates the cat!");
        } else {
            System.out.println("This is wrong, maybe the cat has hacked your computer");
        }
        bdd.deref(W);
        bdd.deref(Wp);
        bdd.deref(not_cat);
        bdd.deref(X);
        bdd.deref(Y);
        bdd.deref(Z);
        bdd.deref(friend);
        bdd.deref(enemy);
        bdd.deref(cube);
        bdd.deref(catp);
        bdd.deref(dogp);
        bdd.deref(manp);
        bdd.deref(mousep);
        bdd.deref(cat);
        bdd.deref(dog);
        bdd.deref(man);
        bdd.deref(mouse);
    }
}

