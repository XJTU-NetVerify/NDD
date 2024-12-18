
package jdd.des.automata;

import jdd.graph.*;

/**
 * The alphabet of one automaton.
 * Note that the alphabet of the whole project is handled by EventManager
 * @see EventManager
 * @see Automaton
 * @see Event
 */

public class Alphabet {
	/** Alphabt uses a linked list to store the events, root and curr point to the ends of this list */
	/* package */ Event root, curr;
	private int size;

	public Alphabet() {
		root = curr = null;
		size = 0;
	}

	// ------------------------------------------------------------

	/** size of the alphabet */
	public int getSize() { return size; }

	/** head of the linked-list*/
	public Event head() { return root; }
	// ------------------------------------------------------------

	/** create and add an event with given controllability. doesnt register to EventManager! */
	/* package */ Event add(String label, boolean c) {
		Event e = findByLabel(label);
		if(e == null) {
			size++;
			e = new Event(label);
			if(root == null)	root = e;
			else curr.next = e;
			curr = e;
		}
		e.set( Event.FLAG_CONTROLLABLE, c);

		return e;
	}


	// ------------------------------------------------------------

	/** find an event that has this ID, returns null if not found */
	public Event findByID(int id) {
		Event tmp = root;
		while(tmp != null) {
			if(tmp.id == id) return tmp;
			tmp = tmp.next;
		}
		return null;
	}

	/** find an event that has this LABEL, returns null if not found */
	public Event findByLabel(String label) {
		Event tmp = root;
		while(tmp != null) {
			if(tmp.getLabel().equals(label)) return tmp;
			tmp = tmp.next;
		}
		return null;
	}

	/** [INTERNAL] find an event by its declaration order in the alphabet */
	public Event findByOrder(int x) {
		int i = 0;
		Event tmp = root;

		while(true) {
			if(tmp == null || i == x) return tmp;
			i++;
			tmp = tmp.next;
		}
	}

	// --------------------------------------------

	/**
	 * retruns the number of events that this alphabet shares with another alphabet.<br>
	 * it is very important that we do not change the structure of this functions, as
	 * other operations DEPEND on how *.parent.extra1 is set here!
	 */

	public int sharedEvents(Alphabet a) {
		Event tmp;
		int ret = 0;
		for(tmp = root; tmp != null; tmp = tmp.next) tmp.parent.extra1 = 0;
		for(tmp = a.root; tmp != null; tmp = tmp.next) tmp.parent.extra1 = 1;
		for(tmp = root; tmp != null; tmp = tmp.next)  if(tmp.parent.extra1 == 1) ret ++;
		return ret;
	}
}
