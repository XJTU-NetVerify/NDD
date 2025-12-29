/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.bdd.Permutation;
import jdd.bdd.debug.ProfiledBDD2;
import jdd.util.Configuration;
import jdd.util.JDDConsole;
import jdd.util.Options;

public class Solitaire
extends ProfiledBDD2 {
    private static final int SIZE = 33;
    private static final int CENTER = 16;
    private int[] boardC = new int[33];
    private int[] not_boardC = new int[33];
    private int[] boardN = new int[33];
    private int[] not_boardN = new int[33];
    private double dummyStateNum;
    private int I;
    private int T;
    private int currentvar;
    Permutation pair;
    private static final int[][] moves = new int[][]{{1, 4, 9}, {1, 2, 3}, {2, 5, 10}, {3, 2, 1}, {3, 6, 11}, {4, 5, 6}, {4, 9, 16}, {5, 10, 17}, {6, 5, 4}, {6, 11, 18}, {7, 8, 9}, {7, 14, 21}, {8, 9, 10}, {8, 15, 22}, {9, 8, 7}, {9, 10, 11}, {9, 4, 1}, {9, 16, 23}, {10, 9, 8}, {10, 11, 12}, {10, 5, 2}, {10, 17, 24}, {11, 10, 9}, {11, 12, 13}, {11, 6, 3}, {11, 18, 25}, {12, 11, 10}, {12, 19, 26}, {13, 12, 11}, {13, 20, 27}, {14, 15, 16}, {15, 16, 17}, {16, 15, 14}, {16, 17, 18}, {16, 9, 4}, {16, 23, 28}, {17, 16, 15}, {17, 18, 19}, {17, 10, 5}, {17, 24, 29}, {18, 17, 16}, {18, 19, 20}, {18, 11, 6}, {18, 25, 30}, {19, 18, 17}, {20, 19, 18}, {21, 22, 23}, {21, 14, 7}, {22, 23, 24}, {22, 15, 8}, {23, 22, 21}, {23, 24, 25}, {23, 16, 9}, {23, 28, 31}, {24, 23, 22}, {24, 25, 26}, {24, 17, 10}, {24, 29, 32}, {25, 24, 23}, {25, 26, 27}, {25, 18, 11}, {25, 30, 33}, {26, 25, 24}, {26, 19, 12}, {27, 26, 25}, {27, 20, 13}, {28, 29, 30}, {28, 23, 16}, {29, 24, 17}, {30, 29, 28}, {30, 25, 18}, {31, 32, 33}, {31, 28, 23}, {32, 29, 24}, {33, 32, 31}, {33, 30, 25}};

    public Solitaire() {
        super(8300000, 63000);
        Configuration.minFreeNodesProcent = 1;
    }

    public void setup() {
        this.dummyStateNum = Math.pow(2.0, 33.0);
        this.make_board();
        this.make_transition_relation();
        this.make_initial_state();
    }

    private void make_board() {
        for (int n = 0; n < 33; ++n) {
            this.boardC[n] = this.createVar();
            this.not_boardC[n] = this.ref(this.not(this.boardC[n]));
            this.boardN[n] = this.createVar();
            this.not_boardN[n] = this.ref(this.not(this.boardN[n]));
        }
    }

    private void make_initial_state() {
        this.I = 1;
        for (int n = 0; n < 33; ++n) {
            this.I = this.andTo(this.I, n == 16 ? this.not_boardC[n] : this.boardC[n]);
        }
    }

    private int all_other_idle(int src, int tmp, int dst) {
        int idle = 1;
        for (int n = 0; n < 33; ++n) {
            if (n == src || n == tmp || n == dst) continue;
            int tmp2 = this.ref(this.biimp(this.boardC[n], this.boardN[n]));
            idle = this.andTo(idle, tmp2);
            this.deref(tmp2);
        }
        return idle;
    }

    private int make_move(int src, int tmp, int dst) {
        int tmp1 = this.ref(this.and(this.boardC[src], this.not_boardN[src]));
        int tmp2 = this.ref(this.and(this.boardC[tmp], this.not_boardN[tmp]));
        int tmp5 = this.ref(this.and(tmp1, tmp2));
        this.deref(tmp1);
        this.deref(tmp2);
        int tmp3 = this.ref(this.and(this.boardN[dst], this.not_boardC[dst]));
        int tmp4 = this.all_other_idle(src, tmp, dst);
        int tmp6 = this.ref(this.and(tmp3, tmp4));
        this.deref(tmp3);
        this.deref(tmp4);
        int move = this.ref(this.and(tmp5, tmp6));
        this.deref(tmp5);
        this.deref(tmp6);
        return move;
    }

    private void make_transition_relation() {
        this.T = 0;
        for (int n = 0; n < moves.length; ++n) {
            int tmp = this.make_move(moves[n][0] - 1, moves[n][1] - 1, moves[n][2] - 1);
            this.T = this.orTo(this.T, tmp);
            this.deref(tmp);
        }
        JDDConsole.out.println("Transition relation: " + this.nodeCount(this.T) + " nodes, " + this.satCount(this.T) + " distinct transitions.");
    }

    private void make_itedata() {
        this.pair = this.createPermutation(this.boardN, this.boardC);
        this.currentvar = 1;
        for (int n = 0; n < 33; ++n) {
            this.currentvar = this.andTo(this.currentvar, this.boardC[n]);
        }
    }

    private void iterate() {
        int tmp;
        int reachable = this.I;
        int cou = 1;
        this.make_itedata();
        do {
            tmp = reachable;
            int next = this.ref(this.relProd(reachable, this.T, this.currentvar));
            int tmp2 = this.ref(this.replace(next, this.pair));
            this.deref(next);
            reachable = this.orTo(reachable, tmp2);
            this.deref(tmp2);
            JDDConsole.out.println("" + cou + ": " + this.nodeCount(reachable) + " nodes, " + this.satCount(reachable) / this.dummyStateNum + " states.");
            ++cou;
        } while (tmp != reachable);
    }

    public static void main(String[] args) {
        Options.verbose = true;
        long c1 = System.currentTimeMillis();
        Solitaire s = new Solitaire();
        s.setup();
        s.iterate();
        s.showStats();
        long c2 = System.currentTimeMillis();
        JDDConsole.out.println("Time: " + (c2 - c1) + " [ms]");
    }
}

