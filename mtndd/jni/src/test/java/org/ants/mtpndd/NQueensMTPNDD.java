package org.ants.mtpndd;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NQueensMTPNDD {
    private NQueensMTPNDD() {}

    public static final class Result {
        public final int n;
        public final double seconds;
        public final long solutions;

        Result(int n, double seconds, long solutions) {
            this.n = n;
            this.seconds = seconds;
            this.solutions = solutions;
        }

        @Override
        public String toString() {
            return "Result{n=" + n + ", seconds=" + seconds + ", solutions=" + solutions + '}';
        }
    }

    public static Result solve(int n) {
        return solve(n, 1);
    }

    public static Result solve(int n, int workers) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        if (workers < 0) {
            throw new IllegalArgumentException("workers must be zero or positive");
        }

        Timings timings = new Timings();
        timings.mark("start");
        MTPNDDConfig config = buildConfigForSize(n, workers);
        MTPNDDEngine.init(config);
        timings.mark("init");

        try {
            int[] fieldIds = new int[n];
            for (int i = 0; i < n; i++) {
                fieldIds[i] = MTPNDDEngine.declareField(n);
            }
            MTPNDDEngine.generateFields();
            timings.mark("declare");

            MTPNDD[][] vars = new MTPNDD[n][n];
            MTPNDD[][] notVars = new MTPNDD[n][n];
            for (int row = 0; row < n; row++) {
                for (int col = 0; col < n; col++) {
                    vars[row][col] = MTPNDD.getVar(fieldIds[row], col);
                    notVars[row][col] = MTPNDD.getNotVar(fieldIds[row], col);
                }
            }
            timings.mark("vars");

            MTPNDD[] rowRequirements = new MTPNDD[n];
            for (int row = 0; row < n; row++) {
                MTPNDD clause = MTPNDD.getFalse().ref();
                for (int col = 0; col < n; col++) {
                    clause = orRelease(clause, vars[row][col].ref());
                }
                rowRequirements[row] = clause;
            }
            timings.mark("rows");

            MTPNDD[][] cellConstraints = new MTPNDD[n][n];
            for (int row = 0; row < n; row++) {
                for (int col = 0; col < n; col++) {
                    cellConstraints[row][col] = buildCellConstraints(row, col, n, vars, notVars);
                }
            }
            timings.mark("constraints");

            MTPNDD formula = MTPNDD.getTrue().ref();
            for (MTPNDD requirement : rowRequirements) {
                formula = andRelease(formula, requirement.ref());
            }
            for (int row = 0; row < n; row++) {
                for (int col = 0; col < n; col++) {
                    formula = andRelease(formula, cellConstraints[row][col].ref());
                }
            }
            timings.mark("formula");

            double solutions = formula.satCount();
            timings.mark("satcount");
            formula.deref();
            releaseCache(cellConstraints);
            releaseArray(rowRequirements);
            printStats("nqueens", n);
            logTimings("nqueens", n, timings);
            return new Result(n, timings.elapsedSeconds(), Math.round(solutions));
        } finally {
            MTPNDDEngine.shutdown();
        }
    }

    private static MTPNDDConfig buildConfigForSize(int n, int workers) {
        long bddSize = 1L << 19;
        long nddSize = 1L << 19;
        long cacheSize = 1L << 19;
        long edgeBuckets = 16;
        long nodetableBuckets = nddSize;
        long nodeSlab = 1536;
        long edgeEntrySlab = 3072;
        long nodetableEntrySlab = 1536;
        long edgeMapSlab = 768;

        if (n > 8 && n <= 10) {
            bddSize = 1L << 20;
            nddSize = 1L << 19;
            cacheSize = 1L << 20;
            edgeBuckets = 16;
            nodetableBuckets = nddSize;
            nodeSlab = 2048;
            edgeEntrySlab = 4096;
            nodetableEntrySlab = 2048;
            edgeMapSlab = 1024;
        } else if (n > 10) {
            bddSize = 1L << 20;
            nddSize = 1L << 22;
            cacheSize = 1L << 20;
            edgeBuckets = 16;
            nodetableBuckets = 1L << 19;
            nodeSlab = 3072;
            edgeEntrySlab = 6144;
            nodetableEntrySlab = 3072;
            edgeMapSlab = 1280;
        }

        return MTPNDDConfig.builder()
                .workers(workers)
                .laceDequeSize(1L << 20)
                .bddNodeTableSize(bddSize)
                .mtpnddNodeTableSize(nddSize)
                .operationCacheSize(cacheSize)
                .quickGrowthThreshold(0.1d)
                .edgeBucketCount(edgeBuckets)
                .nodetableBucketCount(nodetableBuckets)
                .nodeSlabCapacity(nodeSlab)
                .edgeEntrySlabCapacity(edgeEntrySlab)
                .nodetableEntrySlabCapacity(nodetableEntrySlab)
                .edgeMapSlabCapacity(edgeMapSlab)
                .build();
    }

    private static void logTimings(String label, int n, Timings timings) {
        System.out.printf("[%s n=%d] timings (s): init=%.3f declare=%.3f vars=%.3f rows=%.3f constraints=%.3f formula=%.3f satcount=%.3f total=%.3f%n",
                label, n,
                timings.duration("start", "init"),
                timings.duration("init", "declare"),
                timings.duration("declare", "vars"),
                timings.duration("vars", "rows"),
                timings.duration("rows", "constraints"),
                timings.duration("constraints", "formula"),
                timings.duration("formula", "satcount"),
                timings.elapsedSeconds());
    }

    private static void printStats(String label, int n) {
        MTPNDDStats stats = MTPNDDEngine.stats();
        if (stats == null) {
            System.out.printf("[%s n=%d] stats unavailable (recording disabled)%n", label, n);
            return;
        }
        System.out.printf("[%s n=%d] nodeCount=%d recording=%s%n",
                label, n, stats.nodeCount(), stats.recordingEnabled());
        if (!stats.recordingEnabled()) {
            return;
        }
        System.out.printf("[%s n=%d] nodesCreated=%d reused=%d collectedLast=%d maxEdges=%d cacheHit=%d miss=%d%n",
                label, n,
                stats.nodesCreatedTotal(),
                stats.nodesReusedTotal(),
                stats.nodesCollectedLast(),
                stats.maxEdgesPerNode(),
                stats.cacheHits(),
                stats.cacheMisses());
    }

    private static MTPNDD buildCellConstraints(int row, int col, int n,
                                               MTPNDD[][] vars,
                                               MTPNDD[][] notVars) {
        MTPNDD guard = vars[row][col].ref();
        MTPNDD constraints = MTPNDD.getTrue();

        for (int otherCol = 0; otherCol < n; otherCol++) {
            if (otherCol != col) {
                MTPNDD clause = implies(guard, notVars[row][otherCol]);
                constraints = andRelease(constraints, clause);
            }
        }
        for (int otherRow = 0; otherRow < n; otherRow++) {
            if (otherRow != row) {
                MTPNDD clause = implies(guard, notVars[otherRow][col]);
                constraints = andRelease(constraints, clause);
            }
        }
        for (int otherRow = 0; otherRow < n; otherRow++) {
            if (otherRow == row) {
                continue;
            }
            int diagRight = col + (otherRow - row);
            if (diagRight >= 0 && diagRight < n) {
                MTPNDD clause = implies(guard, notVars[otherRow][diagRight]);
                constraints = andRelease(constraints, clause);
            }
            int diagLeft = col - (otherRow - row);
            if (diagLeft >= 0 && diagLeft < n) {
                MTPNDD clause = implies(guard, notVars[otherRow][diagLeft]);
                constraints = andRelease(constraints, clause);
            }
        }

        guard.deref();
        return constraints;
    }

    private static MTPNDD implies(MTPNDD premise, MTPNDD consequence) {
        MTPNDD premiseRef = premise.ref();
        MTPNDD negatedPremise = premiseRef.not();
        premiseRef.deref();
        negatedPremise.ref();
        MTPNDD consequenceRef = consequence.ref();
        return orRelease(negatedPremise, consequenceRef);
    }

    private static MTPNDD andRelease(MTPNDD left, MTPNDD right) {
        MTPNDD result = left.and(right);
        left.deref();
        right.deref();
        return result.ref();
    }

    private static MTPNDD orRelease(MTPNDD left, MTPNDD right) {
        MTPNDD result = left.or(right);
        left.deref();
        right.deref();
        return result.ref();
    }

    private static void releaseCache(MTPNDD[][] cache) {
        for (MTPNDD[] row : cache) {
            for (int i = 0; i < row.length; i++) {
                if (row[i] != null) {
                    row[i].deref();
                    row[i] = null;
                }
            }
        }
    }

    private static void releaseArray(MTPNDD[] array) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] != null) {
                array[i].deref();
                array[i] = null;
            }
        }
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        Result result = solve(n, workers);
        System.out.printf("n=%d solutions=%d time=%.3fs%n", result.n, result.solutions, result.seconds);
    }

    private static final class Timings {
        private final Map<String, Long> marks = new LinkedHashMap<>();

        void mark(String name) {
            marks.put(name, System.nanoTime());
        }

        double duration(String start, String end) {
            Long s = marks.get(start);
            Long e = marks.get(end);
            if (s == null || e == null) {
                return Double.NaN;
            }
            return (e - s) / 1_000_000_000.0;
        }

        double elapsedSeconds() {
            Long start = marks.get("start");
            Long end = marks.get("satcount");
            if (start == null || end == null) {
                return Double.NaN;
            }
            return (end - start) / 1_000_000_000.0;
        }
    }
}
