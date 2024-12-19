
package jdd.des.petrinets;


import jdd.util.*;
import jdd.bdd.*;
import jdd.zdd.*;

import java.util.*;

/**
 * This class reads an ordinary Petrinet and does all additional work needed to begin
 * symbloic computation. this includes allocating zdd variables and computing *t, t* etc...
 */

public class SymbolicPetrinet extends ZDDPN {

	// --- [INTERNAL CLASS: SymbolicPetrinetNames ] --------------
	public class SymbolicPetrinetNames extends  ZDDNames {
		public String variable(int n) {
			if(n < 0) return "(none)";
			return pn.getPlaceByIndex(n).getName();
		}
	}

	// ----------------------------------------------------------

	private Petrinet pn;
	private int zdd_M0; /** the initial marking */

	/**
	 * create a symbolic version of a ONE-SAFE petri net
	 * @throws IllegalArgumentException when the petri net is not one-safe
	 */
	public SymbolicPetrinet(Petrinet target)
		throws IllegalArgumentException
	{
		super(10000, 1000); // XXX: to be changed
		this.pn = target;
		this.nodeNames = new SymbolicPetrinetNames();

		compute_symbolic_variables();
	}

	public void cleanup() {
		deref(zdd_M0);
		// TODO: cleanup all ZDD vars in transitions
		super.cleanup();
	}


	private void compute_symbolic_variables()
		throws IllegalArgumentException
	{
		zdd_M0 = 1;


		int n = pn.numberOfPlaces();
		for(int i = 0; i < n; i++) {
			Place p = pn.getPlaceByIndex(i);


			// we dont like petri net places with too many tokens
			int tokens = p.getTokens();
			if(tokens != 0 && tokens != 1) {
				throw new IllegalArgumentException("Place " + p.getName() + " should have zero or one tokens instead of " + tokens + "!");
			}

			p.zdd_place = createVar();
			if(tokens == 1) {
				int tmp = ref( change(zdd_M0, p.zdd_place) );
				deref(zdd_M0);
				zdd_M0 = tmp;
			}
		}

		n = pn.numberOfTransitions();
		PlaceEnumeration pe = pn.createPlaceEnumeration();
		Place tmp;
		for(int i = 0; i < pn.transitions.length; i++) {
			Transition t = pn.transitions[i];
			t.zdd_ot = 1;
			t.zdd_to = 1;

			// compute zdd_ot
			pn.incomingPlaces(pe, t);
			while( (tmp = pe.nextPlace()) != null) {
				int x= ref( change(t.zdd_ot, tmp.zdd_place) );
				deref(t.zdd_ot);
				t.zdd_ot = x;
			}


			pn.outgoingPlaces(pe, t);
			while( (tmp = pe.nextPlace()) != null) {
				int x= ref( change(t.zdd_to, tmp.zdd_place) );
				deref(t.zdd_to);
				t.zdd_to = x;
			}

			t.zdd_otUto = ref( Change(t.zdd_ot, t.zdd_to) );
		}
	}

	// ----------------------------------------------------------

	/** compute [M0> */
	public int forward() {
		return forward(zdd_M0, pn.transitions);
	}

	/** compute reduced [M0> by partial order */




	// -------------------------------------------------------
	/** Partial order helper function. */
	private int [] sigma_ready; /** used internally by partial order code */
	private int sigma_tmp;  /** partial order, internal use */

	private int  enabledInFuture(int sigma, Transition tc, TreeSet TD) {
		if( TD.contains(tc)) return 0;
		int index = tc.index;

		int tmp = ref(enabled(sigma, tc) );
		if(tmp != 0) sigma_ready[index] = union(sigma_ready[index], tmp);
		int sigma_p = ref( diff( sigma, tmp) );
		deref(tmp);

		PlaceEnumeration pe = pn.createPlaceEnumeration();
		TransitionEnumeration te = pn.createTransitionEnumeration();
		Place p;
		Transition t;
		for(pn.incomingPlaces(pe,tc); (p = pe.nextPlace() ) != null; ) {
			int tmp2 = ref(subset0(sigma_p, p.zdd_place));
			int sigma_pp = ref(tmp2);
			int sigma_tmp2 = 0;
			for(pn.outgoingTransitions(te,p); (t = te.nextTransition()) != null; ) {
				boolean added = TD.add(t);
				tmp = ref( enabledInFuture(sigma_pp, t, TD) );
				if(tmp != 0) sigma_tmp2 = unionTo(sigma_tmp2, tmp);
				if(added) TD.remove(t);
				deref(tmp);
			}

			tmp = ref( diff( sigma_pp, sigma_tmp2));
			sigma = diffTo(sigma, tmp);
			deref(tmp);

			tmp = ref(subset0(sigma_p, tmp2));

			deref(tmp2);
			deref(sigma_pp);
			deref(sigma_tmp2);
		}

		return sigma;
	}
	// -------------------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("SymbolicPetrinet");


		// those this test suck or what??
		SymbolicPetrinet sp = new SymbolicPetrinet( PetrinetIO.loadXML("data/pn.xml") );
		int R = sp.forward();
		Test.checkEquality(sp.count(R), 4, "4 reachable markings");
		sp.cleanup();

		// stupid large PN
		sp = new SymbolicPetrinet( PetrinetIO.loadXML("data/largepn1.xml") );
		R = sp.forward();
		Test.checkEquality(sp.count(R), 43046721, "43046721 reachable markings");
		sp.cleanup();

		// another stupid but not equally large PN
		sp = new SymbolicPetrinet( PetrinetIO.loadXML("data/largepn2.xml") );
		R = sp.forward();
		Test.checkEquality(sp.count(R), 729, "729 reachable markings");
		sp.cleanup();

		// the famous AGV Petri net model
		sp = new SymbolicPetrinet( PetrinetIO.loadXML("data/agv.xml") );
		R = sp.forward();
		Test.checkEquality(sp.count(R), 30965760, "30965760 reachable markings");
		sp.cleanup();

		Test.end();
	}


	/**
	 * Writes the number of reachable marking vectors (PN states) in the given
	 * PN model (in JDD XML format)
	 */
	public static void main(String [] args) {
		for(int i = 0; i< args.length; i++) {
			long time = System.currentTimeMillis();
			SymbolicPetrinet sp = new SymbolicPetrinet( PetrinetIO.loadXML(args[i]) );
			int R = sp.forward();
			int size = sp.count(R);
			time = System.currentTimeMillis() - time;
			long mem = (sp.getMemoryUsage() + 512) / 1024;
			System.out.println(args[i] + ": | [m0> | = " + size);
			System.out.println("Time = " + time + " [ms], Memory = " + mem  + "[kB]");
			sp.cleanup();
		}
	}
}
