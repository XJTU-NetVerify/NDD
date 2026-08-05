package org.ants.jndd.bdd;

import java.util.Arrays;

/**
 * Primitive complemented-edge reduced ordered BDD manager.
 *
 * <p>Handles reserve bit zero as a complement flag. Internal node {@code n} has regular handle
 * {@code n << 1}; terminals remain the conventional handles 0 and 1. The unique-table invariant
 * requires every internal high child to be regular. Consequently negation is constant time and a
 * function and its complement share every internal node.
 *
 * <p>The manager is deliberately self-contained for use as an NDD label backend: nodes are stored
 * in structure-of-arrays form, the unique and computed tables are primitive arrays, externally
 * referenced roots drive mark/sweep collection, and tables grow without collecting temporary
 * nodes in the middle of a recursive apply.
 */
public final class ComplementedBDD {
    private static final int FALSE = 0;
    private static final int TRUE = 1;
    private static final int INVALID_VAR = -1;
    private static final int PERMANENT_REF = Integer.MAX_VALUE;
    private static final byte CACHE_EMPTY = -1;
    private static final byte OP_AND = 0;
    private static final byte OP_OR = 1;
    private static final int MIN_TABLE_SIZE = 256;
    private static final int MAX_NODE_ID = (Integer.MAX_VALUE >>> 1) - 1;

    private int[] vars;
    private int[] lows;
    private int[] highs;
    private int[] next;
    private int[] refs;
    private byte[] marks;
    private int[] uniqueBuckets;
    private int uniqueMask;
    private int freeHead;
    private int freeCount;
    private int activeCount;
    private long totalCreated;
    private int numVars;
    private int gcCount;
    private int growCount;

    private final int requestedCacheSize;
    private int[] cacheA;
    private int[] cacheB;
    private int[] cacheResult;
    private byte[] cacheOp;
    private int cacheMask;
    private long cacheHits;
    private long cacheMisses;

    private int[] satKeys;
    private double[] satValues;
    private int satMask;
    private int[] minKeys;
    private int[] minValues;
    private long satHits;
    private long satMisses;

    private int[] traversalStack;

    public ComplementedBDD(int nodeTableSize, int cacheSize) {
        int capacity = Math.max(MIN_TABLE_SIZE, nodeTableSize + 1);
        if (capacity - 1 > MAX_NODE_ID) {
            throw new IllegalArgumentException("BCDD node table is too large: " + nodeTableSize);
        }
        requestedCacheSize = Math.max(64, cacheSize);
        allocateNodeArrays(capacity);
        initializeFreeList(1);
        allocateUniqueTable(capacity);
        allocateCaches(requestedCacheSize);
        traversalStack = new int[Math.min(capacity, 4096)];
    }

    /** Create and permanently protect the next variable. */
    public int createVar() {
        maybeCollect();
        int variable = uniqueMk(numVars, FALSE, TRUE);
        refs[regularNodeId(variable)] = PERMANENT_REF;
        numVars++;
        clearSatCache();
        return variable;
    }

    public int numberOfVariables() {
        return numVars;
    }

    public int ref(int handle) {
        int node = checkedNodeIdOrZero(handle);
        if (node != 0 && refs[node] != PERMANENT_REF) {
            refs[node]++;
        }
        return handle;
    }

    public void deref(int handle) {
        int node = checkedNodeIdOrZero(handle);
        if (node != 0 && refs[node] != PERMANENT_REF && refs[node] > 0) {
            refs[node]--;
        }
    }

    public int getNodeCount() {
        return activeCount;
    }

    public long getTotalCreated() {
        return totalCreated;
    }

    public int getTableCapacity() {
        return vars.length - 1;
    }

    public int getGcCount() {
        return gcCount;
    }

    public int getGrowCount() {
        return growCount;
    }

    public long getCacheHits() {
        return cacheHits;
    }

    public long getCacheMisses() {
        return cacheMisses;
    }

    public long getSatCacheHits() {
        return satHits;
    }

    public long getSatCacheMisses() {
        return satMisses;
    }

    public int nodeCount(int root) {
        int node = checkedNodeIdOrZero(root);
        if (node == 0) return 0;
        Arrays.fill(marks, (byte) 0);
        int count = markFrom(node);
        Arrays.fill(marks, (byte) 0);
        return count;
    }

