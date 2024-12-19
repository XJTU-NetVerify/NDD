
package jdd.internal.profiler.stubs;

import jdd.internal.profiler.*;
import jdd.examples.*;

/**
 * stuf for running the BDD version of n-queens
 *
 */

public class BDDQueenStub implements ProfiledObject {
	private int n, time;
	private long mem;

	public BDDQueenStub(int n) {
		this.n = n;
		this.time = -1;
		this.mem = -1;
	}



	public void run() {
		BDDQueens q = new BDDQueens( n );
		time = (int)q.getTime();
		mem = (long)q.getMemory();
		// BDDQueens calls cleanup itself
	}
	public String getProfileName() { return "BDDQueens:" + n; }
	public int getRunningTime() { return time; }
	public long getMemoryUsage() { return mem; }
}