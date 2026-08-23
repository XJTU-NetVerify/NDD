package org.ants.jndd.diagram;

import org.ants.jndd.utils.Rational;

public class Terminal extends NDD {
    Terminal(int nodeId) {
        super(nodeId);
    }

    public static Terminal tadd(Terminal a, Terminal b) {
        return (Terminal) a.plus(b);
    }

    public static Terminal tminus(Terminal a, Terminal b) {
        return (Terminal) a.minus(b);
    }

    public static Terminal ttimes(Terminal a, Terminal b) {
        return (Terminal) a.times(b);
    }

    public static Terminal tdivide(Terminal a, Terminal b) {
        return (Terminal) a.divide(b);
    }

    public Terminal tadd(Terminal b) {
        return tadd(this, b);
    }

    public Terminal tminus(Terminal b) {
        return tminus(this, b);
    }

    public Terminal ttimes(Terminal b) {
        return ttimes(this, b);
    }

    public Terminal tdivide(Terminal b) {
        return tdivide(this, b);
    }

    @Override
    public double getTerminalVal() {
        return super.getTerminalVal();
    }

    public Rational get() {
        return getTerminalRational();
    }
}
