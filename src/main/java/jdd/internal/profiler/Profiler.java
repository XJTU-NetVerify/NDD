
package jdd.internal.profiler;

import jdd.internal.profiler.stubs.*;
import jdd.internal.profiler.tests.*;
import jdd.util.*;


import java.util.*;


/**
 * The main profiler class. here is where all the tests are done
 *
 */
public class Profiler {

	/** how many times is a test repeated ? */
	private final static int NUMBER_OF_RUNS = 3;

	private BenchmarkSet [] sets; /** sets of objects to be profiled */
	private ProfilerDB db; 		/** the profiling database */

	public Profiler() {
		db = new ProfilerDB();
		create_benchmark_sets();
	}


	// -----------------------------------------------------------------
	/** here we create a list of what can be profiled */
	private void create_benchmark_sets() {
		sets = new BenchmarkSet[5];

		// ------------ set 0: warm up
		sets[0] = new BenchmarkSet("(warmup)");
		sets[0].add( new BDDAdderStub(32) );
		sets[0].add( new BDDAdderStub(64) );


		// ------------ set 1: the simple BDD operations
		sets[1] = new BenchmarkSet("simple BDD (binary operations)");
		sets[1].add( new BDDQueenStub(10) );
		sets[1].add( new BDDQueenStub(11) );

		sets[1].add( new BDDAdderStub(256) );
		sets[1].add( new BDDAdderStub(512) );




		// ------------ set 2: bdd-automata stuff
		sets[2] = new BenchmarkSet("symbolic automata (relProd and replace)");

		sets[2].add( new BDDMilnerStub(48) );
		sets[2].add( new BDDMilnerStub(56) );

		sets[2].add( new BDDAutomataStub("data/phil.xml") );
		sets[2].add( new BDDAutomataStub("data/ft66.xml") );
		sets[2].add( new BDDAutomataStub("data/agv.xml") );


		// ------------ set 3: ZBDD stuff
		sets[3] = new BenchmarkSet("Zero-Supressed BDDs");

		sets[3].add( new ZDDQueenStub(10, false) );
		sets[3].add( new ZDDQueenStub(11, false) );

		sets[3].add( new ZDDQueenStub(10, true) );
		sets[3].add( new ZDDQueenStub(11, true) );

		sets[3].add( new ZDDPetrinetStub("data/largepn1.xml") );
		sets[3].add( new ZDDPetrinetStub("data/largepn2.xml") );
		sets[3].add( new ZDDPetrinetStub("data/pn.xml") );
		sets[3].add( new ZDDPetrinetStub("data/agv_ctrl.xml") );


	// ------------ set 4: quant stuff
		sets[4] = new BenchmarkSet("Quantification");


		sets[4].add( new QuantTest(100) );
		sets[4].add( new QuantTest(125) );
		sets[4].add( new QuantTest(150) );
		sets[4].add( new QuantTest(175) );
		sets[4].add( new QuantTest(200) );


	}

	// -----------------------------------------------------------------
	/**
	 * run the profiler on ALL benchmark sets once and write the results to the database
	 * <p>to actually save the results to disc, call save() after this.
	 */
	public void run() {
		for(int b = 0; b < sets.length; b++) run(sets[b]);
	}

	/**
	 * run the profiler on the given benchmark set and write the results to the database
	 * <p>to actually save the results to disc, call save() after this.
	 */
	public void run(BenchmarkSet set) {
		for(Iterator it = set.iterator(); it.hasNext(); ) {
			ProfiledObject po = (ProfiledObject) it.next();

			int tim = 0;
			long mem = 0;

			for(int i = 0; i < NUMBER_OF_RUNS; i++) {
				po.run();
				tim += po.getRunningTime();
				mem += po.getMemoryUsage();
			}
			tim /= NUMBER_OF_RUNS;
			mem /= NUMBER_OF_RUNS;

			db.insert(po.getProfileName(), tim, mem);
		}
	}

	public Collection getReults(BenchmarkSet set) {
		Collection ret = new LinkedList();
		for(Iterator it = set.iterator(); it.hasNext(); ) {
			ProfiledObject po = (ProfiledObject) it.next();
			ProfilerResults pr = new ProfilerResults(db, po.getProfileName());
			pr.setCurrent(getMyId() );
			ret.add(pr);
		}

		return ret;
	}



	// ----------------------------------------------------------

	/** get the set of benchmarks */
	public  BenchmarkSet [] getBenchmarkSets() { return sets; }
	/** get the description of current run  (if any) */
	public String getDesc() { return db.getDesc(); }

	/** set the description of current run */
	public void setDesc(String str) { db.setDesc(str); }

	/** get ID for the current run */
	public int getMyId() { return db.getMyId(); }

	/** compare to runs */
	public double getChange(int id1, int id2, boolean for_time) {
		return db.getChange(id1, id2, for_time);
	}

	/** get a profiling-info set by its id */
	public  ProfilingInfo findInfoById(int id) {
		return db.findInfoById(id);
	}
	// ----------------------------------------------------------

	/** save the XML database to disk */
	public void save() {
		db.save();
	}


	// ----------------------------------------------------------

	/**
	 * dump the current database to the Console.
	 */
	public void dump() {
		if(getMyId() != 0) {
			double mdiff = db.getChange( getMyId(), getMyId()  -1, false);
			double tdiff = db.getChange( getMyId(), getMyId()  -1, true);
			JDDConsole.out.println("TIME DIFF: " + ((int)( 10000 * tdiff)) / 100.0  + "%");
			JDDConsole.out.println("MEMORY DIFF: " + ((int)( 10000 * mdiff)) / 100.0 + "%" );
		}

		db.dump();
	}

	// ---------------------------------------------------------------------
	/** the Console-based profiler is started from here */
	public static void main(String [] args) {
		Profiler p = new Profiler();
		p.run();
		p.dump();
		p.save();
	}
}


