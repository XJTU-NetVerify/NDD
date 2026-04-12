package application.nqueen;

import org.ants.jndd.diagram.NDD;

public class NDDSolution {
    public static final int NDD_TABLE_SIZE = 100000000;

    private static final class Result {
        final double solutions;
        final long nodesCreated;
        final long nodesAlive;
        final long nddNodesCreated;
        final long nddNodesAlive;
        final long bddNodesCreated;
        final long bddNodesAlive;
        final double seconds;
        final NDD.LabelMode mode;

        Result(
            double solutions,
            long nodesCreated,
            long nodesAlive,
            long nddNodesCreated,
            long nddNodesAlive,
            long bddNodesCreated,
            long bddNodesAlive,
            double seconds,
            NDD.LabelMode mode
        ) {
            this.solutions = solutions;
            this.nodesCreated = nodesCreated;
            this.nodesAlive = nodesAlive;
            this.nddNodesCreated = nddNodesCreated;
            this.nddNodesAlive = nddNodesAlive;
            this.bddNodesCreated = bddNodesCreated;
            this.bddNodesAlive = bddNodesAlive;
            this.seconds = seconds;
            this.mode = mode;
        }
    }

    // declare n fields, n bits per field
    private static void declareFields(int n) {
        for (int i = 0;i < n;i++) {
            NDD.declareField(n);
        }
    }

    private static void build(int i, int j, int n, int[][] impBatch) {
        int a, b, c, d;
        a = b = c = d = NDD.getTrue();

        int k, l;

        /* No one in the same column */
        for (l = 0; l < n; l++) {
            if (l != j) {
                int mp = NDD.ref(NDD.imp(NDD.getVar(i, j), NDD.getNotVar(i, l)));
                a = NDD.andTo(a, mp);
                NDD.deref(mp);
            }
        }

        /* No one in the same row */
        for (k = 0; k < n; k++) {
            if (k != i) {
                int mp = NDD.ref(NDD.imp(NDD.getVar(i, j), NDD.getNotVar(k, j)));
                b = NDD.andTo(b, mp);
                NDD.deref(mp);
            }
        }

        /* No one in the same up-right diagonal */
        for (k = 0; k < n; k++) {
            int ll = k - i + j;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    int mp = NDD.ref(NDD.imp(NDD.getVar(i, j), NDD.getNotVar(k, ll)));
                    c = NDD.andTo(c, mp);
                    NDD.deref(mp);
                }
            }
        }

        /* No one in the same down-right diagonal */
        for (k = 0; k < n; k++) {
            int ll = i + j - k;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    int mp = NDD.ref(NDD.imp(NDD.getVar(i, j), NDD.getNotVar(k, ll)));
                    d = NDD.andTo(d, mp);
                    NDD.deref(mp);
                }
            }
        }

        c = NDD.andTo(c, d); 
        NDD.deref(d); 
        b = NDD.andTo(b, c);
        NDD.deref(c); 
        a = NDD.andTo(a, b);
        NDD.deref(b); 
        impBatch[i][j] = a;
    }

    // N is the number of queens, fieldNum is the number of fields in NDD library.
    private static Result solve(int n, NDD.LabelMode mode) {
        long startTimeNanos = System.nanoTime();
        jdd.bdd.NodeTable.mkCount = 0;

        // init NDD library
        NDD.initNDD(NDD_TABLE_SIZE, 1 + Math.max(1000, (int) (Math.pow(4.4, n - 6)) * 1000), 10000, mode);

        // declare ndd fields
        declareFields(n);
        NDD.generateFields();

        int[] orBatch = new int[n];
        int[][] impBatch = new int[n][n];

        for (int i = 0; i < n; i++) {
            int condition = NDD.getFalse();
            for (int j = 0; j < n; j++) {
                condition = NDD.orTo(condition, NDD.getVar(i, j));
            }
            orBatch[i] = condition;
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                build(i, j, n, impBatch);
            }
        }

        int queen = NDD.getTrue();

        for (int i = 0; i < n; i++) {
            queen = NDD.andTo(queen, orBatch[i]);
            NDD.deref(orBatch[i]);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                queen = NDD.andTo(queen, impBatch[i][j]);
                NDD.deref(impBatch[i][j]);
            }
        }
        double solutions = NDD.satCount(queen);
        long nddNodesCreated = NDD.getTotalCreated();
        long bddNodesCreated = NDD.getLabelTotalCreated();
        NDD.deref(queen);
        double seconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        NDD.gc();
        NDD.gcLabelEngine();
        long nddNodesAlive = NDD.getNodeCount();
        long bddNodesAlive = NDD.getLabelNodeCount();
        return new Result(
            solutions,
            nddNodesCreated + bddNodesCreated,
            nddNodesAlive + bddNodesAlive,
            nddNodesCreated,
            nddNodesAlive,
            bddNodesCreated,
            bddNodesAlive,
            seconds,
            mode
        );
    }

    private static Result solve(int n) {
        return solve(n, NDD.LabelMode.BOOLEAN_BDD);
    }

    /**
     * Legacy helper kept for compatibility with existing experiments.
     */
    public static String Solution(int n) {
        Result result = solve(n);
        return "\t" + String.format("%.3f", result.seconds) + "\t" + result.solutions + "\t" + result.nodesAlive;
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: NDDSolution [--bcdd | --finite-domain-zdd] <N> [<N> ...]");
            System.exit(1);
        }

        NDD.LabelMode mode = NDD.LabelMode.BOOLEAN_BDD;
        int startArg = 0;
        if ("--bcdd".equals(args[0])) {
            mode = NDD.LabelMode.COMPLEMENTED_BDD;
            startArg = 1;
        } else if ("--finite-domain-zdd".equals(args[0])) {
            mode = NDD.LabelMode.FINITE_DOMAIN_ZDD;
            startArg = 1;
        }

        if (startArg >= args.length) {
            System.err.println("Usage: NDDSolution [--bcdd | --finite-domain-zdd] <N> [<N> ...]");
            System.exit(1);
        }

        for (int i = startArg; i < args.length; i++) {
            String arg = args[i];
            int n = Integer.parseInt(arg);
            Result result = solve(n, mode);
            System.out.printf(
                "NQUEENS_METRICS n=%d solutions=%.0f nodes_created=%d nodes_alive=%d "
                    + "ndd_nodes_created=%d ndd_nodes_alive=%d "
                    + "bdd_nodes_created=%d bdd_nodes_alive=%d "
                    + "seconds=%.6f mode=%s implementation=%s%n",
                n,
                result.solutions,
                result.nodesCreated,
                result.nodesAlive,
                result.nddNodesCreated,
                result.nddNodesAlive,
                result.bddNodesCreated,
                result.bddNodesAlive,
                result.seconds,
                result.mode,
                result.mode
            );
            //System.out.printf(Solution(n));
        }
    }
}
