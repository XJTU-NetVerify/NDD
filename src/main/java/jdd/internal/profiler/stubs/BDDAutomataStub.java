
package jdd.internal.profiler.stubs;

import jdd.internal.profiler.*;
import jdd.bdd.*;
import jdd.des.automata.*;
import jdd.des.automata.bdd.*;

/**
 * symbolic reachability search with automata
 *
 */

public class BDDAutomataStub implements ProfiledObject {
	private long time, mem;
	private Automata automata;
	private String filename;

	public BDDAutomataStub(String filename) {
		this.time = this.mem = -1;
		this.filename = filename;

		try {
			automata = AutomataIO.loadXML(filename);
		} catch(Exception exx) {
			System.out.println("Profiling error: " + exx);
			automata = null;
		}

	}



	public void run() {
		if(automata == null) return;

		time = System.currentTimeMillis();
		BDDAutomata ba = new BDDAutomata(automata);
		/*
		DisjunctivePartitions part = new DisjunctivePartitions(ba);
		DisjunctiveSearch ds = new DisjunctiveSearch(part);

		int initial = BDDAutomataHelper.getI(ba);
		int x = ds.forward(initial);

		double states = BDDAutomataHelper.countStates(ba, x);

		System.out.println("Found " + states + " states");
		mem = (long)ba.getMemoryUsage();
		time = System.currentTimeMillis() - time;

		// Cleanup!
		part.cleanup();
		ds.cleanup();
		ba.cleanup();
		*/

		MonolithicSearch ms = new MonolithicSearch(ba);
		int initial = BDDAutomataHelper.getI(ba);
		int x = ms.forward(initial);

		double states = BDDAutomataHelper.countStates(ba, x);

		mem = (long)ba.getMemoryUsage();
		time = System.currentTimeMillis() - time;

		System.out.println("Found " + states + " states in " + time + "ms with " + (mem /1024) +"KB");


		ms.cleanup();
		ba.cleanup();
	}

	public String getProfileName() { return "BDDAuomata" + filename; }
	public int getRunningTime() { return (int)time; }
	public long getMemoryUsage() { return mem; }
}




