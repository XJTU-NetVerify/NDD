/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

import jdd.util.PrintTarget;

public class StdoutTarget
implements PrintTarget {
    @Override
    public void printf(String format, Object ... args) {
        System.out.printf(format, args);
    }

    @Override
    public void println(String s) {
        System.out.println(s);
    }

    @Override
    public void print(String s) {
        System.out.print(s);
    }

    @Override
    public void print(char c) {
        System.out.print(c);
    }

    @Override
    public void flush() {
        System.out.flush();
    }
}

