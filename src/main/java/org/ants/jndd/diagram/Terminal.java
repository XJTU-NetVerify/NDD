package org.ants.jndd.diagram;

import org.ants.jndd.utils.Rational;

public class Terminal extends NDD {
    private final Rational terminalVal;

    Terminal(int nodeId, Rational value) {
        super(nodeId);
        this.terminalVal = value;
    }

    public static Terminal tadd(Terminal a, Terminal b) {
        return (Terminal) createTerminal(a.terminalVal.add(b.terminalVal));
    }

    public static Terminal tminus(Terminal a, Terminal b) {
        return (Terminal) createTerminal(a.terminalVal.subtract(b.terminalVal));
    }

    public static Terminal ttimes(Terminal a, Terminal b) {
        return (Terminal) createTerminal(a.terminalVal.multiply(b.terminalVal));
    }

    public static Terminal tdivide(Terminal a, Terminal b) {
        return (Terminal) createTerminal(a.terminalVal.divide(b.terminalVal));
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
        return terminalVal.doubleValue();
    }

    public Rational get() {
        return terminalVal;
    }
}
