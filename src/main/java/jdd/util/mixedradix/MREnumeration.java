
package jdd.util.mixedradix;

import jdd.util.*;

import jdd.util.sets.*;

/**
 * enumeration of a mixed-radix set
 *
 * @see MRUniverse
 * @see MRSet
 */

// NOTE: the tests are in MRUniverse
public class MREnumeration  implements SetEnumeration {
	private MRUniverse univ;
	private MRSet set;
	private long curr, last;
	private int [] element;

	/* package */ MREnumeration(MRUniverse univ, MRSet set) {
		this.univ = univ;
		this.set = set;
		this.curr = 0;
		this.last = (long)univ.domainSize(); // FIXME: double to long conversion
		this.element = new int[univ.subdomainCount()];

		nextValid();

	}


	// -------------------------------------------------------

	// FIXME: if data[ curr>> 5] is zero, we should skip the entire block!
	/** find the next element in the set */
	private void nextValid() {
		while( curr < last && !set.test(curr) )  curr++;
	}

	// -------------------------------------------------------

	public void free() {
		// nothing to do, just help gc
		univ = null;
		set = null;
		element= null;
	}


	public boolean hasMoreElements() {
		return curr < last;
	}

	public int [] nextElement() {
		univ.indexToValue(curr, element);

		if(curr < last) {
			curr++;
			nextValid();
		}
		return element;
	}
}
