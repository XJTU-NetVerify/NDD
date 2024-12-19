
package jdd.des.petrinets;

import jdd.util.*;

/**
 * A Place in a Transition net.
 */

// NOTE:
// if you add anything here, make sure that you also modify ITransition and PetrinetTransform too!

public class Transition extends Flags {
	/** flags for Transition. */
	public static final int  FLAG_CONTROLLABLE = 0, FLAG_OBSERVABLE = 1;

	private String name;
	/* package */ int index;

	/** symbolic stuff, might be used to store BDD/ZDD info */
	public int zdd_to, zdd_ot, zdd_otUto; // disjoint union, o represents the filled DOT here :)

	public Transition(String name) {
		set(FLAG_CONTROLLABLE, true); // defualt
		set(FLAG_OBSERVABLE, true);	// defualt

		this.name  = name;
		this.index = -1;

		this.zdd_to = this.zdd_ot = this.zdd_otUto = 0; // to be added later
	}

	// ---------------------------------------------------------

	public String getName() { return name; }
	public String toString() { return name; }


	// short-cuts for the flags
	public boolean isControllable() { return get(FLAG_CONTROLLABLE); }
	public void setControllable(boolean c) { set(FLAG_CONTROLLABLE, c); }

	public boolean isObservable() { return get(FLAG_OBSERVABLE); }
	public void setObservable(boolean c) { set(FLAG_OBSERVABLE, c); }

}
