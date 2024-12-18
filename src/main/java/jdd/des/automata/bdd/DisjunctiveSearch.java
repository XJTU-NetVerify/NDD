
package jdd.des.automata.bdd;

import jdd.bdd.*;
import jdd.util.*;

import jdd.des.automata.*;

/**
 * state reachability search by disjunctive partitioning
 *
 */

public class DisjunctiveSearch
	implements SymbolicAutomataSearch
{
	private DisjunctivePartitions dp;
	private BDDAutomata manager;


	// --------------------------------------------------
	/**
	 * initialize a disjunctive search for the given partitions
	 *
	 */
	public DisjunctiveSearch(DisjunctivePartitions dp) {
		this.dp = dp;
		this.manager = dp.getBDDAutomata();
	}

	public void cleanup() {
		// nothing to do here
	}

	// --------------------------------------------------

	public int forward(int bdd_initial) {

		int qkk, qk = manager.ref(bdd_initial);

		do {
			qkk = qk;
			int next = image(qk);
			qk = manager.orTo(qk, next);
			manager.deref(next);
		} while(qkk != qk) ;

		return qk;
	}

	// -----------------------------------------------------------

	public int image(int from) {
		int cube = manager.getBDDCubeS();
		Permutation perm = manager.getPermSp2S();


		int sum = 0;
		for(int i = 0; i < dp.getSize(); i++) {
			int tmp = manager.ref( manager.relProd( from, dp.getBDDTWave(i), cube) );
			sum = manager.orTo(sum, tmp);
			manager.deref(tmp);
		}

		int next = manager.ref( manager.replace(sum, perm ) );
		manager.deref(sum);
		return next;
	}
	// -----------------------------------------------------------
	public static void main(String [] args) {
		try {
			Options.verbose = true; // DEBUG
			for(int i = 0; i< args.length; i++) {
				Automata agv = AutomataIO.loadXML(args[i]);

				long time = System.currentTimeMillis();
				BDDAutomata ba = new BDDAutomata(agv);
				DisjunctivePartitions part = new DisjunctivePartitions(ba);
				DisjunctiveSearch ds = new DisjunctiveSearch(part);

				int initial = BDDAutomataHelper.getI(ba);
				int x = ds.forward(initial);

				double states = BDDAutomataHelper.countStates(ba, x);
				part.cleanup();
				ds.cleanup();
				ba.cleanup();

				time = System.currentTimeMillis() - time;
				System.out.println("Found " + states + " states in " + args[i] + " in " + time + "ms.");
			}

		} catch(Exception exx) {
			exx.printStackTrace();
		}
	}
}