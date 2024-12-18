
package jdd.graph;

import jdd.util.*;
import jdd.util.math.*;

import java.util.*;


/** shortest-path algorithms */
public class ShortestPath {

	/** Internal stuff */
	private static TreeSet initialize_single_source(Graph g, Node source, boolean zero_extra1) {
		AttributeExplorer.setAllNodesExtra3(g,Double.POSITIVE_INFINITY);
		if(zero_extra1) AttributeExplorer.setAllNodesExtra1(g,0);

		TreeSet ts = new TreeSet( NodeExtra3Comparator.nodeExtra3Comparator);
		ts.add(source);
		source.extra3 = 0;
		return ts;
	}


	// ---------------------------------------------------------------------------

	/**
	 *
	 * Bellman-Ford positive or negative weight shortest path algorithm
	 * <p>Side effect: changes Node.extra3
	 * <p>output: Node.extra3 gives the visiting cost from which a path can be built
	 * <p>        Tree is a E' \subseteq E
	 *
	 * <p>compelxity: O( |V| * |E| )
	 *
	 */
	public static Tree bellman_ford(Graph g, Node source) {
		TreeSet ts = initialize_single_source(g, source, false);
		Tree t = new Tree(g);

		int n = g.numOfNodes()-1;
		for(int i = 0; i < n; i++) {
			for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;) {
				Edge ed = (Edge) e.nextElement();
				if( ed.n2.extra3 > ed.n1.extra3  + ed.weight) {
					ed.n2.extra3 = ed.n1.extra3  + ed.weight;
					t.add(ed.n1, ed.n2);
				}
			}
		}

		t.extractTree();
		return t;
	}
	// ---------------------------------------------------------------------------

	/**
	 * positive weight shortest path
	 * <p>Side effect: changes Node.extra1 and Node.extra3
	 * <p>output: Node.extra3 gives the visiting cost from which a path can be built
	 *
	 * <p>compelxity: depending on the heap from O( |V| ^2 ) to O(|V| * lg |V| ) ??
	 */
	public static Vector dijkstra(Graph g, Node source) {
		TreeSet ts = initialize_single_source(g, source, true);
		Tree t = new Tree(g);

		while(! ts.isEmpty()) {
			Node u = (Node) ts.first();
			ts.remove(u);
			u.extra1 = 1; // visited!

			Edge ed = u.firstOut;
			while(ed != null) {
				// TODO: check if ed.weight  is NEGATIVE!!
				if(ed.n1.extra3 + ed.weight < ed.n2.extra3) {
					ed.n2.extra3 = ed.n1.extra3 + ed.weight;
					t.add( ed.n1, ed.n2);
					if(ed.n2.extra1 == 0)	ts.add(ed.n2); // <-- NOT sure about this part!
				}
				ed = ed.next;
			}
		}
		t.extractTree();
		return t;
	}


	// ---------------------------------------------------------------------------

	/**
	 * All-pairs shortest path algorithm of Floyd-Warshall.
	 * <p>side-effect: changes extra1
	 *
	 * <p>compelxity: \Theta( |V|^3 ) time, \Theta( |V|^2 ) space
	 */
	public static Matrix floyd_warshall(Graph g) {
		int offset = 0, n = g.numOfNodes();
		Matrix m1 = new Matrix(n,n, Double.POSITIVE_INFINITY);
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;)
			((Node) e.nextElement()).extra1 = offset++;

		// compute D^0
		for(int i = 0; i < n; i++) m1.set(i,i,0);
		for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge ed = (Edge) e.nextElement();
			m1.set( ed.n2.extra1, ed.n1.extra1, ed.weight);
		}

		for(int k = 0; k < n; k++) {
			for(int i = 0; i < n; i++) {
				for(int j = 0; j < n; j++) {
					double dik = m1.get(i,k);
					double dij = m1.get(i,j);
					double dkj = m1.get(k,j);
					m1.set( i,j, Math.min(dij, dik + dkj));
				}
			}
		}

		return m1;
	}


	// ---------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {

		Test.start("ShortestPath");

		// test Dijkstra and Bellman-Ford [fig. 24.6 in Cormens book, same as p596.pcg]
		Graph g = GraphIO.loadEdgeList("data/p596.pcg");
		Node n1 = g.findNode("V1");
		Node n2 = g.findNode("V2");
		Node n3 = g.findNode("V3");
		Node n4 = g.findNode("V4");
		Node n5 = g.findNode("V5");

		for(int test = 0; test < 2; test++) {
			if(test == 0) dijkstra(g, n1);
			else		bellman_ford(g, n1);

			Test.check( n1.extra3 == 0, "ShortestPath Dijkstra/Bellman-Ford (1)");
			Test.check( n1.extra3 < n4.extra3, "ShortestPath Dijkstra/Bellman-Ford (2)");
			Test.check( n4.extra3 < n5.extra3, "ShortestPath Dijkstra/Bellman-Ford (3)");
			Test.check( n5.extra3 < n2.extra3, "ShortestPath Dijkstra/Bellman-Ford (4)");
			Test.check( n2.extra3 < n3.extra3, "ShortestPath Dijkstra/Bellman-Ford (5)");
		}





		// test Floyd-Warshall
		g = GraphIO.loadEdgeList("data/p626.pcg");

		// test against some known points, requires the graph to be loaded IN A GIVEN ORDER :(
		Matrix mx = floyd_warshall(g);
		Test.check( mx.get(0,4) ==  8.0, "floyd_warshall (1)");
		Test.check( mx.get(2,1) == -4.0, "floyd_warshall (2)");
		Test.check( mx.get(4,3) == -2.0, "floyd_warshall (3)");
		Test.check( mx.get(2,0) == -3.0, "floyd_warshall (4)");

		Test.end();

	}

}
