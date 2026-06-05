package application.nqueen;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.ants.jndd.bdd.ComplementedBDD;

public class BCDDSolution {
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

    private static ComplementedBDD bddEngine;
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
        int a = BDD_TRUE;
        int b = BDD_TRUE;
        int c = BDD_TRUE;
        int d = BDD_TRUE;

        for (int l = 0; l < n; l++) {
            if (l != j) {
                int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[i][l]));
                a = bddEngine.andTo(a, mp);
                bddEngine.deref(mp);
            }
        }

        for (int k = 0; k < n; k++) {
            if (k != i) {
                int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[k][j]));
                b = bddEngine.andTo(b, mp);
                bddEngine.deref(mp);
            }
        }

        for (int k = 0; k < n; k++) {
            int ll = k - i + j;
            if (ll >= 0 && ll < n && k != i) {
                int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[k][ll]));
                c = bddEngine.andTo(c, mp);
                bddEngine.deref(mp);
            }
        }

        for (int k = 0; k < n; k++) {
            int ll = i + j - k;
            if (ll >= 0 && ll < n && k != i) {
                int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[k][ll]));
                d = bddEngine.andTo(d, mp);
                bddEngine.deref(mp);
            }
        }

        c = bddEngine.andTo(c, d);
        b = bddEngine.andTo(b, c);
        a = bddEngine.andTo(a, b);
        bddEngine.deref(d);
        impBatch[i][j] = a;
    }

    private static Result solve(int n, String dotFile) {
        bddEngine = new ComplementedBDD(1 + Math.max(1000, (int) (Math.pow(4.4, n - 6)) * 1000), 10000);
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
        long nodesCreated = bddEngine.getTotalCreated();
        long nodesAlive = bddEngine.nodeCount(queen);
        if (dotFile != null) {
            writeDot(dotFile, queen, n);
        }
        bddEngine.deref(queen);
        bddEngine.gc();
        double seconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        return new Result(solutions, nodesCreated, nodesAlive, seconds);
    }

    private static void writeDot(String dotFile, int root, int n) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph BCDD_Graph {\n");
        sb.append("  rankdir=TD;\n");
        sb.append("  overlap=false;\n");
        sb.append("  splines=true;\n");
        sb.append("  init__ [label=\"\", style=invis, height=0, width=0];\n");
        sb.append("  BCDD_TRUE [shape=box, style=\"filled,rounded\", label=\"TRUE\", fillcolor=\"#d4edda\"];\n");
        sb.append("  init__ -> ").append(nodeName(root)).append(" [style=dashed");
        if (bddEngine.isComplemented(root)) {
            sb.append(", color=crimson, fontcolor=crimson, label=\"!\"");
        } else {
            sb.append(", label=\"root\"");
        }
        sb.append("];\n");
        appendDot(root, n, sb, new HashSet<Integer>());
        sb.append("}\n");
        writeFile(dotFile, sb.toString());
    }

    private static void appendDot(int handle, int n, StringBuilder sb, Set<Integer> visited) {
        if (handle <= 1 || !visited.add(bddEngine.getNodeId(handle))) {
            return;
        }

        int regularHandle = regularHandle(handle);
        int low = bddEngine.getLow(regularHandle);
        int high = bddEngine.getHigh(regularHandle);
        sb.append("  ").append(nodeName(regularHandle))
                .append(" [shape=circle, label=\"").append(coordinateLabel(bddEngine.getVar(regularHandle), n)).append("\"];\n");

        if (high == 1) {
            sb.append("  ").append(nodeName(regularHandle)).append(" -> BCDD_TRUE [");
            if (bddEngine.isComplemented(high)) {
                sb.append("style=dashed, color=crimson, fontcolor=crimson, label=\"1!\"");
            } else {
                sb.append("label=\"1\"");
            }
            sb.append("];\n");
        } else if (high > 1) {
            appendEdge(nodeName(regularHandle), high, false, sb);
        }

        if (low > 1) {
            appendEdge(nodeName(regularHandle), low, true, sb);
        }

        appendDot(high, n, sb, visited);
        appendDot(low, n, sb, visited);
    }

    private static void appendEdge(String from, int to, boolean low, StringBuilder sb) {
        sb.append("  ").append(from).append(" -> ").append(nodeName(to)).append(" [");
        if (low) {
            sb.append(bddEngine.isComplemented(to) ? "style=\"dotted,dashed\"" : "style=dotted");
        } else if (bddEngine.isComplemented(to)) {
            sb.append("style=dashed, penwidth=2");
        } else {
            sb.append("penwidth=2");
        }
        if (bddEngine.isComplemented(to)) {
            sb.append(", color=crimson, fontcolor=crimson, label=\"").append(low ? "0!" : "1!").append("\"");
        } else {
            sb.append(", label=\"").append(low ? "0" : "1").append("\"");
        }
        sb.append("];\n");
    }

    private static String nodeName(int handle) {
        if (handle == 1) {
            return "BCDD_TRUE";
        }
        return "bcdd_" + bddEngine.getNodeId(handle);
    }

    private static int regularHandle(int handle) {
        return bddEngine.isComplemented(handle) ? bddEngine.not(handle) : handle;
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
            throw new RuntimeException("Failed to write BCDD dot file: " + path, e);
        }
    }

    private static Result solve(int n) {
        return solve(n, null);
    }

    private static void printMetrics(int n, Result result) {
        System.out.printf(
            "NQUEENS_METRICS n=%d solutions=%.0f nodes_created=%d nodes_alive=%d seconds=%.6f implementation=BCDD%n",
            n,
            result.solutions,
            result.nodesCreated,
            result.nodesAlive,
            result.seconds
        );
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: BCDDSolution [--dot <file>] <N> [<N> ...]");
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
            System.err.println("Usage: BCDDSolution [--dot <file>] <N> [<N> ...]");
            System.exit(1);
        }

        for (int i = startArg; i < args.length; i++) {
            int n = Integer.parseInt(args[i]);
            String nDotFile = dotFile != null ? dotFile.replace("{n}", String.valueOf(n)) : null;
            printMetrics(n, solve(n, nDotFile));
        }
    }
}
