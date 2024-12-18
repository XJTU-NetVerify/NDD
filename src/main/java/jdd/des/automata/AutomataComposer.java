

// TODO: make this a little more efficient!

// FIXME: the recursive part can also take care of transition creation?

package jdd.des.automata;

import jdd.util.*;
import jdd.util.pool.*;
import jdd.graph.*;
import jdd.util.mixedradix.*;
import jdd.util.sets.*;

import java.util.Enumeration;
import java.util.Iterator;


/**
 * full synchronous composition of fairly small automata.
 *
 * FSC is denoted by the ||-operator is defined by Hoare in his Communicating Sequential Process.
 *
 * <pr><b>BEWARE</b> that this implementation is currently very inefficient!<br>
 * Altough the code may use BDDs in some situations, it still does it in a very inefficient way.
 *
 * If you need lighting fast composition, check the implementation in <b>Supremica</b>.
 *
 *
 * @see <a href="http://www.usingcsp.com/">Using CSP</a>
 * @see <a href="http://www.supremica.org/">Supremica</a>
 */


public class AutomataComposer extends ReachabilityTool {


	/**  we wont compose if its larger that this */
	private static final double MAX_COMPOSE_SIZE = Math.pow(2, 24);

	private Automaton answer; // the result of the composition
	private Automata target; // the Automata object with its event manager and all that

	private Event []answer_events; // same as above but local in "answer" automaton


	private StringBuffer sb_int = new StringBuffer(); // MICRO-OPTIMIZATION: reuseable object
	private java.util.Map name_map = new java.util.HashMap(); // maps name -> State


	// -----------------------------------------------------------------------------

	public AutomataComposer(Automata target)
		throws AutomatonException
	{
		this( target, AutomataOperations.asArray(target));
	}


	public AutomataComposer(Automata target, Automaton [] automata)
		throws AutomatonException
	{
		super(automata);

		// create the new automata
		answer = target.add( AutomataOperations.getSafeName(target, "Composition") );

		// create the local version of the events
		answer_events = new Event[alphabet_size];
		for(int i = 0; i < alphabet_size; i++)
		answer_events[i] = answer.addEvent(alphabet[i].getLabel(), alphabet[i].isControllable());

		compose();
	}

	private void compose() throws AutomatonException {

		Set reachable = univ.createEmptySet();

		// get the global initial state, throws exception if something goes wrong
		int [] initial = new int[size];
		for(int i = 0; i < size; i++) {
			initial[i] = AutomataOperations.getInitialState(automata[i]).extraindex;
		}


		reachable.insert(initial); // the initial state is always reachable

		g_r = reachable;
		traverse_and_compose(initial);
		g_r = null;

		reachable.free();
	}

	// -------------------------------------------------------------

	protected State traverse_and_compose(int x[]) {
		// XXX: this is NOT efficient!		BUT ObjectPool is even worse!
		int [] next = new int[size];

		State me = createState(x);

		for(int i = 0; i < alphabet_size; i++) {
			if(elig(x, next, i)) {
				if( !g_r.member(next)) {
					g_r.insert(next);
					State s2 = traverse_and_compose(next);
					answer.addTransition(me, s2, answer_events[i]);
				} else {
					// already in
					String name2 = createName(next);
					State s2 = (State) name_map.get(name2);
					answer.addTransition(me, s2, answer_events[i]);
				}
			}
		}
		return me;
	}

	// -----------------------------------------------------------

	private String createName(int [] x) {
		StringBuffer sb = sb_int; sb.setLength(0);
		for(int i = 0; i < size; i++) {
			sb.append(map[i][ x[i]].getLabel());
			sb.append('.');
		}
		return sb.toString();
	}


	private State createState(int [] x) {
		String name = createName(x);
		boolean initial = true;
		boolean marked = true;
		boolean forbidden = false;
		for(int i = 0; i < size; i++) {
			initial &= map[i][ x[i]].isInitial();
			marked &= map[i][ x[i]].isMarked();
			forbidden |= map[i][ x[i]].isForbidden();
		}

		State s = answer.addState(name);
		s.setInitial( initial );
		s.setMarked( marked );
		s.setForbidden( forbidden );
		name_map.put(name, s);
		return s;
	}

	// -----------------------------------------------------------

	/** get the size of the composition */
	public double getActualSize() { return answer.numOfNodes(); }

	/** the composite automaton */
	public Automaton getComposition() { return answer; }



	// ----------------------------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("AutomataComposer");

		try {

			// small automata test
			Automata as = AutomataIO.loadXML("data/phil.xml");
			AutomataComposer ac = new AutomataComposer(as);

			Automaton a = ac.getComposition();
			Test.checkEquality(a.numOfNodes(), 19, "Q size ");
			Test.checkEquality(a.numOfEdges(), 30, "T size ");
			Test.checkEquality(a.getAlphabet().getSize(), 10, "E size ");




			// a "large" test, not very large thought
			final int c1 = 4;
			final int c2 = 6;
			as = new Automata();
			for(int i = 0; i < c1; i++) { //this code build "c1" independent counters with "c2 states each
				a = as.add("test" + i);
				Event e = a.addEvent("e" + i);
				State last = a.addState("s");
				last.setInitial(true);
				for(int j = 1; j < c2; j++) {
					State s2 = a.addState("q" + j);
					a.addTransition(last,s2, e);
					last = s2;
				}
			}
			ac = new AutomataComposer(as);
			a = ac.getComposition();
			Test.checkEquality(a.numOfNodes(), (int)Math.pow(c2,c1), "Q size ");
			// Test.checkEquality(a.numOfEdges(), ??? , "T size ");  // dont know how many they are, brain not working today
			Test.checkEquality(a.getAlphabet().getSize(), c1, "E size ");


		} catch(Exception exx) {
			exx.printStackTrace();
			Test.check(false, exx.toString() ); // oh shit!
		}
		Test.end();
	}

}
