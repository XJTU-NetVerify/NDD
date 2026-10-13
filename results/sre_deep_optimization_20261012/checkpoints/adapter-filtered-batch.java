package sre.ndd;

import org.ants.jndd.parallel.NDDNode;
import org.ants.jndd.parallel.ParallelNDD;
import org.ants.jndd.parallel.ParallelNDDConfig;
import org.ants.jndd.bdd.ComplementedBDD;
import jdd.bdd.BDD;
import jdd.zdd.ZDD;
import java.util.*;

/** SRE's legacy integer/borrowed-result contract over the current Java or native
 * NDD. The native core owns one reference per retained adapter entry; logical
 * ref/deref counts determine the external roots at adapter collection. */
public final class NDD extends org.ants.jndd.diagram.NDD {
    public static boolean isNative() { return "native".equals(System.getProperty("sre.ndd.engine", "java")); }
    private static ParallelNDD engine;
    private static final ArrayList<ParallelNDD.Field> fields = new ArrayList<>();
    private static final ArrayList<LabelMode> modes = new ArrayList<>();
    private static final ArrayList<Entry> entries = new ArrayList<>();
    private static final HashMap<NDDNode, Integer> ids = new HashMap<>();
    private static int newlyCreated;
    private static long adapterCollections;
    private static final boolean PROFILE = Boolean.getBoolean("sre.ndd.profile");
    private static long adoptCalls, adoptNs, collectNs, applyNs, applyCalls;
    private static final class Entry {
        final NDDNode root;
        int refs;
        boolean permanent;
        int field = -1;
        int[] targets, labels;
        Entry(NDDNode root) { this.root = root; }
    }
    private static ParallelNDD.Backend backend(LabelMode mode) {
        return mode == LabelMode.COMPLEMENTED_BDD ? ParallelNDD.Backend.BCDD : ParallelNDD.Backend.valueOf(mode.name());
    }
    private static Entry entry(int id) {
        if (id < 0 || id >= entries.size() || entries.get(id) == null)
            throw new IllegalStateException("Released native adapter handle: " + id);
        return entries.get(id);
    }
    private static int adopt(NDDNode root) {
        if (!PROFILE) return adoptRaw(root);
        long start = System.nanoTime();
        try { return adoptRaw(root); }
        finally { ++adoptCalls; adoptNs += System.nanoTime() - start; }
    }
    private static int adoptRaw(NDDNode root) {
        Integer old = ids.get(root);
        if (old != null) { root.close(); return old; }
        int id = entries.size(); entries.add(new Entry(root)); ids.put(root, id); ++newlyCreated;
        return id;
    }
    private static void maybeCollect(int... protect) {
        if (newlyCreated >= Integer.getInteger("sre.ndd.collectEvery", 16384)) collect(protect);
    }
    private static void collect(int... protect) {
        if (!PROFILE) { collectRaw(protect); return; }
        long start = System.nanoTime();
        try { collectRaw(protect); }
        finally { collectNs += System.nanoTime() - start; }
    }
    private static void collectRaw(int... protect) {
        ArrayList<Entry> live = new ArrayList<>(); ArrayList<NDDNode> roots = new ArrayList<>();
        for (Entry e : entries) if (e != null) {
            live.add(e); if (e.permanent || e.refs > 0) roots.add(e.root);
        }
        for (int id : protect) roots.add(entry(id).root);
        NDDNode[] candidates = new NDDNode[live.size()];
        for (int i = 0; i < candidates.length; ++i) candidates[i] = live.get(i).root;
        boolean[] reachable = engine.reachable(roots.toArray(new NDDNode[0]), candidates);
        for (int i = 0; i < live.size(); ++i) if (!reachable[i]) {
            Entry e = live.get(i); int id = ids.remove(e.root); entries.set(id, null); e.root.close();
        }
        // Snapshot integer caches refer only to reachable children and permanent
        // condition entries; canonical integer identity survives collection.
        engine.collect(); newlyCreated = 0; ++adapterCollections;
    }
    public static void initNDD(int table, int cache, int labelTable, int labelCache) {
        if (engine != null) engine.close(); engine = null;
        fields.clear(); modes.clear(); entries.clear(); ids.clear(); newlyCreated = 0; adapterCollections = 0;
        adoptCalls = adoptNs = collectNs = applyNs = applyCalls = 0;
        if (!isNative()) org.ants.jndd.diagram.NDD.initNDD(table, cache, labelTable, labelCache);
    }
    public static void initNDD(int table, int labelTable, int labelCache) { initNDD(table, 10000, labelTable, labelCache); }
    public static void configureBackendCapacity(LabelMode mode, int table, int cache) {
        if (!isNative()) org.ants.jndd.diagram.NDD.configureBackendCapacity(mode, table, cache);
    }
    public static int declareField(int width, LabelMode mode) {
        if (engine != null) throw new IllegalStateException("Fields already generated");
        int index = isNative() ? fields.size() : org.ants.jndd.diagram.NDD.declareField(width, mode);
        fields.add(new ParallelNDD.Field(width, backend(mode))); modes.add(mode); return index;
    }
    public static int declareField(int width) { return declareField(width, LabelMode.BDD); }
    public static void generateFields() {
        if (!isNative()) { org.ants.jndd.diagram.NDD.generateFields(); return; }
        int workers = Integer.getInteger("sre.ndd.workers", 6);
        long memory = Long.getLong("sre.ndd.memoryMiB", 3072) << 20;
        int cache = Integer.getInteger("sre.ndd.cache", 262144);
        ParallelNDDConfig config = new ParallelNDDConfig(workers, memory, cache,
                Integer.getInteger("sre.ndd.threshold", 4), Integer.getInteger("sre.ndd.chunk", 2),
                Integer.getInteger("sre.ndd.depth", 4), 0,
                Boolean.parseBoolean(System.getProperty("sre.ndd.nddParallel", "true")),
                Boolean.parseBoolean(System.getProperty("sre.ndd.labels", "false")), true, true);
        engine = new ParallelNDD(config, fields.toArray(new ParallelNDD.Field[0]));
        if (adopt(engine.constant(false)) != 0 || adopt(engine.constant(true)) != 1) throw new AssertionError();
        entry(0).permanent = entry(1).permanent = true;
        System.out.println("ndd_engine:native"); System.out.println("ndd_workers:" + workers);
        System.out.println("ndd_native_budget_mib:" + (memory >> 20));
        System.out.println("ndd_label_order:legacy-lsb-first");
        System.out.println("ndd_parallel:" + config.nddParallel);
        System.out.println("ndd_label_parallel:" + config.labelParallel);
    }
    public static int getVar(int field, int bit) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.getVar(field, bit);
        int id = adopt(engine.literal(field, bit, true)); entry(id).permanent = true; return id;
    }
    public static int getNotVar(int field, int bit) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.getNotVar(field, bit);
        int id = adopt(engine.literal(field, bit, false)); entry(id).permanent = true; return id;
    }
    public static int getFieldNum() { return isNative() ? fields.size() - 1 : org.ants.jndd.diagram.NDD.getFieldNum(); }
    public static int fieldWidth(int field) { return fields.get(field).width; }
    public static LabelMode getFieldLabelMode(int f) { return isNative() ? modes.get(f) : org.ants.jndd.diagram.NDD.getFieldLabelMode(f); }
    public static int ref(int id) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.ref(id);
        if (id > 1) ++entry(id).refs; return id;
    }
    public static void deref(int id) {
        if (!isNative()) { org.ants.jndd.diagram.NDD.deref(id); return; }
        if (id > 1) { Entry e = entry(id); if (e.refs > 0) --e.refs; }
    }
    public static int getReferenceCount(int id) {
        return isNative() ? (id <= 1 ? Integer.MAX_VALUE : entry(id).refs) : org.ants.jndd.diagram.NDD.getReferenceCount(id);
    }
    private static int operation(ParallelNDD.Operation op, int a, int b) {
        maybeCollect(a, b);
        try { return adopt(nativeApply(op, a, b)); }
        catch (OutOfMemoryError failure) {
            collect(a, b); return adopt(nativeApply(op, a, b));
        }
    }
    private static NDDNode nativeApply(ParallelNDD.Operation op, int a, int b) {
        if (!PROFILE) return engine.apply(op, entry(a).root, entry(b).root);
        long start = System.nanoTime();
        try { return engine.apply(op, entry(a).root, entry(b).root); }
        finally { ++applyCalls; applyNs += System.nanoTime() - start; }
    }
    public static int and(int a, int b) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.and(a, b);
        if (a == 0 || b == 0) return 0; if (a == 1 || a == b) return b; if (b == 1) return a;
        return operation(ParallelNDD.Operation.AND, a, b);
    }
    public static boolean batchPropagation() {
        return isNative() && Boolean.parseBoolean(System.getProperty("sre.ndd.batch", "true"));
    }
    public static boolean batchPriority() {
        return isNative() && Boolean.parseBoolean(System.getProperty("sre.ndd.priorityBatch", "true"));
    }
    /** Owned selected conditions for the suffix [start,arrive.length). */
    public static int[] prioritySelectOwned(int budget, int[] arrive, int[] ends, int start, boolean multipath) {
        if (!isNative()) throw new IllegalStateException("Native priority selection required");
        int[] out = new int[arrive.length - start];
        int[] protect = new int[arrive.length + 1]; protect[0] = budget;
        System.arraycopy(arrive, 0, protect, 1, arrive.length); maybeCollect(protect);
        NDDNode[] roots = new NDDNode[arrive.length];
        for (int i = 0; i < roots.length; ++i) roots[i] = entry(arrive[i]).root;
        NDDNode[] results;
        try { results = engine.prioritySelect(entry(budget).root, roots, ends, start, multipath); }
        catch (OutOfMemoryError failure) { collect(protect); results = engine.prioritySelect(entry(budget).root, roots, ends, start, multipath); }
        int completed = 0;
        try { for (; completed < out.length; ++completed) out[completed] = ref(adopt(results[completed])); }
        catch (RuntimeException | Error failure) {
            for (int i = 0; i < completed; ++i) deref(out[i]);
            for (int i = completed; i < results.length; ++i) results[i].close();
            throw failure;
        }
        return out;
    }
    /** One logical owned reference per output, including duplicate results.
     * Inputs are borrowed and all are protected during adapter collection. */
    public static int[] andBatchOwned(int[] a, int[] b) {
        Objects.requireNonNull(a); Objects.requireNonNull(b);
        if (a.length != b.length) throw new IllegalArgumentException("Batch lengths differ");
        int[] out = new int[a.length];
        if (!isNative()) {
            int completed = 0;
            try { for (; completed < out.length; ++completed) out[completed] = ref(and(a[completed], b[completed])); }
            catch (RuntimeException | Error failure) { for (int i = 0; i < completed; ++i) deref(out[i]); throw failure; }
            return out;
        }
        int[] positions = new int[a.length];
        int protectedA = 0, protectedB = 0, consumed = 0;
        NDDNode[] results = null;
        try {
            // Hold all borrowed operands through scalar fallback and collection.
            for (; protectedA < a.length; ++protectedA) ref(a[protectedA]);
            for (; protectedB < b.length; ++protectedB) ref(b[protectedB]);
            int count = 0;
            for (int i = 0; i < out.length; ++i) {
                if (a[i] == 0 || b[i] == 0) out[i] = 0;
                else if (a[i] == 1 || a[i] == b[i]) out[i] = ref(b[i]);
                else if (b[i] == 1) out[i] = ref(a[i]);
                else positions[count++] = i;
            }
            // Sparse port lists frequently contain only 1-2 real operations.
            // Sending terminal cases through JNI/Lace would cost more than it saves.
            if (count < 4) {
                for (int i = 0; i < count; ++i) {
                    int index = positions[i]; out[index] = ref(and(a[index], b[index]));
                }
                return out;
            }
            maybeCollect();
            NDDNode[] left = new NDDNode[count], right = new NDDNode[count];
            for (int i = 0; i < count; ++i) {
                left[i] = entry(a[positions[i]]).root; right[i] = entry(b[positions[i]]).root;
            }
            try { results = engine.applyBatch(ParallelNDD.Operation.AND, left, right); }
            catch (OutOfMemoryError failure) { collect(); results = engine.applyBatch(ParallelNDD.Operation.AND, left, right); }
            for (; consumed < count; ++consumed) out[positions[consumed]] = ref(adopt(results[consumed]));
            return out;
        } catch (RuntimeException | Error failure) {
            for (int value : out) deref(value);
            if (results != null) for (int i = consumed; i < results.length; ++i) results[i].close();
            throw failure;
        } finally {
            for (int i = 0; i < protectedA; ++i) deref(a[i]);
            for (int i = 0; i < protectedB; ++i) deref(b[i]);
        }
    }
    public static int or(int a, int b) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.or(a, b);
        if (a == 1 || b == 1) return 1; if (a == 0 || a == b) return b; if (b == 0) return a;
        return operation(ParallelNDD.Operation.OR, a, b);
    }
    public static int diff(int a, int b) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.diff(a, b);
        if (a == 0 || b == 1 || a == b) return 0; if (b == 0) return a;
        return operation(ParallelNDD.Operation.DIFF, a, b);
    }
    public static int not(int a) { return isNative() ? diff(1, a) : org.ants.jndd.diagram.NDD.not(a); }
    /** Valid retained native roots have stable canonical identity across GC.
     * Adapter IDs are never reused; an ID can only disappear after its root is
     * unreachable. Java retains the historical semantic comparison path. */
    public static boolean equivalent(int a, int b) {
        if (isNative() && Boolean.parseBoolean(System.getProperty("sre.ndd.canonicalCompare", "true"))) {
            if (engine == null || engine.isClosed()) throw new IllegalStateException("Native engine closed");
            entry(a); entry(b); return a == b;
        }
        return a == b || (isFalse(diff(a, b)) && isFalse(diff(b, a)));
    }
    public static int andTo(int a, int b) { int out = ref(and(a, b)); deref(a); return out; }
    public static int orTo(int a, int b) { int out = ref(or(a, b)); deref(a); return out; }
    public static int exist(int a, int... ff) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.exist(a, ff);
        int out = a;
        for (int f : ff) { maybeCollect(a, out); out = adopt(engine.exist(entry(out).root, f)); }
        return out;
    }
    public static int exist(int a, int f) { return exist(a, new int[]{f}); }
    public static double satCount(int a) { return isNative() ? engine.satCount(entry(a).root) : org.ants.jndd.diagram.NDD.satCount(a); }
    public static int encodePrefix(int[] prefix, int field) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.encodePrefix(prefix, field);
        int result = 1;
        for (int bit = 0; bit < prefix.length; ++bit) {
            int literal = prefix[bit] == 0 ? getNotVar(field, bit) : getVar(field, bit);
            result = and(result, literal);
        }
        return result;
    }
    public static int encodePrefixs(ArrayList<int[]> prefixes, int field) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.encodePrefixs(prefixes, field);
        int result = 0;
        for (int[] prefix : prefixes) { int next = ref(encodePrefix(prefix, field)); result = orTo(result, next); deref(next); }
        deref(result);
        return result;
    }
    private static Entry snapshot(int id) {
        Entry e = entry(id);
        if (e.field < 0) {
            ParallelNDD.Snapshot s = engine.snapshot(e.root); e.field = s.field;
            e.targets = new int[s.targets.length]; e.labels = new int[s.targets.length];
            for (int i = 0; i < s.targets.length; ++i) {
                e.targets[i] = adopt(s.targets[i]); e.labels[i] = adopt(s.conditions[i]);
                // Label tokens can escape into HeaderLabels memo keys. Protect
                // their opaque condition roots independently of parent lifetime.
                entry(e.labels[i]).permanent = true;
            }
        }
        return e;
    }
    public static int getField(int id) { return isNative() ? (id <= 1 ? fields.size() : snapshot(id).field) : org.ants.jndd.diagram.NDD.getField(id); }
    public static int getEdgeCount(int id) { return isNative() ? (id <= 1 ? 0 : snapshot(id).targets.length) : org.ants.jndd.diagram.NDD.getEdgeCount(id); }
    public static int getEdgeTarget(int id, int offset) { return isNative() ? snapshot(id).targets[offset] : org.ants.jndd.diagram.NDD.getEdgeTarget(id, offset); }
    public static int getEdgeLabel(int id, int offset) { return isNative() ? snapshot(id).labels[offset] : org.ants.jndd.diagram.NDD.getEdgeLabel(id, offset); }
    public static int addAtField(int field, Map<Integer, Integer> edges) {
        if (!isNative()) return org.ants.jndd.diagram.NDD.addAtField(field, edges);
        int result = 0;
        for (Map.Entry<Integer, Integer> edge : edges.entrySet()) { ref(edge.getKey()); ref(edge.getValue()); }
        try {
            for (Map.Entry<Integer, Integer> edge : edges.entrySet()) {
                int branch = ref(and(edge.getValue(), edge.getKey()));
                try { result = orTo(result, branch); } finally { deref(branch); }
            }
            int out = result; deref(result); result = 0; return out;
        } finally {
            deref(result);
            for (Map.Entry<Integer, Integer> edge : edges.entrySet()) { deref(edge.getKey()); deref(edge.getValue()); }
        }
    }
    public static boolean isUniverseEdgeLabel(int field, int label) {
        return isNative() ? label == 1 : org.ants.jndd.diagram.NDD.isUniverseEdgeLabel(field, label);
    }
    public static int minimumFailures(int field, int label) {
        LabelMode mode = getFieldLabelMode(field);
        if (isNative()) return engine.minLabelCost(entry(label).root, field, mode == LabelMode.ZDD);
        if (mode == LabelMode.COMPLEMENTED_BDD) return getBCDDEngine().toOne(label);
        if (mode == LabelMode.BDD) return getBDDEngine().toOne(label);
        return zddCost(label, new HashMap<>());
    }
    private static int zddCost(int a, Map<Integer, Integer> memo) {
        if (a == 0) return Integer.MAX_VALUE / 4; if (a == 1) return 0;
        Integer old = memo.get(a); if (old != null) return old;
        int low = zddCost(getZDDEngine().getLow(a), memo), high = zddCost(getZDDEngine().getHigh(a), memo);
        int cost = Math.min(low, high + 1); memo.put(a, cost); return cost;
    }
    public static byte[][] labelCubes(int field, int label, int width) {
        if (isNative()) return engine.labelCubes(entry(label).root, field, 65536);
        ArrayList<byte[]> cubes = new ArrayList<>(); byte[] bits = new byte[width]; Arrays.fill(bits, (byte)-1);
        labelCubesRec(field, label, bits, cubes); return cubes.toArray(new byte[0][]);
    }
    private static void labelCubesRec(int field, int label, byte[] bits, List<byte[]> cubes) {
        if (label == 0) return;
        if (isUniverseEdgeLabel(field, label)) { cubes.add(bits.clone()); return; }
        LabelMode mode = getFieldLabelMode(field); int var, low, high;
        if (mode == LabelMode.COMPLEMENTED_BDD) {
            ComplementedBDD b = getBCDDEngine(); var = b.getVar(label); low = b.getLow(label); high = b.getHigh(label);
        } else if (mode == LabelMode.BDD) {
            BDD b = getBDDEngine(); var = b.getVar(label); low = b.getLow(label); high = b.getHigh(label);
        } else throw new UnsupportedOperationException("Header cube adapter currently supports BDD/BCDD");
        int bit = -1;
        for (int i = 0; i < bits.length; ++i) if (getBackendVariableId(field, i) == var) { bit = i; break; }
        if (bit < 0) throw new IllegalStateException("Label variable outside header field");
        bits[bit] = 0; labelCubesRec(field, low, bits, cubes);
        bits[bit] = 1; labelCubesRec(field, high, bits, cubes); bits[bit] = -1;
    }
    public static BDD getBDDEngine() { return isNative() ? null : org.ants.jndd.diagram.NDD.getBDDEngine(); }
    public static ComplementedBDD getBCDDEngine() { return isNative() ? null : org.ants.jndd.diagram.NDD.getBCDDEngine(); }
    public static ZDD getZDDEngine() { return isNative() ? null : org.ants.jndd.diagram.NDD.getZDDEngine(); }
    public static long getTotalCreated() { return isNative() ? engine.stats().nodesCreated : org.ants.jndd.diagram.NDD.getTotalCreated(); }
    public static long getNodeCount() { return isNative() ? engine.stats().nodesLive : org.ants.jndd.diagram.NDD.getNodeCount(); }
    public static long getLabelNodeCount() { return isNative() ? engine.stats().labelsLive : org.ants.jndd.diagram.NDD.getLabelNodeCount(); }
    public static long getLabelTotalCreated() { return isNative() ? engine.stats().labelsCreated : org.ants.jndd.diagram.NDD.getLabelTotalCreated(); }
    public static long getLabelNodeCount(LabelMode mode) {
        return isNative() ? (modes.contains(mode) ? getLabelNodeCount() : 0) : org.ants.jndd.diagram.NDD.getLabelNodeCount(mode);
    }
    public static long getLabelTotalCreated(LabelMode mode) {
        return isNative() ? (modes.contains(mode) ? getLabelTotalCreated() : 0) : org.ants.jndd.diagram.NDD.getLabelTotalCreated(mode);
    }
    public static void gc() { if (isNative()) collect(); else org.ants.jndd.diagram.NDD.gc(); }
    public static void gcLabelEngine() { if (isNative()) collect(); else org.ants.jndd.diagram.NDD.gcLabelEngine(); }
    public static void gcLabelEngine(LabelMode mode) { if (isNative()) collect(); else org.ants.jndd.diagram.NDD.gcLabelEngine(mode); }
    public static void printNativeStats() {
        if (!isNative()) { System.out.println("ndd_engine:java"); return; }
        ParallelNDD.Stats s = engine.stats();
        System.out.println("native_tasks:" + s.tasks); System.out.println("native_stolen_tasks:" + s.stolenTasks);
        System.out.println("native_collections:" + s.collections); System.out.println("adapter_collections:" + adapterCollections);
        System.out.println("native_label_collections:" + s.labelCollections);
        System.out.println("native_cache_hits:" + s.cacheHits); System.out.println("native_cache_misses:" + s.cacheMisses);
        System.out.println("native_bytes:" + s.nativeBytes); System.out.println("native_peak_bytes:" + s.peakNativeBytes);
        System.out.println("native_external_runs:" + s.externalRuns);
        System.out.println("native_merge_seconds:" + s.mergeNanos / 1e9);
        if (PROFILE) {
            System.out.println("profile_adapter_adopt_calls:" + adoptCalls);
            System.out.println("profile_adapter_adopt_seconds:" + adoptNs / 1e9);
            System.out.println("profile_adapter_collect_seconds:" + collectNs / 1e9);
            System.out.println("profile_adapter_apply_calls:" + applyCalls);
            System.out.println("profile_adapter_apply_seconds:" + applyNs / 1e9);
            System.out.println("profile_adapter_slots:" + entries.size());
            System.out.println("profile_adapter_live:" + ids.size());
        }
    }
    public static void closeNative() { if (engine != null) { engine.close(); engine = null; } }

    private static final Map<Integer, Long> nodeDigests = new HashMap<>();
    private static final Map<Long, Long> labelDigests = new HashMap<>();
    public static void clearFingerprints() { nodeDigests.clear(); labelDigests.clear(); }
    private static long mix(long x) {
        x ^= x >>> 30; x *= 0xbf58476d1ce4e5b9L;
        x ^= x >>> 27; x *= 0x94d049bb133111ebL; return x ^ (x >>> 31);
    }
    public static long fingerprint(int id) {
        if (isNative()) return engine.fingerprint(entry(id).root);
        if (id <= 1) return mix(0xf00 + id);
        Long old = nodeDigests.get(id); if (old != null) return old;
        long sum = 0; int field = getField(id), count = getEdgeCount(id);
        for (int edge = 0; edge < count; ++edge) {
            long l = fingerprintLabel(field, getEdgeLabel(id, edge));
            long t = fingerprint(getEdgeTarget(id, edge)); sum += mix(l ^ Long.rotateLeft(t, 7));
        }
        long result = mix(sum ^ ((long)field << 32) ^ count); nodeDigests.put(id, result); return result;
    }
    private static long fingerprintLabel(int field, int label) {
        if (label <= 1) return mix(0xf00 + label);
        LabelMode mode = getFieldLabelMode(field);
        long key = ((long)mode.ordinal() << 32) | (label & 0xffffffffL);
        Long old = labelDigests.get(key); if (old != null) return old;
        int maxWidth = 0;
        for (int i = 0; i < fields.size(); ++i) if (modes.get(i) == mode) maxWidth = Math.max(maxWidth, fields.get(i).width);
        int var, low, high;
        if (mode == LabelMode.COMPLEMENTED_BDD) {
            ComplementedBDD b = getBCDDEngine(); var = b.getVar(label); low = b.getLow(label); high = b.getHigh(label);
        } else if (mode == LabelMode.BDD) {
            BDD b = getBDDEngine(); var = b.getVar(label); low = b.getLow(label); high = b.getHigh(label);
        } else throw new UnsupportedOperationException("Fingerprint supports BDD/BCDD labels");
        long l = fingerprintLabel(field, low), h = fingerprintLabel(field, high);
        long result = mix(var ^ Long.rotateLeft(l, 13) ^ Long.rotateLeft(h, 37) ^ 0xabcdef1234567890L);
        labelDigests.put(key, result); return result;
    }
}
