/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

import java.awt.TextArea;
import jdd.util.PrintTarget;

public class TextAreaTarget
implements PrintTarget {
    private TextArea ta;

    public TextAreaTarget(TextArea ta) {
        this.ta = ta;
    }

    @Override
    public void printf(String format, Object ... args) {
        this.ta.append(String.format(format, args));
    }

    @Override
    public void println(String str) {
        this.ta.append(str);
        this.ta.append("\n");
    }

    @Override
    public void print(String str) {
        this.ta.append(str);
    }

    @Override
    public void print(char c) {
        this.ta.append("" + c);
    }

    @Override
    public void flush() {
    }
}

