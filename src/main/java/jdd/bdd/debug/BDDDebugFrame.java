/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd.debug;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextArea;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.util.Collection;
import java.util.LinkedList;
import jdd.bdd.CacheBase;
import jdd.bdd.NodeTable;
import jdd.bdd.debug.BDDDebuger;
import jdd.util.JDDConsole;
import jdd.util.PrintTarget;
import jdd.util.TextAreaTarget;

public class BDDDebugFrame
extends Frame
implements WindowListener,
Runnable,
BDDDebuger {
    private static final int SLEEP_TIME = 1000;
    private NodeTable nodetable;
    private Thread thread;
    private boolean stop;
    private LinkedList<CacheFrame> list;
    private Label status;
    private TextArea statistics;

    public BDDDebugFrame(NodeTable nodetable) {
        super("[BDD Profiler]");
        this.nodetable = nodetable;
        Collection<CacheBase> caches = nodetable.addDebugger(this);
        this.list = new LinkedList<>();
        Panel p = new Panel(new GridLayout(3, Math.max(1, caches.size() / 3), 5, 5));
        for (CacheBase cb : caches) {
            CacheFrame cf = new CacheFrame(cb);
            p.add(cf);
            this.list.add(cf);
        }
        this.add((Component)p, "Center");
        this.status = new Label("");
        this.add((Component)this.status, "South");
        this.statistics = new TextArea(10, 80);
        this.add((Component)this.statistics, "North");
        this.statistics.setEditable(false);
        this.statistics.setVisible(false);
        this.addWindowListener(this);
        this.pack();
        this.pack();
        this.setVisible(true);
        this.thread = new Thread(this);
        this.thread.start();
    }

    @Override
    public void run() {
        long update = 0L;
        while (!this.stop) {
            try {
                Thread.sleep(1000L);
                this.status.setText("Update " + ++update);
                for (CacheFrame cf : this.list) {
                    cf.repaint();
                }
            }
            catch (Exception exception) {
            }
        }
        this.status.setText("stopped");
    }

    @Override
    public void stop() {
        if (this.stop) {
            return;
        }
        this.stop = true;
        this.statistics.setVisible(true);
        this.pack();
        this.pack();
        TextAreaTarget taa = new TextAreaTarget(this.statistics);
        PrintTarget save = JDDConsole.out;
        JDDConsole.out = taa;
        JDDConsole.out.printf("\nPackage statistics:\n==================\n", new Object[0]);
        this.nodetable.showStats();
        JDDConsole.out = save;
    }

    @Override
    public void windowActivated(WindowEvent e) {
    }

    @Override
    public void windowClosed(WindowEvent e) {
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
    public void windowClosing(WindowEvent e) {
        this.stop = true;
        this.setVisible(false);
        this.dispose();
    }

    private class MiniGraph {
        private static final int GRAPH_HEIGH = 40;
        private int[] memory;
        private int current;
        private int size;
        private int last;
        private double min;
        private double max;

        public MiniGraph(int size, double min, double max) {
            this.size = size;
            this.current = 0;
            if (min == max) {
                max += 1.0;
            }
            this.memory = new int[size];
            this.min = min;
            this.max = max;
            for (int i = 0; i < size; ++i) {
                this.memory[i] = -1;
            }
        }

        public void add(double v) {
            this.last = (int)(0.5 + (v - this.min) * 100.0 / (this.max - this.min));
            v = (v - this.min) * 40.0 / (this.max - this.min);
            this.current = (this.current + 1) % this.size;
            this.memory[this.current] = 40 - (int)v;
        }

        public void draw(Graphics g, int x0, int y0) {
            g.setColor(Color.lightGray);
            g.fillRect(x0, y0, this.size, 40);
            g.setColor(Color.blue);
            int n = this.current;
            x0 += this.size - 1;
            for (int i = 0; i < this.size; ++i) {
                int p = this.memory[n];
                if (p >= 0 & p <= 40) {
                    g.drawLine(x0, y0 + p, x0, y0 + p + 1);
                }
                --x0;
                if (--n != -1) continue;
                n = this.size - 1;
            }
            g.setColor(Color.black);
            g.drawString("" + this.last, x0 + 5, y0 + 25);
        }
    }

    private class CacheFrame
    extends Canvas {
        private CacheBase cb;
        private MiniGraph g1;
        private MiniGraph g2;

        public CacheFrame(CacheBase cb) {
            this.cb = cb;
            this.g1 = new MiniGraph(95, 0.0, 100.0);
            this.g2 = new MiniGraph(95, 0.0, 100.0);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(200, 90);
        }

        @Override
        public void paint(Graphics g) {
            int h = this.getHeight();
            int w = this.getWidth();
            g.drawRect(1, 1, w - 2, h - 2);
            long accss = this.cb.getAccessCount();
            if (accss == 0L) {
                g.drawString(this.cb.getName() + " unused.", 20, 30);
            } else {
                g.drawString(this.cb.getName() + ", SIZE=" + this.cb.getCacheSize(), 5, 12);
                g.drawString("Load factor and hitrate:", 5, 24);
                this.g1.add(this.cb.computeLoadFactor());
                this.g1.draw(g, 3, 28);
                this.g2.add(this.cb.computeHitRate());
                this.g2.draw(g, 103, 28);
                g.drawString("Acss=" + accss + ", CLRS=" + this.cb.getNumberOfClears() + "/" + this.cb.getNumberOfPartialClears(), 5, 85);
            }
        }
    }
}
