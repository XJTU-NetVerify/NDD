package org.ants.jndd.parallel;

import org.ants.jndd.diagram.NDD;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "pndd.tests", matches = "true")
class ParallelNDDTest {
    @Test void multiplePriorityListsShareOneRunAndKeepAllOwnedResultsThroughGc() {
        ParallelNDDConfig config = new ParallelNDDConfig(6, 16L << 20, 4096, 2, 1, 6, 1, true, true, true);
        try (ParallelNDD e = new ParallelNDD(config, new ParallelNDD.Field(3, ParallelNDD.Backend.BCDD));
             NDDNode budget = e.literal(0, 2, false)) {
            NDDNode[] original = {e.literal(0, 0, true), e.literal(0, 1, true),
                    e.literal(0, 2, true), e.literal(0, 0, false)};
            try {
                ParallelNDD.PriorityRequest[] requests = new ParallelNDD.PriorityRequest[8];
                for (int i = 0; i < requests.length; ++i) {
                    NDDNode[] editable = original.clone(); int[] ends = {2, 4};
                    requests[i] = new ParallelNDD.PriorityRequest(budget, editable, ends, i % 3, (i & 1) != 0, (i & 2) != 0);
                    editable[0] = null; ends[0] = 0; // descriptions snapshot arrays
                }
                long runs = e.stats().externalRuns;
                NDDNode[][] selected = e.prioritySelectBatch(requests);
                assertEquals(runs + 1, e.stats().externalRuns); e.collect();
                for (int r = 0; r < selected.length; ++r) {
                    for (int i = r % 3; i < original.length; ++i) {
                        for (int value = 0; value < 8; ++value) {
                            boolean expected = e.evaluate(budget, value) && e.evaluate(original[i], value);
                            int prior = (r & 1) != 0 ? (i < 2 ? 0 : 2) : i;
                            for (int j = 0; j < prior; ++j) expected &= !e.evaluate(original[j], value);
                            assertEquals(expected, e.evaluate(selected[r][i - r % 3], value));
                        }
                        selected[r][i - r % 3].close();
                    }
                }
                e.collect(); assertEquals(4, e.satCount(original[0]));
                assertEquals(0, e.prioritySelectBatch().length);
                original[3].close();
                assertThrows(IllegalStateException.class, () -> e.prioritySelectBatch(requests));
            } finally { for (NDDNode root : original) root.close(); }
        }
    }
    @Test void prioritySelectionMatchesEnumerationAndSurvivesGc() {
        try (ParallelNDD e = new ParallelNDD(new ParallelNDDConfig(4),
                new ParallelNDD.Field(3, ParallelNDD.Backend.BCDD))) {
            NDDNode[] inputs = {e.literal(0, 0, true), e.literal(0, 1, true),
                    e.literal(0, 2, true), e.literal(0, 0, false)};
            try (NDDNode budget = e.literal(0, 2, false)) {
                for (boolean multipath : new boolean[]{false, true}) for (boolean first : new boolean[]{false, true}) {
                    long runs = e.stats().externalRuns;
                    NDDNode[] selected = e.prioritySelect(budget, inputs, new int[]{2, 4}, 1, multipath, first);
                    assertEquals(runs + 1, e.stats().externalRuns); e.collect();
                    for (int i = 1; i < inputs.length; ++i) {
                        int groupStart = i < 2 ? 0 : 2;
                        for (int value = 0; value < 8; ++value) {
                            boolean expected = e.evaluate(budget, value) && e.evaluate(inputs[i], value);
                            for (int j = 0; j < (multipath ? groupStart : i); ++j) expected &= !e.evaluate(inputs[j], value);
                            assertEquals(expected, e.evaluate(selected[i - 1], value));
                        }
                        selected[i - 1].close();
                    }
                }
                assertEquals(0, e.prioritySelect(budget, inputs, new int[]{2, 4}, 4, true).length);
                assertThrows(IllegalArgumentException.class, () -> e.prioritySelect(budget, inputs, new int[]{2, 2, 4}, 0, true));
                inputs[0].close();
                assertThrows(IllegalStateException.class, () -> e.prioritySelect(budget, inputs, new int[]{2, 4}, 0, true));
            } finally { for (NDDNode node : inputs) node.close(); }
        }
    }
    @Test void emptyReachabilityRejectsClosedEngine() {
        ParallelNDD closed = new ParallelNDD(new ParallelNDDConfig(1),
                new ParallelNDD.Field(1, ParallelNDD.Backend.BCDD));
        closed.close();
        try (ParallelNDD replacement = new ParallelNDD(new ParallelNDDConfig(1),
                new ParallelNDD.Field(1, ParallelNDD.Backend.BCDD))) {
            assertThrows(IllegalStateException.class,
                    () -> closed.reachable(new NDDNode[0], new NDDNode[0]));
            assertEquals(0, replacement.reachable(new NDDNode[0], new NDDNode[0]).length);
        }
    }
    @Test void cachedScalarApplyAvoidsExternalRunAndSurvivesCollection() {
        ParallelNDDConfig c = new ParallelNDDConfig(2, 64L << 20, 4096, 4, 2, 4, 0, true, false, true);
        try (ParallelNDD e = new ParallelNDD(c, new ParallelNDD.Field(4, ParallelNDD.Backend.BCDD));
             NDDNode a = e.literal(0, 0, true); NDDNode b = e.literal(0, 1, true);
             NDDNode first = e.and(a, b)) {
            long runs = e.stats().externalRuns;
            try (NDDNode second = e.and(a, b)) {
                assertEquals(first, second); assertEquals(runs, e.stats().externalRuns);
            }
            e.collect(); runs = e.stats().externalRuns;
            try (NDDNode third = e.and(a, b)) {
                assertEquals(first, third); assertEquals(runs + 1, e.stats().externalRuns);
            }
        }
    }
    @Test void wideFieldsInspectionAndBothVariableOrders() {
        for (boolean reverse : new boolean[]{false, true})
            for (ParallelNDD.Backend mode : new ParallelNDD.Backend[]{ParallelNDD.Backend.BDD, ParallelNDD.Backend.BCDD}) {
                ParallelNDDConfig config = new ParallelNDDConfig(2, 64L << 20, 4096, 2, 1, 4, 1, true, true, true, reverse);
                try (ParallelNDD e = new ParallelNDD(config, new ParallelNDD.Field(108, mode), new ParallelNDD.Field(2, mode));
                     NDDNode a = e.literal(0, 0, true); NDDNode b = e.literal(0, 107, false);
                     NDDNode root = e.and(a, b); NDDNode dead = e.not(root);
                     NDDNode value = e.value(0, -1L)) {
                    assertEquals(Math.scalb(1.0, 108), e.satCount(root));
                    assertEquals(4.0, e.satCount(value)); assertTrue(e.evaluate(value, -1L, 0));
                    assertArrayEquals(new boolean[]{false, false, true, false},
                            e.reachable(new NDDNode[]{root}, new NDDNode[]{a, b, root, dead}));
                    try (ParallelNDD.Snapshot s = e.snapshot(root)) {
                        assertEquals(0, s.field); assertEquals(1, s.targets.length);
                        assertEquals(1, e.minLabelCost(s.conditions[0], 0, false));
                        byte[][] cubes = e.labelCubes(s.conditions[0], 0, 16);
                        assertEquals(1, cubes.length); assertEquals(1, cubes[0][0]); assertEquals(0, cubes[0][107]);
                        for (int bit = 1; bit < 107; ++bit) assertEquals(-1, cubes[0][bit]);
                        e.collect(); assertEquals(e.fingerprint(root), e.fingerprint(s.conditions[0]));
                    }
                }
            }
    }
    private static NDD.LabelMode legacyMode(ParallelNDD.Backend b) {
        return b == ParallelNDD.Backend.BCDD ? NDD.LabelMode.COMPLEMENTED_BDD : NDD.LabelMode.valueOf(b.name());
    }
    @Test void matchesLegacyAndEnumerationAcrossFourBackendsAndMixedFields() {
        for (boolean mixed : new boolean[]{false, true}) for (ParallelNDD.Backend mode : ParallelNDD.Backend.values()) {
            ParallelNDD.Field[] fields = new ParallelNDD.Field[4];
            NDD.initNDD(20000, 20000, 2000, legacyMode(mode));
            for (int f = 0; f < fields.length; ++f) {
                fields[f] = new ParallelNDD.Field(f % 2 == 0 ? 2 : 1, mixed ? ParallelNDD.Backend.values()[f] : mode);
                NDD.declareField(fields[f].width, legacyMode(fields[f].backend));
            }
            NDD.generateFields();
            try (ParallelNDD engine = new ParallelNDD(new ParallelNDDConfig(4), fields)) {
                NDDNode[] roots = new NDDNode[64]; int[] legacy = new int[64]; long[] oracle = new long[64]; int count = 0;
                for (int f = 0; f < fields.length; ++f) for (int bit = 0; bit < fields[f].width; ++bit) {
                    roots[count] = engine.literal(f, bit, true); legacy[count] = NDD.ref(NDD.getVar(f, bit));
                    for (int assignment = 0; assignment < 64; ++assignment) {
                        long[] values = values(assignment);
                        if (((values[f] >>> (fields[f].width - 1 - bit)) & 1) != 0) oracle[count] |= 1L << assignment;
                    }
                    ++count;
                }
                Random random = new Random(13);
                for (int i = 0; i < 40; ++i) {
                    int a = random.nextInt(count), b = random.nextInt(count);
                    ParallelNDD.Operation op = ParallelNDD.Operation.values()[i % 8];
                    roots[count] = engine.apply(op, roots[a], roots[b]);
                    switch (op) {
                        case AND: legacy[count] = NDD.and(legacy[a], legacy[b]); oracle[count] = oracle[a] & oracle[b]; break;
                        case OR: legacy[count] = NDD.or(legacy[a], legacy[b]); oracle[count] = oracle[a] | oracle[b]; break;
                        case DIFF: legacy[count] = NDD.diff(legacy[a], legacy[b]); oracle[count] = oracle[a] & ~oracle[b]; break;
                        case XOR: legacy[count] = NDD.or(NDD.diff(legacy[a], legacy[b]), NDD.diff(legacy[b], legacy[a])); oracle[count] = oracle[a] ^ oracle[b]; break;
                        case IMP: legacy[count] = NDD.imp(legacy[a], legacy[b]); oracle[count] = ~oracle[a] | oracle[b]; break;
                        case EQUIV: legacy[count] = NDD.not(NDD.or(NDD.diff(legacy[a], legacy[b]), NDD.diff(legacy[b], legacy[a]))); oracle[count] = ~(oracle[a] ^ oracle[b]); break;
                        case NAND: legacy[count] = NDD.not(NDD.and(legacy[a], legacy[b])); oracle[count] = ~(oracle[a] & oracle[b]); break;
                        default: legacy[count] = NDD.not(NDD.or(legacy[a], legacy[b])); oracle[count] = ~(oracle[a] | oracle[b]); break;
                    }
                    NDD.ref(legacy[count]);
                    assertEquals(Long.bitCount(oracle[count]), engine.satCount(roots[count]));
                    assertEquals(NDD.satCount(legacy[count]), engine.satCount(roots[count]));
                    for (int assignment = 0; assignment < 64; ++assignment)
                        assertEquals(((oracle[count] >>> assignment) & 1) != 0, engine.evaluate(roots[count], values(assignment)));
                    ++count;
                    if (i % 7 == 0) { engine.collect(); NDD.gc(); }
                }
                for (int f = 0; f < fields.length; ++f) {
                    try (NDDNode x = engine.exist(roots[count - 1], f)) {
                        assertEquals(NDD.satCount(NDD.exist(legacy[count - 1], f)), engine.satCount(x));
                    }
                }
                for (int i = 0; i < count; ++i) { roots[i].close(); NDD.deref(legacy[i]); }
            }
            if (mixed) break;
        }
    }
    private static long[] values(int n) { return new long[]{n & 3, (n >>> 2) & 1, (n >>> 3) & 3, (n >>> 5) & 1}; }

