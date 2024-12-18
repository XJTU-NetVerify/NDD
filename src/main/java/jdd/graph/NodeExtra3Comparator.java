

package jdd.graph;

import jdd.util.*;

import java.util.*;


/**
 * "<"-predicate on Node.extra3, for internal use in ordered sets/trees.
 * <p>does not return equals, unless the same object is compared
 */

class NodeExtra3Comparator implements Comparator {
	public static NodeExtra3Comparator nodeExtra3Comparator = new NodeExtra3Comparator();
	public int compare(Object o1, Object o2) {
		Node n1 = (Node) o1;
		Node n2 = (Node) o2;
		if(n1.extra3 == n2.extra3) { // we _dont_ like equal elements (they dissappear in TreeSet:s)
			if(o1 == o2) return 0;
			return (o1.hashCode() < o2.hashCode()) ? -1 : 1;
		} else if(n1.extra3 > n2.extra3) return 1;
		else return -1;
	}
	public boolean equals(Object obj) { return (this == obj); }
}
