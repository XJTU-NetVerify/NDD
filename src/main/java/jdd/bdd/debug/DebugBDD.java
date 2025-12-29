/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd.debug;

import jdd.bdd.BDD;
import jdd.bdd.NodeTableChecker;

public class DebugBDD
extends BDD {
    private NodeTableChecker ntc = new NodeTableChecker(this);

    public DebugBDD(int nodesize) {
        this(nodesize, 1000);
    }

    public DebugBDD(int nodesize, int cache_size) {
        super(nodesize, cache_size);
    }

    private void check_node(int bdd, String msg) {
        String err = null;
        err = this.getRef(bdd) <= 0 ? "Unrefrenced node , '" + msg + "'" : this.ntc.checkNode(bdd, msg);
        if (err != null) {
            this.fatal(null, "nodeCheck failed: " + err);
        }
    }

    @Override
    public int and(int a, int b) {
        this.check_node(a, "AND a");
        this.check_node(b, "AND b");
        return super.and(a, b);
    }

    @Override
    public int or(int a, int b) {
        this.check_node(a, "OR a");
        this.check_node(b, "OR b");
        return super.or(a, b);
    }

    @Override
    public int xor(int a, int b) {
        this.check_node(a, "xor a");
        this.check_node(b, "xor b");
        return super.xor(a, b);
    }

    @Override
    public int biimp(int a, int b) {
        this.check_node(a, "biimp a");
        this.check_node(b, "biimp b");
        return super.biimp(a, b);
    }

    @Override
    public int imp(int a, int b) {
        this.check_node(a, "imp a");
        this.check_node(b, "imp b");
        return super.imp(a, b);
    }

    @Override
    public int nor(int a, int b) {
        this.check_node(a, "nor a");
        this.check_node(b, "nor b");
        return super.nor(a, b);
    }

    @Override
    public int nand(int a, int b) {
        this.check_node(a, "nand a");
        this.check_node(b, "nand b");
        return super.nand(a, b);
    }

    @Override
    public int ite(int a, int b, int c) {
        this.check_node(a, "ite a");
        this.check_node(b, "ite b");
        this.check_node(c, "ite c");
        return super.ite(a, b, c);
    }

    @Override
    public int not(int a) {
        this.check_node(a, "not a");
        return super.not(a);
    }

    @Override
    public int relProd(int u1, int u2, int c) {
        this.check_node(u1, "relProd u1");
        this.check_node(u2, "relProd u2");
        this.check_node(c, "relProd c");
        return super.relProd(u1, u2, c);
    }

    @Override
    protected void post_removal_callbak() {
        super.post_removal_callbak();
        if (!this.quant_cache.check_cache(this)) {
            this.fatal(null, "quant_cache sanity check failed");
        }
        if (!this.replace_cache.check_cache(this)) {
            this.fatal(null, "replace_cache sanity check failed");
        }
        if (!this.not_cache.check_cache(this)) {
            this.fatal(null, "not_cache sanity check failed");
        }
        if (!this.op_cache.check_cache(this)) {
            this.fatal(null, "op_cache sanity check failed");
        }
    }
}

