
package jdd.graph;

import jdd.util.*;
import jdd.util.math.*;


import java.util.*;

/**
 * some very simple graph "algorithms" are gathered here.
 *
 *
 * @see GraphOperation
 */

public class SimpleAlgorithms {


	/**
	 * divide into subgraphs: divide G into a set of graphs, that are
	 * connected and maximal. In other words, put each "island" in the graph
	 * in a separate graph.
	 */
	 public static Vector divide(Graph g) {
		int size = g.numOfNodes();

		HashMap on = new HashMap(); // map old node -> new node
		Vector ret = new Vector();	// set of new (sub)graphs
		Vector edges = new Vector(); // list of edges to be copied

		Node [] stack = new Node[size]; // working stack
		int tos = 0;

		HashSet hs = new HashSet(g.getNodes()); // remaining nodes

		while(!hs.isEmpty()) {

			on.clear();
			edges.clear();

			// create a new graph
			Graph g2 = new Graph( g.isDirected() );
			ret.add(g2);

			// pick one node to start with
			Node n = (Node) hs.iterator().next();
			hs.remove(n);
			stack[0] = n; tos = 1; // and put it on the stack

			while(tos > 0) {
				Node node = stack[--tos];
				Node new_node = g2.addCopy(node);
				on.put( node, new_node);

				Edge e = node.firstOut;
				while(e != null) {
					if(hs.remove(e.n2))  stack[tos++] = e.n2;
					edges.add(e);
					e = e.next;
				}

				e = node.firstIn;
				while(e != null) {
					if(hs.remove(e.n1))  stack[tos++] = e.n1;
					edges.add(e);
					e = e.prev;
				}
			}

			// now add edges:
			for (Enumeration it = edges.elements() ; it.hasMoreElements() ;) {
				Edge e = (Edge) it.nextElement();
				g2.addCopy( (Node) on.get(e.n1), (Node) on.get(e.n2), e);
			}
		}
		return ret;
	 }

	/**
	 * computes the "level-n" degree of a node.
	 * <p>Needs a some sort of set to store its values in
	 */
	public static int level_n_degree(Node nod, int n, AbstractSet set) {
		set.clear();
		add_adjacent_rec(set, nod, n);
		set.remove(nod);
		return set.size();
	}
	private static void add_adjacent_rec(AbstractSet set, Node n, int depth) {
		if(depth <= 0) return;

		for(Edge e = n.firstOut; e != null; e = e.next)
			if(set.add(e.n2)) add_adjacent_rec(set, e.n2, depth-1);

		for(Edge e = n.firstIn; e != null; e = e.prev)
			if(set.add(e.n1)) add_adjacent_rec(set, e.n1, depth-1);
	}

