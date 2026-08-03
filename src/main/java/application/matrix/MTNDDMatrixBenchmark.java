package application.matrix;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import jdd.bdd.BDD;
import org.ants.jndd.diagram.NDD;
import org.ants.jndd.diagram.NDDManager;

/** End-to-end symbolic integer matrix multiplication benchmark for MTNDD. */
public final class MTNDDMatrixBenchmark {
    private MTNDDMatrixBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: MTNDDMatrixBenchmark DATASET");
            System.exit(2);
        }
        long processStart = System.nanoTime();
        Dataset dataset = Dataset.read(args[0]);
        long loadDone = System.nanoTime();
        int bits = Integer.numberOfTrailingZeros(dataset.size);
        if ((1 << bits) != dataset.size) {
            throw new IllegalArgumentException("matrix dimension must be a power of two");
        }

        NDDManager manager = new NDDManager(2_000_000, 100_003, 20_011);
        int rowField = manager.declareField(bits);
        int sumField = manager.declareField(bits);
        int colField = manager.declareField(bits);
        manager.generateFields();

        BDD bdd = NDD.getBDDEngine();
        int[] exactLabels = buildExactLabels(bdd, rowField, dataset.size);
        HashMap<Integer, NDD> terminals = new HashMap<>();
        terminals.put(0, NDD.getFalse());
        terminals.put(1, NDD.getTrue());

        long computeStart = System.nanoTime();
        NDD a = buildMatrix(dataset.a, rowField, sumField, exactLabels, terminals).withRef();
        NDD b = buildMatrix(dataset.b, sumField, colField, exactLabels, terminals).withRef();
        NDD product = NDD.mul(a, b).withRef();
        NDD result = NDD.sumAbstract(product, sumField).withRef();
        long computeDone = System.nanoTime();

        Checksum actual = checksum(result, dataset.size, rowField, colField);
        Checksum expected = explicitChecksum(dataset);
        if (!actual.equals(expected)) {
            throw new AssertionError("checksum mismatch: actual=" + actual + ", expected=" + expected);
        }

        NDD.GraphStats stats = NDD.graphStats(result);
        long heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.println("engine=mtndd-optimized");
        System.out.println("case=" + dataset.name);
        System.out.println("dimension=" + dataset.size);
        System.out.println("load_ms=" + millis(loadDone - processStart));
        System.out.println("compute_ms=" + millis(computeDone - computeStart));
        System.out.println("total_ms=" + millis(computeDone - processStart));
        System.out.println("mtndd_internal_nodes=" + stats.internalNodes);
        System.out.println("mtndd_physical_edges=" + stats.physicalEdges);
        System.out.println("mtndd_label_bdd_nodes=" + stats.labelBddNodes);
        System.out.println("mtndd_terminals=" + stats.terminals);
        System.out.println("manager_internal_nodes=" + NDD.getInternalNodeCount());
        System.out.println("manager_live_edges=" + NDD.getLivePhysicalEdgeCount());
        System.out.println("manager_physical_edge_slots=" + NDD.getPhysicalEdgeSlots());
        System.out.println("manager_label_bdd_nodes=" + liveBddNodes(bdd));
        System.out.println("manager_terminals=" + NDD.gettersize());
        System.out.println("engine_memory_bytes=" + heapUsed);
        System.out.println("checksum_sum=" + actual.sum);
        System.out.println("checksum_hash=" + Long.toUnsignedString(actual.hash));

        NDD.deref(result);
        NDD.deref(product);
        NDD.deref(b);
        NDD.deref(a);
        for (int label : exactLabels) {
            bdd.deref(label);
        }
    }

    private static NDD buildMatrix(
            int[][] matrix,
            int firstField,
            int secondField,
            int[] exactLabels,
            HashMap<Integer, NDD> terminals) {
        int size = matrix.length;
        NDD[] firstNodes = new NDD[size];
        for (int first = 0; first < size; first++) {
            HashMap<NDD, Integer> grouped = new HashMap<>();
            for (int second = 0; second < size; second++) {
                int value = matrix[first][second];
                if (value == 0) {
                    continue;
                }
                NDD target = terminals.get(value);
                if (target == null) {
                    target = NDD.createTerminal(value);
                    terminals.put(value, target);
                }
                mergeLabel(grouped, target, exactLabels[second]);
            }
            firstNodes[first] = makeNode(secondField, grouped).withRef();
        }

        HashMap<NDD, Integer> groupedFirst = new HashMap<>();
        for (int first = 0; first < size; first++) {
            if (!firstNodes[first].isFalse()) {
                mergeLabel(groupedFirst, firstNodes[first], exactLabels[first]);
            }
        }
        NDD result = makeNode(firstField, groupedFirst);
        for (NDD firstNode : firstNodes) {
            NDD.deref(firstNode);
        }
        return result;
    }

    private static void mergeLabel(
            HashMap<NDD, Integer> grouped, NDD target, int exactLabel) {
        BDD bdd = NDD.getBDDEngine();
        Integer previous = grouped.get(target);
        int merged = previous == null ? bdd.ref(exactLabel) : bdd.orTo(previous, exactLabel);
        grouped.put(target, merged);
    }

    private static NDD makeNode(int field, HashMap<NDD, Integer> grouped) {
        NDD[] targets = new NDD[grouped.size()];
        int[] labels = new int[grouped.size()];
        int index = 0;
        for (Map.Entry<NDD, Integer> entry : grouped.entrySet()) {
            targets[index] = entry.getKey();
            labels[index++] = entry.getValue();
        }
        NDD result = NDD.mk(field, targets, labels);
        BDD bdd = NDD.getBDDEngine();
        for (int label : labels) {
            bdd.deref(label);
        }
        return result;
    }

    private static int[] buildExactLabels(BDD bdd, int field, int size) {
        int[] variables = NDD.getBDDVars(field);
        int[] notVariables = NDD.getNotBDDVars(field);
        int[] labels = new int[size];
        for (int value = 0; value < size; value++) {
            int label = bdd.ref(1);
            for (int bit = 0; bit < variables.length; bit++) {
                int shift = variables.length - 1 - bit;
                int literal = ((value >>> shift) & 1) == 0
                        ? notVariables[bit] : variables[bit];
                label = bdd.andTo(label, literal);
            }
            labels[value] = label;
        }
        return labels;
    }

    private static Checksum checksum(NDD result, int size, int rowField, int colField) {
        long sum = 0;
        long hash = 0xcbf29ce484222325L;
        int[] values = new int[3];
        for (int row = 0; row < size; row++) {
            values[rowField] = row;
            for (int col = 0; col < size; col++) {
                values[colField] = col;
                long value = Math.round(NDD.evaluate(result, values));
                sum += value;
                hash ^= value;
                hash *= 0x100000001b3L;
            }
        }
        return new Checksum(sum, hash);
    }

    private static Checksum explicitChecksum(Dataset dataset) {
        long sum = 0;
        long hash = 0xcbf29ce484222325L;
        for (int row = 0; row < dataset.size; row++) {
            for (int col = 0; col < dataset.size; col++) {
                long value = 0;
                for (int k = 0; k < dataset.size; k++) {
                    value += (long) dataset.a[row][k] * dataset.b[k][col];
                }
                sum += value;
                hash ^= value;
                hash *= 0x100000001b3L;
            }
        }
        return new Checksum(sum, hash);
    }

    private static int liveBddNodes(BDD bdd) {
        return bdd.debug_table_size() - bdd.debug_free_nodes_count();
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static final class Checksum {
        final long sum;
        final long hash;

        Checksum(long sum, long hash) {
            this.sum = sum;
            this.hash = hash;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Checksum)) {
                return false;
            }
            Checksum other = (Checksum) value;
            return sum == other.sum && hash == other.hash;
        }

        @Override
        public int hashCode() {
            return (int) (sum ^ (sum >>> 32) ^ hash ^ (hash >>> 32));
        }

        @Override
        public String toString() {
            return sum + "/" + Long.toUnsignedString(hash);
        }
    }

    private static final class Dataset {
        final String name;
        final int size;
        final int[][] a;
        final int[][] b;

        Dataset(String name, int size, int[][] a, int[][] b) {
            this.name = name;
            this.size = size;
            this.a = a;
            this.b = b;
        }

        static Dataset read(String filename) throws IOException {
            try (FastScanner input = new FastScanner(filename)) {
                String magic = input.next();
                if (!"MTMATRIX1".equals(magic)) {
                    throw new IOException("invalid dataset magic: " + magic);
                }
                String name = input.next();
                int size = input.nextInt();
                return new Dataset(name, size, readMatrix(input, size), readMatrix(input, size));
            }
        }

        private static int[][] readMatrix(FastScanner input, int size) throws IOException {
            int[][] matrix = new int[size][size];
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    matrix[row][col] = input.nextInt();
                }
            }
            return matrix;
        }
    }

    private static final class FastScanner implements AutoCloseable {
        private final BufferedInputStream input;
        private final byte[] buffer = new byte[1 << 16];
        private int offset;
        private int limit;

        FastScanner(String filename) throws IOException {
            input = new BufferedInputStream(new FileInputStream(filename), buffer.length);
        }

        String next() throws IOException {
            StringBuilder value = new StringBuilder();
            int next;
            do {
                next = read();
            } while (next >= 0 && next <= ' ');
            while (next > ' ') {
                value.append((char) next);
                next = read();
            }
            return value.toString();
        }

        int nextInt() throws IOException {
            int next;
            do {
                next = read();
            } while (next >= 0 && next <= ' ');
            int sign = 1;
            if (next == '-') {
                sign = -1;
                next = read();
            }
            int value = 0;
            while (next > ' ') {
                value = value * 10 + next - '0';
                next = read();
            }
            return sign * value;
        }

        private int read() throws IOException {
            if (offset >= limit) {
                limit = input.read(buffer);
                offset = 0;
                if (limit < 0) {
                    return -1;
                }
            }
            return buffer[offset++];
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
