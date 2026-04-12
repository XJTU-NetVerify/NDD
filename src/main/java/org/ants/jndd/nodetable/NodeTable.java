/**
 * Node table of NDD (Node Decision Diagram).
 * Manages node storage, unique table lookup, and reference counting.
 *
 * @author Zechun Li & Yichi Zhang - XJTU ANTS NetVerify Lab
 * @version 1.0
 */
package org.ants.jndd.nodetable;

import jdd.bdd.BDD;
import org.ants.jndd.diagram.NDD;

import java.util.ArrayList;
import java.util.Arrays;

public class NodeTable {
    /**
     * The total number of nodes ever created.
     */
    private long totalCreated;

    /**
     * The current number of stored nodes.
     */
    private long currentSize;

    /**
     * The max size of the node table before gc or grow.
     */
    private long nddTableSize;

    /**
     * Per-field unique tables for node deduplication.
     */
    private final ArrayList<UniqueTable> nodeTable;

    /**
     * The internal BDD engine for edge labels.
     */
    private final BDD bddEngine;

    /**
     * If the number of free nodes is less than this threshold after garbage collection, the node table will grow.
     */
    private final double QUICK_GROW_THRESHOLD = 0.1;

    /**
     * Capacity of node arrays (nodeField, nodeEdgeBlock, etc.).
     */
    private int nodeCapacity;

    /**
     * Capacity of edge arrays (edgeTarget, edgeLabel).
     */
    private int edgeCapacity;

    /**
     * Capacity of block metadata arrays.
     */
    private int blockCapacity;

    /**
     * Next node id to allocate (0=FALSE, 1=TRUE, 2+ = internal nodes).
     */
    private int nextNodeId;

    /**
     * Head of the free-node list, recycled at safe points only.
     */
    private int freeNodeHead;

    /**
     * Head of the retired-node list awaiting safe-point recycling.
     */
    private int retiredNodeHead;

    /**
     * Next free index in edge arrays.
     */
    private int edgeTop;

    /**
     * Next edge-block id to allocate (0 reserved as invalid).
     */
    private int nextBlockId = 1;

    /**
     * Head of the free-block list, recycled at safe points only.
     */
    private int freeBlockHead;

    /**
     * Head of the retired-block list awaiting safe-point recycling.
     */
    private int retiredBlockHead;

    /**
     * Number of edges owned by currently live nodes.
     */
    private long liveEdgeCount;

    /**
     * Field index for each node (index 0/1 reserved for terminals).
     * Public for direct array access in hot paths (NDD operations).
     */
    public int[] nodeField;

    /**
     * Stable edge-block id for each node.
     */
    public int[] nodeEdgeBlock;

    /**
     * Number of edges for each node.
     */
    public int[] nodeEdgeCount;

    /**
     * Next node in the same unique-table bucket (linked list).
     */
    int[] nodeNext;

    /**
     * Hash value for each node (for unique table lookup).
     */
    int[] nodeHash;

    /**
     * Reference count of each node.
     */
    public int[] refCount;

    /**
     * Target node id for each edge.
     */
    public int[] edgeTarget;

    /**
     * BDD handle for each edge label.
     */
    public int[] edgeLabel;

    /**
     * Whether each node slot is alive (not freed by gc).
     * Separated from nodeField so that freed nodes' field/edge data remains readable
     * by recursive NDD operations that hold stale references on the stack.
     */
    private boolean[] nodeAlive;

    /**
     * Physical start index in edge arrays for each block id.
     */
    private int[] blockStart;

    /**
     * Whether a block id is alive.
     */
    private boolean[] blockAlive;

    /**
     * Next block in the retired/free block list.
     */
    private int[] blockNext;

