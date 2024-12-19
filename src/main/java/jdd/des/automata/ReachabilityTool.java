


package jdd.des.automata;

import jdd.util.*;
import jdd.util.pool.*;
import jdd.graph.*;
import jdd.util.sets.*;

import java.util.Enumeration;
import java.util.Iterator;


/**
 * simple (andprobably not very efficient) algorithm for traversing the reachable states
 * of a set of automata.
 *
 *
 * <p><b>Note 1:</b> To save memory, call cleanup() when you are done with this object.
 *
 * <p><b>Note 2:</b> The returned sets are valid only as long as the ReachabilityTool object
 * is valid. A call to cleanup() will make all returned Set:s invalid.
 *
 * <p><b>Note 3:</b> Exactly how the reachable sets are stored is defined by the variable
 * Configuration-automataStateSetType
 *
 * <p>
 * TODO: backward reachability
 *
 * @see jdd.bdd.sets.BDDSet
 * @see AutomataComposer
 * @see jdd.util.Configuration
 */


public class ReachabilityTool {

	protected Automaton [] automata;
	protected Event [] alphabet;
	protected int size, alphabet_size;
	protected boolean [][]event_care; // events we care about
	protected State [][] map;	// map State_automata to extraindex
	protected State [] s_initial; // the initial state
	protected int [] subdomains;
	protected Universe univ;
	protected Set g_r; // global for speed up
	protected double size_theoretical, size_reserved;

	/**  we wont compose if its larger that this */
	private static final double MAX_SEARCH_SIZE = Math.pow(2, 38);


	// -----------------------------------------------------------------------------

	public ReachabilityTool(Automata automata)
		throws AutomatonException
	{
		this( AutomataOperations.asArray(automata));
	}

	public ReachabilityTool(Automaton [] automata)
		throws AutomatonException
	{
		this.automata = automata;
		this.size = automata.length;


		// get some statistics:
			size_theoretical = 1;
			size_reserved = 1;
			for(int i = 0; i < size; i++) {
				int tmp = automata[i].numOfNodes();
				size_theoretical *= tmp;
				size_reserved *= Math.pow(2, Math.ceil( Math.log(tmp) / Math.log(2)));
		}


		// see if it is small enough
		if(size_theoretical > MAX_SEARCH_SIZE)
			throw new AutomatonException(
					"The current set of automata are too large (max " + size_theoretical +
					" states) for this implementation. Try to pre-sync");




		// get the alphabet, write it to an array
		java.util.Set ua = AutomataOperations.getUnionAlphabet(automata);
		alphabet_size = ua.size();
		alphabet = new Event[ alphabet_size ];
		int index = 0;
		for(Iterator it = ua.iterator(); it.hasNext(); index++)
			alphabet[index] = (Event) it.next();

		// get event care for each automaton
		event_care = new boolean[size][alphabet.length];
		for(int i = 0; i < size; i++) {
			for(int j = 0; j < alphabet.length; j++) alphabet[j].extra2 = 0;
			for(Event e = automata[i].getAlphabet().head(); e != null; e = e.next) e.parent.extra2++;
			for(int j = 0; j < alphabet.length; j++) event_care[i][j] = alphabet[j].extra2 != 0;
		}

		// build the new representation
		map = new State[size][]; // one for each automata. maps State -> extraindex
		subdomains = new int[size];
		for(int i = 0; i < size; i++) {
			subdomains[i] = automata[i].numOfNodes();
			map[i] = new State[ subdomains[i] ];
			AttributeExplorer.updateExtraIndex(automata[i]); // set extraindex!
			for (Enumeration e = automata[i].getNodes().elements() ; e.hasMoreElements() ;) {
				State s = (State) e.nextElement();
				map[i][ s.extraindex] = s;
			}
		}

		// get the global initial state, throws exception if something goes wrong
		s_initial = new State[size];
		for(int i = 0; i < size; i++)
		s_initial[i] = AutomataOperations.getInitialState(automata[i]);


		// now, get the universe. the type is defined in the configuration
		switch(Configuration.automataStateSetType) {
			case Configuration.AUTOMATA_STATES_BDD:
				univ = new jdd.bdd.sets.BDDUniverse(subdomains);
				break;
			case Configuration.AUTOMATA_STATES_MIXEDRADIX:
				univ = new jdd.util.mixedradix.MRUniverse(subdomains);
				break;
			default:
				throw new AutomatonException("invalid value in Configuration.automataStateSetType");
		}
	}

