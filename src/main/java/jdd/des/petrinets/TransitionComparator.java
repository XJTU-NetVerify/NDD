
package jdd.des.petrinets;

import java.util.*;


/**
 * TransitionComparator compares transition for complete order.
 * Used for ordered data structures such as TreeSet
 *
 */

public class TransitionComparator implements Comparator {

	/** the one and only comparator that should be used */
	public static TransitionComparator comparator = new TransitionComparator();

	private TransitionComparator() { /* I AM PRIVATE */ }

	public int compare(Object o1, Object o2) {
		if(o1 == o2) return 0;
		Transition t1 = (Transition) o1;
		Transition t2 = (Transition) o2;
		return (t1.index > t2.index) ? +1 : -1;
	}
	public boolean equals(Object obj) { return this == obj; }
}