    /**
     * Construct the node table.
     *
     * @param nddTableSize The max size of the ndd node table.
     * @param bddTableSize The BDD node table size.
     * @param bddCacheSize The BDD cache size.
     */
    public NodeTable(long nddTableSize, int bddTableSize, int bddCacheSize) {
        this.totalCreated = 0L;
        this.currentSize = 0L;
        this.nddTableSize = nddTableSize;
        this.nodeTable = new ArrayList<>();
        this.bddEngine = new BDD(bddTableSize, bddCacheSize);

        int initialNodeCap = (int) Math.max(4, Math.min(4096, nddTableSize + 2));
        int initialEdgeCap = Math.max(16, initialNodeCap * 4);

        this.nodeCapacity = initialNodeCap;
        this.edgeCapacity = initialEdgeCap;
        this.blockCapacity = initialNodeCap;
        this.nextNodeId = 2;
        this.freeNodeHead = 0;
        this.retiredNodeHead = 0;
        this.edgeTop = 0;
        this.freeBlockHead = 0;
        this.retiredBlockHead = 0;
        this.liveEdgeCount = 0L;

        this.nodeField = new int[nodeCapacity];
        this.nodeEdgeBlock = new int[nodeCapacity];
        this.nodeEdgeCount = new int[nodeCapacity];
        this.nodeNext = new int[nodeCapacity];
        this.nodeHash = new int[nodeCapacity];
        this.refCount = new int[nodeCapacity];
        this.edgeTarget = new int[edgeCapacity];
        this.edgeLabel = new int[edgeCapacity];
        this.nodeAlive = new boolean[nodeCapacity];
        this.blockStart = new int[blockCapacity];
        this.blockAlive = new boolean[blockCapacity];
        this.blockNext = new int[blockCapacity];

        Arrays.fill(nodeField, -1);
        nodeField[0] = Integer.MAX_VALUE;
        nodeField[1] = Integer.MAX_VALUE;
        nodeAlive[0] = true;
        nodeAlive[1] = true;
        refCount[0] = Integer.MAX_VALUE;
        refCount[1] = Integer.MAX_VALUE;

        // NOTE: Bug 3 fix (pre-GC callback) requires jdd.bdd.NodeTable.setPreGCCallback(),
        // which is not available in the jdd-111 JAR. Skipped for standalone NDD-SoA build.
    }

    /**
     * Get the internal BDD engine.
     *
     * @return The internal BDD engine.
     */
    public BDD getBddEngine() { return bddEngine; }

    /**
     * Declare a new field and add a unique table for it.
     */
    public void declareField() {
        nodeTable.add(new UniqueTable(4096));
    }

    /**
     * Get the number of stored nodes (excluding freed slots).
     *
     * @return The current stored node count.
     */
    public long getCurrentSize() {
        return currentSize;
    }

    /**
     * Get the total number of NDD nodes ever created.
     * @return Total number of nodes created (including those later garbage collected).
     */
    public long getTotalCreated() {
        return totalCreated;
    }

    /**
     * Get the field index of a node.
     *
     * @param nodeId The node id.
     * @return The field index, or Integer.MAX_VALUE for terminal nodes.
     */
    public int getField(int nodeId) {
        if (nodeId <= 1) return Integer.MAX_VALUE;
        return nodeField[nodeId];
    }

    /**
     * Get the start index of edges for a node.
     *
     * @param nodeId The node id.
     * @return The start index in edge arrays.
     */
    public int getEdgeStart(int nodeId) {
        return blockStart[nodeEdgeBlock[nodeId]];
    }

    /**
     * Get the number of edges of a node.
     *
     * @param nodeId The node id.
     * @return The edge count.
     */
    public int getEdgeCount(int nodeId) {
        return nodeEdgeCount[nodeId];
    }

    /**
     * Get the target node id of an edge.
     *
     * @param edgeIndex The edge index in edge arrays.
     * @return The target node id.
     */
    public int getEdgeTarget(int edgeIndex) {
        return edgeTarget[edgeIndex];
    }

    /**
     * Get the BDD handle of an edge label.
     *
     * @param edgeIndex The edge index in edge arrays.
     * @return The BDD label handle.
     */
    public int getEdgeLabel(int edgeIndex) {
        return edgeLabel[edgeIndex];
    }