	/**
	 * returns true if g is bi-partie<br>
	 * <p>A bi-parite graph is a graph where V can be divided into V1 and V2 where there are now
	 * edged with both ends in either V1 or V2.<br>
	 * <p><b>Note:</b> This algorithm ignores self-loops!<br>
	 * <p>side-effect: extra1 is changed<br>
	 * <p>complexity O( |E| )<br>
	 */
	public static boolean is_bipartie(Graph g) {
		AttributeExplorer.setAllNodesExtra1(g,-1);

		for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge ed = (Edge) e.nextElement();
			if(ed.n1 ==  ed.n2) { // self-loop, ignore it
			} else if(ed.n1.extra1 ==  -1 && ed.n2.extra1 == -1) { // both clean
				ed.n1.extra1 = 0;
				ed.n2.extra1 = 1;
			} else if(ed.n1.extra1 == -1)  ed.n1.extra1 = 1^ed.n2.extra1;
			else if(ed.n2.extra1 == -1)  ed.n2.extra1 = 1^ed.n1.extra1;
			else if( (ed.n2.extra1 ^ed.n1.extra1) == 0) return false;
		}
		return true;
	}


	/**
	 * is this graph connected??
	 * @return true if g is connected<br>
	 * <p>side-effect: extra1 is changed<br>
	 */
	public static boolean is_connected(Graph g) {
		int n = g.numOfNodes();
		if(n == 0) return false; // conected graphs are non-empty!


		/*
		// the worlds most stupid connectivity test: (note that we temporary make it undirected)
		boolean save = g.directed;
		g.directed  = false;
		BitMatrix bm = transistive_closure(g);
		boolean ret = (bm.sum() == n * n);
		g.directed = save;
		return ret;
		*/

		return number_of_islands(g) <= 1;
	}


	/**
	 * is this graph a tree??
	 * @return true if g is a tree<br>
	 * <p>side-effect: extra1 is changed<br>
	 *
	 * <p>XXX: is this code really correct???
	 */
	public static boolean is_tree(Graph g) {
		if(g.numOfNodes() != g.numOfEdges() + 1) return false;
		return is_connected(g);
	}

	// --------------------------------------------------------

	/**
	 * compute transistive closure of a graph<br>
	 *
	 * <p>side-effect: extra1 is changed<br>
	 * @return matrix m s.t. m(i,j) = 1 if (i,j) are connected
	 *
	 */

	 public static BitMatrix transistive_closure(Graph g) {
		int offset = 0, n = g.numOfNodes();
		BitMatrix m = new BitMatrix(n,n);
		m.clear();

		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;)
			((Node) e.nextElement()).extra1 = offset++;

		for(int i = 0; i < n; i++) 	m.set(i,i);
		for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge ed = (Edge) e.nextElement();
			m.set( ed.n2.extra1, ed.n1.extra1);
			if(!g.isDirected())	m.set( ed.n1.extra1, ed.n2.extra1);
		}


		for(int k = 0; k < n; k++) {
			for(int i = 0; i < n; i++) {
				for(int j = 0; j < n; j++) {
					if(m.get(i,k) && m.get(k,j)) m.set(i,j);
				}
			}
		}

		return m;
	}

	// -------------------------------------------------------------------------------
	/**
	 * max number of islands (maximally connected subgraphs, also known as COMPONENTS).
	 * equals one if the graph is connected
	 * <p> Changes Node.extra1
	 */
	public static int number_of_islands(Graph g) {
		int offset = 0, n = g.numOfNodes();
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;)
			((Node) e.nextElement()).extra1 = offset++;


		DisjointSet ds = new DisjointSet(n);
		for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge ed = (Edge) e.nextElement();
			ds.union( ds.find(ed.n1.extra1) , ds.find(ed.n2.extra1) );
		}

		return ds.classes();

	}
	// --------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("SimpleAlgorithms");

		// check connectivity
		Graph g = new Graph(false);
		Test.checkEquality(is_connected(g), false, "empty graphs not connected");
		Node n1 = g.addNode();
		Node n2 = g.addNode();
		Node n3 = g.addNode();
		g.addEdge(n1,n2);
		Test.checkEquality(is_connected(g), false, "not connected yet");
		g.addEdge(n1,n3);
		Test.checkEquality(is_connected(g), true, "connected now");


		Test.checkEquality(is_connected(Factory.complete(5)) , true, "A complete graphs is always connected");

		// test trees:
		g = Factory.tree(3,4);
		Test.checkEquality(is_connected(g) , true, "A tree connected");
		Test.checkEquality(is_tree(g) , true, "A tree is a tree :)");


		//test bi-partie detection
		Test.check( is_bipartie( Factory.circle(999)) == false, "circle of odd length is not bi-partie");
		Test.check( is_bipartie( Factory.circle(100)) == true, "circle of even length is  bi-partie");
		Test.check( is_bipartie( Factory.path(9)) == true, "a path is always bi-partie");
		Test.check( is_bipartie( Factory.complete_bipartie(4,4)) == true, "K4,4 is bi-partie");
		Test.check( is_bipartie( Factory.complete_bipartie(2,500)) == true, "K2,500 is bi-partie");
		Test.check( is_bipartie( Factory.complete(2)) == true, "K2 is bi-partie");
		Test.check( is_bipartie( Factory.complete(3)) == false, "K3 is not bi-partie");
		Test.check( is_bipartie( Factory.complete(4)) == false, "K4 is not bi-partie");
		Test.check( is_bipartie( Factory.tree(4,4)) == true, "a tree is bi-partie");
		Test.check( is_bipartie( Factory.permutation(4)) == true, "a permutation tree is bi-partie");



		// test divide:
		g = new Graph(false);
		Node d1 = g.addNode(); d1.setLabel("d1");
		Node d2 = g.addNode(); d2.setLabel("d2");
		Node d3 = g.addNode(); d3.setLabel("d3");
		Node d4 = g.addNode(); d4.setLabel("d4");
		g.addEdge(d1,d2);

		Vector v = divide(g);
		Test.checkEquality(v.size(), 3, "divide: 3 subgraphs");
		int nc = 0, ec = 0;
		for (Enumeration e = v.elements() ; e.hasMoreElements() ;) {
			Graph g2 = (Graph)e.nextElement();
			nc += g2.numOfNodes();
			ec += g2.numOfEdges();
		}
		Test.checkEquality(g.numOfNodes(), nc, "divide: correct num of nodes");
		Test.checkEquality(g.numOfEdges(), ec, "divide: corrent num of edges");


		Test.end();
	}
}
