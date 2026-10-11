package org.ants.jndd.parallel;
/** Warm, cache-hit operations isolate scheduler/JNI admission and ownership costs.
 * Batch task splitting is disabled; this is separate from cold N-Queens runs. */
public final class EntryBenchmark {
    public static void main(String[] args) {
        int workers = Integer.parseInt(args[0]), count = args.length > 1 ? Integer.parseInt(args[1]) : 10000;
        int batch = args.length > 2 ? Integer.parseInt(args[2]) : 100;
        if (count < 1 || batch < 1 || count % batch != 0) throw new IllegalArgumentException();
        ParallelNDDConfig config = new ParallelNDDConfig(workers, 512L << 20, 262144, 4, 2, 4, 0, true, false, false);
        try (ParallelNDD e = new ParallelNDD(config, new ParallelNDD.Field(2, ParallelNDD.Backend.BDD), new ParallelNDD.Field(2, ParallelNDD.Backend.BDD));
             NDDNode a = e.value(0, 1); NDDNode b = e.value(1, 2)) {
            try (NDDNode warm = e.and(a, b)) { if (e.satCount(warm) != 1) throw new AssertionError(); }
            for (int i = 0; i < 2000; ++i) e.and(a, b).close();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i) e.and(a, b).close();
            double scalar = (System.nanoTime() - start) / 1e9;
            NDDNode[] left = new NDDNode[batch], right = new NDDNode[batch];
            for (int i = 0; i < batch; ++i) { left[i] = a; right[i] = b; }
            for (int i = 0; i < 20; ++i) { NDDNode[] result = e.applyBatch(ParallelNDD.Operation.AND, left, right); for (NDDNode node : result) node.close(); }
            start = System.nanoTime();
            for (int i = 0; i < count / batch; ++i) { NDDNode[] result = e.applyBatch(ParallelNDD.Operation.AND, left, right); for (NDDNode node : result) node.close(); }
            double batched = (System.nanoTime() - start) / 1e9;
            System.out.printf("{\"engine\":\"jni\",\"workers\":%d,\"operations\":%d,\"batch_size\":%d,\"scalar_s\":%.9f,\"batch_s\":%.9f}%n", workers, count, batch, scalar, batched);
        }
    }
}
