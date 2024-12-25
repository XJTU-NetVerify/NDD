package ndd.jdd.nodetable;

import jdd.bdd.BDD;
import ndd.jdd.diagram.AtomizedNDD;
import ndd.jdd.diagram.NDD;

import java.util.*;

public class AtomizedNodeTable {
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
    ArrayList<HashMap<HashMap<AtomizedNDD, HashSet<Integer>>, AtomizedNDD>> nodeTable;

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
    HashMap<AtomizedNDD, Integer> referenceCount;

    /**
     * Construct function for atomized ndd.
     * @param nddTableSize The max size of ndd node table.
     * @param bddEngine The engine for bdd.
     */
    public AtomizedNodeTable(long nddTableSize, BDD bddEngine) {
        this.currentSize = 0L;
        this.nddTableSize = nddTableSize;
        this.nodeTable = new ArrayList<>();
        this.bddEngine = bddEngine;
        this.referenceCount = new HashMap<>();
    }

    public ArrayList<HashMap<HashMap<AtomizedNDD, HashSet<Integer>>, AtomizedNDD>> getNodeTable() {
        return nodeTable;
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
    public AtomizedNDD mk(int field, HashMap<AtomizedNDD, HashSet<Integer>> edges) {
        if (edges.size() == 0) {
            // Since NDD omits all edges pointing to FALSE, the empty edge represents FALSE.
            return AtomizedNDD.getFalse();
        } else if (edges.size() == 1 && edges.values().iterator().next().size() == AtomizedNDD.getAllAtoms(field).size()) {
            // Omit nodes with the only edge labeled by BDD TRUE.
            return edges.keySet().iterator().next();
        } else {
            AtomizedNDD node = nodeTable.get(field).get(edges);
            if (node == null) {
                // create a new node
                // 1. add ref count of all descendants
                Iterator<AtomizedNDD> iterator = edges.keySet().iterator();
                while (iterator.hasNext()) {
                    ref(iterator.next());
                }

                // 2. check if there should be a gc or grow
                if (currentSize >= nddTableSize) {
                    gcOrGrow();
                }

                // 3. create node
                AtomizedNDD newNode = new AtomizedNDD(field, edges);
                nodeTable.get(field).put(edges, newNode);
                referenceCount.put(newNode, 0);
                currentSize++;
                return newNode;
            } else {
                // reuse node
                return node;
            }
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
        AtomizedNDD.clearCaches();
    }

    /**
     * Garbage collection.
     */
    private void gc() {
        // protect temporary nodes during NDD operations
        for (AtomizedNDD ndd : AtomizedNDD.getAtomizedTemporarilyProtect()) {
            ref(ndd);
        }

        // remove unused nodes by topological sorting
        Queue<AtomizedNDD> deadNodesQueue = new LinkedList<>();
        for (Map.Entry<AtomizedNDD, Integer> entry : referenceCount.entrySet()) {
            if (entry.getValue() == 0) {
                deadNodesQueue.offer(entry.getKey());
            }
        }
        while (!deadNodesQueue.isEmpty()) {
            AtomizedNDD deadNode = deadNodesQueue.poll();
            for (AtomizedNDD descendant : deadNode.getAtomizedEdges().keySet()) {
                if (descendant.isTerminal()) continue;
                int newReferenceCount = referenceCount.get(descendant) - 1;
                referenceCount.put(descendant, newReferenceCount);
                if (newReferenceCount == 0) {
                    deadNodesQueue.offer(descendant);
                }
            }
            // delete current dead node
            referenceCount.remove(deadNode);
            nodeTable.get(deadNode.getField()).remove(deadNode.getAtomizedEdges());
            currentSize--;
        }

        for (AtomizedNDD ndd : AtomizedNDD.getAtomizedTemporarilyProtect()) {
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
    public AtomizedNDD ref(AtomizedNDD ndd) {
        if (!ndd.isTerminal()) {
            referenceCount.put(ndd, referenceCount.get(ndd) + 1);
        }
        return ndd;
    }

    /**
     * Unprotect a root node, such that the node can be cleared during garbage collection.
     * @param ndd The ndd node to be unprotected.
     */
    public void deref(AtomizedNDD ndd) {
        if (!ndd.isTerminal()) {
            referenceCount.put(ndd, referenceCount.get(ndd) - 1);
        }
    }
}