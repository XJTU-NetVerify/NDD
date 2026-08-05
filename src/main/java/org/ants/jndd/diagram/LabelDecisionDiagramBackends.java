package org.ants.jndd.diagram;

import java.lang.reflect.Field;

import org.ants.jndd.bdd.ComplementedBDD;

import jdd.bdd.BDD;
import jdd.zdd.ZDD;

final class LabelDecisionDiagramBackends {
    private LabelDecisionDiagramBackends() {}

    static LabelDecisionDiagramBackend create(NDD.LabelMode mode, int nodeTableSize, int cacheSize) {
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
            return reflectActiveNodeCount(engine);
        }

        @Override
        public long totalCreated() {
            return engine.getTotalCreated();
        }

        @Override
        public void gc() {
            engine.gc();
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
        public void gc() {
            engine.gc();
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
            return reflectActiveNodeCount(engine);
        }

        @Override
        public long totalCreated() {
            return engine.getTotalCreated();
        }

        @Override
        public void gc() {
            engine.gc();
        }
    }
}
