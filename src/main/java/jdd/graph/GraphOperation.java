
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * some simple operations on graphs
 *
 * @see AttributeExplorer
 * @see BreadthFirstSearch
 * @see DepthFirstSearch
 * @see TopologicalSort
 * @see SimpleAlgorithms
 */

public class GraphOperation {


	/**
	 * Make a complete copy of this graph. copies most (all?) attributes along
	 *
	 */
	public static Graph clone(Graph g) {
		Graph ret = new Graph(g.directed);
		HashMap hm = new HashMap();

		for(Enumeration  it = g.getNodes().elements(); it.hasMoreElements() ; ) {
			Node n1 = (Node) it.nextElement();
			Node n2 = ret.addCopy(n1);
			hm.put(n1, n2);
		}


		for (Enumeration it = g.getEdges().elements() ; it.hasMoreElements() ;) {
			Edge e1 = (Edge) it.nextElement();
			ret.addCopy((Node) hm.get(e1.n1), (Node) hm.get(e1.n2), e1);
		}

		return ret;
	}

	/**
	 * <tt>Haj�s's construction</tt>: Let G1 and G2 be already disjoint graphs with edges x1y1<br>
	 * and x2y2. Remove x1y1 and x2y2, identify x1 and 2 and join y1 and y2 by a new edge.<br>
	 *<br>
	 * This is interesting due to the Hajs Theorem from 1961:<br><i>
	 * A graph has chromatic number at least k iff it contains a Hajs-k-constructible subgraph.<br>
	 * Every k-ciritical graph is Hajs-k-constructible
	 * </i><br>
	 * <br><br>
	 * (text stolen from Anders Grevette's great B.Sc.thesis at Chalmers).<br>
	 * <br><br>
	 * XXX: I am not sure about the implementation :(
	 */
	public static Graph hajos_construction(Graph g1, Node x1, Node y1, Graph g2, Node x2, Node y2) {
		Graph ret = new Graph( g1.isDirected());
		int n = g1.numOfNodes();
		int m = g2.numOfNodes();
		Node [] nodes = new Node[ n + m];

		int i = 0;
		for(Enumeration  it = g1.getNodes().elements(); i < n; i ++) {
			Node n2 = (Node) it.nextElement();
			nodes[i] = ret.addCopy(n2);
			n2.extra1 = i;
		}

		for(Enumeration  it = g2.getNodes().elements(); i < n+m; i ++) {
			Node n2 = (Node) it.nextElement();
			nodes[i] = ret.addCopy(n2);
			n2.extra1 = i;
		}

		for (Enumeration e = g1.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge edge = (Edge) e.nextElement();
			Edge edge2 = ret.addEdge( nodes[ edge.n1.extra1], nodes[ edge.n2.extra1]);
			if( (edge.flags & Edge.FLAGS_WEIGTHED) != 0) edge2.setLabel( edge.getLabel() );
			edge2.flags = edge.flags;
			edge2.weight = edge.weight;
		}
		for (Enumeration e = g2.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge edge = (Edge) e.nextElement();
			Edge edge2 = ret.addEdge( nodes[ edge.n1.extra1], nodes[ edge.n2.extra1]);
			if( (edge.flags & Edge.FLAGS_WEIGTHED) != 0) edge2.setLabel( edge.getLabel() );
			edge2.flags = edge.flags;
			edge2.weight = edge.weight;
		}

		disconnect(ret, nodes[ x1.extra1], nodes[ y1.extra1]);
		disconnect(ret, nodes[ x2.extra1], nodes[ y2.extra1]);

		contraction(ret, nodes[ x1.extra1], nodes[ x2.extra1]);
		connection(ret, nodes[ y1.extra1], nodes[ y2.extra1]);
		return ret;
	}



