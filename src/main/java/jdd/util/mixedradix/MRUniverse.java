
package jdd.util.mixedradix;

import jdd.util.*;
import jdd.util.sets.*;

/**
 * mixed radix representation of a set, implemented as a bitset.
 * <p> time but not memory efficient, as opposed to the BDD implementation.
 */

public class MRUniverse implements Universe {
	/* package */ int []subdomains;
	/* package */ int []mult;
	private int domain_count;
	private double domain_size;

	public MRUniverse(int [] subdomains) {
		this.subdomains = Array.clone(subdomains);
		domain_count = subdomains.length;
		domain_size = 1.0;

		mult = new int[domain_count];

		for(int i = 0; i < domain_count;i++) {
			mult[i] = (int) domain_size;
			domain_size *= subdomains[i];

		}
	}


	// -------------------------------------------
	public void free() {
		// a good way to make the object INVALID
		subdomains = null;
		mult = null;
	}
	// -------------------------------------------
	public double domainSize() { return domain_size; }
	public int subdomainCount() { return domain_count; }
	public Set createEmptySet() { return new MRSet(this, false); }
	public Set createFullSet() { return new MRSet(this, true); }

	/* packate */ long valueToIndex(int [] v) {
		long index = 0;
		for(int i = 0; i < domain_count; i++) index += v[i] * mult[i];
		return index;
	}

	/* packate */ void indexToValue(long index, int [] v) {
		for(int i = 0; i < domain_count; i++)  {
			v[i] = (int)(index % subdomains[i]);
			index /= subdomains[i];
		}
	}

	// ---- random member ----------------------
	public void randomMember(int [] out) {
		for(int i = 0; i < domain_count; i++)
			out[i] = (int)(Math.random() * subdomains[i]);
	}

	// ---------------------------------------------------------

	static int [] dum = { 3, 4, 5 , 1};
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("MRUniverse");


		MRUniverse u = new MRUniverse(dum);
		Set s1 = u.createEmptySet();
		Set s2 = u.createFullSet();

		// test trivial stuff
		Test.checkEquality( s1.cardinality(), 0.0, "Empty set has zero cardinality");
		Test.checkEquality( s2.cardinality(), u.domainSize(), "Full set as large as the universe");
		s1.free();
		s2.free();



		// test insert andenumeration
		for(int j = 10; j < 33; j++) {
			s1 = u.createEmptySet();

			// fill the vectors with junk
			for(int i = 0; i < j; i++) { u.randomMember(dum); s1.insert(dum ); }
			Test.checkLessThan( (int)s1.cardinality(), j+1, "insert (1)"); // +1 for less-than-or-equal !

			int found = 0;
			boolean failed = false;
			SetEnumeration se = s1.elements();
			while( se.hasMoreElements()) {
				found ++;
				int [] x = se.nextElement();
				if(!s1.member(x)) failed = true;
			}
			Test.check( !failed, "SetEnumeration.nextElement() returned a non- member()");

			Test.checkEquality( (int)s1.cardinality(), found, "MREnumeration (1)");
			s1.free();
		}

		Test.end();
	}
}
