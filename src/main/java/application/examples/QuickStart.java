package application.examples;

import jdd.bdd.BDD;
import jdd.util.Dot;
import org.ants.jndd.diagram.NDD;

/**
 * Quick Start example from the README: builds the Hadamard matrix H_{2^n} as both a BDD and an
 * NDD and compares their node counts. Arguments: [n] [--dot].
 */
public class QuickStart {
    public static void main(String[] args) {
        // Hadamard matrix H_{2^n}: entry (x, y) is -1 iff x0·y0 XOR x1·y1 XOR ... = 1.
        // Arguments: [n] [--dot]. n defaults to 2, which gives H4 from the figure;
        // --dot also writes both diagrams as Graphviz .dot files.
        int n = 2;
        boolean dot = false;
        for (String arg : args) {
            if (arg.equals("--dot")) dot = true;
            else n = Integer.parseInt(arg);
        }

        // BDD: one variable per bit, ordered x0, y0, x1, y1, ... as in (b).
        BDD bdd = new BDD(10_000, 1_000);
        int h = bdd.getZero();
        for (int i = 0; i < n; i++) {
            int xi = bdd.createVar();
            int yi = bdd.createVar();
            h = bdd.xor(h, bdd.and(xi, yi));
        }

        // NDD: one 2-bit field (xi, yi) per i, as in (c).
        NDD.initNDD(10_000, 1_000, 10_000, 1_000);
        for (int i = 0; i < n; i++) {
            NDD.declareField(2);
        }
        NDD.generateFields();
        int ndd = NDD.getFalse();
        for (int i = 0; i < n; i++) {
            int xiAndYi = NDD.and(NDD.getVar(i, 0), NDD.getVar(i, 1));
            ndd = NDD.apply(NDD.BinaryOperation.XOR, ndd, xiAndYi);
        }

        // An NDD has two kinds of nodes: NDD nodes, and the BDD nodes inside its edge labels.
        System.out.println("BDD: " + bdd.nodeCount(h) + " BDD nodes");             // 4n - 2 (6 for n = 2)
        System.out.println("NDD: " + NDD.nodeCount(ndd) + " NDD nodes + "          // 2n - 1 (3 for n = 2)
                + NDD.labelNodeCount(ndd) + " BDD nodes in edge labels");          // 4 for any n

        if (dot) {
            // Keep the .dot files: by default JDD runs Graphviz on them and then deletes them.
            Dot.setExecuteDot(false);
            Dot.setRemoveDotFile(false);
            bdd.printDot("hadamard-bdd.dot", h);
            NDD.printDot(ndd, "hadamard-ndd.dot");
            System.out.println("Wrote hadamard-bdd.dot and hadamard-ndd.dot");
        }
    }
}
