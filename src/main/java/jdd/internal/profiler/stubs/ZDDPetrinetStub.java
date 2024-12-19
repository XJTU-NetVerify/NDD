
package jdd.internal.profiler.stubs;

import jdd.internal.profiler.*;
import jdd.des.petrinets.*;

/**
 * Petrinet reachability search with Z-BDDs
 */

public class ZDDPetrinetStub implements ProfiledObject {
	private long mem, time;
	private Petrinet pn;
	private String filename;

	public ZDDPetrinetStub(String filename) {
		this.filename = filename;
		this.time = -1;
		this.mem = -1;
		this.pn = PetrinetIO.loadXML(filename);

	}



	public void run() {
		time = System.currentTimeMillis();
		SymbolicPetrinet sp = new SymbolicPetrinet( pn );
		int R = sp.forward();
		int size = sp.count(R);
		mem = (long)sp.getMemoryUsage();
		time = System.currentTimeMillis() - time;
		sp.cleanup();

	}

	public String getProfileName() {
		return "ZDDPetrinet:" + filename;
	}
	public int getRunningTime() { return (int)time; }
	public long getMemoryUsage() { return mem; }
}