    @Test void lifecycleBatchAndErrors() {
        ParallelNDD.Field field = new ParallelNDD.Field(3, ParallelNDD.Backend.BITSET);
        ParallelNDD engine = new ParallelNDD(new ParallelNDDConfig(2), field);
        assertThrows(IllegalStateException.class, () -> new ParallelNDD(new ParallelNDDConfig(1), field));
        NDDNode a = engine.value(0, 3), retained = a.retain(); a.close(); a.close();
        assertEquals(1, engine.satCount(retained));
        assertThrows(IllegalStateException.class, () -> engine.satCount(a));
        assertThrows(IllegalArgumentException.class, () -> engine.value(0, 8));
        assertThrows(IllegalArgumentException.class, () -> engine.literal(0, 3, true));
        try (NDDNode b = engine.literal(0, 0, false)) {
            long before = engine.stats().externalRuns;
            NDDNode[] batch = engine.applyBatch(ParallelNDD.Operation.AND, new NDDNode[]{retained, b}, new NDDNode[]{b, retained});
            assertEquals(before + 1, engine.stats().externalRuns);
            assertEquals(batch[0], batch[1]); assertEquals(1, engine.satCount(batch[0]));
            for (NDDNode node : batch) node.close();
            assertEquals(0, engine.applyBatch(ParallelNDD.Operation.AND, new NDDNode[0], new NDDNode[0]).length);
            try (NDDNode emptyAnd = engine.reduce(ParallelNDD.Operation.AND, ParallelNDD.Reduction.BALANCED_TREE);
                 NDDNode emptyOr = engine.reduce(ParallelNDD.Operation.OR, ParallelNDD.Reduction.LEFT_FOLD)) {
                assertEquals(8, engine.satCount(emptyAnd)); assertEquals(0, engine.satCount(emptyOr));
            }
        }
        engine.close(); engine.close(); retained.close();
        assertThrows(IllegalStateException.class, () -> engine.constant(true));
        try (ParallelNDD next = new ParallelNDD(new ParallelNDDConfig(1), field)) {
            assertThrows(IllegalArgumentException.class, () -> next.satCount(retained));
        }
        assertThrows(IllegalArgumentException.class, () -> new ParallelNDD.Field(8, ParallelNDD.Backend.BITSET));
    }
    @Test void fullWordDomainsAndBitsetBoundary() {
        for (ParallelNDD.Backend mode : ParallelNDD.Backend.values()) {
            int width = mode == ParallelNDD.Backend.BITSET ? 7 : 64;
            try (ParallelNDD e = new ParallelNDD(new ParallelNDDConfig(2), new ParallelNDD.Field(width, mode));
                 NDDNode all = e.constant(true); NDDNode value = e.value(0, width == 64 ? -1 : 127);
                 NDDNode complement = e.not(value)) {
                assertEquals(Math.scalb(1.0, width), e.satCount(all)); assertEquals(1, e.satCount(value));
                assertTrue(e.evaluate(value, width == 64 ? -1 : 127));
                assertFalse(e.evaluate(complement, width == 64 ? -1 : 127)); assertTrue(e.evaluate(complement, 0));
                e.collect(); assertEquals(1, e.satCount(value));
            }
        }
    }

