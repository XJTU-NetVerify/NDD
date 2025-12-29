/*
 * Decompiled with CFR 0.152.
 */
package jdd.zdd;

import jdd.util.NodeName;

public class ZDDNames
implements NodeName {
    @Override
    public String zero() {
        return "emptyset";
    }

    @Override
    public String one() {
        return "base";
    }

    @Override
    public String zeroShort() {
        return "{}";
    }

    @Override
    public String oneShort() {
        return "{{}}";
    }

    @Override
    public String variable(int n) {
        if (n < 0) {
            return "(none)";
        }
        return "v" + (n + 1);
    }
}