    /** Collect all nodes unreachable from externally referenced or permanent roots. */
    public int gc() {
        if (activeCount == 0) return 0;
        Arrays.fill(marks, (byte) 0);
        for (int node = 1; node < vars.length; node++) {
            if (vars[node] != INVALID_VAR && refs[node] != 0 && marks[node] == 0) {
                markFrom(node);
            }
        }

        int freed = 0;
        freeHead = 0;
        freeCount = 0;
        Arrays.fill(uniqueBuckets, 0);
        for (int node = vars.length - 1; node >= 1; node--) {
            if (vars[node] == INVALID_VAR || marks[node] == 0) {
                if (vars[node] != INVALID_VAR) {
                    vars[node] = INVALID_VAR;
                    lows[node] = 0;
                    highs[node] = 0;
                    refs[node] = 0;
                    activeCount--;
                    freed++;
                }
                next[node] = freeHead;
                freeHead = node;
                freeCount++;
            } else {
                int bucket = uniqueHash(vars[node], lows[node], highs[node]) & uniqueMask;
                next[node] = uniqueBuckets[bucket];
                uniqueBuckets[bucket] = node;
                marks[node] = 0;
            }
        }
        gcCount++;
        clearComputedCaches();
        return freed;
    }

    public int getNodeId(int handle) {
        return checkedNodeIdOrZero(handle);
    }

    public boolean isComplemented(int handle) {
        return isInternalComplemented(handle);
    }

    public int not(int handle) {
        checkedNodeIdOrZero(handle);
        return handle ^ 1;
    }

    public int and(int a, int b) {
        validateHandle(a);
        validateHandle(b);
        maybeCollect();
        return apply(OP_AND, a, b);
    }

    public int andTo(int ownedLeft, int right) {
        int result = ref(and(ownedLeft, right));
        deref(ownedLeft);
        return result;
    }

    public int or(int a, int b) {
        validateHandle(a);
        validateHandle(b);
        maybeCollect();
        return apply(OP_OR, a, b);
    }

    public int orTo(int ownedLeft, int right) {
        int result = ref(or(ownedLeft, right));
        deref(ownedLeft);
        return result;
    }

    public int imp(int a, int b) {
        validateHandle(a);
        validateHandle(b);
        maybeCollect();
        return apply(OP_OR, a ^ 1, b);
    }

    public int mk(int var, int low, int high) {
        if (var < 0 || var >= numVars) {
            throw new IllegalArgumentException("invalid BCDD variable: " + var);
        }
        validateHandle(low);
        validateHandle(high);
        maybeCollect();
        return uniqueMk(var, low, high);
    }

    public int getVar(int handle) {
        int node = checkedNodeIdOrZero(handle);
        return node == 0 ? numVars : vars[node];
    }

    public int getLow(int handle) {
        int node = checkedInternalNode(handle);
        int child = lows[node];
        return isInternalComplemented(handle) ? child ^ 1 : child;
    }

    public int getHigh(int handle) {
        int node = checkedInternalNode(handle);
        int child = highs[node];
        return isInternalComplemented(handle) ? child ^ 1 : child;
    }

    public double satCount(int handle) {
        validateHandle(handle);
        if (handle == FALSE) return 0.0;
        return Math.scalb(satCountRec(handle), getVar(handle));
    }

    /** Minimum number of low (zero-valued) variables on a path to TRUE. */
    public int toOne(int handle) {
        validateHandle(handle);
        return minZeroRec(handle);
    }

    private int minZeroRec(int handle) {
        if (handle == FALSE) return Integer.MAX_VALUE / 4;
        if (handle == TRUE) return 0;
        int slot = mix(handle ^ 0x5bd1e995) & satMask;
        if (minKeys[slot] == handle) return minValues[slot];
        int low = minZeroRec(getLowUnchecked(handle));
        if (low < Integer.MAX_VALUE / 4) low++;
        int result = Math.min(low, minZeroRec(getHighUnchecked(handle)));
        minKeys[slot] = handle;
        minValues[slot] = result;
        return result;
    }

    private double satCountRec(int handle) {
        if (handle == FALSE) return 0.0;
        if (handle == TRUE) return 1.0;

        int slot = mix(handle) & satMask;
        if (satKeys[slot] == handle) {
            satHits++;
            return satValues[slot];
        }
        satMisses++;

        int var = getVarUnchecked(handle);
        double count;
        if (isInternalComplemented(handle)) {
            int regular = handle ^ 1;
            count = Math.scalb(1.0, numVars - var) - satCountRec(regular);
        } else {
            int low = lows[regularNodeId(handle)];
            int high = highs[regularNodeId(handle)];
            double lowCount = Math.scalb(satCountRec(low), getVarUnchecked(low) - var - 1);
            double highCount = Math.scalb(satCountRec(high), getVarUnchecked(high) - var - 1);
            count = lowCount + highCount;
        }
        satKeys[slot] = handle;
        satValues[slot] = count;
        return count;
    }

