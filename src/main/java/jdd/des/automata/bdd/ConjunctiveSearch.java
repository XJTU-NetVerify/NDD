
package jdd.des.automata.bdd;

import jdd.bdd.*;
import jdd.util.*;

import jdd.des.automata.*;


public class ConjunctiveSearch
	implements SymbolicAutomataSearch
{
	private ConjunctivePartitions cp;
	private BDDAutomata manager;


	// --------------------------------------------------
	public ConjunctiveSearch(ConjunctivePartitions cp) {
		this.cp = cp;
		this.manager = cp.getBDDAutomata();
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
			System.err.print("."); // DEBUG
		} while(qkk != qk) ;
		return qk;
	}

	// -----------------------------------------------------------
	public int image(int from) {
		int product = manager.ref(from);

		// conjunctive partitioning with early quantification:
		for(int i = 0; i < cp.getSize(); i++) {
			int tmp = manager.relProd( product, cp.getBDDDeltaTop(i), cp.getBDDSCube(i));
			manager.deref(product);
			product = manager.ref( tmp);
		}


		// remove the events and replace S' with S
		int tmp = manager.ref( manager.exists( product, manager.getBDDCubeEvents() ) );
		manager.deref(product);

		int tmp2 = manager.ref( manager.replace(tmp, manager.getPermSp2S() ) );
		manager.deref(tmp);
		return tmp2;
	}
	// -----------------------------------------------------------
	public static void main(String [] args) {
		try {
			Automata agv = AutomataIO.loadXML("data/agv.xml");
			BDDAutomata ba = new BDDAutomata(agv);
			ConjunctivePartitions part = new ConjunctivePartitions(ba);
			ConjunctiveSearch cs = new ConjunctiveSearch(part);

			int initial = BDDAutomataHelper.getI(ba);
			int x = cs.forward(initial);

			double states = BDDAutomataHelper.countStates(ba, x);

			System.out.println("Found " + states + " states");

			part.cleanup();
			ba.cleanup();

		} catch(Exception exx) {
			exx.printStackTrace();
		}
	}
}