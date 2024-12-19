
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * This class does some simple operation on the graph node/edge attributes
 *
 */

public class AttributeExplorer {

	// ----[ operations on EDGES ] ---------------------------------------
	public static void setEdgeFlag(Graph g, int f) {
		Enumeration it = g.getEdges().elements();
		while(it.hasMoreElements()) ((Edge) it.nextElement()).flags |= f;
	}

	public static void resetEdgeFlag(Graph g, int f) {
		f = ~f;
		Enumeration it = g.getEdges().elements();
		while(it.hasMoreElements()) ((Edge) it.nextElement()).flags &= f;
	}

	public static int countEdgeFlag(Graph g, int f) {
		int ret = 0;
		Enumeration it = g.getEdges().elements();
		while(it.hasMoreElements()) if( (((Edge) it.nextElement()).flags & f) == f) ret++;
		return ret;
	}

	public static double sumEdgeWeights(Graph g) {
		double ret = 0.0;
		Enumeration it = g.getEdges().elements();
		while(it.hasMoreElements()) {
			Edge e = (Edge) it.nextElement();
			ret += e.weight;
		}
		return ret;
	}

	public static double sumEdgeWeightsIf(Graph g, int flag) {
		double ret = 0.0;
		Enumeration it = g.getEdges().elements();
		while(it.hasMoreElements()) {
			Edge e = (Edge) it.nextElement();
			if((e.flags & flag)== flag) ret += e.weight;
		}
		return ret;
	}


	/** set attribute 1 for all edges to v [type int] */
	public static void setAllEdgesExtra1(Graph g, int v) {
		for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;) ((Edge) e.nextElement()).extra1 = v;
	}

	public static void changeAllEdgeFlags(Graph g, int set, int reset) {
		reset = ~reset;
		for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;) {
			Edge n = (Edge) e.nextElement();
			n.flags = (n.flags & reset) | set;
		}
	}


	// ----[ operations on NODES ] ---------------------------------------
	public static void setNodeFlag(Graph g, int f) {
		Enumeration it = g.getNodes().elements();
		while(it.hasMoreElements()) ((Node) it.nextElement()).flags |= f;
	}

	public static void resetNodeFlag(Graph g, int f) {
		f = ~f;
		Enumeration it = g.getNodes().elements();
		while(it.hasMoreElements()) ((Node) it.nextElement()).flags &= f;
	}

	public static int countNodeFlag(Graph g, int f) {
		int ret = 0;
		Enumeration it = g.getNodes().elements();
		while(it.hasMoreElements()) if( (((Node) it.nextElement()).flags & f) == f) ret++;
		return ret;
	}

	public static double sumNodeWeights(Graph g) {
		double ret = 0.0;
		Enumeration it = g.getNodes().elements();
		while(it.hasMoreElements()) {
			Node e = (Node) it.nextElement();
			ret += e.weight;
		}
		return ret;
	}

	public static double sumNodeWeightsIf(Graph g, int flag) {
		double ret = 0.0;
		Enumeration it = g.getNodes().elements();
		while(it.hasMoreElements()) {
			Node e = (Node) it.nextElement();
			if((e.flags & flag)== flag) ret += e.weight;
		}
		return ret;
	}

	public static void changeAllNodesFlag(Graph g, int set, int reset) {
		reset = ~reset;
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;) {
			Node n = (Node) e.nextElement();
			n.flags = (n.flags & reset) | set;
		}
	}


	/** set attribute 1 for all nodes to v [type int] */
	public static void setAllNodesExtra1(Graph g, int v) {
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;) ((Node) e.nextElement()).extra1 = v;
	}

	/** set attribute 3 for all nodes to v [type double] */
	public static void setAllNodesExtra3(Graph g, double v) {
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;) ((Node) e.nextElement()).extra3 = v;
	}

	/** set "extraindex" to comply with the order in the vector */
	public static void updateExtraIndex(Graph g) {
		int c = 0;
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;) ((Node) e.nextElement()).extraindex = c++;
	}

	/** find a node with the mathcing extra1 */
	public static Node findExtra1(Graph g, int v) {
		for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;) {
			Node n = (Node) e.nextElement();
			if(n.extra1 == v) return n;
		}
		return null;
	}

}

