
package jdd.des.petrinets;

import jdd.util.*;


public class TransitionEnumeration extends PNEnumeration {
	private Petrinet pn;
	/* package */ TransitionEnumeration(Petrinet pn) {
		this.pn = pn;
	}

	public void free() { pn = null; }

	public Transition nextTransition() {
		return empty() ? null : pn.getTransitionByIndex( next() );
	}

	public void show() {
		JDDConsole.out.print("<");
		Transition t;
		while ( (t = nextTransition()) != null) JDDConsole.out.print(" " + t.getName());

		JDDConsole.out.println(" >");
	}


}
