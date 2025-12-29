/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import jdd.bdd.BDDNames;
import jdd.bdd.NodeTable;
import jdd.util.Allocator;
import jdd.util.Dot;
import jdd.util.JDDConsole;
import jdd.util.NodeName;

public class BDDPrinter {
    private static PrintStream ps;
    private static boolean had_0;
    private static boolean had_1;
    private static char[] set_chars;
    private static int set_chars_len;

    private static void helpGC() {
        ps = null;
    }

    private static final void print_unmark(int bdd, NodeTable nt) {
        if (bdd == 0 || bdd == 1) {
            return;
        }
        if (!nt.isNodeMarked(bdd)) {
            return;
        }
        nt.unmark_node(bdd);
        BDDPrinter.print_unmark(nt.getLow(bdd), nt);
        BDDPrinter.print_unmark(nt.getHigh(bdd), nt);
    }

    public static void print(int bdd, NodeTable nt) {
        JDDConsole.out.printf("\nBDD %d\n", bdd);
        BDDPrinter.print_rec(bdd, nt);
        BDDPrinter.print_unmark(bdd, nt);
        BDDPrinter.helpGC();
    }

    public static void print_rec(int i, NodeTable nt) {
        if (i < 2) {
            return;
        }
        if (nt.isNodeMarked(i)) {
            return;
        }
        JDDConsole.out.printf("%d\t%d\t%d\t%d\n", i, nt.getVar(i), nt.getLow(i), nt.getHigh(i));
        nt.mark_node(i);
        BDDPrinter.print_rec(nt.getLow(i), nt);
        BDDPrinter.print_rec(nt.getHigh(i), nt);
    }

    public static void printDot(String filename, int bdd, NodeTable nt, NodeName nn) {
        if (nn == null) {
            nn = new BDDNames();
        }
        try {
            ps = new PrintStream(new FileOutputStream(filename));
            had_1 = false;
            had_0 = false;
            ps.println("digraph G {");
            ps.println("\tinit__ [label=\"\", style=invis, height=0, width=0];");
            ps.println("\tinit__ -> " + bdd + ";");
            BDDPrinter.printDot_rec(bdd, nt, nn);
            if (had_0) {
                ps.println("0 [shape=box, label=\"" + nn.zeroShort() + "\", style=filled, shape=box, height=0.3, width=0.3];");
            }
            if (had_1) {
                ps.println("1 [shape=box, label=\"" + nn.oneShort() + "\", style=filled, shape=box, height=0.3, width=0.3];\n");
            }
            ps.println("}\n");
            BDDPrinter.print_unmark(bdd, nt);
            ps.close();
            Dot.showDot(filename);
            BDDPrinter.helpGC();
        }
        catch (IOException exx) {
            JDDConsole.out.printf("BDDPrinter.printDOT failed: %s\n", exx);
        }
    }

    private static void printDot_rec(int bdd, NodeTable nt, NodeName nn) {
        if (bdd == 0) {
            had_0 = true;
            return;
        }
        if (bdd == 1) {
            had_1 = true;
            return;
        }
        if (nt.isNodeMarked(bdd)) {
            return;
        }
        int low = nt.getLow(bdd);
        int high = nt.getHigh(bdd);
        int var = nt.getVar(bdd);
        nt.mark_node(bdd);
        ps.println("" + bdd + "[label=\"" + nn.variable(var) + ":" + bdd + "\"];");
        ps.println("" + bdd + "-> " + low + " [style=dotted];");
        ps.println("" + bdd + "-> " + high + " [style=filled];");
        BDDPrinter.printDot_rec(low, nt, nn);
        BDDPrinter.printDot_rec(high, nt, nn);
    }

    public static void printSet(int bdd, int max, NodeTable nt, NodeName nn) {
        if (bdd < 2) {
            if (nn == null) {
                JDDConsole.out.printf("%s\n", bdd == 0 ? "FALSE" : "TRUE");
            } else {
                JDDConsole.out.printf("%s\n", bdd == 0 ? nn.zero() : nn.one());
            }
        } else {
            if (set_chars == null || set_chars.length < max) {
                set_chars = Allocator.allocateCharArray(max);
            }
            set_chars_len = max;
            BDDPrinter.printSet_rec(bdd, 0, nt, nn);
            JDDConsole.out.printf("\n", new Object[0]);
            BDDPrinter.helpGC();
        }
    }

    private static void printSet_rec(int bdd, int level, NodeTable nt, NodeName nn) {
        if (level == set_chars_len) {
            if (nn == null) {
                for (int i = 0; i < set_chars_len; ++i) {
                    JDDConsole.out.print(set_chars[i]);
                }
            } else {
                for (int i = 0; i < set_chars_len; ++i) {
                    if (set_chars[i] != '1') continue;
                    JDDConsole.out.printf("%s ", nn.variable(i));
                }
            }
            JDDConsole.out.printf("\n", new Object[0]);
            return;
        }
        int var = nt.getVar(bdd);
        if (var > level || bdd == 1) {
            BDDPrinter.set_chars[level] = 45;
            BDDPrinter.printSet_rec(bdd, level + 1, nt, nn);
            return;
        }
        int low = nt.getLow(bdd);
        int high = nt.getHigh(bdd);
        if (low != 0) {
            BDDPrinter.set_chars[level] = 48;
            BDDPrinter.printSet_rec(low, level + 1, nt, nn);
        }
        if (high != 0) {
            BDDPrinter.set_chars[level] = 49;
            BDDPrinter.printSet_rec(high, level + 1, nt, nn);
        }
    }

    static {
        set_chars = null;
    }
}

