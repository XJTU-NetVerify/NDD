package application.nqueen;

import org.ants.jndd.diagram.FiniteDomainNDD;

/**
 * N-Queens benchmark entrypoint for the finite-domain ZDD-backed NDD variant.
 */
public class FiniteDomainZddNDDSolution {
    public static final int NDD_TABLE_SIZE = NDDSolution.NDD_TABLE_SIZE;

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

    private static void declareFields(int n) {
        for (int i = 0; i < n; i++) {
            FiniteDomainNDD.declareField(n);
        }
    }

    private static void build(int i, int j, int n, int[][] impBatch) {
        int a = FiniteDomainNDD.getTrue();
        int b = FiniteDomainNDD.getTrue();
        int c = FiniteDomainNDD.getTrue();
        int d = FiniteDomainNDD.getTrue();

        for (int l = 0; l < n; l++) {
            if (l != j) {
                int mp = FiniteDomainNDD.ref(FiniteDomainNDD.imp(FiniteDomainNDD.getVar(i, j), FiniteDomainNDD.getNotVar(i, l)));
                a = FiniteDomainNDD.andTo(a, mp);
                FiniteDomainNDD.deref(mp);
            }
        }

        for (int k = 0; k < n; k++) {
            if (k != i) {
                int mp = FiniteDomainNDD.ref(FiniteDomainNDD.imp(FiniteDomainNDD.getVar(i, j), FiniteDomainNDD.getNotVar(k, j)));
                b = FiniteDomainNDD.andTo(b, mp);
                FiniteDomainNDD.deref(mp);
            }
        }

        for (int k = 0; k < n; k++) {
            int ll = k - i + j;
            if (ll >= 0 && ll < n && k != i) {
                int mp = FiniteDomainNDD.ref(FiniteDomainNDD.imp(FiniteDomainNDD.getVar(i, j), FiniteDomainNDD.getNotVar(k, ll)));
                c = FiniteDomainNDD.andTo(c, mp);
                FiniteDomainNDD.deref(mp);
            }
        }

        for (int k = 0; k < n; k++) {
            int ll = i + j - k;
            if (ll >= 0 && ll < n && k != i) {
                int mp = FiniteDomainNDD.ref(FiniteDomainNDD.imp(FiniteDomainNDD.getVar(i, j), FiniteDomainNDD.getNotVar(k, ll)));
                d = FiniteDomainNDD.andTo(d, mp);
                FiniteDomainNDD.deref(mp);
            }
        }

        c = FiniteDomainNDD.andTo(c, d);
        FiniteDomainNDD.deref(d);
        b = FiniteDomainNDD.andTo(b, c);
        FiniteDomainNDD.deref(c);
        a = FiniteDomainNDD.andTo(a, b);
        FiniteDomainNDD.deref(b);
        impBatch[i][j] = a;
    }

    private static Result solve(int n) {
        long startTimeNanos = System.nanoTime();

        FiniteDomainNDD.init(NDD_TABLE_SIZE, 1 + Math.max(1000, (int) (Math.pow(4.4, n - 6)) * 1000), 10000);
        declareFields(n);
        FiniteDomainNDD.generateFields();

        int[] orBatch = new int[n];
        int[][] impBatch = new int[n][n];

        for (int i = 0; i < n; i++) {
            int condition = FiniteDomainNDD.getFalse();
            for (int j = 0; j < n; j++) {
                condition = FiniteDomainNDD.orTo(condition, FiniteDomainNDD.getVar(i, j));
            }
            orBatch[i] = condition;
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                build(i, j, n, impBatch);
            }
        }

        int queen = FiniteDomainNDD.getTrue();
        for (int i = 0; i < n; i++) {
            queen = FiniteDomainNDD.andTo(queen, orBatch[i]);
            FiniteDomainNDD.deref(orBatch[i]);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                queen = FiniteDomainNDD.andTo(queen, impBatch[i][j]);
                FiniteDomainNDD.deref(impBatch[i][j]);
            }
        }

        double solutions = FiniteDomainNDD.satCount(queen);
        long nodesCreated = FiniteDomainNDD.getTotalCreated();
        FiniteDomainNDD.deref(queen);
        double seconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        FiniteDomainNDD.gc();
        long nodesAlive = FiniteDomainNDD.getNodeCount();
        return new Result(solutions, nodesCreated, nodesAlive, seconds);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: FiniteDomainZddNDDSolution <N> [<N> ...]");
            System.exit(1);
        }
        for (String arg : args) {
            int n = Integer.parseInt(arg);
            Result result = solve(n);
            System.out.printf(
                "NQUEENS_METRICS n=%d solutions=%.0f nodes_created=%d nodes_alive=%d seconds=%.6f implementation=FINITE_DOMAIN_ZDD%n",
                n, result.solutions, result.nodesCreated, result.nodesAlive, result.seconds
            );
        }
    }
}
