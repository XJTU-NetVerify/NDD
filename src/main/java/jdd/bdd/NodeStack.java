/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import jdd.util.Allocator;

public final class NodeStack {
    private int tos = 0;
    private int[] stack;

    public NodeStack(int size) {
        this.stack = new int[size];
    }

    public int push(int node) {
        this.stack[this.tos++] = node;
        return node;
    }

    public int pop() {
        return this.stack[--this.tos];
    }

    public void drop(int count) {
        this.tos -= count;
    }

    public void reset() {
        this.tos = 0;
    }

    public int getCapacity() {
        return this.stack.length;
    }

    public int getTOS() {
        return this.tos;
    }

    public int[] getData() {
        return this.stack;
    }

    public void grow(int newsize) {
        if (this.stack.length < newsize) {
            int[] newstack = Allocator.allocateIntArray(newsize);
            for (int i = 0; i < this.tos; ++i) {
                newstack[i] = this.stack[i];
            }
            this.stack = newstack;
        }
    }
}

