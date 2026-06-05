package application.nqueen;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import jdd.bdd.NodeTable;
import jdd.zdd.ZDD2;

public class ZDDSolution extends ZDD2 {
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

    private final int n;
    private final int[] positions;
    private final int[] positionVars;

    private ZDDSolution(int n) {
        super(1 + Math.max(1000, (int) (Math.pow(3.5, n - 6)) * 1000), 10000);
        this.n = n;
        this.positions = new int[n * n];
        this.positionVars = new int[n * n];
    }

    private static Result solve(int n, String dotFile) {
        NodeTable.mkCount = 0;
        ZDDSolution solver = new ZDDSolution(n);
        long startTimeNanos = System.nanoTime();

        Result result = solver.run(startTimeNanos, dotFile);
        solver.cleanup();
        return result;
    }

    private static Result solve(int n) {
        return solve(n, null);
    }

    private Result run(long startTimeNanos, String dotFile) {
        boolean[] blocked = new boolean[n * n];
        for (int i = 0; i < n * n; i++) {
            positionVars[i] = createVar();
            positions[i] = ref(change(base(), positionVars[i]));
        }

        int frontier = empty();
        for (int col = 0; col < n; col++) {
            frontier = unionWith(frontier, get(0, col));
        }

        for (int row = 1; row < n; row++) {
            int next = empty();
            for (int col = 0; col < n; col++) {
                int extension = build(row, col, frontier, blocked);
                next = unionWith(next, extension);
                deref(extension);
            }
            deref(frontier);
            frontier = next;
        }

        double solutions = count(frontier);
        long nodesCreated = NodeTable.mkCount;
       long nodesAlive = nodeCount(frontier);
        if (dotFile != null) {
            writeDot(dotFile, frontier);
        }
        double seconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        deref(frontier);
        return new Result(solutions, nodesCreated, nodesAlive, seconds);
    }

    private void writeDot(String dotFile, int root) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph ZDD_Graph {\n");
        sb.append("  rankdir=TD;\n");
        sb.append("  overlap=false;\n");
        sb.append("  splines=true;\n");
        sb.append("  init__ [label=\"\", style=invis, height=0, width=0];\n");
        sb.append("  ZDD_BASE [shape=box, style=\"filled,rounded\", label=\"TRUE\", fillcolor=\"#d4edda\"];\n");
        sb.append("  init__ -> ").append(nodeName(root)).append(" [style=dashed, label=\"root\"];\n");
        appendDot(root, sb, new HashSet<Integer>());
        sb.append("}\n");
        writeFile(dotFile, sb.toString());
    }

    private void appendDot(int handle, StringBuilder sb, Set<Integer> visited) {
        if (handle <= 1 || !visited.add(handle)) {
            return;
        }

        int low = getLow(handle);
        int high = getHigh(handle);
        sb.append("  ").append(nodeName(handle))
                .append(" [shape=box, style=\"rounded,filled\", fillcolor=\"#fff4cc\", label=\"")
                .append(coordinateLabel(getVar(handle))).append("\"];\n");

        if (high == 1) {
            sb.append("  ").append(nodeName(handle)).append(" -> ZDD_BASE [penwidth=2, label=\"1\"];\n");
        } else if (high > 1) {
            sb.append("  ").append(nodeName(handle)).append(" -> ").append(nodeName(high)).append(" [penwidth=2, label=\"1\"];\n");
        }

        if (low > 1) {
            sb.append("  ").append(nodeName(handle)).append(" -> ").append(nodeName(low)).append(" [style=dotted, label=\"0\"];\n");
        }

        appendDot(high, sb, visited);
        appendDot(low, sb, visited);
    }

    private String nodeName(int handle) {
        if (handle == 1) {
            return "ZDD_BASE";
        }
        return "zdd_" + handle;
    }

    private String coordinateLabel(int var) {
        int row = var % n;
        int col = var / n;
        return "(" + (row + 1) + "," + (col + 1) + ")";
    }

    private static void writeFile(String path, String content) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write ZDD dot file: " + path, e);
        }
    }

    private int get(int row, int col) {
        return positions[row + col * n];
    }

    private int getVar(int row, int col) {
        return positionVars[row + col * n];
    }

    private boolean valid(int row, int col) {
        return row >= 0 && row < n && col >= 0 && col < n;
    }

    private int build(int row, int col, int solutionSet, boolean[] blocked) {
        ref(solutionSet);
        for (int i = 0; i < blocked.length; i++) {
            blocked[i] = false;
        }

        for (int prevRow = 0; prevRow < row; prevRow++) {
            blocked[prevRow + n * col] = true;
        }

        for (int delta = 1; delta <= row; delta++) {
            int prevRow = row - delta;
            int leftCol = col - delta;
            if (valid(prevRow, leftCol)) {
                blocked[prevRow + n * leftCol] = true;
            }
            int rightCol = col + delta;
            if (valid(prevRow, rightCol)) {
                blocked[prevRow + n * rightCol] = true;
            }
        }

        for (int idx = 0; idx < blocked.length; idx++) {
            if (!blocked[idx]) {
                continue;
            }
            int blockedCol = idx / n;
            int blockedRow = idx % n;
            solutionSet = subset0With(solutionSet, getVar(blockedRow, blockedCol));
        }

        int result = ref(mul(solutionSet, get(row, col)));
        deref(solutionSet);
        return result;
    }

    private int unionWith(int current, int add) {
        int merged = ref(union(current, add));
        deref(current);
        return merged;
    }

    private int subset0With(int current, int var) {
        int filtered = ref(subset0(current, var));
        deref(current);
        return filtered;
    }

    private static void printMetrics(int n, Result result) {
        System.out.printf(
            "NQUEENS_METRICS n=%d solutions=%.0f nodes_created=%d nodes_alive=%d seconds=%.6f implementation=ZDD%n",
            n,
            result.solutions,
            result.nodesCreated,
            result.nodesAlive,
            result.seconds
        );
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: ZDDSolution [--dot <file>] <N> [<N> ...]");
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
            System.err.println("Usage: ZDDSolution [--dot <file>] <N> [<N> ...]");
            System.exit(1);
        }

        for (int i = startArg; i < args.length; i++) {
            int n = Integer.parseInt(args[i]);
            String nDotFile = dotFile != null ? dotFile.replace("{n}", String.valueOf(n)) : null;
            printMetrics(n, solve(n, nDotFile));
        }
    }
}
