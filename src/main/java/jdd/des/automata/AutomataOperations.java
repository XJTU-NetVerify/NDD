

package jdd.des.automata;

import jdd.util.*;
import jdd.graph.*;
import java.util.*;
import java.io.*;

import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;


/**
 * Some simple operations on automaton structures
 *
 * @see Automaton
 * @see Automata
 */
public class AutomataOperations {

	/**
	 * This fix the problem with having an initial state that is also marked.
	 * In scheduling for example, often the goal state marked and if it is also initial then
	 * the scheduel will be empty.
	 * <br>
	 * This algorithm will create a new initial state. The language will be the same, but the
	 * marked language will not include EPSILON (from the first state)
	 * @throws AutomatonException something bad happened
	 */
	public static void distinguishMarkedAndInitial(Automaton a)
		throws AutomatonException
	{

		// if there is only one state, there is nothing to do!
		if(a.numOfNodes() <= 1) return;

		State initial = AutomataOperations.getInitialState(a);
		if(!initial.isMarked()) return; // nothing to do!

		// XXX: we should check if this is really needed, if for example, all or none of
		// the other states are marked!

		String name = AutomataOperations.safeStateName(a, initial.getLabel() + "_distinguished");
		State new_initial = a.addState(name);

		// copy the transitions of the old initial
		Transition t = (Transition) initial.firstOut;
		while(t != null) {
			Transition t2;

			if(t.n2 == t.n1) // selfloop!
				t2 = a.addTransition(new_initial, new_initial, t.event);
			else
				t2 = a.addTransition(new_initial, (State) t.n2, t.event);

			t2.copyAttributesFrom(t);
			t =(Transition) t.next;
		}



		new_initial.copyAttributesFrom(initial); // must have same attributes...

		// ...beside these:
		new_initial.setInitial(true);
		new_initial.setMarked(false);
		initial.setInitial(false);

	}


	// -----------------------------------------------------------------------
	/**
	 * suggest a non-existing state name for an automaton.
	 * @see State
	 * @see Automaton
	 */
	public static String safeStateName(Automaton a, String prefix) {
		for(int i = 1; ;i++) {
			String name = (i == 1) ? (prefix) : (prefix + "_" + i);
			if(a.findState(name) == null) return name;
		}
	}
	// -----------------------------------------------------------------------
	/**
	 * Find the initial state in an automaton.
	 * @see Automaton
	 * @see State
	 * @throws AutomatonException if there is either none or more than one initial state
	 */
	public static State getInitialState(Automaton a)
		throws AutomatonException
	{
		State ret = null;
		int count = 0;

		for (Enumeration e = a.getNodes().elements() ; e.hasMoreElements() ;) {
			State s = (State) e.nextElement();
			if(s.isInitial()) {
				ret = s;
				count++;
			}
		}

		if(count == 0) throw new AutomatonException("No initial state found");
		if(count != 1) throw new AutomatonException("Multiple (" + count + ") initial states found");
		return ret;
	}

	// -----------------------------------------------------------------------
		/**
		 * count the number of marked states in an automaton
		 * @see Automaton
		 * @see State
		 */
		public static int countMarked(Automaton a) {
			int count = 0;
			for (Enumeration e = a.getNodes().elements() ; e.hasMoreElements() ;) {
				State s = (State) e.nextElement();
				if(s.isMarked()) count++;
			}
			return count;
	}

	// -----------------------------------------------------------------------
	/**
	 * create an array with the members of this Automata object
	 *
	 * @see Automata
	 * @see Automaton
	 */
	public static Automaton [] asArray(Automata a) {
		Automaton [] ret = new Automaton[a.size()];
		int i = 0;
		for (Enumeration e = a.elements() ; e.hasMoreElements() ;i++)
			ret[i] = (Automaton) e.nextElement();
		return ret;
	}

	// -----------------------------------------------------------------------

	/**
	 * Retrun a new automaton name starting with "prefix" that is guranteed
	 * not to be in the automata object.
	 *
	 * @see Automaton
	 * @see Automata
	 */
	public static String getSafeName(Automata a, String prefix) {
		Set s = new HashSet();

		for (Enumeration e = a.elements() ; e.hasMoreElements() ;)
			s.add( ((Automaton) e.nextElement()).getName() );

		for(int i = 1; ; i++) {
			String name = (i == 1) ? prefix : prefix + i;
			if(!s.contains(name)) return name;
		}
	}

	// -----------------------------------------------------------------------
	/**
	 * return the set of events in these automatas alphabet.
	 * Note: they should all belong to same Automata
	 *
	 */
	public static Set getUnionAlphabet(Automaton [] a) {
		Set ret = new HashSet();

		for(int i = 0; i < a.length; i++) {
			for(Event e = a[i].getAlphabet().head(); e != null; e = e.next) {
				ret.add(e.parent);
			}
		}
		return ret;
	}

	// -----------------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("AutomataOperations");
		try {
			Automata as = new Automata();
			Automaton aut = as.add("test");

			Event e1 = aut.addEvent("a");
			Event e2 = aut.addEvent("b");

			State s1 = aut.addState("0");
			State s2 = aut.addState("1");
			State s3 = aut.addState("2");

			Transition t1 = aut.addTransition(s1,s2,e1);
			Transition t2 = aut.addTransition(s1,s3,e2);
			s1.setInitial(true);
			s1.setMarked(true);

			//aut.showDot("a1");
			Test.checkEquality(1, AutomataOperations.countMarked(aut), "count marked" );
			Test.check( s1 == AutomataOperations.getInitialState(aut), "getInitialState");



			// check the distinguishMarkedAndInitial stuff...
			AutomataOperations.distinguishMarkedAndInitial(aut);
			// aut.showDot("a2");

			State initial = AutomataOperations.getInitialState(aut);
			Test.check( s1 != initial, "distinguishMarkedAndInitial (1)");
			Test.check( !s1.isInitial(), "distinguishMarkedAndInitial (2)");

			Test.checkEquality( 4, aut.numOfNodes(), "distinguishMarkedAndInitial (3)"); // plus one
			Test.checkEquality( 4, aut.numOfEdges(), "distinguishMarkedAndInitial (4)"); // plus two

		} catch(Exception e) {
			Test.check(false, e.toString());
		}

		Test.end();
	}
}