	/**
	 * Given G and two nodes n1 and n2, it modifies G to contain no connections between <br>
	 * n1 to n2.<br>
	 */
	public static void disconnect(Graph g, Node n1, Node n2) {
		Edge e1 = g.findEdge(n1,n2);
		if(e1 != null)	g.removeEdge(e1);
		e1 = g.findEdge(n2,n1);
		if(e1 != null)	g.removeEdge(e1);
	}


	/**
	 * Given G and two nodes n1 and n2, it modifies G to contain a connection from <br>
	 * n1 to n2 (and from n2 to n1 if its a digraph).<br>
	 */
	public static void connection(Graph g, Node n1, Node n2) {
		g.addEdge(n1,n2);
		if(g.isDirected()) g.addEdge(n2,n1);
	}

	/**
	 * Given G and two nodes n1 and n2, it makes n1 and n2 to a single node.<br>
	 * Note that it take cares about the incomng and outgoing edges.
	 */
	public static void contraction(Graph g, Node n1, Node n2) {
		if(n1 == n2) return;
		Vector saved_in = new Vector();
		Vector saved_out = new Vector();

		Edge ed = n1.firstOut;
		while(ed != null) { if(ed.n2 != n1) saved_out.add(ed); ed = ed.next; }
		ed = n1.firstIn;
		while(ed != null) {  if(ed.n1 != n1) saved_in.add(ed);ed = ed.prev; }
		g.removeNode(n1);

		JDDConsole.out.println("Removing " +  n1.getLabel() );
		JDDConsole.out.println("Connecting to  " +  n2.getLabel() );

		for(Enumeration  it = saved_in.elements(); it.hasMoreElements();) {
			Edge e = (Edge) it.nextElement();
			Edge e2 = g.addEdge( e.n1, n2);
			e2.flags = e.flags;
			e2.weight = e.weight;
			e2.setLabel( e.getLabel() );
		}

		for(Enumeration  it = saved_out.elements(); it.hasMoreElements();) {
			Edge e = (Edge) it.nextElement();
			Edge e2 = g.addEdge( n2, e.n2);
			e2.flags = e.flags;
			e2.weight = e.weight;
			e2.setLabel( e.getLabel() );
		}
	}
	/**
	 * computes G1 + G2 = < V1 \cup V2, E1 \cup E2>. <br>
	 * side-effects: Node.extra1 is changed <br>
	 *
	 */
	public static Graph union(Graph g1, Graph g2) {	return add_graphs(g1,g2, false);	}

	/**
	 * computes G1 \cup G2 = < V1 \cup V2, E1 \cup E2 \cup V1xV2>. <br>
	 * side-effects: Node.extra1 is changed<br>
	 */
	public static Graph join(Graph g1, Graph g2) {	return add_graphs(g1,g2, true);}

	// ----------------------------------------------------------------------------

	/** internal routine */
	private static Graph add_graphs(Graph g1, Graph g2, boolean interconnect) {
		Graph ret = new Graph( g1.isDirected());
		int n = g1.numOfNodes();
		int m = g2.numOfNodes();
		Node [] nodes = new Node[ n + m];

		int i = 0;
		for(Enumeration  it = g1.getNodes().elements(); i < n; i ++) {
			Node n2 = (Node) it.nextElement();
			nodes[i] = ret.addCopy(n2);
			n2.extra1 = i;
		}

		for(Enumeration  it = g2.getNodes().elements(); i < n+m; i ++) {
			Node n2 = (Node) it.nextElement();
			nodes[i] = ret.addCopy(n2);
			n2.extra1 = i;
		}

		for (Enumeration e = g1.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge edge = (Edge) e.nextElement();
			Edge edge2 = ret.addEdge( nodes[ edge.n1.extra1], nodes[ edge.n2.extra1]);
			if( (edge.flags & Edge.FLAGS_WEIGTHED) != 0) edge2.setLabel( edge.getLabel() );
			edge2.flags = edge.flags;
			edge2.weight = edge.weight;
		}
		for (Enumeration e = g2.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge edge = (Edge) e.nextElement();
			Edge edge2 = ret.addEdge( nodes[ edge.n1.extra1], nodes[ edge.n2.extra1]);
			if( (edge.flags & Edge.FLAGS_WEIGTHED) != 0) edge2.setLabel( edge.getLabel() );
			edge2.flags = edge.flags;
			edge2.weight = edge.weight;
		}

		if(interconnect) {
			for( i = 0; i < n; i++)
				for(int j = 0; j < m; j++)
					ret.addEdge(nodes[i], nodes[n+j] );

		}
		return ret;
	}


