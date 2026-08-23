package org.ants.jndd.diagram;

import java.lang.reflect.Field;

import org.ants.jndd.bdd.ComplementedBDD;

import jdd.bdd.BDD;
import jdd.zdd.ZDD;

final class LabelDecisionDiagramBackends {
    private LabelDecisionDiagramBackends() {}

    static LabelDecisionDiagramBackend create(NDD.LabelMode mode, int nodeTableSize, int cacheSize) {
        if (mode == NDD.LabelMode.BITSET) {
            return new BitsetBackend();
        }
        if (mode == NDD.LabelMode.COMPLEMENTED_BDD) {
            return new ComplementedBddBackend(new ComplementedBDD(nodeTableSize, cacheSize));
        }
        if (mode == NDD.LabelMode.ZDD) {
            return new SetFamilyZddBackend(new ZDD(nodeTableSize, cacheSize));
        }
        return forBooleanBdd(new BDD(nodeTableSize, cacheSize));
    }

    static LabelDecisionDiagramBackend forBooleanBdd(BDD engine) {
        return new BooleanBddBackend(engine);
    }

    private static long reflectActiveNodeCount(Object engine) {
        try {
            long tableSize = readLongField(engine, "table_size");
            long freeNodes = readLongField(engine, "free_nodes_count");
            return tableSize - freeNodes;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to read active label-node count", e);
        }
    }

    private static long readLongField(Object target, String fieldName) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return ((Number) field.get(target)).longValue();
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static final class BooleanBddBackend implements LabelDecisionDiagramBackend {
        private final BDD engine;

        private BooleanBddBackend(BDD engine) {
            this.engine = engine;
        }

        @Override
        public NDD.LabelMode mode() {
            return NDD.LabelMode.BDD;
        }

        @Override
        public boolean hasExplicitUniverse() {
            return false;
        }

        @Override
        public Object rawEngine() {
            return engine;
        }

        @Override
        public int createVariableLabel() {
            return engine.createVar();
        }

        @Override
        public int variableId(int label) {
            return engine.getVar(label);
        }

        @Override
        public int buildUniverse(int[] variableLabels, int offset, int length) {
            return 1;
        }

        @Override
        public int positiveLiteral(int universe, int variableLabel) {
            return variableLabel;
        }

        @Override
        public int negativeLiteral(int universe, int variableLabel) {
            return engine.not(variableLabel);
        }

        @Override
        public int ref(int label) {
            return engine.ref(label);
        }

        @Override
        public void deref(int label) {
            engine.deref(label);
        }

        @Override
        public int and(int left, int right) {
            return engine.and(left, right);
        }

        @Override
        public boolean matches(int label, int assignment) {
            return engine.and(label, assignment) != 0;
        }

        @Override
        public int or(int left, int right) {
            return engine.or(left, right);
        }

        @Override
        public int diff(int universe, int left, int right) {
            return engine.and(left, engine.not(right));
        }

        @Override
        public int not(int universe, int label) {
            return engine.not(label);
        }

        @Override
        public int orTo(int current, int add) {
            return engine.orTo(current, add);
        }

        @Override
        public int andTo(int current, int other) {
            return engine.andTo(current, other);
        }

        @Override
        public double satCount(int label, int fieldBits, int maxBits) {
            return engine.satCount(label) / Math.pow(2.0, maxBits - fieldBits);
        }

        @Override
        public long nodeCount() {
            return engine.getNodeCount();
        }

        @Override
        public long totalCreated() {
            return engine.getTotalCreated();
        }

        @Override
        public long gcCount() {
            return engine.getGcCount();
        }

        @Override
        public long gcFreedCount() {
            return engine.getGcFreedCount();
        }

        @Override
        public long gcTimeMillis() {
            return engine.getGcTimeMillis();
        }

        @Override
        public long gcNotifyTimeMillis() {
            return engine.getGcNotifyTimeMillis();
        }

        @Override
        public long growCount() {
            return engine.getGrowCount();
        }

        @Override
        public long growTimeMillis() {
            return engine.getGrowTimeMillis();
        }

        @Override
        public void gc() {
            engine.gc();
        }

        @Override
        public void showStats() {
            engine.showStats();
        }
    }

    private static final class ComplementedBddBackend implements LabelDecisionDiagramBackend {
        private final ComplementedBDD engine;

        private ComplementedBddBackend(ComplementedBDD engine) {
            this.engine = engine;
        }

        @Override
        public NDD.LabelMode mode() {
            return NDD.LabelMode.COMPLEMENTED_BDD;
        }

        @Override
        public boolean hasExplicitUniverse() {
            return false;
        }

        @Override
        public Object rawEngine() {
            return engine;
        }

