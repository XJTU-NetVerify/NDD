
package jdd.des.automata;

import jdd.graph.*;
import jdd.util.*;

import java.util.*;

/**
 * An event in its DES meaning, see also Alphabet
 * @see Alphabet
 * @see EventManager
 */

public class Event extends Flags {
	/** flags for Event. */
	public static final int  FLAG_CONTROLLABLE = 0, FLAG_OBSERVABLE = 1;

	private static int event_id = 0; // ignore this internal variable, used to generate unqieue ids */

	private String label;
	/* package */ int id; /** id is guaranteed to be unique */

	/** extra1 and extra2 are used and _changed_ internally be some algos */
	public int extra1, extra2;

	/** next is for the linked list of alphabeth. If this is a local event, then parnet points to the original in EventManager */
	public Event next, parent;

	/** this extra variable  may be used by some algos to store the probability of firiing/whatever here */
	public double probability, weight;

	/** which automata are currently using this event ? */
	public Vector users;

	/** create an observable and  controllable event with this label */
	public Event(String label) {
		this(label, true);
	}

	/** create an observable event with this label */
	public Event(String label, boolean controllable) {

		this.label = label;
		this.id = event_id++;
		this.next = null;
		this.parent = null;
		this.users = null;
		this.weight = 1.0;

		set(FLAG_CONTROLLABLE, controllable);
		set(FLAG_OBSERVABLE, true); // we assume that it is observable by default
	}


	// -------------------------------------------------

	// short-cuts for the flags
	public boolean isControllable() { return get(FLAG_CONTROLLABLE); }
	public void setControllable(boolean c) { set(FLAG_CONTROLLABLE, c); }

	public boolean isObservable() { return get(FLAG_OBSERVABLE); }
	public void setObservable(boolean c) { set(FLAG_OBSERVABLE, c); }

	public String getLabel() { return label; }
	public String toString() { return label; }

	// -------------------------------------------------

	/** returns the number of automata that havethis event in their alphabets */
	public int getUsers() {
		return (parent != null) ? parent.getUsers() : users.size();
	}

	/** set the weight/cost for this event in the alphabet */
	public void setWeight(double w) {
		if(parent != null) parent.setWeight(w);
		weight = w;
	}

	/** get the weight/cost for this event in the alphabet */
	public double getWeight() {
		return (parent != null) ? parent.getWeight() : weight;
	}
	// ----------------------------------------------
	public void copyAttibutes(Event e) {

		this.label = e.label;
		this.copyFlags(e);
		this.extra1 = e.extra1;
		this.extra2 = e.extra2;
		this.probability = e.probability;
		this.weight = e.weight;
	}

	// --- [ BDD stuff ] ------------------------------------
	/**
	 * symbolic stuff, might be used to store BDD/ZDD info. <b>DO NOT TOUCH!!!</b><br><br>
	 * Note: this element is only valid if it comes from an EventManager, inside a regular Alphabet,
	 * you must use its "parent" instead!
	 * @see jdd.des.automata.bdd.BDDAutomata
	 * @see EventManager
	 */
	/* package */ int bdd_event;

	public void setBDD(int bdd) { bdd_event = bdd; }
	public int getBDD() { return bdd_event; }
}
