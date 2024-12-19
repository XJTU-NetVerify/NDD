
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * Weak Topological Ordering (WTO) algorithms.
 *
 * @see TopologicalSort
 */


public class WeakTopologicalOrdering {

	private static int num, tos;
	private static Node [] stack = null;
	private static boolean directed;


	/**
	 * Bourdoncle's algorithm which is based on Tarjan's SCC algorithm,
	 * use the first node as root. treat the graph is undirected.
	 * This version is used to analyze PCG graphs [it ignores edge directions!]
	 */

	public static Topology bourdoncle_PCG(Graph g) {
		// XXX: im not sure if we should take a light or a heavy node here
		Node first = GraphOperation.lightNode(g, true);
		return bourdoncle_internal(g, first, false );
	}

	/** Bourdoncle's algorithm which is based on Tarjan's SCC algorithm, use the first node as root*/
	public static Topology bourdoncle(Graph g) {
		return bourdoncle(g, (Node) g.getNodes().elementAt(0) );
	}

	/**
	 * Bourdoncle's algorithm which is based on Tarjan's SCC algorithm.
	 * <p>note that we also take care of undirected graphs...
	 *
	 */
	public static Topology bourdoncle(Graph g, Node root) {
		return bourdoncle_internal(g, root, g.isDirected() );

	}
	// ---------------------------------------------------------
	private static Topology bourdoncle_internal(Graph g, Node root, boolean directed) {
		AttributeExplorer.setAllNodesExtra1(g, -1);
		WeakTopologicalOrdering.directed = directed;
		num = 0;
		tos = 0;
		if(stack == null || stack.length < g.numOfNodes()) stack = new Node[g.numOfNodes()];

		Topology top = new Topology();
		top.disjoint = true; // top level, elements are disjoint
		do {
			Topology t = new Topology();
			bourdoncle_visit(root, t);
			top.add(t);
			root = AttributeExplorer.findExtra1(g, -1); // this is a stupid O(|V|) call!
		} while(root != null);

		return top.simplify();
	}

	private static Topology bourdoncle_component(Node node) {
		Topology t = new Topology();

		Edge succ = node.firstOut;
		while(succ != null) {
			Node n2 = succ.n2;
			succ = succ.next;
			if(n2.extra1 == -1) bourdoncle_visit(n2, t);
		}


		//extra stuff
		if(!directed) {
			succ = node.firstIn;
			while(succ != null) {
				Node n2 = succ.n1;
				succ = succ.prev;
				if(n2.extra1 == -1) bourdoncle_visit(n2, t);
			}
		}

		t.add(node);
		return t;
	}

	private static int bourdoncle_visit(Node node, Topology t) {
		stack[tos++] = node;
		int head = node.extra1 = num++;
		boolean loop = false;

		Edge succ = node.firstOut;
		while(succ != null) {
			Node n2 = succ.n2;
			succ = succ.next;
			int min = (n2.extra1 == -1) ? bourdoncle_visit(n2, t) : n2.extra1;
			if(min <= head) { head = min; loop = true; }
		}

		// extra stuff:
		if(!directed) {
			succ = node.firstIn;
			while(succ != null) {
				Node n2 = succ.n1;
				succ = succ.prev;
				int min = (n2.extra1 == -1) ? bourdoncle_visit(n2, t) : n2.extra1;
				if(min <= head) { head = min; loop = true; }
			}
		}


		if(head == node.extra1) {
			node.extra1 = Integer.MAX_VALUE;
			Node n3 = stack[--tos];
			if(loop) {
				while(n3 != node) {
					n3.extra1 = -1;
					n3 = stack[--tos];
				}
				t.add( bourdoncle_component(node) );
			} else {
				t.add( node);
			}
		}
		return head;
	}



	// ---------------------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("WeakTopologicalOrdering");
		Graph g = GraphIO.loadEdgeList("data/tarjan.pcg");
		// should return 1 2 [ 3 4 [ 5 6] 7] 8
		Topology t = WeakTopologicalOrdering.bourdoncle(g );
		// t.show(); // <-- prints V8 V2 V1 ( V7 V4 V3 ( V6 V5) )


		// ok, this is for the C3 example
		// g = GraphIO.loadEdgeList("data/c3.pcg");
		// t = WeakTopologicalOrdering.bourdoncle_PCG(g );
		// t.showDot("c3");

		Test.end();
	}

}
