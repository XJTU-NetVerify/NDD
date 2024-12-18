
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * <pre>
 * simple implementation of a graph: G = < V, E>
 *
 * to save memory, the adjacency list is inside each node.
 * Note that this gives a "directed" feel to the graph even if its is undirected.
 * the Vector "edges" contains all edges in case the the adjacency list is not enough
 *
 *
 * Note 1: if you remove a node in custom code, you must update "edges" and the adjacency lists!!
 * Note 2: duplicate nodes/edges are not allowed, and automatically removed by Graph
 * Note 3: self-loops are currently allowed, I dont know if they should be removed
 *
 * </pre>
 *
 */

public class Graph {
	protected Vector nodes, edges; /** V and E */
	protected int count_nodes, count_edges; /** used to create unique ID:s for each element */
	/* package */ boolean directed; /** G is a digraph */

	public Graph(boolean directed) {
		this.directed = directed;
		this.nodes = new Vector();
		this.edges = new Vector();

		// this is not a counter, its more of a label and can therefore start at 1
		this.count_nodes = 1;
		this.count_edges = 1;
	}

	// ----------------------------------------------------

	public Vector getNodes() { return nodes; }
	public Vector getEdges() { return edges; }

	public int numOfNodes() { return nodes.size(); }	// order of the graph
	public int numOfEdges() { return edges.size(); }

	public boolean isDirected() { return directed; }

	// ----------------------------------------------------------------

	public Node addNode(Node n) {
		Node n2 = findNode(n);
		if(n2 == null) {
			n.id = count_nodes++;
			nodes.add(n);
		}
		return n;
	}

	public Node addNode() {
		Node n = new Node(count_nodes++);
		nodes.add(n);
		return n;
	}

	/**
	 * makes a copy of this node which have the same attributes.
	 * "n" may come from another graph. It does not copy any edges!
	 * XXX: we may get duplicate names after this!
	 *
	 */
	public Node addCopy(Node n) {
		Node ret = addNode();
		copyAttibutes(ret, n);
		return ret;
	}

	/** make sure to has same attibutes as from. XXX: the node label may become duplicate by this operation */
	public void copyAttibutes(Node to, Node from) {
		// if( (n.flags & Node.FLAGS_LABLED) != 0) ret.setLabel( n.getLabel() );
		to.label  = from.label;
		to.flags  = from.flags;
		to.weight = from.weight;
	}

	// ----------------------------------------------------

	public Edge addEdge(Node n1, Node n2) {
		Edge e = findEdge(n1,n2);
		if(e == null) {
			e = new Edge(n1,n2, count_edges++);
			edges.add(e);
			e.next = n1.firstOut;	n1.firstOut = e;
			e.prev = n2.firstIn;	n2.firstIn  = e;
		}
		return e;
	}

	/** add an edge and make sure it has same attibutes as e */
	public Edge addCopy(Node n1, Node n2, Edge e) {
		Edge e2 = addEdge(n1,n2);
		copyAttibutes(e2,e);
		return e2;
	}

	/**
	 * make sure "to" has same attibutes as "from".
	 * XXX: does not check for duplicate names
	 */
	public void copyAttibutes(Edge to, Edge from) {
		to.flags  = from.flags;
		to.weight = from.weight;
		to.label  = from.label;
	}

	// ----------------------------------------------------
	public void removeEdge(Edge e) {
		edges.remove(e);
		removeForwardList(e, e.n1);
		removeBackwardList(e, e.n2);
	}

	/** remove edge "ed" from the forward chain of "n" */
	protected void removeForwardList(Edge ed, Node n) {
		while(n.firstOut != null && (n.firstOut == ed))	n.firstOut = n.firstOut.next;
		Edge e = n.firstOut, last = null;
		while(e != null) {
			if(e == ed) {
				last.next = e.next;
			} else last = e;
			e = e.next;
		}
	}

	/** remove edge "ed" from the backward chain of "n" */
	protected void removeBackwardList(Edge ed, Node n) {
		while(n.firstIn != null && (n.firstIn == ed))	n.firstIn = n.firstIn.prev;
		Edge e = n.firstIn, last = null;
		while(e != null) {
			if(e == ed) {
				last.prev = e.prev;
			} else last = e;
			e = e.prev;
		}
	}
	public void removeNode(Node n) {
		nodes.remove(n);

		Edge e = n.firstOut;
		while(e != null) { edges.remove(e); removeBackwardList(e, e.n2); e = e.next; }

		e = n.firstIn;
		while(e != null) {edges.remove(e); removeForwardList(e, e.n1); e = e.prev; }

		n.firstIn = n.firstOut = null;
	}

	// ----------------------------------------------------

