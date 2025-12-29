/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextArea;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import jdd.examples.BDDQueens;
import jdd.examples.ChessBoard;
import jdd.examples.Queens;
import jdd.examples.ZDDCSPQueens;
import jdd.examples.ZDDQueens;
import jdd.util.JDDConsole;
import jdd.util.Options;
import jdd.util.TextAreaTarget;

public class QueensApp
extends Frame
implements WindowListener,
ActionListener {
    private TextArea msg;
    private Button bSolve;
    private Button bClear;
    private Choice cSize;
    private Choice cSolver;
    private Checkbox cbVerbose;
    private ChessBoard board;

    public QueensApp() {
        this.setLayout(new BorderLayout());
        Panel p = new Panel(new FlowLayout(0));
        this.add((Component)p, "North");
        this.bSolve = new Button("Solve!");
        p.add(this.bSolve);
        this.bClear = new Button("Clear");
        p.add(this.bClear);
        p.add(new Label("        N = "));
        this.cSize = new Choice();
        p.add(this.cSize);
        for (int i = 4; i < 14; ++i) {
            this.cSize.add("" + i);
        }
        this.cSize.select(5);
        p.add(new Label("        Solver: "));
        this.cSolver = new Choice();
        p.add(this.cSolver);
        this.cSolver.add("BDD");
        this.cSolver.add("ZDD");
        this.cSolver.add("ZDD-CSP");
        this.cbVerbose = new Checkbox("Verbose");
        p.add(this.cbVerbose);
        this.msg = new TextArea(10, 80);
        this.add((Component)this.msg, "South");
        this.msg.setEditable(false);
        this.bSolve.addActionListener(this);
        this.bClear.addActionListener(this);
        this.board = new ChessBoard();
        this.add((Component)this.board, "Center");
        JDDConsole.out = new TextAreaTarget(this.msg);
        this.addWindowListener(this);
        this.pack();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Object src = e.getSource();
        if (src == this.bSolve) {
            this.doSolve();
        } else if (src == this.bClear) {
            this.doClear();
        }
    }

    private void doClear() {
        this.msg.setText("");
    }

    private Queens getSolver(int n) {
        JDDConsole.out.println("Loading solver '" + this.cSolver.getSelectedItem() + "'...");
        int type = this.cSolver.getSelectedIndex();
        switch (type) {
            case 0: {
                return new BDDQueens(n);
            }
            case 1: {
                return new ZDDQueens(n);
            }
            case 2: {
                return new ZDDCSPQueens(n);
            }
        }
        return null;
    }

    private void doSolve() {
        try {
            int n = Integer.parseInt(this.cSize.getSelectedItem());
            Options.verbose = this.cbVerbose.getState();
            Queens q = this.getSolver(n);
            boolean[] sol = q.getOneSolution();
            this.board.set(sol);
            JDDConsole.out.println("" + q.numberOfSolutions() + " solutions /" + q.getTime() + "ms");
        }
        catch (Exception ex) {
            JDDConsole.out.println("ERROR: " + ex);
            ex.printStackTrace();
        }
    }

    @Override
    public void windowActivated(WindowEvent e) {
    }

    @Override
    public void windowClosed(WindowEvent e) {
    }

    @Override
    public void windowClosing(WindowEvent e) {
        this.setVisible(false);
        this.dispose();
    }

    @Override
    public void windowDeactivated(WindowEvent e) {
    }

    @Override
    public void windowDeiconified(WindowEvent e) {
    }

    @Override
    public void windowIconified(WindowEvent e) {
    }

    @Override
    public void windowOpened(WindowEvent e) {
    }

    public static void main(String[] args) {
        QueensApp app = new QueensApp();
        app.setVisible(true);
    }
}

