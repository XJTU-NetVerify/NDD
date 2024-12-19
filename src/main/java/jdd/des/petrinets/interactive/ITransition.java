
package jdd.des.petrinets.interactive;


import java.util.*;
import jdd.util.*;

public class ITransition extends Flags {
	/** flags for ITransition */
	public static final int  FLAG_CONTROLLABLE = 0, FLAG_OBSERVABLE = 1;

	public String name;
	public Vector incoming, outgoing;

	public ITransition(String name) {
		this.name  = name; // XXX: do we need to clone this object in case the original is changed?
		this.incoming = new Vector();
		this.outgoing = new Vector();

		set(FLAG_CONTROLLABLE, true); // defualt
		set(FLAG_OBSERVABLE, true);	// defualt
	}


	// ----------------------------------------------------

	public String toString() {		return name ;	}
	public boolean equals(ITransition t) { return name.equals(t.name); }

	// short-cuts for the flags
	public boolean isControllable() { return get(FLAG_CONTROLLABLE); }
	public void setControllable(boolean c) { set(FLAG_CONTROLLABLE, c); }

	public boolean isObservable() { return get(FLAG_OBSERVABLE); }
	public void setObservable(boolean c) { set(FLAG_OBSERVABLE, c); }

}
