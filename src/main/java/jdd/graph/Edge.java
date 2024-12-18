
package jdd.graph;

import jdd.util.*;

/** Edge in graph: contains (u,v), the graph may or may not be directed */

public class Edge implements Sortable {
	public static final int FLAGS_NONE = 0, FLAGS_STRONG = 1, FLAGS_MARKED = 2, FLAGS_WEIGTHED = 4;
	public static final int FLAGS_LABLED = 8;

	public Node n1, n2;
	public int id, flags, extra1, extra2;
	public double weight, extra3;
	public Edge next, prev;
	public String label;

	public Edge(Node n1, Node n2, int id) {
		this(n1,n2,id,null);
	}
	public Edge(Node n1, Node n2, int id, String label) {
		this.n1 = n1;
		this.n2 = n2;
		this.id = id;
		this.flags = 0;
		this.weight = 1; // unit weight for all
		this.next = null;
		this.prev = null;
		setLabel(label);

	}

	// ------------------------------------

	public void setLabel(String label) {
		this.label = label;
		if(label != null) flags |= FLAGS_LABLED;
		else flags &= ~FLAGS_LABLED;
	}

	public String getLabel() {
		return (flags & FLAGS_LABLED) == 0 ? ("E"+id) : (label);
	}

	// ------------------------------------

  public boolean greater_than(Sortable s) {
		return this.weight > ((Edge)s).weight;
	}

	public void setWeight(double d) {
		weight = d;
		flags |= FLAGS_WEIGTHED;
	}

	public double getWeight() {
		return weight;
	}



	// ------------------------------------------------
	/** copy attributes of this edge to me */
	public void copyAttributesFrom(Edge e) {
		this.flags = e.flags ;
		this.extra1 = e.extra1 ;
		this.extra2 = e.extra2 ;
		this.extra3 = e.extra3 ;
		this.weight = e.weight ;
	}
}