        @Override
        public int createVariableLabel() {
            return engine.createVar();
        }

        @Override
        public int variableId(int label) {
            return engine.getVar(label);
        }

        @Override
        public int buildUniverse(int[] variableLabels, int offset, int length) {
            return 1;
        }

        @Override
        public int positiveLiteral(int universe, int variableLabel) {
            return variableLabel;
        }

        @Override
        public int negativeLiteral(int universe, int variableLabel) {
            return engine.not(variableLabel);
        }

        @Override
        public int ref(int label) {
            return engine.ref(label);
        }

        @Override
        public void deref(int label) {
            engine.deref(label);
        }

        @Override
        public int and(int left, int right) {
            return engine.and(left, right);
        }

        @Override
        public boolean matches(int label, int assignment) {
            return engine.and(label, assignment) != 0;
        }

        @Override
        public int or(int left, int right) {
            return engine.or(left, right);
        }

        @Override
        public int diff(int universe, int left, int right) {
            return engine.and(left, engine.not(right));
        }

        @Override
        public int not(int universe, int label) {
            return engine.not(label);
        }

        @Override
        public int orTo(int current, int add) {
            return engine.orTo(current, add);
        }

        @Override
        public int andTo(int current, int other) {
            return engine.andTo(current, other);
        }

        @Override
        public double satCount(int label, int fieldBits, int maxBits) {
            return engine.satCount(label) / Math.pow(2.0, maxBits - fieldBits);
        }

        @Override
        public long nodeCount() {
            return engine.getNodeCount();
        }

        @Override
        public long totalCreated() {
            return engine.getTotalCreated();
        }

        @Override
        public long gcCount() {
            return engine.getGcCount();
        }

        @Override
        public long gcFreedCount() {
            return engine.getGcFreedCount();
        }

        @Override
        public long gcTimeMillis() {
            return engine.getGcTimeMillis();
        }

        @Override
        public long gcNotifyTimeMillis() {
            return 0L;
        }

        @Override
        public long growCount() {
            return engine.getGrowCount();
        }

        @Override
        public long growTimeMillis() {
            return engine.getGrowTimeMillis();
        }

        @Override
        public void gc() {
            engine.gc();
        }

        @Override
        public void showStats() {
            System.out.println("BCDD nodes=" + engine.getNodeCount()
                    + " created=" + engine.getTotalCreated()
                    + " gc=" + engine.getGcCount()
                    + " grow=" + engine.getGrowCount());
        }
    }

    private static final class SetFamilyZddBackend implements LabelDecisionDiagramBackend {
        private final ZDD engine;

        private SetFamilyZddBackend(ZDD engine) {
            this.engine = engine;
        }

        @Override
        public NDD.LabelMode mode() {
            return NDD.LabelMode.ZDD;
        }

        @Override
        public boolean hasExplicitUniverse() {
            return true;
        }

        @Override
        public Object rawEngine() {
            return engine;
        }

        @Override
        public int createVariableLabel() {
            return engine.single(engine.createVar());
        }

        @Override
        public int variableId(int label) {
            return engine.getVar(label);
        }

        @Override
        public int buildUniverse(int[] variableLabels, int offset, int length) {
            boolean[] selected = new boolean[variableLabels.length];
            for (int i = offset; i < offset + length; i++) {
                selected[variableId(variableLabels[i])] = true;
            }
            return engine.subsets(selected);
        }

        @Override
        public int positiveLiteral(int universe, int variableLabel) {
            int variable = variableId(variableLabel);
            int withoutVariable = engine.ref(engine.subset1(universe, variable));
            int result = engine.change(withoutVariable, variable);
            engine.deref(withoutVariable);
            return result;
        }

        @Override
        public int negativeLiteral(int universe, int variableLabel) {
            return engine.subset0(universe, variableId(variableLabel));
        }

        @Override
        public int ref(int label) {
            return engine.ref(label);
        }

        @Override
        public void deref(int label) {
            engine.deref(label);
        }

        @Override
        public int and(int left, int right) {
            return engine.intersect(left, right);
        }

        @Override
        public boolean matches(int label, int assignment) {
            return engine.intersect(label, assignment) != 0;
        }

        @Override
        public int or(int left, int right) {
            return engine.union(left, right);
        }

        @Override
        public int diff(int universe, int left, int right) {
            return engine.diff(left, right);
        }

        @Override
        public int not(int universe, int label) {
            return engine.diff(universe, label);
        }

        @Override
        public int orTo(int current, int add) {
            if (current == 0) {
                return add;
            }
            int result = engine.ref(engine.union(current, add));
            engine.deref(current);
            engine.deref(add);
            return result;
        }

        @Override
        public int andTo(int current, int other) {
            int result = engine.ref(engine.intersect(current, other));
            engine.deref(current);
            return result;
        }

