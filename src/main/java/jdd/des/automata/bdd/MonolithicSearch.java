
package jdd.des.automata.bdd;

import jdd.bdd.*;
import jdd.util.*;

import jdd.des.automata.*;


public class MonolithicSearch
	implements SymbolicAutomataSearch
{

	private BDDAutomata manager;
	private int mono_t; /** BDD for monolithic T */

	public MonolithicSearch(BDDAutomata manager) {
		this.manager = manager;
		this.mono_t = 0; // to start with

		this.mono_t = BDDAutomataHelper.getT(manager);
	}

	public void cleanup() {
		manager.deref(mono_t);
	}

	// --------------------------------------------------
	public int forward(int bdd_initial) {
		int qkk, qk = manager.ref(bdd_initial);
		int cube = manager.getBDDCubeS();
		Permutation perm = manager.getPermSp2S();


		do {
			qkk = qk;

			int next = manager.ref( manager.relProd(qk, mono_t, cube) );
			int tmp = manager.ref( manager.replace(next, perm ) );
			manager.deref(next);

			qk = manager.orTo(qk, tmp);
			manager.deref(tmp);

		} while(qkk != qk) ;

		return qk;
	}


	public static void main(String [] args) {

		try {
			Options.verbose = true; // DEBUG
			for(int i = 0; i < args.length; i++) {
				Automata agv = AutomataIO.loadXML(args[i]);

				long time = System.currentTimeMillis();
				BDDAutomata ba = new BDDAutomata(agv);
				MonolithicSearch ms = new MonolithicSearch(ba);
				int initial = BDDAutomataHelper.getI(ba);
				int x = ms.forward(initial);

				double states = BDDAutomataHelper.countStates(ba, x);
				ba.cleanup();

				time = System.currentTimeMillis() - time;
				System.out.println("Found " + states + " states in " + args[i] + " in " + time + "ms.");

			}

		} catch(Exception exx) {
			exx.printStackTrace();
		}
	}
}