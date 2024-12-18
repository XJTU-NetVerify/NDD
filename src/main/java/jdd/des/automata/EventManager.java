
package jdd.des.automata;

import jdd.util.*;
import jdd.graph.*;
import java.util.*;


/** <pre>
 * An event manager is the super-alphabet.
 * Every alphabets events is a subset of the event-managers event set.
 *
 * When you have a automata-level event, check its "parnet"-field to get automata-level
 * event. This is used, for example fot *DD variable allocation when same events in
 * two different automata will get the same variable assigned this way...
 * </pre>
 *
 * @see Alphabet
 * @see Automata
 * @see Event
 */

public class EventManager extends Alphabet {
	public EventManager() {
	}

	public void registerEvent(Event ev, Automaton owner) {
		Event e = add(ev.getLabel() , ev.isControllable() );
		ev.parent = e;

		if(ev.parent.users == null) ev.parent.users = new Vector();
		ev.parent.users.add(owner);
	}

}
