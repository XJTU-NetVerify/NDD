
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * produces some well-known simple graph structures for the user.
 *
 * <p>
 * This class can be used to quickly generate simple test-cases for your algorithms
 *
 */

public class Factory {

	/** returns a complete graph of degree "nodes", "K_nodes"  in litterateur */
	public static Graph complete(int nodes) {
		Node [] ns = new Node[nodes];
		Graph g = new Graph(false);
		for(int i = 0; i < nodes; i++)  ns[i] = g.addNode();
		for(int i = 0; i < nodes; i++)
			for(int j = i +1; j < nodes; j++)
				g.addEdge( ns[i], ns[j] );

		return g;
	}

	// ------------------------------------------------------------------------
	/**
	 * returns a complete bipartie graph (S_n,S_m,E) = "K_n,m"<br>
	 * (where S_n and S_m are stable sets of size n and m and<br>
	 *            for each x \in S_n, y \in S_m : (x,y) \in E  )<br>
	 */
	public static Graph complete_bipartie(int n,int m) {
		Node [] ns = new Node[n+m];
		Graph g = new Graph(false);
		for(int i = 0; i < ns.length; i++)  ns[i] = g.addNode();

		for(int i = 0; i < n; i++)
			for(int j = 0; j < m; j++)
				g.addEdge( ns[i], ns[n+j] );
		return g;
	}
	// ------------------------------------------------------------------------

	/** returns a complete path P_n */
	public static Graph path(int n) { return sequence(n,false); }

	/** returns a circle [aka closed path] C_n */
	public static Graph circle(int n) { return sequence(n,true); }

	/** internal routine used by path and circle */
	private static Graph sequence(int n, boolean loop) {
		Node [] ns = new Node[n];
		Graph g = new Graph(false);
		for(int i = 0; i < n; i++)  ns[i] = g.addNode();
		for(int i = 1; i < n; i++)	g.addEdge( ns[i-1], ns[i] );

		if(loop) g.addEdge(ns[0], ns[n-1]);
		return g;
	}


	// ------------------------------------------------------------------------

	/**
	 * returns a tree of given depth and num of branches per level<br>
	 * sets FLAGS_ROOT and FLAGS_TERMINAL in the graph.<br>
	 */
	public static Graph tree(int depth, int branches) {
		Graph g = new Graph(true);
		if(depth > 0) {
			Node root = g.addNode();
			root.flags |= Node.FLAGS_ROOT;
			tree_rec(g, root, depth-1, branches);
		}
		return g;
	}
	private static void tree_rec(Graph g, Node n, int depth, int branches) {
		if(depth == 0) {
			n.flags |= Node.FLAGS_TERMINAL;
			return;
		}
		for(int i = 0; i < branches; i++) {
			Node n2 = g.addNode();
			g.addEdge(n, n2);
			tree_rec(g,n2, depth-1, branches);
		}
	}

	// ------------------------------------------------------------------------

	/**
	 * returns a permutation tree of given possible choices<br>
	 * sets FLAGS_ROOT and FLAGS_TERMINAL in the graph.<br>
	 */
	public static Graph permutation(int choices) { return permutation(choices,choices); }

	/**
	 * returns a permutation tree of [at most] given depth and num of possible choices<br>
	 * sets FLAGS_ROOT and FLAGS_TERMINAL in the graph.<br>
	 */
	public static Graph permutation(int depth, int choices) {
		Graph g = new Graph(true);
		if(depth > 0) {
			Node root = g.addNode();
			root.flags |= Node.FLAGS_ROOT;
			permutation_rec(g, root, depth-1, choices);
		}
		return g;
	}
	private static void permutation_rec(Graph g, Node n, int depth, int choices) {
		if(depth == 0) {
			n.flags |= Node.FLAGS_TERMINAL;
			return;
		}
		for(int i = 0; i < choices; i++) {
			Node n2 = g.addNode();
			Edge e = g.addEdge(n, n2);
			tree_rec(g,n2, depth-1, choices-1);
		}
	}

	// -------------------------------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("Factory");

		// complete graphs
		Graph c4 = complete(4);
		Test.checkEquality( c4.numOfEdges(), ( c4.numOfNodes() * (c4.numOfNodes() -1)) / 2, "complete graph (1)");

		Graph c7 = complete(7);
		Test.checkEquality( c7.numOfEdges(), ( c7.numOfNodes() * (c7.numOfNodes() -1)) / 2, "complete graph (2)");

		Graph c32 = complete(32);
		Test.checkEquality( c32.numOfEdges(), ( c32.numOfNodes() * (c32.numOfNodes() -1)) / 2, "complete graph (3)");

		// trees:
		Graph t32 = tree(3,2);
		Test.checkEquality( t32.numOfEdges(), 1 * 2 * 3, "tree (1)");
		Test.checkEquality( t32.numOfNodes(), 1 + t32.numOfEdges(), "tree (2)");

		Graph t53 = tree(5,3);
		Test.checkEquality( t53.numOfEdges(), 120, "tree (3)");
		Test.checkEquality( t53.numOfNodes(), 1 + t53.numOfEdges() , "tree (4)");


		Graph p35 = permutation(3,5);
		Test.checkEquality( p35.numOfNodes(), 1 + p35.numOfEdges(), "permutation (1)");
		Test.checkEquality( p35.numOfEdges(), 25, "permutation (2)");

		Graph p53 = permutation(5, 3);
		Test.checkEquality( p53.numOfNodes(), 1 + p53.numOfEdges(), "permutation (3)");
		Test.checkEquality( p53.numOfEdges(), 45, "permutation (4)");

		Test.end();
	}
}
