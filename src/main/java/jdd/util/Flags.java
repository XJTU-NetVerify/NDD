/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

public class Flags {
    private int flags;

    public Flags(int f) {
        this.flags = f;
    }

    public Flags() {
        this(0);
    }

    protected void setAll(int f) {
        this.flags = f;
    }

    public int getAll() {
        return this.flags;
    }

    public void copyFlags(Flags f) {
        this.flags = f.flags;
    }

    private void set(int flag) {
        this.flags |= 1 << flag;
    }

    private void reset(int flag) {
        this.flags &= ~(1 << flag);
    }

    public void set(int f, boolean set) {
        if (set) {
            this.set(f);
        } else {
            this.reset(f);
        }
    }

    public boolean get(int flag) {
        return (this.flags & 1 << flag) != 0;
    }
}

