package jdd.des.automata.bdd;

import jdd.bdd.*;
import jdd.util.*;

import jdd.graph.*;
import jdd.des.automata.*;


/**
 * Conjunctive partitioning of automata under full synchronous composition of Hoare
 * <p>
 *
 * A set of conjunctive partition is the composite transfer function divided under conjunction.
 */

public class ConjunctivePartitions {
	private BDDAutomata automata; /** the automata object */
	private int count; /** number of partitions */
	private int [] delta_conj; /** the conjunctive representation of the transition relation */
	private int [] s_cube; /** the cube for this partition */

	/**
	 * create a conjunctive representation for the automata in <tt>automata</tt>
	 */
	public ConjunctivePartitions(BDDAutomata automata) {
		this.automata = automata;
		count = automata.automata.length;


		// allocate and get the S-cube and delta-top
		delta_conj = new int[count];
		s_cube = new int[count];

		for(int i = 0; i < count; i++){
			delta_conj[i] = automata.ref( automata.automata[i].getBDDDeltaTop() );
			s_cube[i] = automata.ref( automata.automata[i].getBDDCubeS()  );
		}
	}


	/**
	 * cleanup the conjunctive partitions and free memory
	 */
	 public void cleanup() {
		 for(int i = 0; i < count; i++){
			 automata.deref(delta_conj[i]);
			 automata.deref(s_cube[i]);
		 }
		 count = 0; // so it wont get called again
	 }

	 // -----------------------------------------------------------------
	 /**
	  * get number of partitions.
	  * <p>
	  * note that this number may differ from number of automata!
	  * @return number of conjunctive partitions.
	  */
	 public int getSize() {
		 return count;
	 }

	 /**
	  * get the <tt>i</tt>:th cojunctive partition of the transition relation, T^
	  * as a BDD
	  */
	public int getBDDDeltaTop(int i) {
		return delta_conj[i];
	}

	/**
	 * return the cube for the from-state in partition <tt>i</tt>.
	 */
	public int getBDDSCube(int i) {
		return s_cube[i];
	}
	/**
	 * get the BDDAutomata object for these automata
	 */
	 public BDDAutomata getBDDAutomata() {
		 return automata;
	 }
}