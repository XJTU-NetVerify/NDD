package jdd.util.mixedradix;

import jdd.util.*;
import jdd.util.sets.*;

/**
 * Mixed-radix representation of a set.
 * @see MRUniverse
 */
public class MRSet implements Set {
	private MRUniverse universe;
	/* package */ int [] data;

	/* package */ int data_size;
	/* package */ int last_mask;

	/**
	 * create a new set. if <tt>fill</tt> is set, the set will be full
	 * (equal to the universe) otherwise empty.
	 */
	public MRSet(MRUniverse universe, boolean fill) {
		init(universe);

		int value = fill ? 0xFFFFFFFF : 0;
		for(int i = 0; i < data_size; i++) data[i] = value;

		data[data_size-1] &= last_mask;
	}

	public MRSet(MRSet another, boolean invert) {
		init(another.universe);

		if(invert)	for(int i = 0; i < data_size; i++) data[i] = ~another.data[i];
		else 		for(int i = 0; i < data_size; i++) data[i] = another.data[i];

		data[data_size-1] &= last_mask;
	}

	private void init(MRUniverse universe) {
		this.universe = universe;
		data_size = (int)(1 + universe.domainSize() / 32);
		data = new int[data_size];

		int extra_bits = (int)(data_size * 32 - universe.domainSize());
		last_mask = (int)((1L << (32-extra_bits)) -1);
	}

	// ---------------------------------------


	public Set union(Set s1) {
		MRSet ret = new MRSet(this, false);
		int [] x = ((MRSet) s1).data;
		for(int i = 0; i < data_size; i++) ret.data[i] |= x[i];
		return ret;
	}

	public Set intersection(Set s1) {
		MRSet ret = new MRSet(this, false);
		int [] x = ((MRSet) s1).data;
		for(int i = 0; i < data_size; i++) ret.data[i] &= x[i];
		return ret;
	}

	public Set diff(Set s1) {
		MRSet ret = new MRSet(this, false);
		int [] x = ((MRSet) s1).data;
		for(int i = 0; i < data_size; i++) ret.data[i] &= ~x[i];
		return ret;
	}

	// ---------------------------------------
	public void clear() {
		for(int i = 0; i < data_size; i++) data[i] = 0;
	}


	public Set invert() { return new MRSet(this,  true); }
	public Set copy() { return new MRSet(this,  false); }

	// ---------------------------------------
	public void free() { }

	public double cardinality() {
		double ret = 0;

		data[data_size-1] &= last_mask;
		for(int i = 0; i < data_size; i++) {
			if(data[i] != 0)  ret += countOnes(data[i]);
		}
		return ret;
	}

	public boolean insert(int [] value) {
		long index = universe.valueToIndex(value);
		return test_and_set(index);
	}
	public boolean remove(int [] value) {
		long index = universe.valueToIndex(value);
		return test_and_remove(index);
	}
	public boolean member(int [] value) {
		long index = universe.valueToIndex(value);
		return test(index);
	}



	public boolean isEmpty() {
		if( (data[data_size-1] & last_mask) != 0) return false;
		for(int i = 0; i < data_size-1; i++) if(data[i] != 0) return false;
		return true;
	}
	public boolean isFull() {
		if( (data[data_size-1] & last_mask) != last_mask) return false;
		for(int i = 0; i < data_size-1; i++) if(data[i] != 0xFFFFFFFF) return false;
		return true;
	}



	// ----------------------------------------------
	/** retruns 0 if equal, -1 if this \subset s, +1 if s \subset this, Integer.MAX_VALUE otherwise */
	public int compare(Set s) {
		int [] x = ((MRSet) s).data;
		int got = 0;

		// makes things easier
		x[data_size-1] &= last_mask;
		data[data_size-1] &= last_mask;

		for(int i = 0; i < data_size; i++) {
			int one = compare_one(data[i],x[i]);
			if(one != 0) {
				if(got == 0) got = one;
				else if(one != got) return Integer.MAX_VALUE;
			}
		}
		return got;
	}

	private int compare_one(int v1, int v2) {
		if(v1 == v2) return 0;
		int and = v1 & v2;
		if(and == v1) return -1;
		if(and == v2) return +1;
		return Integer.MAX_VALUE;
	}

