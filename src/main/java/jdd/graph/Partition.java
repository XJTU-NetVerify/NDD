

package jdd.graph;

import jdd.util.*;
import java.util.*;


/**
 * This partition class is used to divide V[G] into a series of disjoint subsets.
 * <p>It is primarily used by Tarjans SCC algorithm.
 */

public class Partition extends DisjointSet {
	private int root;
	private Graph graph;

	public Partition(Graph g) {
		super( g.numOfNodes() );
		this.graph = g;

		AttributeExplorer.updateExtraIndex(g);
		root = -1;
	}

	/* package */ void newPartition() {
		root = -1;
	}

	/* package */ void addToPartition(Node n) {
		int id = n.extraindex;
		if(root == -1) root = id;
		else union(id, root);
		root = find(id);
	}

	public boolean inSamePartition(Node n1, Node n2) {
		return find(n1.extraindex) == find(n2.extraindex);
	}

	/** very inefficient, for debugging small partitions only */
	public void show() {
		for(int c = 0; c < size; c++){
			if(s[c] < 0) {
				JDDConsole.out.print(" (");
				for(int i = 0; i < size; i++) {
					if(find(i) == c) {
						Node n = (Node) graph.getNodes().elementAt(i);
						JDDConsole.out.print( n.label + " ");
					}
				}
				JDDConsole.out.print(") ");
			}
		}

		JDDConsole.out.println();
	}
}
