
package jdd.des.automata;

import jdd.util.*;
import jdd.graph.*;
import java.util.*;

/**
 * An automaton, FA=(Q,Sigma,delta, q_i,...)
 * @see State
 * @see Event
 * @see Transition
 * @see Alphabet
 * @see EventManager
 * @see Automata
 */

public class Automaton extends Graph {
	private String name, type;
	private Alphabet alphabet;
	private EventManager event_manager;
	public int extra1, extra2, extra3;

	/** create a new automaton. you probebly wont need to use this constructor. use Automata.add(name) instead */
	public Automaton(String name, EventManager event_manager) {
		super(true);
		this.name = name;
		this.type = null;
		this.alphabet = new Alphabet();
		this.event_manager = event_manager;
		this.automaton_locked = false;
	}


	// ---------------------------------------------------
	/** get the alphabet of this automaton */
	public Alphabet getAlphabet() { return alphabet; }

	/** get the name of this automaton */
	public String getName() { return name; }

	/** get the type of this automaton, may return null */
	public String getType() { return type; }

	/** set the type of this automaton */
	public void setType(String type) { this.type = type; }

	/* how many events are we sharing with this automaton */
	public int sharedEvents(Automaton a) { return alphabet.sharedEvents( a.alphabet) ; }

	// ---------------------------------------------------

	/** create a new event. name must be unique. the new event is by default controllable  */
	public Event addEvent(String name) {	return addEvent(name, true); }

		/** create a new event. name must be unique */
	public Event addEvent(String name, boolean controllable) {
		Event e1 = alphabet.add(name, controllable);
		event_manager.registerEvent(e1, this);
		return e1;
	}

	/** create a new state. if label already exists its state will be returned */
	public State addState(String label) {
		State s = findState(label);
		if(s == null) {
			s = new State(label, count_nodes++);
			nodes.add(s);
		}
		return s;

	}

	/** find an state by label. returns null if state not found */
	public State findState(String label) {
		for (Enumeration e = nodes.elements() ; e.hasMoreElements() ;) {
			State s = (State) e.nextElement();
			if(s.label.equals(label))
			return s;
		}
		return null;
	}

	/** remove the state (and all associated transitions)  */
	public void removeState(State s) {
		removeNode(s);
	}
	// ---------------------------------------------------


	public Transition addTransition(State from, State to, Event event) {
		Transition t = findTransition(from, to, event);

		if(t == null) {
			t = new Transition(from,to,event);
			edges.add(t);
			t.next = from.firstOut;	from.firstOut = t;
			t.prev = to.firstIn;	to.firstIn  = t;
		}
		return t;
	}

	public Transition findTransition(State from, State to, Event event) {
		for (Enumeration e = edges.elements() ; e.hasMoreElements() ;) {
			Transition edge = (Transition) e.nextElement();
			if(edge.n1 == from && edge.n2 == to && edge.event == event)
				return edge;
		}
		return null;
	}

	public void removeTransition(Transition t)  {
		removeEdge(t);
	}


	// ---[BDD STUFF ]--------------------------------------------
	/** the automaton is somehow in use and should not be used by anyone else, used in BDD stuff. @see BDDAutomata */
	/* package */ boolean automaton_locked;
	public boolean isAutomatonLocked() { return automaton_locked; }

	public void lockAutomaton() {
		if(automaton_locked)
			throw new IllegalArgumentException("Automaton " + getName() + " is already used in another object (BDDAutomata ?)!");
			automaton_locked = true;
	}

	public void unlockAutomaton() {
			if(!automaton_locked)
				throw new IllegalArgumentException("Automaton " + getName() + " was not locked piror to unlock!");
				automaton_locked = false;
	}

	// -------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("Automaton");
		Automata as = new Automata();
		Automaton aut = as.add("test");

		Event e1 = aut.addEvent("a");
		Event e2 = aut.addEvent("b");
		Event e3 = aut.addEvent("c");

		State s1 = aut.addState("0");
		State s2 = aut.addState("1");
		State s3 = aut.addState("2");

		s1.setInitial(true);
		s3.setMarked(true);

		Transition t1 = aut.addTransition(s1,s2,e1);
		Transition t2 = aut.addTransition(s2,s3,e2);
		Transition t3 = aut.addTransition(s3,s1,e3);


		Test.check(s1.isInitial(), "Initial");
		Test.check(s3.isMarked(), "Marked");
		Test.check(!s3.isForbidden(), "!Forbidden");

		Test.checkEquality( aut.numOfNodes(), 3, "3 states (1)");
		Test.checkEquality( aut.numOfEdges(), 3, "3 transitions (1)");

		aut.addState("0");
		aut.addTransition(s3,s1,e3);
		aut.addTransition(s3,s1,e3);
		Test.checkEquality( aut.numOfNodes(), 3, "3 states (2)");
		Test.checkEquality( aut.numOfEdges(), 3, "3 transitions (2)");

		aut.removeState(s1);
		Test.checkEquality( aut.numOfNodes(), 2, "2 states (3)");
		Test.checkEquality( aut.numOfEdges(), 1, "1 transitions (3)");

		aut.removeTransition(t2);
		Test.checkEquality( aut.numOfNodes(), 2, "2 states (4)");
		Test.checkEquality( aut.numOfEdges(), 0, "0 transitions (4)");




		Test.end();
	}
}
