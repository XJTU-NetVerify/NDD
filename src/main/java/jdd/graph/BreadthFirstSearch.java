
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * BFS algorithms.
 *
 * <p>
 * This class is the home of the Breadth First Search algorithm used in JDD
 */


public class BreadthFirstSearch {

	/**
	 * set the Node.extra1 variable to the BFS label.
	 * unreachable nodes will have Node.extra1 == -1
	 */
	public static void BFS_label_simple(Graph g, Node root) {
		AttributeExplorer.setAllNodesExtra1(g, -1);
		BFS_label_internal_one(g, root, g.isDirected(), 0);
	}


	/**
	 * set the Node.extra1 variable to the BFS label.
	 * unreachable nodes will be assigned BFS labels LARGER than the reachable ones.
	 * for example:
	 * <pre>
	 * BFS_complete( {a->b, a->c, d->x}, a) may result in
	 *  a < b < c < d < x
	 * the respective Node.extra1 value is
	 *  0 , 1 , 2 , 3 , 4
	 * </pre>
	 */
	public static void BFS_label_complete(Graph g, Node root) {
		AttributeExplorer.setAllNodesExtra1(g, -1);
		int count = BFS_label_internal_one(g, root, g.isDirected(), 0);

		// now, lets handle those we didnt handle the first time:
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;) {
			Node n = (Node) e.nextElement();
			if(n.extra1 < 0)  count = BFS_label_internal_one(g, n, g.isDirected(), count );
		}
	}

	// -------------------------------------------------------------

	/**
	 * internal function for labelling the components
	 *
	 * @returns total number of labelled nodes (new + already labelled)
	 * @param count where to start labelling.
	 */
	private static int BFS_label_internal_one(Graph g, Node root, boolean directed, int count) {
		int size = g.numOfNodes();
		RingQueue riq = new RingQueue(size);


		riq.enqueue(root);

		while(!riq.empty()) {
			Node n = (Node) riq.dequeue();
			n.extra1 = count++;

			for(Edge e = n.firstOut; e != null; e = e.next) {
				if(e.n2.extra1 == -1) {
					e.n2.extra1 = -2;
					riq.enqueue(e.n2);
				}
			}

			if(!directed) {
				for(Edge e = n.firstIn; e != null; e = e.prev) {
					if(e.n1.extra1 == -1) {
						e.n1.extra1 = -2;
						riq.enqueue(e.n1);
					}
				}
			}
		}
		return count;
	}

	// -----------------------------------------------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("BreadthFirstSearch");

		Graph g = new Graph(true);
		Node n1 = g.addNode();
		Node n2 = g.addNode();
		Node n3 = g.addNode();
		Node n4 = g.addNode();
		Node n5 = g.addNode();
		Node n6 = g.addNode();

		g.addEdge(n1, n2);
		g.addEdge(n2, n3);
		g.addEdge(n2, n4);


		for(int i = 0; i < 2; i++) {
			if(i == 0)	BFS_label_simple(g, n1);
			else				BFS_label_complete(g, n1);

			Test.checkEquality(n1.extra1, 0, "node 1");
			Test.checkEquality(n2.extra1, 1, "node 2");
			Test.checkGreaterThan(n3.extra1, n2.extra1, "node 3");
			Test.checkGreaterThan(n4.extra1, n2.extra1, "node 4");
			Test.checkInequality(n3.extra1, n4.extra1, "parallel nodes have not same label");

			if(i == 0){
				Test.checkEquality(n5.extra1, -1, "unreachable node should not be labelled (1)");
				Test.checkEquality(n6.extra1, -1, "unreachable node should not be labelled (2)");
			} else {
				int last = Math.max( n3.extra1, n4.extra1);
				Test.checkGreaterThan(n5.extra1, last, "unreachable node should be labelled (1)");
				Test.checkGreaterThan(n6.extra1, last, "unreachable node should be labelled (2)");
				Test.checkInequality(n5.extra1, n6.extra1, "unreachable nodes have not same label");
			}
		}


		Test.end();
	}

}
