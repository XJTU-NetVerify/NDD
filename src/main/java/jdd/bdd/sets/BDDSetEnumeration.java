/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd.sets;

import jdd.bdd.sets.BDDUniverse;
import jdd.util.sets.SetEnumeration;

public class BDDSetEnumeration
implements SetEnumeration {
    private BDDUniverse universe;
    private int bdd;
    private int[] vec;

    BDDSetEnumeration(BDDUniverse u, int bdd) {
        this.universe = u;
        this.bdd = bdd;
        this.vec = new int[this.universe.subdomainCount()];
        this.universe.ref(bdd);
    }

    @Override
    public void free() {
        this.universe.deref(this.bdd);
        this.bdd = 0;
    }

    @Override
    public boolean hasMoreElements() {
        return this.bdd != 0;
    }

    @Override
    public int[] nextElement() {
        this.universe.satOneVector(this.bdd, this.vec);
        int sat1 = this.universe.ref(this.universe.vectorToBDD(this.vec));
        int not_sat1 = this.universe.ref(this.universe.not(sat1));
        this.universe.deref(sat1);
        int tmp = this.universe.ref(this.universe.and(not_sat1, this.bdd));
        this.universe.deref(not_sat1);
        this.universe.deref(this.bdd);
        this.bdd = tmp;
        return this.vec;
    }
}

