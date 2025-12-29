/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd.sets;

import jdd.bdd.BDDUtil;
import jdd.bdd.sets.BDDUniverse;
import jdd.util.Test;
import jdd.util.math.Digits;

class SubDomain {
    private BDDUniverse universe;
    int bits;
    int size;
    int all;
    int[] vars;
    int[] numbers;

    SubDomain(BDDUniverse universe, int size) {
        int i;
        Test.checkInequality(size, 0, "Empty subdomain :(");
        this.universe = universe;
        this.size = size;
        this.bits = Digits.log2_ceil(size);
        this.vars = new int[this.bits];
        this.numbers = new int[size];
        for (i = 0; i < this.bits; ++i) {
            this.vars[i] = universe.createVar();
        }
        this.all = 0;
        for (i = 0; i < size; ++i) {
            this.numbers[i] = BDDUtil.numberToBDD(universe, this.vars, i);
            int tmp = universe.ref(universe.or(this.all, this.numbers[i]));
            universe.deref(this.all);
            this.all = tmp;
        }
    }

    public int getSize() {
        return this.size;
    }

    public int find(int bdd) {
        if (bdd == 1 || bdd == 0 || bdd == this.all) {
            return 0;
        }
        for (int i = 0; i < this.size; ++i) {
            if (this.universe.and(bdd, this.numbers[i]) != bdd) continue;
            return i;
        }
        return -1;
    }
}

