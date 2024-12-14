package ndd.nodetable;

import jdd.bdd.BDD;
import ndd.diagram.NDD;

import java.util.*;

public class NodeTable <E> {
    // node table
    long currentSize;
    long maxSize;
    ArrayList<HashMap<HashMap<NDD, Integer>, NDD>> nodeTable; // each element is an node table for a field
    // bdd engine
    BDD bddEngine;
    // garbage collection
    final double QUICK_GROW_THRESHOLD = 0.1;
    HashMap<NDD, Integer> referenceCount;

    public NodeTable(long maxSize, int bddTableSize, int bddCacheSize) {
        this.currentSize = 0L;
        this.maxSize = maxSize;
        this.nodeTable = new ArrayList<>();
        bddEngine = new BDD(bddTableSize, bddCacheSize);
        this.referenceCount = new HashMap<>();
    }

    public BDD getBddEngine() {
        return bddEngine;
    }

    // declare a new node table for a new field
    public void declareField() {
        nodeTable.add(new HashMap<>());
    }

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
            if (currentSize >= maxSize) {
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

    private void gcOrGrow() {
        gc();
        if (maxSize - currentSize <= maxSize * QUICK_GROW_THRESHOLD) {
            grow();
        }
        NDD.clearCaches();
    }

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
            if (!deadNode.isTerminal()) {
                for (NDD descendant : deadNode.getEdges().keySet()) {
                    int newReferenceCount = referenceCount.get(descendant) - 1;
                    referenceCount.put(descendant, newReferenceCount);
                    if (newReferenceCount == 0) {
                        deadNodesQueue.offer(descendant);
                    }
                }
                // delete current dead node
                referenceCount.remove(deadNode);
                nodeTable.get(deadNode.getField()).remove(deadNode.getEdges());
                currentSize--;
            }
        }

        for (NDD ndd : NDD.getTemporarilyProtect()) {
            deref(ndd);
        }
    }

    private void grow() {
        maxSize *= 2;
    }

    public NDD ref(NDD ndd) {
        if (!ndd.isTerminal()) {
            referenceCount.put(ndd, referenceCount.get(ndd) + 1);
        }
        return ndd;
    }
    
    public void deref(NDD ndd) {
        if (!ndd.isTerminal()) {
            referenceCount.put(ndd, referenceCount.get(ndd) - 1);
        }
    }
}
