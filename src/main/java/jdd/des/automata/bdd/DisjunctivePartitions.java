package jdd.des.automata.bdd;

import jdd.bdd.*;
import jdd.util.*;

import jdd.graph.*;
import jdd.des.automata.*;



import java.util.*;

/**
 * Disjunctive partitioning of automata under full synchronous composition of Hoare
 * <p>
 *
 * A set of disjunctive partition is the composite transfer function divided under disjunction.
 */

public class DisjunctivePartitions {
	private BDDAutomata automata; /** the automata object */
	private int count; /** number of partitions */
	private int [] t_disj; /** the disjunctive representation of the transition relation */

	/* internal code for building T~ */
	private int get_one(int care, BDDAutomaton a) {
		Node n = automata.getPCGNode(a);
		n.extra1 = 1; // mark it used

		int my_care = automata.ref( automata.and( care, a.getBDDCareEvent()) );
		int my_not_care = automata.ref( automata.not( my_care ) );
		int t1 = automata.ref( automata.and( my_care, a.getBDDDelta())  );
		int t2 = automata.ref( automata.and( my_not_care, a.getBDDKeep()) );
		automata.deref(my_care);
		automata.deref(my_not_care);

		t1 = automata.orTo(t1, t2);
		automata.deref(t2);
		return t1;
	}
	// ---------------------------------------------------------------------------------
	/**
	 * create a conjunctive representation for the automata in <tt>automata</tt>
	 */
	public DisjunctivePartitions(BDDAutomata automata) {
		this.automata = automata;
		this.count = automata.automata.length;


		// 0. we will remove the events from delta~ to get T~
		int cube = automata.getBDDCubeEvents();

		// 1. allocate some memory :)
		t_disj = new int[count];


		// 2 get the PCG
		Graph pcg = automata.getPCG();

		// 3. create disjunctive transition relations
		for(int i = 0; i < count; i++){
			AttributeExplorer.setAllNodesExtra1(pcg, 0);

			BDDAutomaton a = automata.automata[i]; // the automaton we are working with right now
			Node n = automata.getPCGNode(a);
			int care = a.getBDDCareEvent();

			n.extra1 = 1; // mark it used
			t_disj[i] = automata.ref( a.getBDDDelta() );

			// 3.a insert the dependency set:

			// 3.a.1 forward transitions (in PCG)
			for(Edge e = n.firstOut; e != null; e = e.next) {
				BDDAutomaton a2 = automata.getBDDAutomaton(e.n2);
				int t = get_one(care, a2);
				t_disj[i] = automata.andTo(t_disj[i], t);
				automata.deref(t);
			}

			// 3.a.2 backward transitions (in PCG)
			for(Edge e = n.firstIn; e != null; e = e.prev) {
				BDDAutomaton a2 = automata.getBDDAutomaton(e.n1);
				int t = get_one(care, a2);
				t_disj[i] = automata.andTo(t_disj[i], t);
				automata.deref(t);
			}

			// 3.b add the "keep" stuff, i.e. automata outside the dependency set
			int keep = 1;
			for (Enumeration e = pcg.getNodes().elements() ; e.hasMoreElements() ;) {
				Node n2 = (Node) e.nextElement();
				if(n2.extra1 == 0) {
					BDDAutomaton a2 = automata.getBDDAutomaton(n2);
					keep = automata.andTo(keep, a2.getBDDKeep() );
				}
			}

			// XXX: maybe its bette to compute "(EXISTS cube, t_disj[i]) AND keep" instead??
			t_disj[i] = automata.ref( automata.relProd(t_disj[i], keep, cube) );
			automata.deref(keep);


		}
	}


	/**
	 * cleanup the disjunctive partitions and free memory
	 */
	 public void cleanup() {
		 for(int i = 0; i < count; i++) automata.deref(t_disj[i]);
		 count = 0; // so it wont get called again
	 }

	 // -----------------------------------------------------------------
	/**
	  * get number of partitions.
	  * <p>
	  * note that this number may differ from number of automata!
	  * @return number of disjunctive partitions.
	  */
	 public int getSize() {
		 return count;
	 }

	/**
	 * get the <tt>i</tt>:th disjunctive partition of the transition relation, T~
	 * as a BDD
	 */
	public int getBDDTWave(int i) {
		return t_disj[i];
	}

	/**
	 * get the BDDAutomata object for these automata
	 */
	 public BDDAutomata getBDDAutomata() {
		 return automata;
	 }
}