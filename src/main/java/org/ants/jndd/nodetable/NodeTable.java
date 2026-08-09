package org.ants.jndd.nodetable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import org.ants.jndd.diagram.NDD;
import org.ants.jndd.utils.Rational;

public class NodeTable {
    public static final int TERMINAL_FIELD = Integer.MAX_VALUE;

    private long totalCreated;
    private long currentSize;
    private long nddTableSize;
    private long gcCount;
    private long gcFreedCount;
    private long gcTimeNanos;
    private long thresholdGrowCount;
    private final ArrayList<UniqueTable> nodeTable;

    private int nodeCapacity;
    private int edgeCapacity;
    private int blockCapacity;
    private int nextNodeId;
    private int freeNodeHead;
    private int retiredNodeHead;
    private int edgeTop;
    private int nextBlockId;
    private int freeBlockHead;
    private int retiredBlockHead;
    private long liveEdgeCount;

    public int[] nodeField;
    public int[] nodeEdgeBlock;
    public int[] nodeEdgeCount;
    int[] nodeNext;
    int[] nodeHash;
    public int[] refCount;
    public int[] edgeTarget;
    public int[] edgeLabel;
    private boolean[] nodeAlive;
    private int[] blockStart;
    private int[] blockNext;

    private int[] nodeTerminalIndex;
    private Rational[] terminalValues;
    private int[] terminalNodeIds;
    private int terminalCount;
    private int[] terminalBuckets;
    private int terminalMask;
    private int terminalThreshold;

    public NodeTable(long nddTableSize) {
        this.totalCreated = 0L;
        this.currentSize = 0L;
        this.nddTableSize = nddTableSize;
        this.nodeTable = new ArrayList<>();
        this.nextBlockId = 1;

        int initialNodeCap = (int) Math.max(16, Math.min(4096, nddTableSize + 2));
        int initialEdgeCap = Math.max(16, initialNodeCap * 4);
        this.nodeCapacity = initialNodeCap;
        this.edgeCapacity = initialEdgeCap;
        this.blockCapacity = initialNodeCap;
        this.nextNodeId = 0;

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
        this.blockNext = new int[blockCapacity];
        this.nodeTerminalIndex = new int[nodeCapacity];
        Arrays.fill(nodeTerminalIndex, -1);
        this.terminalValues = new Rational[16];
        this.terminalNodeIds = new int[16];
        this.terminalBuckets = new int[32];
        this.terminalMask = terminalBuckets.length - 1;
        this.terminalThreshold = (int) (terminalBuckets.length * 0.7);
        Arrays.fill(nodeField, -1);

        int zero = mkTerminal(new Rational(0));
        int one = mkTerminal(new Rational(1));
        if (zero != 0 || one != 1) {
            throw new IllegalStateException("terminal bootstrap failed");
        }
        fixNDDNodeRefCount(0);
        fixNDDNodeRefCount(1);
    }

    public void declareField() {
        nodeTable.add(new UniqueTable(64));
    }

    public long getCurrentSize() {
        return currentSize;
    }

    public long getTotalCreated() {
        return totalCreated;
    }

    public long getGcCount() {
        return gcCount;
    }

    public long getGcFreedCount() {
        return gcFreedCount;
    }

    public long getGcTimeMillis() {
        return gcTimeNanos / 1_000_000L;
    }

    public long getThresholdGrowCount() {
        return thresholdGrowCount;
    }

    public int getField(int nodeId) {
        return nodeField[nodeId];
    }

    public boolean isTerminal(int nodeId) {
        return nodeId >= 0 && nodeId < nextNodeId && nodeField[nodeId] == TERMINAL_FIELD;
    }

    public Rational getTerminalValue(int nodeId) {
        int terminalIndex = nodeTerminalIndex[nodeId];
        if (terminalIndex < 0) {
            throw new IllegalArgumentException("node " + nodeId + " is not a terminal");
        }
        return terminalValues[terminalIndex];
    }

    public int getTerminalCount() {
        return terminalCount;
    }

    public HashMap<Rational, Integer> getTerminalTable() {
        HashMap<Rational, Integer> result = new HashMap<>(terminalCount * 2);
        for (int i = 0; i < terminalCount; i++) {
            result.put(terminalValues[i], terminalNodeIds[i]);
        }
        return result;
    }

