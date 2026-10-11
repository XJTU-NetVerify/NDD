package org.ants.jndd.parallel;
import org.ants.jndd.diagram.NDD;
/** Same compact row-variable constraints as the C benchmark, separate track. */
public final class LegacyCompactNQueens {
    private static int value(int field, int width, int value) {
        int[] bits = new int[width]; for (int i = 0; i < width; ++i) bits[i] = (value >>> (width - 1 - i)) & 1;
        return NDD.ref(NDD.encodePrefix(bits, field));
    }
    public static void main(String[] args) {
        int n = Integer.parseInt(args[0]); String backend = args[1];
        NDD.LabelMode mode = backend.equals("BCDD") ? NDD.LabelMode.COMPLEMENTED_BDD : NDD.LabelMode.valueOf(backend);
        int width = 1; while ((1 << width) < n) ++width;
        long initStart = System.nanoTime(); NDD.initNDD(100000000, 65536, 20000, 65536, mode);
        for (int i = 0; i < n; ++i) NDD.declareField(width); NDD.generateFields();
        double init = (System.nanoTime() - initStart) / 1e9; long buildStart = System.nanoTime();
        int[] constraints = new int[n + n * (n - 1) / 2]; int count = 0;
        for (int i = 0; i < n; ++i) {
            int allowed = NDD.getFalse();
            for (int v = 0; v < n; ++v) { int x = value(i, width, v); allowed = NDD.orTo(allowed, x); NDD.deref(x); }
            constraints[count++] = allowed;
        }
        for (int i = 0; i < n; ++i) for (int j = i + 1; j < n; ++j) {
            int forbidden = NDD.getFalse();
            for (int a = 0; a < n; ++a) for (int b = 0; b < n; ++b) {
                if (a != b && Math.abs(a - b) != j - i) continue;
                int x = value(i, width, a), y = value(j, width, b), cube = NDD.ref(NDD.and(x, y));
                forbidden = NDD.orTo(forbidden, cube); NDD.deref(x); NDD.deref(y); NDD.deref(cube);
            }
            constraints[count++] = NDD.ref(NDD.not(forbidden)); NDD.deref(forbidden);
        }
        double build = (System.nanoTime() - buildStart) / 1e9; long reduceStart = System.nanoTime();
        int queen = NDD.getTrue();
        for (int c : constraints) { queen = NDD.andTo(queen, c); NDD.deref(c); }
        double reduce = (System.nanoTime() - reduceStart) / 1e9; long countStart = System.nanoTime();
        double solutions = NDD.satCount(queen), countTime = (System.nanoTime() - countStart) / 1e9;
        NDD.deref(queen); double compute = (System.nanoTime() - buildStart) / 1e9;
        long created = NDD.getTotalCreated(); long cleanupStart = System.nanoTime(); NDD.gc(); NDD.gcLabelEngine();
        double cleanup = (System.nanoTime() - cleanupStart) / 1e9;
        System.out.printf("{\"n\":%d,\"backend\":\"%s\",\"compact\":1,\"solutions\":%.0f,\"init_s\":%.9f,\"build_s\":%.9f,\"reduce_s\":%.9f,\"count_s\":%.9f,\"compute_s\":%.9f,\"cleanup_s\":%.9f,\"nodes_created\":%d}%n", n, backend, solutions, init, build, reduce, countTime, compute, cleanup, created);
    }
}