	// -----------------------------------------------------------------
	/** free used memory */
	public void cleanup() {
		univ.free();
		univ = null;
	}

	// -----------------------------------------------------------------

	/** forward from the initial state */
	public Set forward() {
		return forward(s_initial);
	}


	/** forward from a given initial state */
	public Set forward(State [] initial_state) {
		Set reachable = univ.createEmptySet();

		// convert to state index.
		int [] initial = new int[size];
		for(int i = 0; i < size; i++) initial[i] = initial_state[i].extraindex;
		reachable.insert(initial); // the initial state is always reachable

		g_r = reachable;
		traverse(initial);
		g_r = null;

		return reachable;
	}

	// ------------------------------------------------------------
	/** find the next state from "from" with event "e". returns -1 if non found */
	protected int next(int automaton, int from, Event e) {
		State s = map[automaton][from];

		for( Transition t = (Transition) s.firstOut; t != null; t = (Transition) t.next) {
			if(t.event.parent == e) {
				State s2 = (State) t.n2;
				return s2.extraindex;
			}
		}
		return -1;
	}

	/** find if "event" eligable from "from", put the next state  in "to" */
	protected boolean elig(int [] from, int []to, int event) {
		Event e = alphabet[event];
		for(int i = 0; i < size; i++) {
			if(!event_care[i][event]) to[i] = from[i]; // not in its alphabet, self-loop!
			else {
				int tmp = next(i, from[i], e);
				if(tmp == -1) return false;	// automata i cannot participate
				to[i] = tmp;
			}
		}
		return true;
	}

	protected void traverse(int x[]) {
		int [] next = new int[size]; // xxx this is NOT efficient!		BUT ObjectPool is even worse!
		for(int i = 0; i < alphabet_size; i++) {
			if(elig(x, next, i)) {
				if( !g_r.member(next)) {
					g_r.insert(next);
					traverse(next);
				}
			}
		}
	}

	// ----------------------------------------------------------------

	/** get the theoretical upper bound on the size of the composition */
	public double getTheoreticalSize() { return size_theoretical; }

	/** get the state space reserved for the composition, if BDDs where used */
	public double getReservedSize() { return size_reserved; }

	/** the used universe */
	public Universe getUniverse() { return univ; }

	// ------------------------------------------------------------
	public static void internal_test() {
		Test.start("ReachabilityTool");

		try {

			// small automata test
			Automata as = AutomataIO.loadXML("data/phil.xml");
			ReachabilityTool rt = new ReachabilityTool(as);
			Set fwd = rt.forward();
			Test.checkEquality(fwd.cardinality(), 19, "reachable states (1)");
			rt.cleanup();



			// a "large" test, not very large thought
			final int c1 = 4;
			final int c2 = 6;
			as = new Automata();
			for(int i = 0; i < c1; i++) { //this code build "c1" independent counters with "c2 states each
				Automaton a = as.add("test" + i);
				Event e = a.addEvent("e" + i);
				State last = a.addState("s");
				last.setInitial(true);
				for(int j = 1; j < c2; j++) {
					State s2 = a.addState("q" + j);
					a.addTransition(last,s2, e);
					last = s2;
				}
			}

			rt = new ReachabilityTool(as);
			fwd = rt.forward();
			Test.checkEquality(fwd.cardinality(), (int)Math.pow(c2,c1), "reachable states (1)");
			rt.cleanup();


		} catch(Exception exx) {
			exx.printStackTrace();
			Test.check(false, exx.toString() ); // oh shit!
		}
		Test.end();
	}




}