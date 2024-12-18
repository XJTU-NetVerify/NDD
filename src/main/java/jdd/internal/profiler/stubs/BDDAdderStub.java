
package jdd.internal.profiler.stubs;

import jdd.internal.profiler.*;
import jdd.examples.*;

/**
 * stuf for running the BDD adder
 *
 */

public class BDDAdderStub implements ProfiledObject {
	private int n, time;
	private long mem;

	public BDDAdderStub(int n) {
		this.n = n;
		this.time = -1;
		this.mem = -1;
	}



	public void run() {
		long tmp = System.currentTimeMillis();
		Adder add = new Adder( n );
		time = (int)( System.currentTimeMillis() - tmp);
		mem = add.getMemoryUsage();
		add.cleanup();
	}
	public String getProfileName() { return "BDDAdder:" + n; }
	public int getRunningTime() { return time; }
	public long getMemoryUsage() { return mem; }
}