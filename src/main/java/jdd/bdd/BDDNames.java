/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import jdd.util.NodeName;

public class BDDNames
implements NodeName {
    @Override
    public String zero() {
        return "FALSE";
    }

    @Override
    public String one() {
        return "TRUE";
    }

    @Override
    public String zeroShort() {
        return "0";
    }

    @Override
    public String oneShort() {
        return "1";
    }

    @Override
    public String variable(int n) {
        if (n < 0) {
            return "(none)";
        }
        return String.format("v%d", n + 1);
    }
}