    @Test void initializationFailureReleasesTheSingleton() {
        ParallelNDDConfig tiny = new ParallelNDDConfig(1, 4L << 20, 262144, 4, 2, 4, 0, true, false, true);
        assertThrows(OutOfMemoryError.class, () -> new ParallelNDD(tiny, new ParallelNDD.Field(2, ParallelNDD.Backend.BDD)));
        try (ParallelNDD e = new ParallelNDD(new ParallelNDDConfig(1), new ParallelNDD.Field(2, ParallelNDD.Backend.BDD));
             NDDNode all = e.constant(true)) { assertEquals(4, e.satCount(all)); }
    }

    @Test void batchAndReduceEnterOnceEvenWhenEveryAdmissionCollects() {
        ParallelNDDConfig config = new ParallelNDDConfig(6, 64L << 20, 4096, 2, 1, 4, 1, true, true, true);
        try (ParallelNDD e = new ParallelNDD(config,
                new ParallelNDD.Field(2, ParallelNDD.Backend.BCDD),
                new ParallelNDD.Field(2, ParallelNDD.Backend.ZDD));
             NDDNode a = e.value(0, 1); NDDNode b = e.value(1, 2)) {
            ParallelNDD.Stats before = e.stats();
            NDDNode[] result = e.applyBatch(ParallelNDD.Operation.AND, new NDDNode[]{a, b}, new NDDNode[]{b, a});
            ParallelNDD.Stats after = e.stats();
            assertEquals(1, after.externalRuns - before.externalRuns);
            assertEquals(1, after.collections - before.collections);
            for (NDDNode node : result) { assertEquals(1, e.satCount(node)); node.close(); }
            for (ParallelNDD.Reduction order : ParallelNDD.Reduction.values()) {
                before = e.stats();
                try (NDDNode node = e.reduce(ParallelNDD.Operation.AND, order, a, b)) {
                    after = e.stats();
                    assertEquals(1, after.externalRuns - before.externalRuns);
                    assertEquals(1, after.collections - before.collections);
                    assertEquals(1, e.satCount(node));
                }
            }
        }
    }