	public void removeAllEdges()  {
		edges.removeAllElements();
		for (Enumeration e = nodes.elements() ; e.hasMoreElements() ;) {
			Node n = (Node) e.nextElement();
			n.firstIn = n.firstOut = null;
		}
	}

	public void removeAllNodes()  {
		edges.removeAllElements(); // <-- no nodes => no edges...
		nodes.removeAllElements();
	}

	// ----------------------------------------------------
	protected Edge findEdge(Node n1, Node n2) {
		for (Enumeration e = edges.elements() ; e.hasMoreElements() ;) {
			Edge edge = (Edge) e.nextElement();
			if(edge.n1 == n1 && edge.n2 == n2) return edge;
			if(! directed) {
				if(edge.n1 == n2 && edge.n2 == n1) return edge;
			}
		}
		return null;
	}
	protected Node findNode(Node n) {
		for (Enumeration e = nodes.elements() ; e.hasMoreElements() ;) {
			Node n2 = (Node) e.nextElement();
			if(n == n2) return n;
		}
		return null;
	}

	public Node findNode(String label) {
		for (Enumeration e = nodes.elements() ; e.hasMoreElements() ;) {
			Node n = (Node) e.nextElement();
			if(label.equals( n.getLabel())) return n;
		}
		return null;
	}

	// ----------------------------------------------------
	public void show() { GraphPrinter.show(this); }
	public void show(Node n) { GraphPrinter.show(n); }
	public void showDot(String fil) { GraphPrinter.showDot(fil, this, directed); }
	// ----------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("Graph");

		// ------------------- undirected graph  -------------------
		Graph g1 = new Graph(false);
		Node n11 = g1.addNode();
		Node n12 = g1.addNode();
		Node n13 = g1.addNode();

		g1.addEdge(n11, n12);
		g1.addEdge(n11, n12);		// duplicate
		g1.addEdge(n12, n11);		// duplicate
		Test.checkEquality(g1.numOfNodes(), 3, "numOfNodes (1)");
		Test.checkEquality(g1.numOfEdges(), 1, "numOfEdges (1)");



		g1.removeNode(n11);


		Test.checkEquality(g1.numOfNodes(), 2, "numOfNodes (2)");
		Test.checkEquality(g1.numOfEdges(), 0, "numOfEdges (2)");



		// ------------------- directed graph  -------------------
		Graph g2 = new Graph(true);

		Node n21 = g2.addNode();
		Node n22 = g2.addNode();
		Node n23 = g2.addNode();

		Edge e1 = g2.addEdge(n21, n22);
		Edge e2 = g2.addEdge(n21, n22);	// duplicate
		Edge e3 = g2.addEdge(n22, n21);	// NOT duplicate
		Test.check( e1 == e2 );
		Test.checkEquality(g2.numOfNodes(), 3, "numOfNodes (3)");
		Test.checkEquality(g2.numOfEdges(), 2, "numOfEdges (3)");


		g2.removeEdge(e3);
		g2.removeNode(n23);

		Test.checkEquality(g2.numOfNodes(), 2, "numOfNodes (4)");
		Test.checkEquality(g2.numOfEdges(), 1, "numOfEdges (4)");

		// check if removing of node n also removes edge e(x,n) and e from x.firsOut list
		Node n24 = g2.addNode();
		g2.addNode(n23); // put back n23
		Test.check(n23.firstOut == null, "n23 connected no where yet...");

		Edge e34 = g2.addEdge(n23, n24);
		Test.check(n23.firstOut == e34, "n23 connected to n24 now...");
		Test.check(n23.firstOut.n1 == n23, "linked list consitency (1)");
		Test.check(n23.firstOut.n2 == n24, "linked list consitency (2)");
		Test.checkEquality(g2.numOfNodes(), 4, "numOfNodes (5)");
		Test.checkEquality(g2.numOfEdges(), 2, "numOfEdges (5)");


		g2.removeNode(n24);
		Test.check(n23.firstOut == null, "n23 connected nowhere again...");
		Test.checkEquality(g2.numOfNodes(), 3, "numOfNodes (6)");
		Test.checkEquality(g2.numOfEdges(), 1, "numOfEdges (6)");

		// check removing node again, this time another edge before us and one after us
		Test.check(n21.firstOut != null && n21.firstOut.next == null , "linked list consitency (3)");
		Node n25 = g2.addNode();
		Edge e23 = g2.addEdge(n22, n23);
		Edge e25 = g2.addEdge(n22, n25);
		Test.checkEquality(g2.numOfEdges(), 3, "numOfEdges (7)");
		g2.removeNode(n23); // now only e23 should be removed
		Test.check(n23.firstIn == null, "firstIn (1)");
		Test.check(n25.firstIn == e25, "firstIn (2)");
		Test.checkEquality(g2.numOfEdges(), 2, "numOfEdges (8)");
		Test.end();
	}
}

