/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

public interface PrintTarget {
    public void printf(String var1, Object ... var2);

    @Deprecated
    public void println(String var1);

    @Deprecated
    public void print(String var1);

    public void print(char var1);

    public void flush();
}

