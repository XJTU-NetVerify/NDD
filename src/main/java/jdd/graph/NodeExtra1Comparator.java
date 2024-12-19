
package jdd.graph;

import jdd.util.*;

import java.util.*;

/**
 * "<"-predicate on extra1, for internal use in ordered sets/trees.
 * <p>does not return equals, unless the same object is compared
 */

class NodeExtra1Comparator implements Comparator {
	public static NodeExtra1Comparator nodeExtra1Comparator = new NodeExtra1Comparator();
	public int compare(Object o1, Object o2) {
		Node n1 = (Node) o1;
		Node n2 = (Node) o2;
		if(n1.extra1 == n2.extra1) { // we _dont_ like equal elements (they dissappear in TreeSet:s)
			if(o1 == o2) return 0;
			return (o1.hashCode() < o2.hashCode()) ? -1 : 1;	// this is not a bug, trust me /Arash
		} else if(n1.extra1 > n2.extra1) return 1;
		else return -1;
	}
	public boolean equals(Object obj) { return (this == obj); }
}
