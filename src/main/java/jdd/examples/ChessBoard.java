/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import jdd.util.Array;

class ChessBoard
extends Canvas {
    private int n = 8;
    private boolean[] board = new boolean[this.n * this.n];

    public ChessBoard() {
        int d = Math.min(400, this.n * 50);
        this.setSize(new Dimension(d, d));
    }

    void set(boolean[] board) {
        this.board = Array.clone(board);
        this.n = (int)Math.sqrt(board.length);
        this.repaint();
    }

    @Override
    public void paint(Graphics g) {
        Dimension dims = this.getSize();
        int h = 1 + dims.height;
        int w = 1 + dims.width;
        int d = Math.max(20, Math.min(h / this.n, w / this.n));
        int x0 = w - d * this.n;
        int y0 = h - d * this.n;
        g.translate(x0 / 2, y0 / 2);
        g.setColor(Color.black);
        g.drawRect(0, 0, this.n * d, this.n * d);
        for (int x = 0; x < this.n; ++x) {
            for (int y = 0; y < this.n; ++y) {
                if ((x + y) % 2 != 0) continue;
                g.fillRect(x * d, y * d, d, d);
            }
        }
        g.setColor(Color.red);
        int m = d / 8;
        int e = d - 2 * m;
        for (int x = 0; x < this.n; ++x) {
            for (int y = 0; y < this.n; ++y) {
                if (!this.board[x + this.n * y]) continue;
                g.fillOval(m + x * d, m + y * d, e, e);
            }
        }
    }
}

