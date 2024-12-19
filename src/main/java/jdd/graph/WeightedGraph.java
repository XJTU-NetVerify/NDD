
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * directed graph with weights associated to each edge
 */

public class WeightedGraph extends Graph {
	public WeightedGraph() { super(true); }
	public WeightedGraph(boolean directed) { super(directed); }

	public Edge addEdge(Node n1, Node n2, double weight) {
		Edge e = super.addEdge(n1,n2);
		e.weight = weight;
		e.flags |= Edge.FLAGS_WEIGTHED;
		return e;
	}
}