    public int getEdgeTarget(int nodeId, int offset) {
        return edgeTarget[blockStart[nodeEdgeBlock[nodeId]] + offset];
    }

    public int getEdgeLabel(int nodeId, int offset) {
        return edgeLabel[blockStart[nodeEdgeBlock[nodeId]] + offset];
    }

    /**
     * Create or reuse an NDD node with given edges.
     *
     * @param field  The field index.
     * @param targets Array of target node ids.
     * @param labels  Array of BDD label handles (same length as targets).
     * @return The node id (new or reused).
     */
    public int mk(int field, int[] targets, int[] labels) {
        return mk(field, targets, labels, 0, targets.length);
    }

    /**
     * Create or reuse an NDD node with a slice of edges.
     * Edges live in shared append-only arrays; each node stores only a stable block id plus the
     * edge count so the hot path can stay object-free.
     *
     * @param field   The field index.
     * @param targets Array of target node ids.
     * @param labels  Array of BDD label handles.
     * @param offset  Start index in targets/labels.
     * @param length  Number of edges.
     * @return The node id (new or reused).
     */
    public int mk(int field, int[] targets, int[] labels, int offset, int length) {
        if (length == 1 && NDD.isUniverseEdgeLabel(field, labels[offset])) {
            NDD.derefLabel(labels[offset]);
            return targets[offset];
        }

        UniqueTable table = nodeTable.get(field);
        int hash = computeHash(targets, labels, offset, length);
        int nodeId = table.lookup(hash, targets, labels, offset, length, this);

        if (nodeId != 0) {
            for (int i = 0; i < length; i++) NDD.derefLabel(labels[offset + i]);
            return nodeId;
        }

        if (currentSize >= nddTableSize) gcOrGrow();
        int id = allocateNode();
        totalCreated++;
        int blockId = allocateBlock();
        ensureEdgeCapacity(length);

        int start = edgeTop;
        for (int i = 0; i < length; i++) {
            edgeTarget[edgeTop] = targets[offset + i];
            edgeLabel[edgeTop] = labels[offset + i];
            edgeTop++;
        }

        nodeField[id] = field;
        nodeEdgeBlock[id] = blockId;
        nodeEdgeCount[id] = length;
        nodeHash[id] = hash;
        nodeNext[id] = 0;
        refCount[id] = 0;
        nodeAlive[id] = true;
        blockStart[blockId] = start;
        blockAlive[blockId] = true;
        liveEdgeCount += length;

        for (int i = 0; i < length; i++) {
            int target = targets[offset + i];
            if (target > 1 && nodeAlive[target] && refCount[target] != Integer.MAX_VALUE) {
                refCount[target] += 1;
            }
        }

        table.insert(id, this);
        currentSize++;
        return id;
    }

    /**
     * Ensure node arrays have capacity for the given node id.
     *
     * @param id The node id that must be storable.
     */
    private void ensureNodeCapacity(int id) {
        if (id < nodeCapacity) return;
        int newCap = nodeCapacity;
        while (newCap <= id) newCap <<= 1;

        nodeField = Arrays.copyOf(nodeField, newCap);
        nodeEdgeBlock = Arrays.copyOf(nodeEdgeBlock, newCap);
        nodeEdgeCount = Arrays.copyOf(nodeEdgeCount, newCap);
        nodeNext = Arrays.copyOf(nodeNext, newCap);
        nodeHash = Arrays.copyOf(nodeHash, newCap);
        refCount = Arrays.copyOf(refCount, newCap);
        nodeAlive = Arrays.copyOf(nodeAlive, newCap);

        Arrays.fill(nodeField, nodeCapacity, newCap, -1);
        nodeCapacity = newCap;
    }

    private void ensureBlockCapacity(int blockId) {
        if (blockId < blockCapacity) return;
        int newCap = blockCapacity;
        while (newCap <= blockId) newCap <<= 1;
        blockStart = Arrays.copyOf(blockStart, newCap);
        blockAlive = Arrays.copyOf(blockAlive, newCap);
        blockNext = Arrays.copyOf(blockNext, newCap);
        blockCapacity = newCap;
    }

