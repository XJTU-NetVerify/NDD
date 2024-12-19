
package jdd.des.petrinets.interactive;


import jdd.util.*;

import java.util.*;

/**
 This Petrinet class allows you to add/remove places/transition/arcs more  freely
*/

public class IPetrinet {
	public Vector places, transitions;

	public IPetrinet() {
		places = new Vector();
		transitions = new Vector();
	}

	// -----------------------------------------------------------------------

	public IPlace getPlaceByName(String name) {
		for (Enumeration e = places.elements() ; e.hasMoreElements() ;) {
	         IPlace place = (IPlace) e.nextElement();
	         if(place.name.equals(name)) return place;
	     }
	     return null;
	}


	public ITransition getTransitionByName(String name) {
		for (Enumeration e = transitions.elements() ; e.hasMoreElements() ;) {
			 ITransition t = (ITransition) e.nextElement();
			 if(t.name.equals(name)) return t;
		 }
		 return null;
	}

	// -----------------------------------------------------------------------

	public void add(IPlace p) {
		IPlace old = getPlaceByName(p.name);
		if(old == null) places.add(p);
		// else System.err.println(p + " already in this petrinet");
	}

	public void remove(IPlace p) {
		for (Enumeration e = p.incoming.elements() ; e.hasMoreElements();)  {
			 ITransition t = (ITransition) e.nextElement();
			 t.outgoing.remove(p);
		 }

		for (Enumeration e = p.outgoing.elements() ; e.hasMoreElements();)  {
			 ITransition t = (ITransition) e.nextElement();
			 t.incoming.remove(p);
		 }

		 p.incoming.removeAllElements();
		 p.outgoing.removeAllElements();
		 places.remove(p);
	}

	// -----------------------------------------------------------------------

	public void add(ITransition t) {
		ITransition old = getTransitionByName(t.name);
		if(old == null) transitions.add(t);
		// else System.err.println(t + " already in this petrinet");
	}


	public void remove(ITransition t) {
		for (Enumeration e = t.incoming.elements() ; e.hasMoreElements();)  {
			IPlace p = (IPlace) e.nextElement();
			p.outgoing.remove(t);
		}

		for (Enumeration e = t.outgoing.elements() ; e.hasMoreElements();)  {
			IPlace p = (IPlace) e.nextElement();
			p.incoming.remove(t);
		}

		t.incoming.removeAllElements();
		t.outgoing.removeAllElements();
		transitions.remove(t);
	}

	// -----------------------------------------------------------------------
	public void add(IPlace p, ITransition t) {
		IPlace p1 = getPlaceByName(p.name);
		if(p1 == null)	places.add(p); else p = p1;

		ITransition t1 = getTransitionByName(t.name);
		if(t1 == null) transitions.add(t); else t = t1;

		p.outgoing.remove(t);
		p.outgoing.add(t);

		t.incoming.remove(p);
		t.incoming.add(p);
	}

	public void add(ITransition t, IPlace p) {
		IPlace p1 = getPlaceByName(p.name);
		if(p1 == null)	places.add(p); else p = p1;

		ITransition t1 = getTransitionByName(t.name);
		if(t1 == null) transitions.add(t); else t = t1;

		p.incoming.remove(t);
		p.incoming.add(t);

		t.outgoing.remove(p);
		t.outgoing.add(p);
	}

	// -----------------------------------------------------------------------
	public Vector getPlaces() { return places; }
	public Vector getTransitions() { return transitions; }

	// -----------------------------------------------------------------------

	public void show() {

		JDDConsole.out.print("\nPlaces: ");
		for (Enumeration e = places.elements() ; e.hasMoreElements();) JDDConsole.out.print(" " + e.nextElement());
		JDDConsole.out.println();

		JDDConsole.out.print("Transitions: ");
		for (Enumeration e = transitions.elements() ; e.hasMoreElements();) JDDConsole.out.print(" " + e.nextElement());
		JDDConsole.out.println();

		for (Enumeration e = transitions.elements() ; e.hasMoreElements();)  {
			 ITransition t = (ITransition) e.nextElement();
			 JDDConsole.out.print(t + ":");
			 for (Enumeration e2 = t.incoming.elements() ; e2.hasMoreElements();)
			 	JDDConsole.out.print(" " + ((IPlace)e2.nextElement()).name);

			 JDDConsole.out.print(" :");

			 for (Enumeration e2 = t.outgoing.elements() ; e2.hasMoreElements();)
			 	JDDConsole.out.print(" " + ((IPlace)e2.nextElement()).name);

			 JDDConsole.out.println();
		}

	}
	// -----------------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("IPetrinet");

		IPetrinet ip = new IPetrinet();

		ITransition t1 = new ITransition("t1");
		ITransition t2 = new ITransition("t2");

		IPlace p1 = new IPlace("p1", 1);
		IPlace p2 = new IPlace("p2", 0);
		IPlace p3 = new IPlace("p3", 0);

		ip.add(t1);	ip.add(t1);		ip.add(t2);


		ip.add(p1);	ip.add(p1);	ip.add(p2);	ip.add(p2);	ip.add(p3);

		ip.add(p1,t1);
		ip.add(t1,p2);
		ip.add(p2,t2);
		ip.add(t2,p3);

		Test.checkEquality(ip.places.size(), 3, "3 places");
		Test.checkEquality(ip.transitions.size(), 2, "2 transitions");

		ip.remove(p2);
		Test.checkEquality(ip.places.size(), 2, "3-1 places");

		ip.remove(t2);
		Test.checkEquality(ip.transitions.size(), 1, "2-1 transitions");


		Test.end();
	}
}
