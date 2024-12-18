
package jdd.util;

/** <pre>
 * simple is beautiful!
 *
 * disjoint sets [aka equivalnce sets] as path compressed trees
 * </pre>
 */

public class DisjointSet {
	protected int [] s;
	protected int size;

	public DisjointSet(int size) {
		this.size = size;
		this.s = new int[size];
		Array.set(s, -1);
	}

	/** r1 and r2 must be roots, if not use UNION( find(r1), find(r2) ) instead */
	public void union(int r1, int r2) {
		if(r1 == r2) return; // XXX: fix to terminate recursive calls in find() later on

		if( s[r2] < s[r1])  s[r1] = r2;
		else {
			if(s[r1] == s[r2]) s[r1]--;
			s[r2] = r1;
		}
	}

	public int find(int x) {
		if( s[x] < 0) return x;
		else return (s[x] = find( s[x]));
	}

	// ------------------------------------------------------------

	/* even simpler but less efficient
	public DisjointSet(int size) {
		this.size = size;
		this.s = new int[size];
		for(int i = 0; i < size; i++) s[i] = i;
	}

	public void union(int r1, int r2) {
		int s1 = s[r1], s2 = s[r2];
		for(int i = 0; i < size; i++) if(s[i] == s1) s[i] = s2;
	}

	public int find(int x) {	return s[x]; }
	*/

	// ------------------------------------------------------------------------------

	/** total number of elements in this set */
	public int size() { return size; }

	/** total number of classes (islands) in this set */
	public int classes() {
		int ret = 0;
		for(int i = 0; i < size; i++) if(s[i] <0) ret++;
		return ret;
	}

	// ------------------------------------------------------------------------------

	public void showClass(int root) {
		int f = find(root);
		JDDConsole.out.print("{ ");
		for(int i = 0; i < size; i++) if(f == find(i)) JDDConsole.out.print( i + " ");
		JDDConsole.out.println("}");
	}

	public void dump() {
		for(int i = 0; i < size; i++) JDDConsole.out.print( i + " ");
		JDDConsole.out.println();
	}
	// ------------------------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("DisjointSet");

		DisjointSet ds = new DisjointSet(8);

		Test.checkEquality( ds.classes(), 8, "classes 1");
		Test.checkInequality( ds.find(4), ds.find(5), "4 /~ 5");

		ds.union(4,5);
		Test.checkEquality( ds.find(4), ds.find(5), "4 ~ 5");
		Test.checkEquality( ds.classes(), 7, "classes 2");

		ds.union(6,7);
		Test.checkEquality( ds.find(6), ds.find(7), "6 ~ 7");
		Test.checkEquality( ds.classes(), 6, "classes 3");

		ds.union(4,6);
		Test.checkEquality( ds.find(4), ds.find(6), "4 ~ 6");
		Test.checkEquality( ds.find(5), ds.find(7), "5 ~ 7");
		Test.checkInequality( ds.find(3), ds.find(5), "3 /~ 5");
		Test.checkEquality( ds.classes(), 5, "classes 4");

		Test.end();
	}
}