	// -------------------------------------------------------------------

	/**
	 * find heaviest node, that is, a node with most arcs connected (in/out) to it.
	 * if use_weights is true, then we sum the weigts instead of just counting arcs.
	 * @see #lightNode
	 */

	public static Node heavyNode(Graph g, boolean use_weights) {
		Node ret = null;
		double best = Double.NEGATIVE_INFINITY;
		for(Enumeration  it = g.getNodes().elements(); it.hasMoreElements(); ) {
			Node n = (Node) it.nextElement();
			double sum = 0;
			for(Edge e = n.firstOut; e != null; e = e.next) sum += use_weights ? e.weight : 1;
			for(Edge e = n.firstIn; e != null; e = e.prev) sum += use_weights ? e.weight : 1;
			if(sum > best) { ret = n; best = sum; }
		}
		return ret;
	}


	/**
	 * find lightest node, that is, a node with least arcs connected (in/out) to it.
	 * if use_weights is true, then we sum the weigts instead of just counting arcs.
	 * @see #heavyNode
	 */

	public static Node lightNode(Graph g, boolean use_weights) {
		Node ret = null;
		double best = Double.POSITIVE_INFINITY;
		for(Enumeration  it = g.getNodes().elements(); it.hasMoreElements(); ) {
			Node n = (Node) it.nextElement();
			double sum = 0;
			for(Edge e = n.firstOut; e != null; e = e.next) sum += use_weights ? e.weight : 1;
			for(Edge e = n.firstIn; e != null; e = e.prev) sum += use_weights ? e.weight : 1;
			if(sum < best) { ret = n; best = sum; }
		}
		return ret;
	}

	// --------------------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("GraphOperation");

		Graph k2 = Factory.complete(2);
		Graph k3 = Factory.complete(3);

		Graph g1 = union(k2,k3);
		Graph g2 = join(k2,k3);

		Test.checkEquality( SimpleAlgorithms.number_of_islands(g1), 2, "union => not connected");
		Test.checkEquality( SimpleAlgorithms.number_of_islands(g2), 1, "joing => still connected");

		Test.checkEquality( g1.numOfNodes(),  k2.numOfNodes() + k3.numOfNodes(), "g1 |V|");
		Test.checkEquality( g2.numOfNodes(),  k2.numOfNodes() + k3.numOfNodes(), "g2 |V|");

		Test.checkEquality( g1.numOfEdges(),  k2.numOfEdges() + k3.numOfEdges(), "g1 |E|");
		Test.checkEquality( g2.numOfEdges(),  k2.numOfEdges() + k3.numOfEdges() + k2.numOfNodes() * k3.numOfNodes(), "g2 |E|");


		// find heaviest node
		Graph g = new Graph(false);
		Node n1 = g.addNode();
		Node n2 = g.addNode();
		Node n3 = g.addNode();
		g.addEdge(n1,n2);
		g.addEdge(n1,n3);
		Node tmp = lightNode(g, false);
		Test.check( heavyNode(g, false) == n1, "havy node, no weights");
		Test.check( tmp == n2 || tmp == n3, "light node, no weights");

		Node n4 = g.addNode();
		Edge e = g.addEdge(n4,n3); e.setWeight(1000);
		e = g.addEdge(n4,n2); e.setWeight(1000);
		Test.check( heavyNode(g, true) == n4, "havy node, no weights");
		Test.check( lightNode(g, true) == n1, "light node, no weights");

		Test.end();
	}

}