    private int allocateBlock() {
        int blockId;
        if (freeBlockHead != 0) {
            blockId = freeBlockHead;
            freeBlockHead = blockNext[blockId];
        } else {
            blockId = nextBlockId++;
            ensureBlockCapacity(blockId);
        }
        blockNext[blockId] = 0;
        blockAlive[blockId] = true;
        return blockId;
    }

    private int allocateNode() {
        int nodeId;
        if (freeNodeHead != 0) {
            nodeId = freeNodeHead;
            freeNodeHead = nodeNext[nodeId];
        } else {
            nodeId = nextNodeId++;
            ensureNodeCapacity(nodeId);
        }
        nodeNext[nodeId] = 0;
        return nodeId;
    }

    /**
     * Ensure edge arrays have capacity for additional edges.
     *
     * @param needed Number of additional edges required.
     */
    private void ensureEdgeCapacity(int needed) {
        if (edgeTop + needed <= edgeCapacity) return;
        int newCap = edgeCapacity;
        while (newCap < edgeTop + needed) newCap <<= 1;
        edgeTarget = Arrays.copyOf(edgeTarget, newCap);
        edgeLabel = Arrays.copyOf(edgeLabel, newCap);
        edgeCapacity = newCap;
    }

    /**
     * Free unused nodes first by garbage collection, then grow table if needed.
     */
    private void gcOrGrow() {
        gc();
        if (nddTableSize - currentSize <= nddTableSize * QUICK_GROW_THRESHOLD) {
            grow();
        }
        NDD.clearCaches();
    }

    /**
     * Garbage collection: remove nodes with zero reference count and compact edges.
     */
    public void gc() {
        NDD.forEachTemporarilyProtect(this::ref);

        IntQueue queue = new IntQueue((int) Math.max(16, currentSize));
        for (int i = 2; i < nextNodeId; i++) {
            if (nodeAlive[i] && refCount[i] == 0) queue.add(i);
        }

        while (!queue.isEmpty()) {
            int deadNode = queue.poll();
            int blockId = nodeEdgeBlock[deadNode];
            int start = blockStart[blockId];
            int count = nodeEdgeCount[deadNode];

            for (int i = 0; i < count; i++) {
                int target = edgeTarget[start + i];
                if (target <= 1 || !nodeAlive[target]) continue;
                if (refCount[target] != Integer.MAX_VALUE) {
                    int updated = --refCount[target];
                    if (updated == 0) queue.add(target);
                }
            }

            for (int i = 0; i < count; i++) NDD.derefLabel(edgeLabel[start + i]);

            nodeTable.get(nodeField[deadNode]).remove(deadNode, this);
            // DON'T clear nodeField/nodeEdgeBlock/nodeEdgeCount - recursive operations
            // on the stack may hold stale references to this node and still need to read
            // its field and edge data (matching OOP behavior where freed Java objects persist).
            nodeAlive[deadNode] = false;
            nodeHash[deadNode] = 0;
            refCount[deadNode] = 0;
            currentSize--;
            blockAlive[blockId] = false;
            liveEdgeCount -= count;
            nodeNext[deadNode] = retiredNodeHead;
            retiredNodeHead = deadNode;
            blockNext[blockId] = retiredBlockHead;
            retiredBlockHead = blockId;
        }

        // compactEdges() is NOT safe here: recursive NDD operations may cache
        // resolved physical starts in local variables that would become stale.
        NDD.forEachTemporarilyProtect(this::deref);
    }

