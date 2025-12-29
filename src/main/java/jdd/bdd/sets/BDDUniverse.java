/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd.sets;

import jdd.bdd.BDD;
import jdd.bdd.BDDUtil;
import jdd.bdd.sets.BDDSet;
import jdd.bdd.sets.SubDomain;
import jdd.util.Array;
import jdd.util.JDDConsole;
import jdd.util.sets.Set;
import jdd.util.sets.Universe;

public class BDDUniverse
extends BDD
implements Universe {
    private int[] int_subdomains;
    private int[] int_bits;
    private double domainsize;
    private int num_subdomains;
    private int all;
    private int bits;
    private SubDomain[] subdomains;
    private int[] sat_vec = null;
    private int sat_curr;
    private int sat_level;
    private int sat_next;
    private int sat_index;
    private int sat_bit;

    public BDDUniverse(int[] domains) {
        super(1000, 1000);
        int i;
        this.num_subdomains = domains.length;
        this.int_subdomains = Array.clone(domains);
        this.int_bits = new int[this.num_subdomains];
        this.subdomains = new SubDomain[this.num_subdomains];
        this.domainsize = 1.0;
        this.bits = 0;
        for (i = 0; i < this.num_subdomains; ++i) {
            this.subdomains[i] = new SubDomain(this, this.int_subdomains[i]);
            this.domainsize *= (double)this.int_subdomains[i];
            this.int_bits[i] = this.subdomains[i].bits;
            this.bits += this.subdomains[i].bits;
        }
        this.all = 1;
        for (i = 0; i < this.num_subdomains; ++i) {
            int tmp = this.ref(this.and(this.all, this.subdomains[i].all));
            this.deref(this.all);
            this.all = tmp;
        }
    }

    @Override
    public void free() {
        this.cleanup();
        this.subdomains = null;
    }

    int vectorToBDD(int[] assignments) {
        int ret = 1;
        for (int i = 0; i < this.num_subdomains; ++i) {
            if (assignments[i] == -1) continue;
            int tmp = this.ref(this.and(ret, this.subdomains[i].numbers[assignments[i]]));
            this.deref(ret);
            ret = tmp;
        }
        return ret;
    }

    void vectorToMinterm(int[] assignments, boolean[] minterm) {
        int index = 0;
        for (int i = 0; i < this.num_subdomains; ++i) {
            if (assignments[i] == -1) continue;
            BDDUtil.numberToMinterm(assignments[i], this.int_bits[i], index, minterm);
            index += this.int_bits[i];
        }
    }

    public int cardinality(int[] x) {
        int ret = 1;
        for (int i = 0; i < this.num_subdomains; ++i) {
            if (x[i] != -1) continue;
            ret *= this.subdomains[i].getSize();
        }
        return ret;
    }

    @Override
    public Set createEmptySet() {
        return new BDDSet(this, 0);
    }

    @Override
    public Set createFullSet() {
        return new BDDSet(this, this.all);
    }

    public Set simplify(Set s1, Set s2) {
        int new_bdd = this.restrict(((BDDSet)s1).bdd, ((BDDSet)s2).bdd);
        return new BDDSet(this, new_bdd);
    }

    @Override
    public double domainSize() {
        return this.domainsize;
    }

    @Override
    public int subdomainCount() {
        return this.num_subdomains;
    }

    public int numberOfBits() {
        return this.bits;
    }

    int removeDontCares(int bdd) {
        return this.and(bdd, this.all);
    }

    public void print(int[] v) {
        JDDConsole.out.print("<");
        for (int i = 0; i < v.length; ++i) {
            if (i > 0) {
                JDDConsole.out.print(", ");
            }
            if (v[i] == -1) {
                JDDConsole.out.print("-");
                continue;
            }
            JDDConsole.out.print("" + v[i]);
        }
        JDDConsole.out.print(">");
    }

    public void randomMember(int[] out) {
        for (int i = 0; i < this.num_subdomains; ++i) {
            out[i] = (int)(Math.random() * (double)this.int_subdomains[i]);
        }
    }

    public void satOneVector(int bdd, int[] vec) {
        this.sat_vec = vec;
        this.sat_bit = 0;
        this.sat_index = 0;
        this.sat_level = 0;
        this.sat_curr = 0;
        this.sat_next = this.subdomains[0].bits;
        this.satOneVector_rec(bdd);
        while (this.sat_index < this.num_subdomains) {
            this.satOneVector_insert(false);
        }
        this.sat_vec = null;
    }

    private void satOneVector_insert(boolean x) {
        if (x) {
            this.sat_curr |= 1 << this.sat_bit;
        }
        if (++this.sat_level == this.sat_next) {
            this.sat_vec[this.sat_index++] = this.sat_curr;
            this.sat_curr = 0;
            this.sat_bit = 0;
            if (this.sat_index < this.num_subdomains) {
                this.sat_next += this.subdomains[this.sat_index].bits;
            }
        } else {
            ++this.sat_bit;
        }
    }

    private void satOneVector_rec(int bdd) {
        if (bdd < 2) {
            return;
        }
        while (this.getVar(bdd) > this.sat_level) {
            this.satOneVector_insert(false);
        }
        if (this.getLow(bdd) == 0) {
            this.satOneVector_insert(true);
            this.satOneVector_rec(this.getHigh(bdd));
        } else {
            this.satOneVector_insert(false);
            this.satOneVector_rec(this.getLow(bdd));
        }
    }
}

