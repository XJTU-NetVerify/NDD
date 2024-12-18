
package jdd.internal.profiler.stubs;

import jdd.internal.profiler.*;
import jdd.examples.*;

/**
 * stuf for running the example Milner
 *
 */

public class BDDMilnerStub implements ProfiledObject {
	private int n, time;
	private long mem;

	public BDDMilnerStub(int n) {
		this.n = n;
		this.time = -1;
		this.mem = -1;
	}



	public void run() {
		long tmp = System.currentTimeMillis();
		Milner mil = new Milner( n );
		time = (int)( System.currentTimeMillis() - tmp);
		mem = mil.getMemoryUsage();
		mil.cleanup();
	}
	public String getProfileName() { return "BDDMilner:" + n; }
	public int getRunningTime() { return time; }
	public long getMemoryUsage() { return mem; }
}