    public int mkTerminal(Rational value) {
        int bucket = terminalBucket(value.numerator(), value.denominator());
        int encodedNodeId;
        while ((encodedNodeId = terminalBuckets[bucket]) != 0) {
            int nodeId = encodedNodeId - 1;
            Rational existing = terminalValues[nodeTerminalIndex[nodeId]];
            if (existing.numerator() == value.numerator()
                    && existing.denominator() == value.denominator()) {
                return nodeId;
            }
            bucket = (bucket + 1) & terminalMask;
        }

        int id = allocateNode();
        ensureTerminalCapacity();
        nodeField[id] = TERMINAL_FIELD;
        nodeEdgeBlock[id] = 0;
        nodeEdgeCount[id] = 0;
        nodeHash[id] = value.hashCode();
        refCount[id] = Integer.MAX_VALUE;
        nodeAlive[id] = true;
        int terminalIndex = terminalCount++;
        nodeTerminalIndex[id] = terminalIndex;
        terminalValues[terminalIndex] = value;
        terminalNodeIds[terminalIndex] = id;
        terminalBuckets[bucket] = id + 1;
        if (terminalCount >= terminalThreshold) {
            resizeTerminalBuckets();
        }
        totalCreated++;
        currentSize++;
        return id;
    }

    private int terminalBucket(long numerator, long denominator) {
        long hash = numerator * 0x9e3779b97f4a7c15L
                ^ Long.rotateLeft(denominator * 0xc2b2ae3d27d4eb4fL, 29);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return ((int) hash) & terminalMask;
    }

    private void ensureTerminalCapacity() {
        if (terminalCount < terminalValues.length) {
            return;
        }
        int newCapacity = terminalValues.length << 1;
        terminalValues = Arrays.copyOf(terminalValues, newCapacity);
        terminalNodeIds = Arrays.copyOf(terminalNodeIds, newCapacity);
    }

    private void resizeTerminalBuckets() {
        terminalBuckets = new int[terminalBuckets.length << 1];
        terminalMask = terminalBuckets.length - 1;
        terminalThreshold = (int) (terminalBuckets.length * 0.7);
        for (int i = 0; i < terminalCount; i++) {
            Rational value = terminalValues[i];
            int bucket = terminalBucket(value.numerator(), value.denominator());
            while (terminalBuckets[bucket] != 0) {
                bucket = (bucket + 1) & terminalMask;
            }
            terminalBuckets[bucket] = terminalNodeIds[i] + 1;
        }
    }

    public int getEdgeStart(int nodeId) {
        return blockStart[nodeEdgeBlock[nodeId]];
    }

    public int getEdgeCount(int nodeId) {
        return nodeEdgeCount[nodeId];
    }

    public int getEdgeTarget(int edgeIndex) {
        return edgeTarget[edgeIndex];
    }

    public int getEdgeLabel(int edgeIndex) {
        return edgeLabel[edgeIndex];
    }

    public int getEdgeTarget(int nodeId, int offset) {
        return edgeTarget[blockStart[nodeEdgeBlock[nodeId]] + offset];
    }

    public int getEdgeLabel(int nodeId, int offset) {
        return edgeLabel[blockStart[nodeEdgeBlock[nodeId]] + offset];
    }

    public int mk(int field, int[] targets, int[] labels) {
        return mk(field, targets, labels, 0, targets.length);
    }

    public int mk(int field, int[] targets, int[] labels, int offset, int length) {
        if (length == 0) {
            return NDD.getFalseId();
        }
        if (length == 1 && NDD.isUniverseEdgeLabel(field, labels[offset])) {
            NDD.derefLabel(field, labels[offset]);
            return targets[offset];
        }

        UniqueTable table = nodeTable.get(field);
        int hash = computeHash(targets, labels, offset, length);
        int nodeId = table.lookup(hash, targets, labels, offset, length, this);
        if (nodeId != 0) {
            for (int i = 0; i < length; i++) {
                NDD.derefLabel(field, labels[offset + i]);
            }
            return nodeId;
        }

        if (currentSize >= nddTableSize) {
            gcOrGrow();
        }

        int id = allocateNode();
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
        nodeTerminalIndex[id] = -1;
        blockStart[blockId] = start;
        liveEdgeCount += length;

        for (int i = 0; i < length; i++) {
            int target = targets[offset + i];
            if (target >= 0 && nodeAlive[target] && refCount[target] != Integer.MAX_VALUE) {
                refCount[target]++;
            }
        }

        table.insert(id, this);
        totalCreated++;
        currentSize++;
        return id;
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
        return blockId;
    }

