
package jdd.des.petrinets.interactive;


import java.util.*;

public class IPlace {
	public String name;
	public int tokens;
	public Vector incoming, outgoing;

	public IPlace(String name, int tokens) {
		this.name  = name; // XXX: do we need to clone this object in case the original is changed?
		this.tokens = tokens;
		incoming = new Vector();
		outgoing = new Vector();
	}

	public String toString() {		return name + ":" + tokens;	}
	public boolean equals(IPlace p) { return name.equals(p.name); }
}