    @Test void javaThreadsShareOnePoolAndKeepBorrowedRootsThroughCollection() throws Exception {
        try (ParallelNDD e = new ParallelNDD(new ParallelNDDConfig(6),
                new ParallelNDD.Field(2, ParallelNDD.Backend.BCDD),
                new ParallelNDD.Field(2, ParallelNDD.Backend.ZDD));
             NDDNode a = e.value(0, 1); NDDNode b = e.value(1, 2)) {
            java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                java.util.List<java.util.concurrent.Future<?>> work = new java.util.ArrayList<>();
                for (int thread = 0; thread < 4; ++thread) work.add(callers.submit(() -> {
                    for (int i = 0; i < 50; ++i) {
                        try (NDDNode intersection = e.and(a, b); NDDNode union = e.or(a, b)) {
                            assertEquals(1, e.satCount(intersection));
                            assertEquals(7, e.satCount(union));
                            if (i % 7 == 0) e.collect();
                        }
                    }
                }));
                for (java.util.concurrent.Future<?> result : work) result.get();
            } finally { callers.shutdownNow(); }
            assertEquals(4, e.satCount(a)); assertEquals(4, e.satCount(b));
        }
    }

    @Test void enormousDomainsKeepFalseZeroAndConstrainedCountsExact() {
        for (ParallelNDD.Backend mode : new ParallelNDD.Backend[]{ParallelNDD.Backend.BDD, ParallelNDD.Backend.BCDD, ParallelNDD.Backend.ZDD}) {
            ParallelNDD.Field[] fields = new ParallelNDD.Field[32];
            for (int i = 0; i < fields.length; ++i) fields[i] = new ParallelNDD.Field(64, mode);
            try (ParallelNDD e = new ParallelNDD(new ParallelNDDConfig(2), fields);
                 NDDNode empty = e.constant(false); NDDNode all = e.constant(true)) {
                assertEquals(0, e.satCount(empty));
                assertEquals(Double.POSITIVE_INFINITY, e.satCount(all));
                NDDNode root = all.retain();
                for (int f = 0; f < fields.length; ++f) {
                    try (NDDNode value = e.value(f, -1)) { NDDNode next = e.and(root, value); root.close(); root = next; }
                }
                try (NDDNode owned = root) { e.collect(); assertEquals(1, e.satCount(owned)); }
            }
        }
    }

}
