
package jdd.des.automata;

import jdd.graph.*;

/** A transition t \in \delta in an automaton */

public class Transition extends Edge {
	private static int transition_id = 0;
	public Event event;

	public Transition(State from, State to, Event e) {
		super(from, to, transition_id++);
		this.event = e;
	}

	public String getLabel() { return event.getLabel(); }

	public String toString() {
		return "<" + n1 + "," + event + "," + n2 + ">";
	}



	// XXX: should we just ignore the transition weight??
	public double getWeight() {
		return event.getWeight();
	}

	// ------------------------------------------------
	/** copy attributes of this transition to me */
	public void copyAttributesFrom(Transition t) {
		super.copyAttributesFrom(t);
	}

}
