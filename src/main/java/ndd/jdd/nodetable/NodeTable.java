package ndd.jdd.nodetable;

import jdd.bdd.BDD;
import ndd.jdd.diagram.NDD;

import java.util.*;

/**
 * Node table of NDD and atomized NDD.
 * @author Zechun Li
 * @version 0.1
 */

public class NodeTable <E> {
    /**
     * The current size of the node table.
     */
    long currentSize;

    /**
     * The max size of the node table.
     */
    long nddTableSize;

    /**
     * The node table.
     */
    ArrayList<HashMap<HashMap<NDD, Integer>, NDD>> nodeTable;

    /**
     * The internal bdd engine.
     */
    BDD bddEngine;

    /**
     * If the number of free nodes is less than this threshold after garbage collection, the ndd engine will grow its node table.
     */
    final double QUICK_GROW_THRESHOLD = 0.1;

    /**
     * The reference count of each node.
     */
    HashMap<NDD, Integer> referenceCount;

    /**
     * Construct function.
     * @param nddTableSize The max size of ndd node table.
     * @param bddTableSize The max size of bdd node table.
     * @param bddCacheSize The max size of ndd operation cache.
     */
    public NodeTable(long nddTableSize, int bddTableSize, int bddCacheSize) {
        this.currentSize = 0L;
        this.nddTableSize = nddTableSize;
        this.nodeTable = new ArrayList<>();
        bddEngine = new BDD(bddTableSize, bddCacheSize);
        this.referenceCount = new HashMap<>();
    }

    /**
     * Get the internal bdd engine.
     * @return The internal bdd engine.
     */
    public BDD getBddEngine() {
        return bddEngine;
    }

    /**
     * Declare a new field.
     */
    // declare a new node table for a new field
    public void declareField() {
        nodeTable.add(new HashMap<>());
    }

    /**
     * Create or reuse an ndd node.
     * @param field The field of the node.
     * @param edges Edges of the node.
     * @return The ndd node.
     */
    // create or reuse a new node
    public NDD mk(int field, HashMap<NDD, Integer> edges) {
        NDD node = nodeTable.get(field).get(edges);
        if (node == null) {
            // create a new node
            // 1. add ref count of all descendants
            Iterator<NDD> iterator = edges.keySet().iterator();
            while (iterator.hasNext()) {
                NDD descendant = iterator.next();
                if (!descendant.isTerminal()) {
                    referenceCount.put(descendant, referenceCount.get(descendant) + 1);
                }
            }

            // 2. check if there should be a gc or grow
            if (currentSize >= nddTableSize) {
                gcOrGrow();
            }

            // 3. create node
            NDD newNode = new NDD(field, edges);
            nodeTable.get(field).put(edges, newNode);
            referenceCount.put(newNode, 0);
            currentSize++;
            return newNode;
        } else {
            // reuse node
            for (Integer bdd : edges.values()) {
                bddEngine.deref(bdd);
            }
            return node;
        }
    }

    /**
     * Free unused ndd node, first by garbage collection, then by growing the node table.
     */
    private void gcOrGrow() {
        gc();
        if (nddTableSize - currentSize <= nddTableSize * QUICK_GROW_THRESHOLD) {
            grow();
        }
        NDD.clearCaches();
    }

    /**
     * Garbage collection.
     */
    private void gc() {
        // protect temporary nodes during NDD operations
        for (NDD ndd : NDD.getTemporarilyProtect()) {
            ref(ndd);
        }

        // remove unused nodes by topological sorting
        Queue<NDD> deadNodesQueue = new LinkedList<>();
        for (Map.Entry<NDD, Integer> entry : referenceCount.entrySet()) {
            if (entry.getValue() == 0) {
                deadNodesQueue.offer(entry.getKey());
            }
        }
        while (!deadNodesQueue.isEmpty()) {
            NDD deadNode = deadNodesQueue.poll();
            for (NDD descendant : deadNode.getEdges().keySet()) {
                if (descendant.isTerminal()) continue;
                int newReferenceCount = referenceCount.get(descendant) - 1;
                referenceCount.put(descendant, newReferenceCount);
                if (newReferenceCount == 0) {
                    deadNodesQueue.offer(descendant);
                }
            }
            // delete current dead node
            for (int bddLabel : deadNode.getEdges().values()) {
                bddEngine.deref(bddLabel);
            }
            referenceCount.remove(deadNode);
            nodeTable.get(deadNode.getField()).remove(deadNode.getEdges());
            currentSize--;
        }

        for (NDD ndd : NDD.getTemporarilyProtect()) {
            deref(ndd);
        }
    }

    /**
     * Grow the node table.
     */
    private void grow() {
        nddTableSize *= 2;
    }

    /**
     * Protect a root node from garbage collection.
     * @param ndd The root to be protected.
     * @return The ndd node.
     */
    public NDD ref(NDD ndd) {
        if (!ndd.isTerminal()) {
            referenceCount.put(ndd, referenceCount.get(ndd) + 1);
        }
        return ndd;
    }

    /**
     * Unprotect a root node, such that the node can be cleared during garbage collection.
     * @param ndd The ndd node to be unprotected.
     */
    public void deref(NDD ndd) {
        if (!ndd.isTerminal()) {
            referenceCount.put(ndd, referenceCount.get(ndd) - 1);
        }
    }
}