    private void ensureNodeCapacity(int id) {
        if (id < nodeCapacity) {
            return;
        }
        int newCap = nodeCapacity;
        while (newCap <= id) {
            newCap <<= 1;
        }
        nodeField = Arrays.copyOf(nodeField, newCap);
        nodeEdgeBlock = Arrays.copyOf(nodeEdgeBlock, newCap);
        nodeEdgeCount = Arrays.copyOf(nodeEdgeCount, newCap);
        nodeNext = Arrays.copyOf(nodeNext, newCap);
        nodeHash = Arrays.copyOf(nodeHash, newCap);
        refCount = Arrays.copyOf(refCount, newCap);
        nodeAlive = Arrays.copyOf(nodeAlive, newCap);
        nodeTerminalIndex = Arrays.copyOf(nodeTerminalIndex, newCap);
        Arrays.fill(nodeField, nodeCapacity, newCap, -1);
        Arrays.fill(nodeTerminalIndex, nodeCapacity, newCap, -1);
        nodeCapacity = newCap;
    }

    private void ensureBlockCapacity(int blockId) {
        if (blockId < blockCapacity) {
            return;
        }
        int newCap = blockCapacity;
        while (newCap <= blockId) {
            newCap <<= 1;
        }
        blockStart = Arrays.copyOf(blockStart, newCap);
        blockNext = Arrays.copyOf(blockNext, newCap);
        blockCapacity = newCap;
    }

    private void ensureEdgeCapacity(int needed) {
        if (edgeTop + needed <= edgeCapacity) {
            return;
        }
        int newCap = edgeCapacity;
        while (newCap < edgeTop + needed) {
            newCap <<= 1;
        }
        edgeTarget = Arrays.copyOf(edgeTarget, newCap);
        edgeLabel = Arrays.copyOf(edgeLabel, newCap);
        edgeCapacity = newCap;
    }

    private void gcOrGrow() {
        gc();
        if (nddTableSize - currentSize <= nddTableSize * 0.1) {
            nddTableSize *= 2;
            thresholdGrowCount++;
        }
        NDD.clearCaches();
    }

    public void gc() {
        long gcStartedAt = System.nanoTime();
        long before = currentSize;
        NDD.forEachTemporarilyProtect(this::ref);

        IntQueue queue = new IntQueue((int) Math.max(16, currentSize));
        for (int i = 2; i < nextNodeId; i++) {
            if (nodeAlive[i] && !isTerminal(i) && refCount[i] == 0) {
                queue.add(i);
            }
        }

        while (!queue.isEmpty()) {
            int deadNode = queue.poll();
            int blockId = nodeEdgeBlock[deadNode];
            int start = blockStart[blockId];
            int count = nodeEdgeCount[deadNode];

            for (int i = 0; i < count; i++) {
                int target = edgeTarget[start + i];
                if (!nodeAlive[target] || isTerminal(target)) {
                    continue;
                }
                if (refCount[target] != Integer.MAX_VALUE && --refCount[target] == 0) {
                    queue.add(target);
                }
            }

            for (int i = 0; i < count; i++) {
                NDD.derefLabel(nodeField[deadNode], edgeLabel[start + i]);
            }

            nodeTable.get(nodeField[deadNode]).remove(deadNode, this);
            NDD.onNodeRetired(deadNode);
            nodeAlive[deadNode] = false;
            nodeHash[deadNode] = 0;
            refCount[deadNode] = 0;
            currentSize--;
            liveEdgeCount -= count;
            nodeNext[deadNode] = retiredNodeHead;
            retiredNodeHead = deadNode;
            blockNext[blockId] = retiredBlockHead;
            retiredBlockHead = blockId;
        }

        NDD.forEachTemporarilyProtect(this::deref);
        gcCount++;
        gcFreedCount += before - currentSize;
        gcTimeNanos += System.nanoTime() - gcStartedAt;
    }

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

    private int compactCheckCounter;
    private static final int COMPACT_CHECK_INTERVAL = 1000;

