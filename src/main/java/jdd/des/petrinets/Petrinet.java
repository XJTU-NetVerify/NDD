
package jdd.des.petrinets;

import jdd.util.*;
import jdd.util.math.*;

/**
 * more efficient by less friendly petrinet class.
 */

public class Petrinet {
	private int num_places, num_transitions, curr_places, curr_transitions;
	/* package */ Place [] places;
	/* package */ Transition [] transitions;
	/* package */ BitMatrix a_plus, a_minus;
	/* package */ int [] M_i;

	public Petrinet(int ps, int ts) {
		num_places = ps;
		num_transitions = ts;
		curr_places = curr_transitions = 0;

		places = new Place[num_places];
		transitions = new Transition[num_transitions];

		a_plus  = new BitMatrix(ts, ps);
		a_minus = new BitMatrix(ts, ps);
		M_i     = new int[ps];
		Array.set(M_i, 0);
	}

	public int numberOfPlaces() { return curr_places; }
	public int numberOfTransitions() { return curr_transitions; }

	public Transition getTransitionByIndex(int index) { return  transitions[index]; }
	public Place getPlaceByIndex(int index) { return  places[index]; }

	// --------------------------------------------------------------------

	public void add(Place p) {
		if(curr_places >= num_places) return; // XXX: no  error message?
		places[curr_places] = p;
		p.index = curr_places;
		M_i[curr_places]= p.getTokens();
		curr_places ++;
	}

	public void add(Transition t) {
		if(curr_transitions >= num_transitions) return; // XXX: no  error message?
		transitions[curr_transitions] = t;
		t.index = curr_transitions;
		curr_transitions ++;
	}


	// --------------------------------------------------------------------
	public void add(Place p, Transition t) {
		a_minus.set(t.index, p.index);
	}

	public void add(Transition t, Place p) {
		a_plus.set(t.index, p.index);
	}

	// --------------------------------------------------------------------
	public TransitionEnumeration createTransitionEnumeration() {
		return new TransitionEnumeration(this);
	}
	public void incomingTransitions(TransitionEnumeration te, Place p) {
		te.init(p.index, num_places, num_transitions, a_plus.getSet() );
	}
	public void outgoingTransitions(TransitionEnumeration te, Place p) {
		te.init(p.index, num_places, num_transitions, a_minus.getSet() );
	}

	// --------------------------------------------------------------------

	public PlaceEnumeration createPlaceEnumeration() {
		return new PlaceEnumeration(this);
	}
	public void incomingPlaces(PlaceEnumeration te, Transition t) {
		te.init(t.index * num_places, 1, num_places, a_minus.getSet() );
	}
	public void outgoingPlaces(PlaceEnumeration te, Transition t) {
		te.init(t.index * num_places, 1, num_places, a_plus.getSet() );
	}



	public void show() { PetrinetIO.show(this); }

	// --------------------------------------------------------------------

	/* package */ BitMatrix getAPlus() { return a_plus; }
	/* package */ BitMatrix getAMinus() { return a_minus; }
	/* package */ int[] getM() { return M_i; }

	// --------------------------------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("Petrinet");

		Petrinet pn = new Petrinet(4,3);

		Place p1 = new Place("p1", 1);
		Place p2 = new Place("p2", 0);
		Place p3 = new Place("p3", 0);
		Place p4 = new Place("p4", 0);

		pn.add(p1);
		pn.add(p2);
		pn.add(p3);
		pn.add(p4);
		Test.checkEquality( pn.numberOfPlaces(), 4, "4 places");
		Test.check( pn.M_i[0] == 1);
		Test.check( pn.M_i[1] == 0);
		Test.check( pn.M_i[2] == 0);
		Test.check( pn.M_i[3] == 0);


		Transition t1 = new Transition("t1");
		Transition t2 = new Transition("t2");
		Transition t3 = new Transition("t3");
		pn.add(t1);
		pn.add(t2);
		pn.add(t3);
		Test.checkEquality( pn.numberOfTransitions(), 3, "3 transitions");



		pn.add(p1, t1);
		pn.add(p1, t2);

		pn.add(t1, p2);
		pn.add(t1, p3);

		pn.add(t2, p3);
		pn.add(p3, t3);
		pn.add(t3, p4);


		TransitionEnumeration te = pn.createTransitionEnumeration();
		pn.incomingTransitions(te, p3);
		Test.checkEquality( te.getSize(),  2, " | -p3 |");
		pn.outgoingTransitions(te, p3);
		Test.checkEquality( te.getSize(),  1, " | p3- |");

		PlaceEnumeration pe = pn.createPlaceEnumeration();
		pn.incomingPlaces(pe, t2);
		Test.checkEquality( pe.getSize(),  1, " | *t2 |");
		pn.outgoingPlaces(pe, t1);
		Test.checkEquality( pe.getSize(),  2, " | t1* |");

		Test.end();
	}
}
