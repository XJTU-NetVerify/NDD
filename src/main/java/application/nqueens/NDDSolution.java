package application.nqueens;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.ants.javandd.BDD;
import org.ants.javandd.NDDFactory;
import org.ants.jndd.diagram.NDD;

/** N-Queens with shared constraints and selectable NDD implementations. */
public class NDDSolution {
    public static final int NDD_TABLE_SIZE = 100000000;

    public enum Implementation { JNDD, JAVANDD }

    static final class Result {
        final double solutions;
        final long nodesCreated;
        final long nodesAlive;
        final long nddNodesCreated;
        final long nddNodesAlive;
        final long bddNodesCreated;
        final long bddNodesAlive;
        final double seconds;
        final NDD.LabelMode mode;
        final Implementation implementation;

        Result(double solutions, long nddCreated, long nddAlive, long labelCreated,
               long labelAlive, double seconds, NDD.LabelMode mode, Implementation implementation) {
            this.solutions = solutions;
            this.nddNodesCreated = nddCreated;
            this.nddNodesAlive = nddAlive;
            this.bddNodesCreated = labelCreated;
            this.bddNodesAlive = labelAlive;
            this.nodesCreated = nddCreated + labelCreated;
            this.nodesAlive = nddAlive + labelAlive;
            this.seconds = seconds;
            this.mode = mode;
            this.implementation = implementation;
        }
    }

    /** All returned handles are owned; binary operations consume both inputs. */
    private interface Engine<T> extends AutoCloseable {
        T one();
        T zero();
        T variable(int field, int bit, boolean negated);
        T and(T a, T b);
        T or(T a, T b);
        T imp(T a, T b);
        double satCount(T root);
        void release(T root);
        long nddCreated();
        long labelCreated();
        void gc();
        long nddAlive();
        long labelAlive();
        @Override default void close() {}
    }

    private static final class JnddEngine implements Engine<Integer> {
        JnddEngine(int n, int tableSize, NDD.LabelMode mode) {
            NDD.initNDD(tableSize, bddTableSize(n), 10000, mode);
            for (int i = 0; i < n; i++) NDD.declareField(n);
            NDD.generateFields();
        }

        public Integer one() { return NDD.getTrue(); }
        public Integer zero() { return NDD.getFalse(); }
        public Integer variable(int field, int bit, boolean negated) {
            return NDD.ref(negated ? NDD.getNotVar(field, bit) : NDD.getVar(field, bit));
        }
        public Integer and(Integer a, Integer b) {
            int result = NDD.ref(NDD.and(a, b));
            release(a);
            release(b);
            return result;
        }
        public Integer or(Integer a, Integer b) {
            int result = NDD.ref(NDD.or(a, b));
            release(a);
            release(b);
            return result;
        }
        public Integer imp(Integer a, Integer b) {
            int result = NDD.ref(NDD.imp(a, b));
            release(a);
            release(b);
            return result;
        }
        public double satCount(Integer root) { return NDD.satCount(root); }
        public void release(Integer root) { NDD.deref(root); }
        public long nddCreated() { return NDD.getTotalCreated(); }
        public long labelCreated() { return NDD.getLabelTotalCreated(); }
        public void gc() { NDD.gc(); NDD.gcLabelEngine(); }
        public long nddAlive() { return NDD.getNodeCount(); }
        public long labelAlive() { return NDD.getLabelNodeCount(); }
    }

    private static final class JavaNddEngine implements Engine<BDD> {
        private final NDDFactory factory;
        private final int width;

        JavaNddEngine(int n, int tableSize) {
            width = n;
            factory = new NDDFactory(bddTableSize(n), 10000);
            int[] fields = new int[n];
            java.util.Arrays.fill(fields, n);
            factory.setVarNum(fields, tableSize);
        }

        public BDD one() { return factory.one(); }
        public BDD zero() { return factory.zero(); }
        public BDD variable(int field, int bit, boolean negated) {
            return negated ? factory.nithVar(field * width + bit) : factory.ithVar(field * width + bit);
        }
        public BDD and(BDD a, BDD b) {
            BDD result = a.and(b);
            a.free();
            b.free();
            return result;
        }
        public BDD or(BDD a, BDD b) {
            BDD result = a.or(b);
            a.free();
            b.free();
            return result;
        }
        public BDD imp(BDD a, BDD b) {
            BDD result = a.imp(b);
            a.free();
            b.free();
            return result;
        }
        public double satCount(BDD root) { return root.satCount(); }
        public void release(BDD root) { root.free(); }
        public long nddCreated() { return factory.getTotalCreated(); }
        public long labelCreated() { return factory.getLabelTotalCreated(); }
        public void gc() { factory.runGC(); }
        public long nddAlive() { return factory.getNodeTableSize(); }
        public long labelAlive() { return factory.getLabelNodeCount(); }
        public void close() { factory.free(); }
    }

    private static int bddTableSize(int n) {
        return 1 + Math.max(1000, (int) Math.pow(4.4, n - 6) * 1000);
    }

    private static <T> T excludes(Engine<T> engine, int i, int j, int k, int l) {
        return engine.imp(engine.variable(i, j, false), engine.variable(k, l, true));
    }