    private void compactEdges() {
        int newEdgeTop = 0;
        int[] newEdgeTarget = new int[Math.max(16, (int) Math.min(Integer.MAX_VALUE, liveEdgeCount))];
        int[] newEdgeLabel = new int[newEdgeTarget.length];
        for (int nodeId = 2; nodeId < nextNodeId; nodeId++) {
            if (!nodeAlive[nodeId] || isTerminal(nodeId)) {
                continue;
            }
            int count = nodeEdgeCount[nodeId];
            if (newEdgeTop + count > newEdgeTarget.length) {
                int newCapacity = newEdgeTarget.length;
                while (newCapacity < newEdgeTop + count) {
                    newCapacity <<= 1;
                }
                newEdgeTarget = Arrays.copyOf(newEdgeTarget, newCapacity);
                newEdgeLabel = Arrays.copyOf(newEdgeLabel, newCapacity);
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

    public void compactEdgesAtSafePoint() {
        if (liveEdgeCount < edgeTop) {
            compactEdges();
        }
        recycleRetiredSlotsAtSafePoint();
    }

    public long getInternalNodeCount() {
        return currentSize - terminalCount;
    }

    public long getLiveEdgeCount() {
        return liveEdgeCount;
    }

    public int getPhysicalEdgeSlots() {
        return edgeTop;
    }

    public int getNextNodeId() {
        return nextNodeId;
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

    public int ref(int nodeId) {
        if (nodeId >= 0 && nodeAlive[nodeId] && refCount[nodeId] != Integer.MAX_VALUE) {
            refCount[nodeId]++;
        }
        return nodeId;
    }

    public void fixNDDNodeRefCount(int nodeId) {
        refCount[nodeId] = Integer.MAX_VALUE;
    }

    public void deref(int nodeId) {
        if (nodeId >= 0 && nodeAlive[nodeId] && refCount[nodeId] != Integer.MAX_VALUE) {
            refCount[nodeId]--;
        }
    }

    public void showMKCnt() {
        System.out.println("NDD created nodes: " + totalCreated + ", live nodes: " + currentSize);
    }

    private static int computeHash(int[] targets, int[] labels, int offset, int length) {
        int h = 0;
        for (int i = 0; i < length; i++) {
            h = h * 31 + targets[offset + i];
            h = h * 31 + labels[offset + i];
        }
        return h;
    }

    private static class UniqueTable {
        int[] buckets;
        int size;
        int mask;
        int count;
        int threshold;

        UniqueTable(int initCap) {
            size = 1;
            while (size < initCap) {
                size <<= 1;
            }
            buckets = new int[size];
            mask = size - 1;
            threshold = (int) (size * 0.75);
        }

        int lookup(int hash, int[] targets, int[] labels, int offset, int length, NodeTable table) {
            int curr = buckets[hash & mask];
            while (curr != 0) {
                if (table.nodeHash[curr] == hash && arraysMatch(curr, targets, labels, offset, length, table)) {
                    return curr;
                }
                curr = table.nodeNext[curr];
            }
            return 0;
        }

        void insert(int nodeId, NodeTable table) {
            if (count >= threshold) {
                resize(table);
            }
            int pos = table.nodeHash[nodeId] & mask;
            table.nodeNext[nodeId] = buckets[pos];
            buckets[pos] = nodeId;
            count++;
        }

        void remove(int nodeId, NodeTable table) {
            int pos = table.nodeHash[nodeId] & mask;
            int curr = buckets[pos];
            int prev = 0;
            while (curr != 0) {
                if (curr == nodeId) {
                    if (prev == 0) {
                        buckets[pos] = table.nodeNext[curr];
                    } else {
                        table.nodeNext[prev] = table.nodeNext[curr];
                    }
                    count--;
                    return;
                }
                prev = curr;
                curr = table.nodeNext[curr];
            }
        }

        private boolean arraysMatch(int nodeId, int[] targets, int[] labels, int offset, int length, NodeTable table) {
            if (table.nodeEdgeCount[nodeId] != length) {
                return false;
            }
            int start = table.blockStart[table.nodeEdgeBlock[nodeId]];
            for (int i = 0; i < length; i++) {
                if (table.edgeTarget[start + i] != targets[offset + i]) {
                    return false;
                }
                if (table.edgeLabel[start + i] != labels[offset + i]) {
                    return false;
                }
            }
            return true;
        }

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

    private static class IntQueue {
        private int[] data;
        private int head;
        private int tail;

        IntQueue(int initialCapacity) {
            data = new int[Math.max(16, initialCapacity)];
        }

        boolean isEmpty() {
            return head == tail;
        }

        void add(int value) {
            if (tail >= data.length) {
                data = Arrays.copyOf(data, data.length << 1);
            }
            data[tail++] = value;
        }

        int poll() {
            return data[head++];
        }
    }
}