    /**
     * Compact edge arrays by removing gaps left by collected nodes.
     */
    private void compactEdges() {
        int newEdgeTop = 0;
        int[] newEdgeTarget = new int[Math.max(16, edgeTop)];
        int[] newEdgeLabel = new int[newEdgeTarget.length];

        for (int nodeId = 2; nodeId < nextNodeId; nodeId++) {
            if (!nodeAlive[nodeId]) continue;
            int count = nodeEdgeCount[nodeId];
            if (newEdgeTop + count > newEdgeTarget.length) {
                int newCap = newEdgeTarget.length;
                while (newCap < newEdgeTop + count) newCap <<= 1;
                newEdgeTarget = Arrays.copyOf(newEdgeTarget, newCap);
                newEdgeLabel = Arrays.copyOf(newEdgeLabel, newCap);
            }
            int blockId = nodeEdgeBlock[nodeId];
            int oldStart = blockStart[blockId];
            System.arraycopy(edgeTarget, oldStart, newEdgeTarget, newEdgeTop, count);
            System.arraycopy(edgeLabel, oldStart, newEdgeLabel, newEdgeTop, count);
            blockStart[blockId] = newEdgeTop;
            newEdgeTop += count;
        }

        edgeTarget = newEdgeTarget;
        edgeLabel = newEdgeLabel;
        edgeTop = newEdgeTop;
        edgeCapacity = newEdgeTarget.length;
    }

    /**
     * Counter to throttle compaction checks (avoid scanning all nodes every operation).
     */
    private int compactCheckCounter = 0;
    private static final int COMPACT_CHECK_INTERVAL = 1000;

    /**
     * Compact edges if fragmentation ratio exceeds threshold.
     * Safe to call only when no recursive NDD operation is in progress.
     * Throttled to check only every COMPACT_CHECK_INTERVAL calls.
     */
    public void compactEdgesIfNeeded() {
        if (++compactCheckCounter < COMPACT_CHECK_INTERVAL) {
            recycleRetiredSlotsAtSafePoint();
            return;
        }
        compactCheckCounter = 0;
        if (edgeTop > 16384 && liveEdgeCount * 2 < edgeTop) {
            compactEdges();
        }
        recycleRetiredSlotsAtSafePoint();
    }

    /**
     * Force compaction at a caller-provided safe point.
     */
    public void compactEdgesAtSafePoint() {
        if (liveEdgeCount < edgeTop) {
            compactEdges();
        }
        recycleRetiredSlotsAtSafePoint();
    }

    private void recycleRetiredSlotsAtSafePoint() {
        while (retiredNodeHead != 0) {
            int nodeId = retiredNodeHead;
            retiredNodeHead = nodeNext[nodeId];
            nodeNext[nodeId] = freeNodeHead;
            freeNodeHead = nodeId;
        }
        while (retiredBlockHead != 0) {
            int blockId = retiredBlockHead;
            retiredBlockHead = blockNext[blockId];
            blockNext[blockId] = freeBlockHead;
            freeBlockHead = blockId;
        }
    }

    /**
     * Grow the max node table size (double).
     */
    private void grow() {
        nddTableSize *= 2;
    }

    /**
     * Increment reference count of a node (protect from gc).
     *
     * @param nodeId The node id.
     * @return The same node id.
     */
    public int ref(int nodeId) {
        if (nodeId <= 1) return nodeId;
        if (nodeAlive[nodeId] && refCount[nodeId] != Integer.MAX_VALUE) {
            refCount[nodeId] += 1;
        }
        return nodeId;
    }

    /**
     * Mark a node as permanently referenced (e.g. variable nodes), so it is never collected.
     *
     * @param nodeId The node id.
     */
    public void fixNDDNodeRefCount(int nodeId) {
        if (nodeId > 1) refCount[nodeId] = Integer.MAX_VALUE;
    }

    /**
     * Decrement reference count of a node (allow gc when zero).
     *
     * @param nodeId The node id.
     */
    public void deref(int nodeId) {
        if (nodeId <= 1) return;
        if (nodeAlive[nodeId] && refCount[nodeId] != Integer.MAX_VALUE) {
            refCount[nodeId] -= 1;
        }
    }

