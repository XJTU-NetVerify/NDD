package application.nqueens;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import jdd.bdd.BDD;

public class BDDSolution {
    private static final class Result {
        final double solutions;
        final long nodesCreated;
        final long nodesAlive;
        final double seconds;

        Result(double solutions, long nodesCreated, long nodesAlive, double seconds) {
            this.solutions = solutions;
            this.nodesCreated = nodesCreated;
            this.nodesAlive = nodesAlive;
            this.seconds = seconds;
        }
    }

    private static BDD bddEngine;
    private static final int BDD_FALSE = 0;
    private static final int BDD_TRUE = 1;
    private static int[][] vars;
    private static int[][] notVars;

    private static void declareVariables(int n) {
        vars = new int[n][n];
        notVars = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int var = bddEngine.createVar();
                vars[i][j] = var;
                notVars[i][j] = bddEngine.ref(bddEngine.not(var));
            }
        }
    }

    private static void build(int i, int j, int n, int[][] impBatch) {
        int a, b, c, d;
        a = b = c = d = BDD_TRUE;

        int k, l;

        /* No one in the same column */
        for (l = 0; l < n; l++) {
            if (l != j) {
                int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[i][l]));
                a = bddEngine.andTo(a, mp);
                bddEngine.deref(mp);
            }
        }

        /* No one in the same row */
        for (k = 0; k < n; k++) {
            if (k != i) {
                int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[k][j]));
                b = bddEngine.andTo(b, mp);
                bddEngine.deref(mp);
            }
        }

        /* No one in the same up-right diagonal */
        for (k = 0; k < n; k++) {
            int ll = k - i + j;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[k][ll]));
                    c = bddEngine.andTo(c, mp);
                    bddEngine.deref(mp);
                }
            }
        }

        /* No one in the same down-right diagonal */
        for (k = 0; k < n; k++) {
            int ll = i + j - k;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[k][ll]));
                    d = bddEngine.andTo(d, mp);
                    bddEngine.deref(mp);
                }
            }
        }

        c = bddEngine.andTo(c, d);
        b = bddEngine.andTo(b, c);
        a = bddEngine.andTo(a, b);
        bddEngine.deref(d);
        impBatch[i][j] = a;
    }

    private static Result solve(int n, String dotFile) {
        jdd.bdd.NodeTable.mkCount = 0;
        bddEngine = new BDD(1 + Math.max(1000, (int) (Math.pow(4.4, n - 6)) * 1000), 10000);

        long startTimeNanos = System.nanoTime();

        declareVariables(n);

        int[] orBatch = new int[n];
        int[][] impBatch = new int[n][n];

        for (int i = 0; i < n; i++) {
            int condition = BDD_FALSE;
            for (int j = 0; j < n; j++) {
                condition = bddEngine.orTo(condition, vars[i][j]);
            }
            orBatch[i] = condition;
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                build(i, j, n, impBatch);
            }
        }

        int queen = BDD_TRUE;

        for (int i = 0; i < n; i++) {
            queen = bddEngine.andTo(queen, orBatch[i]);
            bddEngine.deref(orBatch[i]);
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                queen = bddEngine.andTo(queen, impBatch[i][j]);
                bddEngine.deref(impBatch[i][j]);
            }
        }

        double solutions = bddEngine.satCount(queen);
        long nodesCreated = jdd.bdd.NodeTable.mkCount;
        long nodesAlive = bddEngine.nodeCount(queen);
        if (dotFile != null) {
            writeDot(dotFile, queen, n);
        }
        double seconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        return new Result(solutions, nodesCreated, nodesAlive, seconds);
    }

    private static void writeDot(String dotFile, int root, int n) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph BDD_Graph {\n");
        sb.append("  rankdir=TD;\n");
        sb.append("  overlap=false;\n");
        sb.append("  splines=true;\n");
        sb.append("  init__ [label=\"\", style=invis, height=0, width=0];\n");
        sb.append("  BDD_TRUE [shape=box, style=\"filled,rounded\", label=\"TRUE\", fillcolor=\"#d4edda\"];\n");
        sb.append("  init__ -> ").append(nodeName(root)).append(" [style=dashed, label=\"root\"];\n");
        appendDot(root, n, sb, new HashSet<Integer>());
        sb.append("}\n");
        writeFile(dotFile, sb.toString());
    }

    private static void appendDot(int handle, int n, StringBuilder sb, Set<Integer> visited) {
        if (handle <= 1 || !visited.add(handle)) {
            return;
        }

        int low = bddEngine.getLow(handle);
        int high = bddEngine.getHigh(handle);
        sb.append("  ").append(nodeName(handle))
                .append(" [shape=circle, label=\"").append(coordinateLabel(bddEngine.getVar(handle), n)).append("\"];\n");

        if (high == 1) {
            sb.append("  ").append(nodeName(handle)).append(" -> BDD_TRUE [label=\"1\"];\n");
        } else if (high > 1) {
            sb.append("  ").append(nodeName(handle)).append(" -> ").append(nodeName(high)).append(" [label=\"1\"];\n");
        }

        if (low == 1) {
            sb.append("  ").append(nodeName(handle)).append(" -> BDD_TRUE [style=dotted, label=\"0\"];\n");
        } else if (low > 1) {
            sb.append("  ").append(nodeName(handle)).append(" -> ").append(nodeName(low)).append(" [style=dotted, label=\"0\"];\n");
        }

        appendDot(high, n, sb, visited);
        appendDot(low, n, sb, visited);
    }

    private static String nodeName(int handle) {
        if (handle == 1) {
            return "BDD_TRUE";
        }
        return "bdd_" + handle;
    }

    private static String coordinateLabel(int var, int n) {
        int row = var / n;
        int col = var % n;
        return "(" + (row + 1) + "," + (col + 1) + ")";
    }

    private static void writeFile(String path, String content) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write BDD dot file: " + path, e);
        }
    }

    private static Result solve(int n) {
        return solve(n, null);
    }

    public static String Solution(int n) {
        Result result = solve(n);
        return "\t" + String.format("%.3f", result.seconds) + "\t" + result.solutions + "\t" + result.nodesAlive;
    }

    private static void printMetrics(int n, Result result) {
        System.out.printf(
            "NQUEENS_METRICS n=%d solutions=%.0f nodes_created=%d nodes_alive=%d seconds=%.6f implementation=BDD%n",
            n,
            result.solutions,
            result.nodesCreated,
            result.nodesAlive,
            result.seconds
        );
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: BDDSolution [--dot <file>] <N> [<N> ...]");
            System.exit(1);
        }

        String dotFile = null;
        int startArg = 0;
        for (int i = 0; i < args.length; i++) {
            if ("--dot".equals(args[i]) && i + 1 < args.length) {
                dotFile = args[++i];
                startArg = i + 1;
            } else {
                startArg = i;
                break;
            }
        }

        if (startArg >= args.length) {
            System.err.println("Usage: BDDSolution [--dot <file>] <N> [<N> ...]");
            System.exit(1);
        }

        for (int i = startArg; i < args.length; i++) {
            int n = Integer.parseInt(args[i]);
            String nDotFile = dotFile != null ? dotFile.replace("{n}", String.valueOf(n)) : null;
            printMetrics(n, solve(n, nDotFile));
        }
    }
}
