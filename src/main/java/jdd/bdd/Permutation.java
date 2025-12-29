/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import jdd.bdd.NodeTable;
import jdd.util.Allocator;
import jdd.util.Array;
import jdd.util.JDDConsole;
import jdd.util.Test;
import jdd.util.math.HashFunctions;

public class Permutation {
    private static int id_c = 0;
    int last;
    int first;
    int id;
    int hash;
    int[] perm;
    int[] from;
    int[] to;
    Permutation next;

    Permutation(int[] from, int[] to, NodeTable nt) {
        int i;
        Test.check(from.length == to.length, "Permutations vectors must have equal length");
        Test.check(from.length > 0, "non empty pemuration vectors");
        this.from = Array.clone(from);
        this.to = Array.clone(to);
        int len = from.length;
        int[] f = new int[len];
        int[] t = new int[len];
        for (i = 0; i < len; ++i) {
            f[i] = nt.getVar(from[i]);
            t[i] = nt.getVar(to[i]);
        }
        this.first = this.last = f[0];
        for (i = 1; i < len; ++i) {
            if (this.last < f[i]) {
                this.last = f[i];
            }
            if (this.first <= f[i]) continue;
            this.first = f[i];
        }
        this.perm = Allocator.allocateIntArray(this.last + 1);
        for (i = 0; i < this.last; ++i) {
            this.perm[i] = i;
        }
        for (i = 0; i < len; ++i) {
            this.perm[f[i]] = t[i];
        }
        this.next = null;
        this.hash = Permutation.computeHash(from, to);
        this.id = id_c++;
    }

    public void show() {
        JDDConsole.out.println("-----------------------------");
        for (int i = this.first; i <= this.last; ++i) {
            JDDConsole.out.println(" " + i + " --> " + this.perm[i]);
        }
    }

    public long getMemoryUsage() {
        return this.perm.length * 4 + this.from.length * 4 + this.to.length * 4;
    }

    static int computeHash(int[] from, int[] to) {
        int hash1 = HashFunctions.hash_FNV(from, 0, from.length);
        int hash2 = HashFunctions.hash_FNV(to, 0, to.length);
        return HashFunctions.hash_FNV(hash1, hash2, 0);
    }

    static Permutation findPermutation(Permutation first, int[] from, int[] to) {
        int new_hash = Permutation.computeHash(from, to);
        while (first != null) {
            if (first.equals(new_hash, from, to)) {
                return first;
            }
            first = first.next;
        }
        return null;
    }

    boolean equals(int hash, int[] from, int[] to) {
        if (hash != this.hash) {
            return false;
        }
        if (from.length != this.from.length || to.length != this.to.length) {
            return false;
        }
        if (!Array.equals(from, this.from, from.length)) {
            return false;
        }
        return Array.equals(to, this.to, to.length);
    }
}

