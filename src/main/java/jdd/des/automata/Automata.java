
package jdd.des.automata;

import jdd.util.*;
import jdd.graph.*;

import java.util.*;

/**
 * An automata object is a list of automata + the global alphabet (i.e., the EventManager)
 *
 */
public class Automata {
	/* package */ Vector automata = new Vector();
	/* package */ EventManager event_manager = new EventManager();
	/* package */ int extra1, extra2;
	private String name;

	public Automata() {
		this(null);
	}

	public Automata(String name) {
		this.name = name;
	}

	// -----------------------------------------------
	/** get the name of automata/problem. may return null */
	public String getName() { return name; }

	/** number of automata */
	public int size() { return automata.size(); }

	/** list of the automata */
	public Enumeration elements() { return automata.elements(); }

	/** list of the automata as an array */
	public Object [] toArray() { return automata.toArray(); }


	/** get the EventManager, the global alphabet */
	public EventManager getEventManager() { return event_manager; }



	/** create a new automaton by this name. make sure the name is unique */
	public Automaton add(String str) {
		Automaton ret = new Automaton(str, event_manager);
		automata.add(ret);
		return ret;
	}


	/** number of events in the global alphabet */
	public int numOfEvents() {
		return event_manager.getSize();
	}

	/** returns the number of nodes in the largest automata */
	public int maxNumOfLocalStates() {
		int ret = 0;
		for (Enumeration e = automata.elements() ; e.hasMoreElements() ;) {
			Automaton at = (Automaton) e.nextElement();
			ret = Math.max(ret, at.numOfNodes() );
		}
		return ret;
	}

	/** returns the theoretical maximum  number of states in the global automata */
	public double maxNumOfGlobalStates() {
		double ret = 1;
		for (Enumeration e = automata.elements() ; e.hasMoreElements() ;) {
			Automaton at = (Automaton) e.nextElement();
			if( at.numOfNodes() > 0) ret *=  at.numOfNodes();
		}
		return ret;
	}
}