        @Override
        public double satCount(int label, int fieldBits, int maxBits) {
            return engine.countDouble(label);
        }

        @Override
        public long nodeCount() {
            return engine.getNodeCount();
        }

        @Override
        public long totalCreated() {
            return engine.getTotalCreated();
        }

        @Override
        public long gcCount() {
            return engine.getGcCount();
        }

        @Override
        public long gcFreedCount() {
            return engine.getGcFreedCount();
        }

        @Override
        public long gcTimeMillis() {
            return engine.getGcTimeMillis();
        }

        @Override
        public long gcNotifyTimeMillis() {
            return engine.getGcNotifyTimeMillis();
        }

        @Override
        public long growCount() {
            return engine.getGrowCount();
        }

        @Override
        public long growTimeMillis() {
            return engine.getGrowTimeMillis();
        }

        @Override
        public void gc() {
            engine.gc();
        }

        @Override
        public void showStats() {
            engine.showStats();
        }
    }

    /** Canonical truth tables for fields of at most seven bits (128 concrete values). */
    private static final class BitsetBackend implements LabelDecisionDiagramBackend {
        private static final int MAX_WIDTH = 7;

        private int variables;
        private int width = -1;
        private long universeLow;
        private long universeHigh;
        private long[] lows = new long[16];
        private long[] highs = new long[16];
        private int[] next = new int[16];
        private int[] buckets = new int[32];
        private final int[] concreteLabels = new int[1 << (2 * MAX_WIDTH)];
        private final int[] assignmentLabels = new int[1 << MAX_WIDTH];
        private int count;
        private long totalCreated;
        private long growCount;

        @Override
        public NDD.LabelMode mode() {
            return NDD.LabelMode.BITSET;
        }

        @Override
        public boolean hasExplicitUniverse() {
            return true;
        }

        @Override
        public Object rawEngine() {
            return this;
        }

        @Override
        public int createVariableLabel() {
            return -(++variables);
        }

        @Override
        public int variableId(int label) {
            if (label >= 0) throw new IllegalArgumentException("not a bitset variable token");
            return -label - 1;
        }

        @Override
        public int buildUniverse(int[] variableLabels, int offset, int length) {
            if (width < 0) initialize(variableLabels.length);
            if (width != variableLabels.length) {
                throw new IllegalStateException("bitset backend width changed");
            }
            return 1;
        }

        private void initialize(int maximumWidth) {
            if (maximumWidth <= 0 || maximumWidth > MAX_WIDTH) {
                throw new IllegalArgumentException(
                        "bitset labels support field widths 1-" + MAX_WIDTH
                        + ", requested " + maximumWidth);
            }
            width = maximumWidth;
            int assignments = 1 << width;
            universeLow = assignments >= 64 ? -1L : (1L << assignments) - 1L;
            universeHigh = assignments == 128 ? -1L : 0L;
            lows[0] = 0L;
            highs[0] = 0L;
            lows[1] = universeLow;
            highs[1] = universeHigh;
            count = 2;
            totalCreated = 2;
            rebuildBuckets();
        }

        @Override
        public int positiveLiteral(int universe, int variableLabel) {
            int variable = variableId(variableLabel);
            long low = 0L;
            long high = 0L;
            int assignments = 1 << width;
            for (int assignment = 0; assignment < assignments; assignment++) {
                if (((assignment >>> variable) & 1) == 0) continue;
                if (assignment < 64) low |= 1L << assignment;
                else high |= 1L << (assignment - 64);
            }
            return intern(low, high);
        }

        @Override
        public int negativeLiteral(int universe, int variableLabel) {
            return not(universe, positiveLiteral(universe, variableLabel));
        }

        @Override
        public int concreteValueLabel(int universe, int[] variableLabels,
                int offset, int length, int value) {
            int selectedVariables = 0;
            int expectedAssignment = 0;
            for (int bit = 0; bit < length; bit++) {
                int variable = variableId(variableLabels[offset + bit]);
                selectedVariables |= 1 << variable;
                if (((value >>> (length - 1 - bit)) & 1) != 0) {
                    expectedAssignment |= 1 << variable;
                }
            }
            int cacheKey = (selectedVariables << MAX_WIDTH) | expectedAssignment;
            int cached = concreteLabels[cacheKey];
            if (cached != 0) return cached;

            long low = 0L;
            long high = 0L;
            int assignments = 1 << width;
            for (int assignment = 0; assignment < assignments; assignment++) {
                if ((assignment & selectedVariables) != expectedAssignment) continue;
                if (assignment < 64) low |= 1L << assignment;
                else high |= 1L << (assignment - 64);
            }
            int result = intern(low, high);
            concreteLabels[cacheKey] = result;
            return result;
        }

