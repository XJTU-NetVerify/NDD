
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * TopologicalSort, based on DFS.
 * <p>see Cormen, page 550.
 */
public class TopologicalSort {

	/**
	 * The topological sort algorithm.
	 * <p>NOTE: g must be a DAG!!
	 *
	 * <p>Changes Node.extra1/3/4
	 *
	 */
	public static Node [] sort(Graph g) {
		DepthFirstSearch.DFS(g);
		TreeSet ts = new TreeSet(NodeExtra4Comparator.nodeExtra4Comparator);

		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;)
			ts.add( (Node) e.nextElement());

		Node [] nodes = new Node[g.numOfNodes() ];
		int offset = 0;
		while(!ts.isEmpty() ) {
			Node n = (Node) ts.last();
			ts.remove(n);
			nodes[offset++] = n;
		}
		return nodes;
	}

	// ---------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("TopologicalSort");
		Graph g = GraphIO.loadXML("data/Bumstead.xml");
		Node []nodes = TopologicalSort.sort(g);

		// what kind of test is this :(
		Node last = nodes[ nodes.length -1];
		Test.check( last.label.equals("jacket") || last.label.equals("watch"), "Last element correct");


		Test.end();
	}
}
