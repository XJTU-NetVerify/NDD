
package jdd.internal.profiler.stubs;

import jdd.internal.profiler.*;
import jdd.examples.*;

/**
 * stuff for running the Z-BDD version of n-queens.
 * you can choose between the normal and the CSP version
 */

public class ZDDQueenStub implements ProfiledObject {
	private int n, time;
	private long mem;
	private boolean use_csp;

	public ZDDQueenStub(int n, boolean use_csp) {
		this.n = n;
		this.time = -1;
		this.mem = -1;
		this.use_csp = use_csp;
	}



	public void run() {
		// NOTE: queen examples will call cleanup themselves
		if(use_csp) {
			ZDDCSPQueens q = new ZDDCSPQueens( n );
			time = (int)q.getTime();
			mem = q.getMemory();
		} else {
			ZDDQueens q = new ZDDQueens( n );
			time = (int)q.getTime();
			mem = q.getMemory();
		}
	}

	public String getProfileName() {
		if(use_csp) return "ZDDCSPQueens:" + n;
		else				return "ZDDQueens:" + n;
	}
	public int getRunningTime() { return time; }
	public long getMemoryUsage() { return mem; }
}