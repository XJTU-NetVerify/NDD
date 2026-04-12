package org.ants.jndd.bdd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal complemented-edge BDD manager for NDD edge labels.
 *
 * <p>This implementation is intentionally small: it supports the subset of
 * Boolean-DD operations needed by the current NDD-SoA code paths and the
 * N-Queens benchmark. Handles use one low-order complement bit for internal
 * nodes, while {@code 0} and {@code 1} remain the constant false/true leaves.
 */
public final class ComplementedBDD {
    private static final int FALSE = 0;
    private static final int TRUE = 1;

    private final ArrayList<Node> nodes = new ArrayList<>();
    private final Map<NodeKey, Integer> uniqueTable = new HashMap<>();
    private final Map<ApplyKey, Integer> andCache = new HashMap<>();
    private final Map<ApplyKey, Integer> orCache = new HashMap<>();
    private final Map<Integer, Double> satCache = new HashMap<>();

    private int numVars;

    public ComplementedBDD(int nodeTableSize, int cacheSize) {
        nodes.add(null); // reserve node id 0 for the constant leaves
        numVars = 0;
    }

    public int createVar() {
        int var = mk(numVars, FALSE, TRUE);
        numVars++;
        satCache.clear();
        return var;
    }

    public int ref(int handle) {
        return handle;
    }

    public void deref(int handle) {
        // This implementation keeps all created nodes alive for the duration of
        // the benchmark process, so ref-counting is intentionally a no-op.
    }

    public int getNodeCount() {
        return nodes.size() - 1;
    }

    public int getTotalCreated() {
        return nodes.size() - 1;
    }

    public int not(int handle) {
        return handle ^ 1;
    }

    public int and(int a, int b) {
        return apply(true, a, b);
    }

    public int andTo(int a, int b) {
        return and(a, b);
    }

    public int or(int a, int b) {
        return apply(false, a, b);
    }

    public int orTo(int a, int b) {
        return or(a, b);
    }

    public int imp(int a, int b) {
        return or(not(a), b);
    }

    public int mk(int var, int low, int high) {
        if (low == high) {
            return low;
        }

        boolean complementResult = false;
        if (isInternalComplemented(high)) {
            complementResult = true;
            low = not(low);
            high = not(high);
            if (low == high) {
                return complementResult ? not(low) : low;
            }
        }

        NodeKey key = new NodeKey(var, low, high);
        Integer existing = uniqueTable.get(key);
        int regularHandle;
        if (existing != null) {
            regularHandle = encodeRegular(existing.intValue());
        } else {
            int nodeId = nodes.size();
            nodes.add(new Node(var, low, high));
            uniqueTable.put(key, Integer.valueOf(nodeId));
            regularHandle = encodeRegular(nodeId);
        }
        return complementResult ? not(regularHandle) : regularHandle;
    }

    public int getVar(int handle) {
        if (isConstant(handle)) {
            return numVars;
        }
        return nodeFor(handle).var;
    }

    public int getLow(int handle) {
        Node node = nodeFor(handle);
        return isInternalComplemented(handle) ? not(node.low) : node.low;
    }

    public int getHigh(int handle) {
        Node node = nodeFor(handle);
        return isInternalComplemented(handle) ? not(node.high) : node.high;
    }

    public double satCount(int handle) {
        if (handle == FALSE) {
            return 0.0;
        }
        return Math.pow(2.0, getVar(handle)) * satCountRec(handle);
    }

    private double satCountRec(int handle) {
        if (handle == FALSE) {
            return 0.0;
        }
        if (handle == TRUE) {
            return 1.0;
        }

        Double cached = satCache.get(Integer.valueOf(handle));
        if (cached != null) {
            return cached.doubleValue();
        }

        int var = getVar(handle);
        int low = getLow(handle);
        int high = getHigh(handle);
        double lowScale = Math.pow(2.0, getVar(low) - var - 1);
        double highScale = Math.pow(2.0, getVar(high) - var - 1);
        double count = satCountRec(low) * lowScale + satCountRec(high) * highScale;
        satCache.put(Integer.valueOf(handle), Double.valueOf(count));
        return count;
    }

    private int apply(boolean isAnd, int a, int b) {
        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        if (isAnd) {
            if (a == FALSE || b == FALSE) return FALSE;
            if (a == TRUE) return b;
            if (b == TRUE) return a;
            if (a == b) return a;
            if (a == not(b)) return FALSE;
        } else {
            if (a == TRUE || b == TRUE) return TRUE;
            if (a == FALSE) return b;
            if (b == FALSE) return a;
            if (a == b) return a;
            if (a == not(b)) return TRUE;
        }

        Map<ApplyKey, Integer> cache = isAnd ? andCache : orCache;
        ApplyKey key = new ApplyKey(isAnd, a, b);
        Integer cached = cache.get(key);
        if (cached != null) {
            return cached.intValue();
        }

        int top = Math.min(getVar(a), getVar(b));
        int low = apply(isAnd, cofactor(a, top, false), cofactor(b, top, false));
        int high = apply(isAnd, cofactor(a, top, true), cofactor(b, top, true));
        int result = mk(top, low, high);
        cache.put(key, Integer.valueOf(result));
        return result;
    }

    private int cofactor(int handle, int var, boolean high) {
        if (isConstant(handle) || getVar(handle) > var) {
            return handle;
        }
        return high ? getHigh(handle) : getLow(handle);
    }

    private boolean isConstant(int handle) {
        return regularNodeId(handle) == 0;
    }

    private boolean isInternalComplemented(int handle) {
        return regularNodeId(handle) != 0 && (handle & 1) != 0;
    }

    private int regularNodeId(int handle) {
        return handle >>> 1;
    }

    private int encodeRegular(int nodeId) {
        return nodeId << 1;
    }

    private Node nodeFor(int handle) {
        int nodeId = regularNodeId(handle);
        if (nodeId <= 0 || nodeId >= nodes.size()) {
            throw new IllegalArgumentException("Handle does not reference an internal BCDD node: " + handle);
        }
        return nodes.get(nodeId);
    }

    private static final class Node {
        final int var;
        final int low;
        final int high;

        Node(int var, int low, int high) {
            this.var = var;
            this.low = low;
            this.high = high;
        }
    }

    private static final class NodeKey {
        final int var;
        final int low;
        final int high;

        NodeKey(int var, int low, int high) {
            this.var = var;
            this.low = low;
            this.high = high;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof NodeKey)) return false;
            NodeKey other = (NodeKey) obj;
            return var == other.var && low == other.low && high == other.high;
        }

        @Override
        public int hashCode() {
            int result = var;
            result = 31 * result + low;
            result = 31 * result + high;
            return result;
        }
    }

    private static final class ApplyKey {
        final boolean isAnd;
        final int a;
        final int b;

        ApplyKey(boolean isAnd, int a, int b) {
            this.isAnd = isAnd;
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ApplyKey)) return false;
            ApplyKey other = (ApplyKey) obj;
            return isAnd == other.isAnd && a == other.a && b == other.b;
        }

        @Override
        public int hashCode() {
            int result = isAnd ? 1 : 0;
            result = 31 * result + a;
            result = 31 * result + b;
            return result;
        }
    }
}
