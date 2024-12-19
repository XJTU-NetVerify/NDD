
package jdd.des.automata;

import jdd.graph.*;

/** A state s \in Q in an automaton */

public class State extends Node {

	/** symbolic stuff, might be used to store BDD/ZDD info. <b>DO NOT TOUCH!!!</b> */
	/* package */ int bdd_state, bdd_state_p;

	public State(String name, int id) {
		this(name, id, false, false, false);
	}

	public State(String name, int id, boolean initial, boolean marked, boolean forbidden) {
		super(id);
		setLabel(name);

		setInitial(initial);
		setMarked(marked);
		setForbidden(forbidden);
	}


	// ---------------------------------------------------
		/** copy the attributes of this node */
	public void copyAttributesFrom(State s) {
		super.copyAttributesFrom(s);
		this.bdd_state = s.bdd_state;
		this.bdd_state_p = s.bdd_state_p;
	}
	// ---------------------------------------------------

	public boolean isInitial() { return (flags & FLAGS_ROOT) != 0; }

	public boolean isMarked() { return (flags & FLAGS_MARKED) != 0; }

	public boolean isForbidden() { return (flags & FLAGS_BAD) != 0; }

	public void setInitial(boolean initial) {
		if(initial)	flags |= FLAGS_ROOT;
		else 		flags &= ~FLAGS_ROOT;
	}

	public void setMarked(boolean marked) {
		if(marked)	flags |= FLAGS_MARKED;
		else 		flags &= ~FLAGS_MARKED;
	}

	public void setForbidden(boolean forbidden) {
		if(forbidden)	flags |= FLAGS_BAD;
		else			flags &= ~FLAGS_BAD;
	}

	// --[ BDD STUFF, DO NOT TOUCH ]-------------------------------

	// XXX: ok these are PUBLIC, but should not be touched by the users
	// (yes, i need the C++ keyworf "friend" here)
	public void setBDDState(int s) { bdd_state = s; }
	public void setBDDStateP(int s) { bdd_state_p = s; }
	public int getBDDState() { return bdd_state; }
	public int getBDDStateP() { return bdd_state_p; }

	// -------------------------------------
	public String toString() { return label; }



}
