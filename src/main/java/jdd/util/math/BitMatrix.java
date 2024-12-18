
package jdd.util.math;

import jdd.util.*;

import java.util.*;

/**
 * This is an M x N matrix of boolean numbers.
 *
 */

public class BitMatrix {
	private BitSet set;
	private int cols, rows;
	public BitMatrix(int rows, int cols) {
		set = new BitSet( rows * cols);
		this.rows = rows;
		this.cols = cols;
	}

	public BitSet getSet() { return set; }

	public void set(int y, int x) {	set.set( x + y * cols, true);	}
	public void clear(int y, int x) {	set.clear( x + y * cols); }
	public void flip(int y, int x) { set.flip( x + y * cols); }
	public boolean get(int y, int x) {	return set.get( x + y * cols); }

	public void clear() { set.clear(); }

	public int sum() { return set.cardinality(); }

	public void show() {
		for(int y = 0; y < rows; y++) {
			for(int x = 0; x < cols; x++) {
				if(x != 0) JDDConsole.out.print(" ");
				JDDConsole.out.print( get(y,x) ? "1" : "0");
			}
			JDDConsole.out.println();
		}
	}

	// --- [test bed] ----
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("BitMatrix");

		BitMatrix bm = new BitMatrix(3,3);

		Test.checkEquality(bm.sum(), 0, "Matrix empty");
		Test.check(bm.get(1,1) == false, "(1,1) = false");

		bm.set(1,1);
		Test.checkEquality(bm.sum(), 1, "Matrix has 1 elemnt");
		Test.check(bm.get(1,1) == true, "(1,1) = true");

		bm.clear(1,1);
		Test.checkEquality(bm.sum(), 0, "Matrix empty");
		Test.check(bm.get(1,1) == false, "(1,1) = false (2)");

		bm.flip(2,2);
		Test.check(bm.get(2,2) == true, "(2,2) = true");
		bm.flip(2,2);
		Test.check(bm.get(2,2) == false, "(2,2) = false");


		Test.end();
	}
}