    private static <T> T build(Engine<T> engine, int i, int j, int n) {
        T a = engine.one();
        T b = engine.one();
        T c = engine.one();
        T d = engine.one();
        for (int l = 0; l < n; l++) {
            if (l != j) a = engine.and(a, excludes(engine, i, j, i, l));
        }
        for (int k = 0; k < n; k++) {
            if (k == i) continue;
            b = engine.and(b, excludes(engine, i, j, k, j));
            int up = k - i + j;
            if (up >= 0 && up < n) c = engine.and(c, excludes(engine, i, j, k, up));
            int down = i + j - k;
            if (down >= 0 && down < n) d = engine.and(d, excludes(engine, i, j, k, down));
        }
        return engine.and(a, engine.and(b, engine.and(c, d)));
    }

    private static <T> Result solve(Engine<T> engine, int n, long started,
                                    Implementation implementation, NDD.LabelMode mode) {
        try (Engine<T> owned = engine) {
            List<T> rows = new ArrayList<>();
            List<T> implications = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                T condition = engine.zero();
                for (int j = 0; j < n; j++) {
                    condition = engine.or(condition, engine.variable(i, j, false));
                }
                rows.add(condition);
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) implications.add(build(engine, i, j, n));
            }
            T queen = engine.one();
            for (T row : rows) queen = engine.and(queen, row);
            for (T implication : implications) queen = engine.and(queen, implication);
            double solutions = engine.satCount(queen);
            long nddCreated = engine.nddCreated();
            long labelCreated = engine.labelCreated();
            engine.release(queen);
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            engine.gc();
            return new Result(solutions, nddCreated, engine.nddAlive(), labelCreated,
                    engine.labelAlive(), seconds, mode, implementation);
        }
    }

    static Result solve(int n, Implementation implementation, NDD.LabelMode mode, int tableSize) {
        if (n < 1) throw new IllegalArgumentException("N must be positive");
        if (tableSize < 8) throw new IllegalArgumentException("NDD table size must be at least 8");
        if (implementation == Implementation.JAVANDD && mode != NDD.LabelMode.BDD) {
            throw new IllegalArgumentException("javandd supports only BDD labels");
        }
        long started = System.nanoTime();
        if (implementation == Implementation.JAVANDD) {
            return solve(new JavaNddEngine(n, tableSize), n, started, implementation, mode);
        }
        return solve(new JnddEngine(n, tableSize, mode), n, started, implementation, mode);
    }

    /** Legacy helper: defaults to jndd with BDD labels. */
    public static String Solution(int n) {
        return Solution(n, NDD.LabelMode.BDD);
    }

    public static String Solution(int n, NDD.LabelMode mode) {
        return Solution(n, Implementation.JNDD, mode);
    }

    /** Returns timing, solution count and allocated nodes after releasing the result and collecting. */
    public static String Solution(int n, Implementation implementation, NDD.LabelMode mode) {
        Result result = solve(n, implementation, mode, NDD_TABLE_SIZE);
        return "\t" + String.format(Locale.ROOT, "%.3f", result.seconds)
                + "\t" + result.solutions + "\t" + result.nodesAlive;
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: NDDSolution [--implementation jndd|javandd] "
                    + "[--label bdd|bcdd|zdd | --bcdd | --zdd] <N> [<N> ...]");
            System.exit(1);
        }
    }

    static void run(String[] args) {
        Implementation implementation = Implementation.JNDD;
        NDD.LabelMode mode = NDD.LabelMode.BDD;
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--implementation":
                    if (++i == args.length) throw new IllegalArgumentException("Missing implementation");
                    implementation = Implementation.valueOf(args[i].toUpperCase(Locale.ROOT));
                    break;
                case "--label":
                    if (++i == args.length) throw new IllegalArgumentException("Missing label mode");
                    switch (args[i].toLowerCase(Locale.ROOT)) {
                        case "bdd": mode = NDD.LabelMode.BDD; break;
                        case "bcdd": mode = NDD.LabelMode.COMPLEMENTED_BDD; break;
                        case "zdd": mode = NDD.LabelMode.ZDD; break;
                        default: throw new IllegalArgumentException("Unknown label mode: " + args[i]);
                    }
                    break;
                case "--bcdd": mode = NDD.LabelMode.COMPLEMENTED_BDD; break;
                case "--zdd": mode = NDD.LabelMode.ZDD; break;
                default: sizes.add(Integer.parseInt(args[i]));
            }
        }
        if (sizes.isEmpty()) throw new IllegalArgumentException("Provide at least one board size");
        if (sizes.stream().anyMatch(n -> n < 1)) throw new IllegalArgumentException("N must be positive");
        if (implementation == Implementation.JAVANDD && mode != NDD.LabelMode.BDD) {
            throw new IllegalArgumentException("javandd supports only BDD labels");
        }
        for (int n : sizes) printMetrics(n, solve(n, implementation, mode, NDD_TABLE_SIZE));
    }

    private static void printMetrics(int n, Result result) {
        System.out.printf(Locale.ROOT,
            "NQUEENS_METRICS n=%d solutions=%.0f nodes_created=%d nodes_alive=%d "
                + "ndd_nodes_created=%d ndd_nodes_alive=%d "
                + "bdd_nodes_created=%d bdd_nodes_alive=%d "
                + "seconds=%.6f mode=%s implementation=%s%n",
            n, result.solutions, result.nodesCreated, result.nodesAlive,
            result.nddNodesCreated, result.nddNodesAlive, result.bddNodesCreated,
            result.bddNodesAlive, result.seconds, result.mode, result.implementation);
    }
}
