

package jdd.des.petrinets;

import jdd.util.*;
import jdd.des.petrinets.interactive.*;

import java.util.*;

/**
 * converts between interactive and normal petrints
 *
 */


// XXX: todo convert from petrinet to IPetrinet too

public class PetrinetTransform {
	public static Petrinet convert(IPetrinet pn) {
		Vector places = pn.getPlaces();
		Vector transitions = pn.getTransitions();
		HashMap placemap = new HashMap();

		Petrinet ret = new Petrinet(places.size(), transitions.size() );


		for (Enumeration e = places.elements() ; e.hasMoreElements();) {
			IPlace p = (IPlace) e.nextElement();
			Place p2 = new Place(p.name, p.tokens);
			ret.add( p2 );
			placemap.put(p.name, p2);
		}

		for (Enumeration e = transitions.elements() ; e.hasMoreElements();) {
			ITransition t = (ITransition) e.nextElement();
			Transition t2 = new Transition(t.name);
			t2.setControllable( t.isControllable() );
			t2.setObservable( t.isObservable() );
			ret.add( t2 );

			for (Enumeration e2 = t.incoming.elements() ; e2.hasMoreElements();) {
				Place p = (Place) placemap.get( ((IPlace)e2.nextElement()).name);
				ret.add(p, t2);
			}

			for (Enumeration e2 = t.outgoing.elements() ; e2.hasMoreElements();) {
				Place p = (Place) placemap.get( ((IPlace)e2.nextElement()).name);
				ret.add(t2, p);
			}
		}


		return ret;
	}

	// --------------------------------------------------------------------------------

}
