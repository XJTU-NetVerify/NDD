package org.ants.jndd.parallel;

import java.util.ArrayList;
import java.util.List;

/** N-Queens through the public JNI API. Original one-hot field encoding only;
 * compact encoding is available in the C driver as a separate benchmark track. */
public final class ParallelNQueens {
    private ParallelNQueens() { }
    private static NDDNode fold(ParallelNDD e, NDDNode a, NDDNode b) {
        NDDNode result = e.and(a, b); a.close(); return result;
    }
    private static NDDNode cell(ParallelNDD e, NDDNode[][] positive,
            NDDNode[][] negative, int row, int col, int n, boolean batch) {
        List<NDDNode> left = new ArrayList<>(), right = new ArrayList<>();
        int[] ends = new int[4];
        for (int j = 0; j < n; ++j) if (j != col) { left.add(positive[row][col]); right.add(negative[row][j]); }
        ends[0] = left.size();
        for (int i = 0; i < n; ++i) if (i != row) { left.add(positive[row][col]); right.add(negative[i][col]); }
        ends[1] = left.size();
        for (int i = 0; i < n; ++i) { int j = i - row + col; if (i != row && j >= 0 && j < n) { left.add(positive[row][col]); right.add(negative[i][j]); } }
        ends[2] = left.size();
        for (int i = 0; i < n; ++i) { int j = row + col - i; if (i != row && j >= 0 && j < n) { left.add(positive[row][col]); right.add(negative[i][j]); } }
        ends[3] = left.size();
        NDDNode[] implications;
        if (batch) implications = e.applyBatch(ParallelNDD.Operation.IMP,
                left.toArray(new NDDNode[0]), right.toArray(new NDDNode[0]));
        else {
            implications = new NDDNode[left.size()];
            for (int i = 0; i < implications.length; ++i) implications[i] = e.apply(ParallelNDD.Operation.IMP, left.get(i), right.get(i));
        }
        NDDNode[] parts = new NDDNode[4]; int first = 0;
        for (int part = 0; part < 4; ++part) {
            NDDNode value = e.constant(true);
            for (int i = first; i < ends[part]; ++i) { value = fold(e, value, implications[i]); implications[i].close(); }
            parts[part] = value; first = ends[part];
        }
        parts[2] = fold(e, parts[2], parts[3]); parts[3].close();
        parts[1] = fold(e, parts[1], parts[2]); parts[2].close();
        parts[0] = fold(e, parts[0], parts[1]); parts[1].close(); return parts[0];
    }
    @SuppressWarnings("unchecked")
    private static List<NDDNode> buildBatched(ParallelNDD e, NDDNode[][] positive, NDDNode[][] negative, int n) {
        List<NDDNode> result = new ArrayList<>();
        NDDNode[] rows = new NDDNode[n];
        for (int i = 0; i < n; ++i) rows[i] = e.constant(false);
        for (int col = 0; col < n; ++col) {
            NDDNode[] literals = new NDDNode[n]; for (int row = 0; row < n; ++row) literals[row] = positive[row][col];
            NDDNode[] next = e.applyBatch(ParallelNDD.Operation.OR, rows, literals);
            for (NDDNode old : rows) old.close(); rows = next;
        }
        for (NDDNode row : rows) result.add(row);
        List<NDDNode>[] targets = (List<NDDNode>[]) new List<?>[n * n * 4];
        NDDNode[] accumulators = new NDDNode[targets.length];
        for (int row = 0; row < n; ++row) for (int col = 0; col < n; ++col) {
            int base = (row * n + col) * 4;
            for (int k = 0; k < 4; ++k) { targets[base + k] = new ArrayList<>(); accumulators[base + k] = e.constant(true); }
            for (int j = 0; j < n; ++j) if (j != col) targets[base].add(negative[row][j]);
            for (int i = 0; i < n; ++i) if (i != row) targets[base + 1].add(negative[i][col]);
            for (int i = 0; i < n; ++i) {
                int up = i - row + col, down = row + col - i;
                if (i != row && up >= 0 && up < n) targets[base + 2].add(negative[i][up]);
                if (i != row && down >= 0 && down < n) targets[base + 3].add(negative[i][down]);
            }
        }
        for (int step = 0; step < n - 1; ++step) {
            List<Integer> indices = new ArrayList<>(); List<NDDNode> left = new ArrayList<>(), right = new ArrayList<>();
            for (int group = 0; group < targets.length; ++group) if (step < targets[group].size()) {
                indices.add(group); int cell = group / 4; left.add(positive[cell / n][cell % n]); right.add(targets[group].get(step));
            }
            NDDNode[] implications = e.applyBatch(ParallelNDD.Operation.IMP, left.toArray(new NDDNode[0]), right.toArray(new NDDNode[0]));
            NDDNode[] old = new NDDNode[indices.size()];
            for (int i = 0; i < old.length; ++i) old[i] = accumulators[indices.get(i)];
            NDDNode[] next = e.applyBatch(ParallelNDD.Operation.AND, old, implications);
            for (int i = 0; i < old.length; ++i) { accumulators[indices.get(i)] = next[i]; old[i].close(); implications[i].close(); }
        }
        for (int part = 2; part >= 0; --part) {
            NDDNode[] left = new NDDNode[n * n], right = new NDDNode[n * n];
            for (int cell = 0; cell < n * n; ++cell) { left[cell] = accumulators[cell * 4 + part]; right[cell] = accumulators[cell * 4 + part + 1]; }
            NDDNode[] next = e.applyBatch(ParallelNDD.Operation.AND, left, right);
            for (int cell = 0; cell < n * n; ++cell) { left[cell].close(); right[cell].close(); accumulators[cell * 4 + part] = next[cell]; }
        }
        for (int cell = 0; cell < n * n; ++cell) result.add(accumulators[cell * 4]);
        return result;
    }
    public static void main(String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("N BDD|BCDD|ZDD|BITSET workers [batch=true] [tree=false] [memoryMiB=4096] [ndd=true] [labels=false] [threshold=4] [chunk=2] [cache=262144]");
        int n = Integer.parseInt(args[0]), workers = Integer.parseInt(args[2]);
        boolean batch = args.length <= 3 || Boolean.parseBoolean(args[3]), tree = args.length > 4 && Boolean.parseBoolean(args[4]);
        long memory = (args.length > 5 ? Long.parseLong(args[5]) : 4096) << 20;
        boolean ndd = args.length <= 6 || Boolean.parseBoolean(args[6]), labels = args.length > 7 && Boolean.parseBoolean(args[7]);
        int threshold = args.length > 8 ? Integer.parseInt(args[8]) : 4, chunk = args.length > 9 ? Integer.parseInt(args[9]) : 2;
        int cache = args.length > 10 ? Integer.parseInt(args[10]) : 262144;
        ParallelNDD.Backend backend = ParallelNDD.Backend.valueOf(args[1]);
        ParallelNDD.Field[] fields = new ParallelNDD.Field[n];
        for (int i = 0; i < n; ++i) fields[i] = new ParallelNDD.Field(n, backend);
        long initStart = System.nanoTime();
        ParallelNDD e = new ParallelNDD(new ParallelNDDConfig(workers, memory, cache, threshold, chunk, 4, 128, ndd, labels, true), fields);
        double init = (System.nanoTime() - initStart) / 1e9;
        long buildStart = System.nanoTime();
        List<NDDNode> constraints = new ArrayList<>(); NDDNode[][] positive = new NDDNode[n][n], negative = new NDDNode[n][n];
        for (int i = 0; i < n; ++i) for (int j = 0; j < n; ++j) { positive[i][j] = e.literal(i, j, true); negative[i][j] = e.literal(i, j, false); }
        if (batch) constraints = buildBatched(e, positive, negative, n);
        else {
        for (int i = 0; i < n; ++i) {
            NDDNode row = e.constant(false);
            for (int j = 0; j < n; ++j) { NDDNode next = e.or(row, positive[i][j]); row.close(); row = next; }
            constraints.add(row);
        }
        for (int i = 0; i < n; ++i) for (int j = 0; j < n; ++j) constraints.add(cell(e, positive, negative, i, j, n, batch));
        }
        for (int i = 0; i < n; ++i) for (int j = 0; j < n; ++j) { positive[i][j].close(); negative[i][j].close(); }
        double build = (System.nanoTime() - buildStart) / 1e9;
        long reduceStart = System.nanoTime(); NDDNode root;
        if (batch || tree) root = e.reduce(ParallelNDD.Operation.AND,
                tree ? ParallelNDD.Reduction.BALANCED_TREE : ParallelNDD.Reduction.LEFT_FOLD, constraints.toArray(new NDDNode[0]));
        else { root = e.constant(true); for (NDDNode c : constraints) root = fold(e, root, c); }
        double reduce = (System.nanoTime() - reduceStart) / 1e9;
        long countStart = System.nanoTime(); double solutions = e.satCount(root); double count = (System.nanoTime() - countStart) / 1e9;
        root.close(); for (NDDNode c : constraints) c.close();
        double compute = (System.nanoTime() - buildStart) / 1e9; ParallelNDD.Stats stats = e.stats();
        long cleanupStart = System.nanoTime(); e.close(); double cleanup = (System.nanoTime() - cleanupStart) / 1e9;
        System.out.printf("{\"n\":%d,\"backend\":\"%s\",\"workers\":%d,\"batch\":%s,\"tree\":%s,\"solutions\":%.0f,\"init_s\":%.9f,\"build_s\":%.9f,\"reduce_s\":%.9f,\"count_s\":%.9f,\"compute_s\":%.9f,\"cleanup_s\":%.9f,\"nodes_created\":%d,\"tasks\":%d,\"stolen_tasks\":%d,\"external_runs\":%d,\"gc\":%d,\"native_bytes\":%d,\"peak_native_bytes\":%d}%n",
                n, backend, workers, batch, tree, solutions, init, build, reduce, count, compute, cleanup, stats.nodesCreated, stats.tasks, stats.stolenTasks, stats.externalRuns, stats.collections, stats.nativeBytes, stats.peakNativeBytes);
    }
}
