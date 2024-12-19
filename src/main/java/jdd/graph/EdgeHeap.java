
package jdd.graph;


import jdd.util.*;

import java.util.*;


/**
 * The EdgeHeap class is a sorted list of Edges in a graph.
 *
 * <p>NOTE: regardless its name, it may or may not use a binary heap. currently it used quick sort :
 *
 */

public class EdgeHeap {
	private int top;
	private Edge [] sorted;

	public EdgeHeap(Graph g, boolean smallest_first) {
		int i = 0, n = g.getEdges().size();
		sorted = new Edge[n];
		for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;)
			sorted[i++] = (Edge) e.nextElement();

		Sort.sort(sorted, !smallest_first);
		top = 0;
	}

	public boolean isEmpty() { return top == sorted.length; }
	public int size(){ return sorted.length - top; }
	public Edge pop() { return sorted[top++]; }
}
