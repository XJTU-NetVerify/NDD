/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd.sets;

import jdd.bdd.sets.BDDSetEnumeration;
import jdd.bdd.sets.BDDUniverse;
import jdd.util.JDDConsole;
import jdd.util.sets.Set;
import jdd.util.sets.SetEnumeration;

public class BDDSet
implements Set {
    private BDDUniverse universe;
    private boolean[] internal_minterm;
    int bdd;

    BDDSet(BDDUniverse u, int bdd) {
        this.universe = u;
        this.bdd = this.universe.ref(bdd);
        this.internal_minterm = new boolean[this.universe.numberOfBits()];
    }

    @Override
    public double cardinality() {
        return this.universe.satCount(this.bdd);
    }

    @Override
    public void free() {
        this.universe.deref(this.bdd);
    }

    @Override
    public boolean equals(Set s) {
        return this.bdd == ((BDDSet)s).bdd;
    }

    @Override
    public boolean isEmpty() {
        return this.bdd == 0;
    }

    public void assign(Set s) {
        this.universe.deref(this.bdd);
        this.bdd = this.universe.ref(((BDDSet)s).bdd);
    }

    @Override
    public void clear() {
        this.universe.deref(this.bdd);
        this.bdd = 0;
    }

    void show() {
        this.universe.printSet(this.bdd);
    }

    public boolean memberDC(int[] assignment) {
        int x = this.universe.vectorToBDD(assignment);
        int tmp = this.universe.or(x, this.bdd);
        boolean ret = tmp == this.bdd;
        this.universe.deref(x);
        return ret;
    }

    @Override
    public boolean member(int[] assignment) {
        this.universe.vectorToMinterm(assignment, this.internal_minterm);
        return this.universe.member(this.bdd, this.internal_minterm);
    }

    @Override
    public boolean remove(int[] assignment) {
        int x = this.universe.vectorToBDD(assignment);
        int notx = this.universe.ref(this.universe.not(x));
        this.universe.deref(x);
        int tmp = this.universe.ref(this.universe.and(this.bdd, notx));
        this.universe.deref(notx);
        if (tmp == this.bdd) {
            this.universe.deref(tmp);
            return false;
        }
        this.universe.deref(this.bdd);
        this.bdd = tmp;
        return true;
    }

    @Override
    public boolean insert(int[] assignments) {
        int x = this.universe.vectorToBDD(assignments);
        int tmp = this.universe.ref(this.universe.or(this.bdd, x));
        if (tmp == this.bdd) {
            this.universe.deref(tmp);
            return false;
        }
        this.universe.deref(this.bdd);
        this.bdd = tmp;
        return true;
    }

    @Override
    public Set copy() {
        return new BDDSet(this.universe, this.bdd);
    }

    @Override
    public Set invert() {
        int neg = this.universe.ref(this.universe.not(this.bdd));
        BDDSet ret = new BDDSet(this.universe, this.universe.removeDontCares(neg));
        this.universe.deref(neg);
        return ret;
    }

    @Override
    public Set union(Set s) {
        return new BDDSet(this.universe, this.universe.or(this.bdd, ((BDDSet)s).bdd));
    }

    @Override
    public Set intersection(Set s) {
        return new BDDSet(this.universe, this.universe.and(this.bdd, ((BDDSet)s).bdd));
    }

    @Override
    public Set diff(Set s_) {
        BDDSet s = (BDDSet)s_;
        int neg = this.universe.ref(this.universe.not(s.bdd));
        int d = this.universe.and(this.bdd, neg);
        this.universe.deref(neg);
        return new BDDSet(this.universe, d);
    }

    @Override
    public int compare(Set s_) {
        BDDSet s = (BDDSet)s_;
        if (s.bdd == this.bdd) {
            return 0;
        }
        int u = this.universe.or(this.bdd, s.bdd);
        if (u == this.bdd) {
            return 1;
        }
        if (u == s.bdd) {
            return -1;
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public SetEnumeration elements() {
        return new BDDSetEnumeration(this.universe, this.bdd);
    }

    public void show(String name) {
        JDDConsole.out.print(name + " = ");
        if (this.bdd == 0) {
            JDDConsole.out.println("empty set");
            return;
        }
        JDDConsole.out.print("{\n  ");
        SetEnumeration se = this.elements();
        int j = 0;
        while (se.hasMoreElements()) {
            int[] x = se.nextElement();
            this.universe.print(x);
            if ((j += x.length + 1) > 20) {
                j = 0;
                JDDConsole.out.print("\n  ");
                continue;
            }
            JDDConsole.out.print(" ");
        }
        if (j != 0) {
            JDDConsole.out.printf("\n", new Object[0]);
        }
        JDDConsole.out.println("\r}");
        se.free();
    }
}