    private int apply(byte operation, int a, int b) {
        if (a > b) {
            int swap = a;
            a = b;
            b = swap;
        }

        if (operation == OP_AND) {
            if (a == FALSE || b == FALSE) return FALSE;
            if (a == TRUE) return b;
            if (b == TRUE || a == b) return a;
            if (a == (b ^ 1)) return FALSE;
            if (isInternalComplemented(a) && isInternalComplemented(b)) {
                return apply(OP_OR, a ^ 1, b ^ 1) ^ 1;
            }
        } else {
            if (a == TRUE || b == TRUE) return TRUE;
            if (a == FALSE) return b;
            if (b == FALSE || a == b) return a;
            if (a == (b ^ 1)) return TRUE;
            if (isInternalComplemented(a) && isInternalComplemented(b)) {
                return apply(OP_AND, a ^ 1, b ^ 1) ^ 1;
            }
        }

        int cacheSlot = applyHash(operation, a, b) & cacheMask;
        if (cacheOp[cacheSlot] == operation && cacheA[cacheSlot] == a && cacheB[cacheSlot] == b) {
            cacheHits++;
            return cacheResult[cacheSlot];
        }
        cacheMisses++;

        int aVar = getVarUnchecked(a);
        int bVar = getVarUnchecked(b);
        int top = Math.min(aVar, bVar);
        int aLow = aVar == top ? getLowUnchecked(a) : a;
        int aHigh = aVar == top ? getHighUnchecked(a) : a;
        int bLow = bVar == top ? getLowUnchecked(b) : b;
        int bHigh = bVar == top ? getHighUnchecked(b) : b;
        int low = apply(operation, aLow, bLow);
        int high = apply(operation, aHigh, bHigh);
        int result = uniqueMk(top, low, high);

        cacheOp[cacheSlot] = operation;
        cacheA[cacheSlot] = a;
        cacheB[cacheSlot] = b;
        cacheResult[cacheSlot] = result;
        return result;
    }

    private int uniqueMk(int var, int low, int high) {
        if (low == high) return low;

        boolean complementResult = isInternalComplemented(high);
        if (complementResult) {
            low ^= 1;
            high ^= 1;
        }

        int bucket = uniqueHash(var, low, high) & uniqueMask;
        for (int node = uniqueBuckets[bucket]; node != 0; node = next[node]) {
            if (vars[node] == var && lows[node] == low && highs[node] == high) {
                int handle = node << 1;
                return complementResult ? handle ^ 1 : handle;
            }
        }

        if (freeHead == 0) growNodeTable();
        // Growth rebuilds and may change the bucket mask.
        bucket = uniqueHash(var, low, high) & uniqueMask;
        int node = freeHead;
        freeHead = next[node];
        freeCount--;
        vars[node] = var;
        lows[node] = low;
        highs[node] = high;
        refs[node] = 0;
        next[node] = uniqueBuckets[bucket];
        uniqueBuckets[bucket] = node;
        activeCount++;
        totalCreated++;

        int handle = node << 1;
        return complementResult ? handle ^ 1 : handle;
    }

    private void maybeCollect() {
        if (freeCount > Math.max(64, vars.length / 20)) return;
        gc();
        if (freeCount <= Math.max(64, vars.length / 20)) growNodeTable();
    }

    private void growNodeTable() {
        int oldCapacity = vars.length;
        long candidate = Math.max((long) oldCapacity + MIN_TABLE_SIZE,
                oldCapacity + (oldCapacity >>> 1));
        if (candidate - 1 > MAX_NODE_ID) {
            throw new IllegalStateException("BCDD node table reached handle capacity");
        }
        int newCapacity = (int) candidate;
        vars = Arrays.copyOf(vars, newCapacity);
        lows = Arrays.copyOf(lows, newCapacity);
        highs = Arrays.copyOf(highs, newCapacity);
        next = Arrays.copyOf(next, newCapacity);
        refs = Arrays.copyOf(refs, newCapacity);
        marks = Arrays.copyOf(marks, newCapacity);
        Arrays.fill(vars, oldCapacity, newCapacity, INVALID_VAR);
        for (int node = newCapacity - 1; node >= oldCapacity; node--) {
            next[node] = freeHead;
            freeHead = node;
            freeCount++;
        }
        allocateUniqueTable(newCapacity);
        rebuildUniqueTable();
        growCount++;
    }

    private void allocateNodeArrays(int capacity) {
        vars = new int[capacity];
        lows = new int[capacity];
        highs = new int[capacity];
        next = new int[capacity];
        refs = new int[capacity];
        marks = new byte[capacity];
        Arrays.fill(vars, INVALID_VAR);
    }

