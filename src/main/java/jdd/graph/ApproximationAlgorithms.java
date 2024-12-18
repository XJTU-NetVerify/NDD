
package jdd.graph;

import jdd.util.*;
import jdd.util.math.*;

import java.util.*;


/**
 * contains a set of approximative algorithms.
 *
 * <p>Vertex cover: note that there is no efficient algorithm to determine whether
 * approx_vertex_cover_ED or approx_vertex_cover_MDG are better for a graph :(
 *
 */

public class ApproximationAlgorithms {


	// TODO:
	// approx_tsp_tour [p 1028]
	// greedy_set_cover [p 1035]


	/**
	 * approximative vertex cover: ploynomial 2-approx of an NP-complete problem
	 * Also known as "Edge Detection Heuristic (ED)"
	 *
	 * <p>side-effects: Node.flags:FLAGS_MARKED and Edge.extra1
	 * <p>output: Node.flags:FLAGS_MARKED set if that node is in the cover
	 */
	public static void approx_vertex_cover_ED(Graph g) {
		AttributeExplorer.changeAllNodesFlag(g, Node.FLAGS_NONE, Node.FLAGS_MARKED); // removed old markings

		AttributeExplorer.setAllEdgesExtra1(g, 1); // all avialable

		for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge ed = (Edge) e.nextElement();
			if(ed.extra1 == 1) {
				vc_add_node(ed.n1);
				vc_add_node(ed.n2);
			}
		}
	}

	private static int vc_add_node(Node n) { // INTERNAL: remove every edge from n, mark n
		int changed = 0;
		if( (n.flags & Node.FLAGS_MARKED) == 0) {
			n.flags |= Node.FLAGS_MARKED;
			Edge e = n.firstOut;
			while(e != null) {	if(e.extra1!= 0) { changed++; e.extra1 = 0; } e = e.next;	}
			e = n.firstIn;
			while(e != null) {	if(e.extra1!= 0) { changed++; e.extra1 = 0; } e = e.prev;	}
		}
		return changed;
	}

	// -------------------------------------------------------------------------

	/**
	 * approximative vertex cover: worst-case lg |V| if an NP-complete problem
	 * Also known as "Maximum-Degree Greedy (MDG)"
	 *
	 * <p>side-effects: Node.flags:FLAGS_MARKED, Node.extra1  and Edge.extra1
	 * <p>output: Node.flags:FLAGS_MARKED set if that node is in the cover
	 */
	public static void approx_vertex_cover_MDG(Graph g) {
		AttributeExplorer.changeAllNodesFlag(g, Node.FLAGS_NONE, Node.FLAGS_MARKED); // removed old markings

		TreeSet ts = new TreeSet(NodeExtra1Comparator.nodeExtra1Comparator);
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;) {
			Node n = (Node) e.nextElement();
			n.flags &= ~Node.FLAGS_MARKED;
			n.extra1 = n.getDegree();
			ts.add(n);
		}

		AttributeExplorer.setAllEdgesExtra1(g, 1); // all avialable
		int count = g.numOfEdges(); // how many available??
		while(count > 0 && !ts.isEmpty()) {
			Node u = (Node) ts.first();
			ts.remove(u);
			count -= vc_add_node(u);
		}
	}


	// -------------------------------------------------------------------
	private static void test_is_vertex_cover(Graph g) {
		for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge ed = (Edge) e.nextElement();
			Test.check( (ed.n1.flags & Node.FLAGS_MARKED) != 0 ||
						(ed.n2.flags & Node.FLAGS_MARKED) != 0, "Edge " + ed.getLabel() + " covered");
		}

	}
	// -------------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("ApproximationAlgorithms");

		// XXX: how do we now the cover is not all nodes [it would still pass the test] ??

		Graph g = GraphIO.loadEdgeList("data/p1025.pcg");
		approx_vertex_cover_ED(g);		test_is_vertex_cover(g);
		approx_vertex_cover_MDG(g);		test_is_vertex_cover(g);

		// a complete graph
		g = Factory.complete(5);
		approx_vertex_cover_ED(g);		test_is_vertex_cover(g);
		approx_vertex_cover_MDG(g);		test_is_vertex_cover(g);

		Test.end();
	}

}
