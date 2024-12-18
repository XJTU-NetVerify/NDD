
package jdd.des.petrinets;

import jdd.util.*;


public class PlaceEnumeration extends PNEnumeration {
	private Petrinet pn;
	/* package */ PlaceEnumeration(Petrinet pn) {
		this.pn = pn;
	}

	public void free() { pn = null; }

	public Place nextPlace() {
		return empty() ? null : pn.getPlaceByIndex( next() );
	}

	public void show() {
		JDDConsole.out.print("<");
		Place p;
		while ( (p = nextPlace()) != null) JDDConsole.out.print(" " + p.getName());
		JDDConsole.out.println(" >");
	}
}