    private void initializeFreeList(int start) {
        freeHead = 0;
        freeCount = 0;
        for (int node = vars.length - 1; node >= start; node--) {
            next[node] = freeHead;
            freeHead = node;
            freeCount++;
        }
    }

    private void allocateUniqueTable(int nodeCapacity) {
        int bucketCount = powerOfTwoAtLeast(nodeCapacity);
        uniqueBuckets = new int[bucketCount];
        uniqueMask = bucketCount - 1;
    }

    private void rebuildUniqueTable() {
        Arrays.fill(uniqueBuckets, 0);
        for (int node = 1; node < vars.length; node++) {
            if (vars[node] == INVALID_VAR) continue;
            int bucket = uniqueHash(vars[node], lows[node], highs[node]) & uniqueMask;
            next[node] = uniqueBuckets[bucket];
            uniqueBuckets[bucket] = node;
        }
    }

    private void allocateCaches(int cacheSize) {
        int applySize = powerOfTwoAtLeast(cacheSize);
        cacheA = new int[applySize];
        cacheB = new int[applySize];
        cacheResult = new int[applySize];
        cacheOp = new byte[applySize];
        cacheMask = applySize - 1;

        int satSize = powerOfTwoAtLeast(Math.max(64, cacheSize >>> 2));
        satKeys = new int[satSize];
        satValues = new double[satSize];
        minKeys = new int[satSize];
        minValues = new int[satSize];
        satMask = satSize - 1;
        clearComputedCaches();
    }

    private void clearComputedCaches() {
        Arrays.fill(cacheOp, CACHE_EMPTY);
        clearSatCache();
    }

    private void clearSatCache() {
        Arrays.fill(satKeys, -1);
        Arrays.fill(minKeys, -1);
    }

    private int markFrom(int startNode) {
        int top = 0;
        ensureTraversalCapacity(1);
        traversalStack[top++] = startNode;
        marks[startNode] = 1;
        int count = 0;
        while (top != 0) {
            int node = traversalStack[--top];
            count++;
            int lowNode = regularNodeId(lows[node]);
            int highNode = regularNodeId(highs[node]);
            if (lowNode != 0 && marks[lowNode] == 0) {
                ensureTraversalCapacity(top + 1);
                marks[lowNode] = 1;
                traversalStack[top++] = lowNode;
            }
            if (highNode != 0 && marks[highNode] == 0) {
                ensureTraversalCapacity(top + 1);
                marks[highNode] = 1;
                traversalStack[top++] = highNode;
            }
        }
        return count;
    }

    private void ensureTraversalCapacity(int required) {
        if (required <= traversalStack.length) return;
        traversalStack = Arrays.copyOf(traversalStack,
                Math.max(required, traversalStack.length + (traversalStack.length >>> 1) + 1));
    }

    private int checkedInternalNode(int handle) {
        int node = checkedNodeIdOrZero(handle);
        if (node == 0) throw new IllegalArgumentException("terminal has no BCDD children");
        return node;
    }

    private int checkedNodeIdOrZero(int handle) {
        if (handle < 0) throw new IllegalArgumentException("negative BCDD handle: " + handle);
        int node = regularNodeId(handle);
        if (node != 0 && (node >= vars.length || vars[node] == INVALID_VAR)) {
            throw new IllegalArgumentException("stale or invalid BCDD handle: " + handle);
        }
        return node;
    }

    private void validateHandle(int handle) {
        checkedNodeIdOrZero(handle);
    }

    private int getVarUnchecked(int handle) {
        int node = regularNodeId(handle);
        return node == 0 ? numVars : vars[node];
    }

    private int getLowUnchecked(int handle) {
        int child = lows[regularNodeId(handle)];
        return isInternalComplemented(handle) ? child ^ 1 : child;
    }

    private int getHighUnchecked(int handle) {
        int child = highs[regularNodeId(handle)];
        return isInternalComplemented(handle) ? child ^ 1 : child;
    }

    private static boolean isInternalComplemented(int handle) {
        return regularNodeId(handle) != 0 && (handle & 1) != 0;
    }

    private static int regularNodeId(int handle) {
        return handle >>> 1;
    }

    private static int uniqueHash(int var, int low, int high) {
        int hash = mix(var * 0x9e3779b9 ^ low);
        return mix(hash ^ Integer.rotateLeft(high, 13));
    }

    private static int applyHash(byte op, int a, int b) {
        return mix((op + 1) * 0x7f4a7c15 ^ a ^ Integer.rotateLeft(b, 16));
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }

    private static int powerOfTwoAtLeast(int requested) {
        int highest = Integer.highestOneBit(Math.max(2, requested - 1));
        if (highest >= (1 << 30)) return 1 << 30;
        return highest << 1;
    }
}
