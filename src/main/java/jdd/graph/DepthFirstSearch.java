
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * DFS algorithms.
 *
 * <p>
 * This class is the home of the Depth First Search algorithm
 */

public class DepthFirstSearch {

	private static int dfs_time; // internal time variable

	/**
	 * Depth first search of a directed unweighted graph
	 * Changes: Node.extra1.
	 * Output: Node.extra3 and Node.extra4 are the discovery and finishing time
	 *         (d[v] and f[v] in literature)
	 */
	public static void DFS(Graph g) {

		AttributeExplorer.setAllNodesExtra1(g, 0); // 0 = white
		dfs_time = 0;
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;) {
			Node n = (Node) e.nextElement();
			if(n.extra1 == 0) DFS_visit_internal(n);
		}
	}

	/** intenal DFS recursive funcion */
	private static void DFS_visit_internal(Node n) {
		n.extra3 = ++dfs_time;
		n.extra1 = 1; // gray

		for(Edge e = n.firstOut; e != null; e = e.next) {
			Node n2 = e.n2;
			if(n2.extra1 == 0)  DFS_visit_internal(n2);
		}

		n.extra1 = 2; // black
		n.extra4 = ++dfs_time;
	}

	// ----------------------------------------------------------------------------

	/**
	 * set the Node.extra1 variable to the DFS label.
	 * unreachable nodes will have Node.extra1 == -1
	 */
	public static void DFS_label_simple(Graph g, Node root) {
		AttributeExplorer.setAllNodesExtra1(g, -1);
		DFS_label_internal(g, root, 0, g.isDirected() );
	}

	/**
	 * set the Node.extra1 variable to the DFS label.
	 * unreachable nodes will be assigned labels larger than any reachable ones
	 */
	public static void DFS_label_complete(Graph g, Node root) {
		boolean directed = g.isDirected();
		AttributeExplorer.setAllNodesExtra1(g, -1);
		int count = DFS_label_internal(g, root, 0, directed );

		// find those without any labels
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;) {
			Node n = (Node) e.nextElement();
			if(n.extra1 < 0) count = DFS_label_internal(g, n, count, directed);
		}
	}


	private static int DFS_label_internal(Graph g, Node root, int count, boolean directed) {
		Node [] stack = new Node[ g.numOfNodes() ];
		int tos = 0;

		stack[tos++] = root;

		while(tos > 0) {
			Node n = stack[--tos];
			n.extra1 = count++;

			for(Edge e = n.firstOut; e != null; e = e.next) {
				if(e.n2.extra1 == -1) {
					e.n2.extra1 = -2;
					stack[tos++] = e.n2;
				}
			}

			if(!directed) {
				for(Edge e = n.firstIn; e != null; e = e.prev) {
					if(e.n1.extra1 == -1) {
						e.n1.extra1 = -2;
						stack[tos++] = e.n1;
					}
				}
			}
		}

		return count;
	}



	// -----------------------------------------------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("DepthFirstSearch");

		// XXX: this is the same as the bfs test. we should write a more dfs-specific test!
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
			if(i == 0)	DFS_label_simple(g, n1);
			else				DFS_label_complete(g, n1);

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
