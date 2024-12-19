
package jdd.des.petrinets;


import jdd.util.*;

/**
 * A Place in a Petri net.
 */

// NOTE:
// if you add anything here, make sure that you also modify IPlace and PetrinetTransform too!

public class Place {
	private String name;
	private int tokens;
	/* package */ int index;

	/** symbolic stuff, might be used to store BDD/ZDD info */
	public int zdd_place;

	public Place(String name, int tokens) {
		this.name  = name;
		this.tokens = tokens;
		this.index = -1;

		this.zdd_place = 0; // to be added later
	}

	// ---------------------------------------------------
	public int getTokens() { return tokens; }
	public void setTokens(int v) { tokens = v; }

	public String getName() { return name; }
	public String toString() { return name; }

}
