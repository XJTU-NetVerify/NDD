
package jdd.graph;

/**
 * A node in a graph, node in V[G]<br>
 *<p> Note that default weight is 0 (compares to 1.0 for edges)<br>
 *
 *
 */

public class Node {
	/** @see #flags */
	public static final int FLAGS_NONE = 0, FLAGS_ROOT = 1, FLAGS_MARKED = 2, FLAGS_TERMINAL = 4;
	public static final int FLAGS_BAD = 8, FLAGS_WEIGTHED = 16, FLAGS_LABLED = 32;
	public static final int FLAGS_INTERNAL1 = 64, FLAGS_INTERNAL2 = 128;



	/** identity of this Node (should be unique in a graph */
	public int id;

	/** Node falgs */
	public int flags;

	/** extra members used by algorithms */
	public int extra1, extra2, extraindex;

	/** extra members used by algorithms */

	public double extra3, extra4, weight;

	/** the in/out-going edges as a linked list */
	public Edge firstOut, firstIn; // outgoing edges linked list

	/** label of this Node */
	public String label;


	public Node(int id) { this(id,null); }

	public Node(int id, String label) {
		this.id = id;
		this.weight = 0.0;
		this.firstOut = null;
		this.firstIn  = null;
		setLabel(label);
	}

	public void setLabel(String label) {
		this.label = label;
		if(label != null) flags |= FLAGS_LABLED;
		else flags &= ~FLAGS_LABLED;
	}

	public String getLabel() {
		return (flags & FLAGS_LABLED) == 0 ? ("V"+id) : (label);
	}

	// ----------------------------------------------
	public double getWeight() {
		return weight;
	}

	public void setWeight(double w) {
		weight = w;
		flags |= FLAGS_WEIGTHED;
	}

	public boolean isWeighted() {
		return (flags & FLAGS_WEIGTHED) != 0;
	}

	// ----------------------------------------------

	/**
	 * computes the degree of the Node, i.e. | { (u,v) \in E : this node is either u or v } |
	 * <p>may be expensive, call it once and save the results instead of recalling each time
	 */
	public int getDegree() {
		int ret = 0;
		Edge e = firstOut; while(e != null) { ret ++; e = e.next; }
		e = firstIn; while(e != null) { ret ++; e = e.prev; }
		return ret;
	}

	// -----------------------------------------
	/** copy the attributes of this node */
	public void copyAttributesFrom(Node n) {
		this.flags = n.flags;
		this.extra1 = n.extra1;
		this.extra2 = n.extra2;
		this.extra3 = n.extra3;
		this.extra4 = n.extra4;
		this.weight = n.weight;
	}
}