    /**
     * Compute hash for a set of edges (for unique table).
     *
     * @param targets Target array.
     * @param labels  Label array.
     * @param offset  Start index.
     * @param length  Number of edges.
     * @return Hash value.
     */
    private static int computeHash(int[] targets, int[] labels, int offset, int length) {
        int h = 0;
        for (int i = 0; i < length; i++) {
            h = h * 31 + targets[offset + i];
            h = h * 31 + labels[offset + i];
        }
        return h;
    }

    /**
     * Per-field unique table: hash table for deduplicating nodes by (targets, labels).
     */
    private static class UniqueTable {
        /** Bucket array (head node id per bucket). */
        int[] buckets;
        /** Table capacity (power of two). */
        int size;
        /** size - 1, for fast modulo. */
        int mask;
        /** Number of nodes in the table. */
        int count;
        /** Resize when count >= threshold. */
        int threshold;

        UniqueTable(int initCap) {
            size = 1;
            while (size < initCap) size <<= 1;
            buckets = new int[size];
            mask = size - 1;
            threshold = (int) (size * 0.75);
        }

        /**
         * Look up an existing node with the same edges; 0 if not found.
         */
        int lookup(int hash, int[] targets, int[] labels, int offset, int length, NodeTable table) {
            int pos = hash & mask;
            int curr = buckets[pos];
            while (curr != 0) {
                if (table.nodeHash[curr] == hash && arraysMatch(curr, targets, labels, offset, length, table)) return curr;
                curr = table.nodeNext[curr];
            }
            return 0;
        }

        /** Insert a node into the unique table. */
        void insert(int nodeId, NodeTable table) {
            if (count >= threshold) resize(table);
            int pos = table.nodeHash[nodeId] & mask;
            table.nodeNext[nodeId] = buckets[pos];
            buckets[pos] = nodeId;
            count++;
        }

        /** Remove a node from the unique table. */
        void remove(int nodeId, NodeTable table) {
            int pos = table.nodeHash[nodeId] & mask;
            int curr = buckets[pos];
            int prev = 0;
            while (curr != 0) {
                if (curr == nodeId) {
                    if (prev == 0) buckets[pos] = table.nodeNext[curr];
                    else table.nodeNext[prev] = table.nodeNext[curr];
                    count--;
                    return;
                }
                prev = curr;
                curr = table.nodeNext[curr];
            }
        }

        /** Check if a stored node has the same edges as the given slice. */
        private boolean arraysMatch(int nodeId, int[] targets, int[] labels, int offset, int length, NodeTable table) {
            int count = table.nodeEdgeCount[nodeId];
            if (count != length) return false;
            int start = table.blockStart[table.nodeEdgeBlock[nodeId]];
            for (int i = 0; i < length; i++) {
                if (table.edgeTarget[start + i] != targets[offset + i]) return false;
                if (table.edgeLabel[start + i] != labels[offset + i]) return false;
            }
            return true;
        }

        /** Double the table size and rehash. */
        private void resize(NodeTable table) {
            int newSize = size << 1;
            int[] newBuckets = new int[newSize];
            int newMask = newSize - 1;

            for (int i = 0; i < size; i++) {
                int curr = buckets[i];
                while (curr != 0) {
                    int next = table.nodeNext[curr];
                    int pos = table.nodeHash[curr] & newMask;
                    table.nodeNext[curr] = newBuckets[pos];
                    newBuckets[pos] = curr;
                    curr = next;
                }
            }

            size = newSize;
            buckets = newBuckets;
            mask = newMask;
            threshold = (int) (newSize * 0.75);
        }
    }

    /** Simple int queue for gc dead-node traversal. */
    private static class IntQueue {
        private int[] data;
        private int head;
        private int tail;

        IntQueue(int initialCapacity) {
            data = new int[Math.max(16, initialCapacity)];
        }

        boolean isEmpty() { return head == tail; }

        void add(int value) {
            if (tail >= data.length) grow();
            data[tail++] = value;
        }

        int poll() { return data[head++]; }

        private void grow() {
            data = Arrays.copyOf(data, data.length << 1);
        }
    }
}