        @Override
        public int ref(int label) {
            return label;
        }

        @Override
        public void deref(int label) {}

        @Override
        public int and(int left, int right) {
            return intern(lows[left] & lows[right], highs[left] & highs[right]);
        }

        @Override
        public boolean matches(int label, int assignment) {
            return (lows[label] & lows[assignment]) != 0L
                    || (highs[label] & highs[assignment]) != 0L;
        }

        @Override
        public int or(int left, int right) {
            return intern(lows[left] | lows[right], highs[left] | highs[right]);
        }

        @Override
        public int diff(int universe, int left, int right) {
            return intern(lows[left] & ~lows[right], highs[left] & ~highs[right]);
        }

        @Override
        public int not(int universe, int label) {
            return intern(universeLow & ~lows[label], universeHigh & ~highs[label]);
        }

        @Override
        public int orTo(int current, int add) {
            return or(current, add);
        }

        @Override
        public int andTo(int current, int other) {
            return and(current, other);
        }

        @Override
        public double satCount(int label, int fieldBits, int maxBits) {
            long assignments = Long.bitCount(lows[label]) + Long.bitCount(highs[label]);
            return assignments / Math.pow(2.0, maxBits - fieldBits);
        }

        @Override
        public int assignmentCapacity() {
            return width < 0 ? 0 : 1 << width;
        }

        @Override
        public void fillAssignmentTargets(int label, int target,
                int[] targets, int offset) {
            long low = lows[label];
            while (low != 0L) {
                int bit = Long.numberOfTrailingZeros(low);
                targets[offset + bit] = target;
                low &= low - 1L;
            }
            long high = highs[label];
            while (high != 0L) {
                int bit = Long.numberOfTrailingZeros(high);
                targets[offset + 64 + bit] = target;
                high &= high - 1L;
            }
        }

        @Override
        public int assignmentLabel(int assignment) {
            int cached = assignmentLabels[assignment];
            if (cached != 0) return cached;
            int result = assignment < 64
                    ? intern(1L << assignment, 0L)
                    : intern(0L, 1L << (assignment - 64));
            assignmentLabels[assignment] = result;
            return result;
        }

        private int intern(long low, long high) {
            low &= universeLow;
            high &= universeHigh;
            if ((low | high) == 0L) return 0;
            if (low == universeLow && high == universeHigh) return 1;
            int bucket = hash(low, high) & (buckets.length - 1);
            for (int entry = buckets[bucket]; entry != 0; entry = next[entry - 1]) {
                int id = entry - 1;
                if (lows[id] == low && highs[id] == high) return id;
            }
            ensureCapacity();
            if (count * 10 >= buckets.length * 7) {
                buckets = new int[buckets.length << 1];
                rebuildBuckets();
                growCount++;
                bucket = hash(low, high) & (buckets.length - 1);
            }
            int id = count++;
            lows[id] = low;
            highs[id] = high;
            next[id] = buckets[bucket];
            buckets[bucket] = id + 1;
            totalCreated++;
            return id;
        }

        private void ensureCapacity() {
            if (count < lows.length) return;
            int capacity = lows.length << 1;
            long[] newLows = new long[capacity];
            long[] newHighs = new long[capacity];
            int[] newNext = new int[capacity];
            System.arraycopy(lows, 0, newLows, 0, count);
            System.arraycopy(highs, 0, newHighs, 0, count);
            System.arraycopy(next, 0, newNext, 0, count);
            lows = newLows;
            highs = newHighs;
            next = newNext;
        }

        private void rebuildBuckets() {
            java.util.Arrays.fill(buckets, 0);
            for (int id = 0; id < count; id++) {
                int bucket = hash(lows[id], highs[id]) & (buckets.length - 1);
                next[id] = buckets[bucket];
                buckets[bucket] = id + 1;
            }
        }

        private static int hash(long low, long high) {
            long value = low * 0x9e3779b97f4a7c15L
                    ^ Long.rotateLeft(high * 0xc2b2ae3d27d4eb4fL, 29);
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            return (int) (value ^ (value >>> 32));
        }

        @Override public long nodeCount() { return count; }
        @Override public long totalCreated() { return totalCreated; }
        @Override public long gcCount() { return 0L; }
        @Override public long gcFreedCount() { return 0L; }
        @Override public long gcTimeMillis() { return 0L; }
        @Override public long gcNotifyTimeMillis() { return 0L; }
        @Override public long growCount() { return growCount; }
        @Override public long growTimeMillis() { return 0L; }
        @Override public void gc() {}

        @Override
        public void showStats() {
            System.out.println("BITSET labels=" + count + " created=" + totalCreated
                    + " grow=" + growCount);
        }
    }
}
