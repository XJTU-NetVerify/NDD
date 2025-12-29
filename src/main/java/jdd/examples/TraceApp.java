/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextArea;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import jdd.bdd.debug.BDDTrace;
import jdd.util.JDDConsole;
import jdd.util.Options;
import jdd.util.TextAreaTarget;

public class TraceApp
extends Frame
implements ActionListener,
WindowListener {
    private TextArea msg;
    private TextArea code;
    private Button bRun;
    private Button bClear;
    private Button bLoad;
    private Checkbox cbVerbose;
    private Choice initialNodes;
    private String initial_text = "MODULE c17\nINPUT\n\t1gat,2gat,3gat,6gat,7gat;\nOUTPUT\n\t22gat,23gat;\nSTRUCTURE\n\t10gat = nand(1gat, 3gat);\n\t11gat = nand(3gat, 6gat);\n\t16gat = nand(2gat, 11gat);\n\t19gat = nand(11gat, 7gat);\n\t22gat = nand(10gat, 16gat);\n\t23gat = nand(16gat, 19gat);\n\tprint_bdd(23gat);\nENDMODULE\n";

    public TraceApp() {
        this.setLayout(new BorderLayout());
        Panel p = new Panel(new FlowLayout(0));
        this.add((Component)p, "North");
        this.bRun = new Button("Run");
        p.add(this.bRun);
        this.bLoad = new Button("Load file");
        p.add(this.bLoad);
        this.bClear = new Button("Clear");
        p.add(this.bClear);
        this.bRun.addActionListener(this);
        this.bLoad.addActionListener(this);
        this.bClear.addActionListener(this);
        p.add(new Label("  Initial node-base"));
        this.initialNodes = new Choice();
        p.add(this.initialNodes);
        this.initialNodes.add("10");
        this.initialNodes.add("100");
        this.initialNodes.add("1000");
        this.initialNodes.add("10000");
        this.initialNodes.add("100000");
        this.initialNodes.select(3);
        this.cbVerbose = new Checkbox("verbose", false);
        p.add(this.cbVerbose);
        this.code = new TextArea(25, 80);
        this.add((Component)this.code, "Center");
        this.msg = new TextArea(16, 80);
        this.add((Component)this.msg, "South");
        this.msg.setEditable(false);
        this.msg.setText("\n       This is C17, from Yirng-An Chen's ISCAS'85 traces.\n\n");
        this.msg.setFont(new Font(null, 0, 10));
        JDDConsole.out = new TextAreaTarget(this.msg);
        this.code.setFont(new Font("Monospaced", 0, 16));
        this.code.setBackground(Color.yellow);
        this.code.setForeground(Color.red);
        this.code.setText(this.initial_text);
        this.addWindowListener(this);
        this.pack();
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

    @Override
    public void actionPerformed(ActionEvent e) {
        Object src = e.getSource();
        if (src == this.bRun) {
            this.doRun();
        } else if (src == this.bClear) {
            this.doClear();
        } else if (src == this.bLoad) {
            this.doLoad();
        }
    }

    private void doClear() {
        this.msg.setText("");
    }

    private void doRun() {
        BDDTrace.verbose = Options.verbose = this.cbVerbose.getState();
        ByteArrayInputStream sbis = new ByteArrayInputStream(this.code.getText().getBytes());
        int nodes = Integer.parseInt(this.initialNodes.getSelectedItem());
        try {
            BDDTrace bDDTrace = new BDDTrace("(memory)", sbis, nodes);
        }
        catch (IOException exx) {
            JDDConsole.out.println("ERROR: " + exx);
        }
    }

    private void doLoad() {
        FileDialog fd = new FileDialog((Frame)this, "Load trace file", 0);
        fd.setVisible(true);
        try {
            File[] fileArray = fd.getFiles();
            int n = fileArray.length;
            int n2 = 0;
            if (n2 < n) {
                File f = fileArray[n2];
                FileInputStream is = new FileInputStream(f);
                StringBuilder sb = new StringBuilder();
                int i = ((InputStream)is).read();
                while (i != -1) {
                    sb.append((char)i);
                    i = ((InputStream)is).read();
                }
                ((InputStream)is).close();
                this.code.setText(sb.toString());
                return;
            }
        }
        catch (Exception ex) {
            JDDConsole.out.println("ERROR: " + ex);
        }
    }

    public static void main(String[] args) {
        TraceApp app = new TraceApp();
        app.setVisible(true);
    }
}