	public boolean equals(Set s) {
		int [] x = ((MRSet) s).data;

		// makes things easier
		x[data_size-1] &= last_mask;
		data[data_size-1] &= last_mask;

		for(int i = 0; i < data_size; i++) if(x[i] != data[i]) return false;
		return true;
	}
	// ----------------------------------------------
	private int countOnes(int x) {
		int ret = 0;
		for(int i = 0; i < 32; i++)
			if( (x & (1 << i) ) != 0) ret++;
		return ret;
	}

	private boolean test_and_set(long index) {
		int bit = 1 << (int)(index & 31) ;
		int i   = (int)(index >> 5);

		if((data[i] & bit) != 0) return false;

		data[i] |= bit;
		return true;
	}

	private boolean test_and_remove(long index) {
		int bit = 1 << (int)(index & 31) ;
		int i   = (int)(index >> 5);
		if((data[i] & bit) == 0) return false;

		data[i] &= ~bit;
		return true;
	}

	/* package */ boolean test(long index) {
		int bit = 1 << (int)(index & 31) ;
		int i   = (int)(index >> 5);
		return ((data[i] & bit) != 0);
	}



	// ----------------------------------------------

	public SetEnumeration elements() {
		return new MREnumeration(universe, this);
	}



	// ----------------------------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("MRSet");
		
        int [] domains = new int [] {3,4,5,2};
        MRUniverse u = new MRUniverse( domains );
        
		Set s1 = u.createEmptySet();
		Set s2 = u.createFullSet();


		// test insert, remove and member
		int [] v = new int[4];
		v[0] = v[1] = v[2] = v[3] = 0;

		Test.check(s1.insert(v), "v not in S1 before");
		Test.check(!s1.insert(v), "v in S1 after");
		Test.checkEquality( s1.cardinality(), 1.0, "Cardinality 1 after inserting v");
		Test.check(s1.member(v), "v \\in S1");
		Test.check(s1.remove(v), "v removed from S1");
		Test.check(!s1.member(v), "v \\not\\in S1");
		Test.check(!s1.remove(v), "v already removed from S1 and not in S1 anymore");
		Test.checkEquality( s1.cardinality(), 0.0, "S1 empty again");

		// check empty and clear:
		Test.check( s1.isEmpty(), "S1 is empty");
		Test.check(!s2.isEmpty(), "S2 is not empty");

		// test invert
		Set s1_neg = s1.invert();
		Test.check( s1_neg.equals( s2), "(NOT  emptyset) = fullset");
		s1_neg.free();

		// test copy:
		Set s2_copy = s2.copy();
		Test.check( s2_copy.equals( s2), "copy() test");

		// ...and clear
		s2_copy.clear();
		Test.check( s2_copy.equals( s1), "clear() test");
		s2_copy.free();


		// check union
		Set x0 = u.createEmptySet();
		Set x1 = u.createEmptySet();
		Set x10 = u.createEmptySet();

		v[0] = v[1] = v[2] = v[3] = 0; x0.insert(v); x10.insert(v);
		v[0] = v[1] = v[2] = v[3] = 1; x1.insert(v); x10.insert(v);
		Set union = x1.union(x0);
		Test.check(union.equals( x10), "union() - test");
		union.free();

		// check diff:
		Set diff1 = x10.diff( x1);
		Set diff2 = x10.diff( x0);
		Test.check(diff1.equals( x0), "diff() - test 1");
		Test.check(diff2.equals( x1), "diff() - test 2");
		diff1.free();
		diff2.free();

		// check intersection
		Set int1 = x10.intersection( x1);
		Set int2 = x10.intersection( x0);
		Test.check(int1.equals( x1), "intersection() - test 1");
		Test.check(int2.equals( x0), "intersection() - test 2");
		int1.free();
		int2.free();


		// check compare:
		Test.checkEquality( x1.compare(x1), 0, "x1 = x1");
		Test.checkEquality( x10.compare(x1), +1, "x1  < x10");
		Test.checkEquality( x1.compare(x10), -1, "x10 > x1");

		Test.checkEquality( x10.compare(x0), +1, "x10 > x0");
		Test.checkEquality( x0.compare(x10), -1, "x0  < x0");

		Test.checkEquality( x1.compare(x0), Integer.MAX_VALUE , "x10 ?? x0"); // no relation


		s1.free();
		s2.free();

		Test.end();
	}


}
