
package jdd.des.automata.bdd;

import jdd.bdd.*;
import jdd.util.*;
import jdd.util.math.*;

import jdd.graph.*;
import jdd.des.automata.*;
import jdd.des.automata.analysis.*;

import java.util.*;

/**
BDDAutomata is a BDD representation of a set of automata.
It is build from a Automata object.<br>
<br>
It is <b>important</b> to know that the BDD representation is closely connected to the automata object.
you may not (A) change any of the automata during the lifetime of the BDDAutomata object
and (B) create another BDDAutomata object that includes any of the automata.<br>
<br>
The reason to this is that [for better performance] BDDAutomata will store some of its data inside
the Automaton objects and its derivatives (such as State and Event objects).<br>
<br>
Here is the recipe for a code that is dead wrong:<br>

<pre>

// THIS CODE I DEAD-WRONG, ILLEGAL. IF YOU CODE LIKE, YOU ARE PROBABLEY A VisualBasic PROGRAMMER :(

Automata a = AutomataIO.load("whatever.xml");
BDDAutomata b1 = new BDDAutomata(a);

// NO, DONT DO THIS! WE CANT USE A [or its automata ] TWICE.
BDDAutomata b2 = new BDDAutomata(a);

// NO!! DONT ALTER THE ORIGINAL IN ANY WAYS!!
Automaton a1 = a.add("Lets_creat_another_automata");

</pre>
@see Automata
@see BDDAutomaton
@see BDD
*/


public class BDDAutomata
	// extends jdd.bdd.debug.DebugBDD
	// extends BDD
	extends jdd.bdd.debug.ProfiledBDD2
	{

	/* package */ Automata original;	/** the automata this BDD stuff represents */
	/* package */ BDDAutomaton [] automata;	/** the BDD version of each automata  */
	private HashMap automaton2bddautomaton; /** get the bdd-automaton of an automaton */
	private Graph pcg; /** the PCG graph */
	private HashMap node_to_automaton_map; /** Map the PCG nodes to automata */
	private HashMap automaton_to_node_map; /** Map automata to the PCG nodes  */
	private int event_bits;			/** number of bits to code events */
	private int state_bits;			/** number of bits in the global state vector Q */
	private int [] bdd_var_events;	/** bdd vars used in the event vector */
	private int bdd_care_events;	/** carefor the events */
	private int bdd_cube_events;	/** cube for the events */
	private int bdd_cube_s;			/** cube for the current states */
	private int bdd_cube_sp;		/** cube for the next states */
	private int bdd_keep_states; 	/** keep for all states, i.e global q == q' */
	private Permutation perm_s2sp, perm_sp2s; /** Q->Q' and Q'->Q permutations */

	/**
	 * build the BDD automata and everything else needed for symbolic computation.
	 * @throws jaba.lang.IllegalArgumentException
	 */
	public BDDAutomata (Automata automata_) /* throws IllegalArgumentException */ {
		super(
			BDDAutomataHelper.suggestInitialNodes(automata_),
			BDDAutomataHelper.suggestInitialNodes(automata_) / 10);

		this.original = automata_;


		int i; // old C habbit :)


		// 0. get the PCG and create the maps
		HashMap h1 = new HashMap();
		HashMap h2 = new HashMap();
		pcg = AutomataAnalyzer.getPCG( original, h1, h2 );


		// 0.5 compute the initial automata ordering
		AutomataOrder ao = new AutomataOrder(pcg, h1);
		Automaton []ordered_automata = ao.getBestOrder();


		// 1. allocate state vector, create the state keep, and size of state vector
		//    plus the sube for Q and Q'
		try {

			bdd_keep_states = 1;
			state_bits = 0;
			bdd_cube_s = bdd_cube_sp = 1;

			automaton2bddautomaton = new HashMap();

			automata = new BDDAutomaton[original.size() ];

			// so cleanup() can work correctly even if we fail here:
			for(i = 0; i < automata.length; i++) automata[i] = null;

			// create the BDDAutomaton objects now...
			for(i = 0; i < ordered_automata.length; i++) {
				Automaton a = ordered_automata[i];
				// System.out.println("ORDER " + i + ", automata= " + a.getName() ); // DEBUG

				automata[i] = new BDDAutomaton( a, this ) ;
				automaton2bddautomaton.put(a, automata[i]);

				bdd_keep_states = andTo(bdd_keep_states , automata[i].getBDDKeep() );
				bdd_cube_s      = andTo(bdd_cube_s , automata[i].getBDDCubeS ());
				bdd_cube_sp     = andTo(bdd_cube_sp, automata[i].getBDDCubeSp());

				state_bits += automata[i].getNumBits();
			}
		} catch(IllegalArgumentException xx) {
			cleanup();
			throw xx;
		}

		// 1.5 get the PCG maps
		node_to_automaton_map = new HashMap();
		automaton_to_node_map = new HashMap();
		for (Enumeration e = pcg.getNodes().elements() ; e.hasMoreElements() ;) {
			Node n = (Node) e.nextElement();
			Automaton a = (Automaton) h1.get(n);
			BDDAutomaton ba = getBDDAutomaton(a);
			node_to_automaton_map.put(n, ba);
			automaton_to_node_map.put(ba, n);
		}



		// 2. allocate event variables
		EventManager em = original.getEventManager();
		event_bits = Digits.log2_ceil( em.getSize() );
		bdd_var_events = new int[event_bits];

		bdd_care_events = 0;
		bdd_cube_events = 1;
		for(i = 0; i < event_bits; i++) {
			bdd_var_events [i] = createVar();
			bdd_care_events =  orTo( bdd_care_events, bdd_var_events [i]);
			bdd_cube_events = andTo( bdd_cube_events, bdd_var_events [i]);
		}

		i = 0;
		for(Event event = em.head(); event != null; event = event.next, i++) {
			event.setBDD(BDDUtil.numberToBDD(this, bdd_var_events , i));
		}


		// 3. build the permutations
		int [] p1 = new int[state_bits];
		int [] p2 = new int[state_bits];
		int index = 0;
		for(i = 0; i < automata.length; i++) {
			int len = automata[i].getNumBits();
			int []a = automata[i].bdd_var_s;
			int []b = automata[i].bdd_var_sp;
			for(int j = 0; j < len; j++) {
				p1[index] = a[j];
				p2[index] = b[j];
				index++;
			}
		}
		perm_s2sp = createPermutation(p1, p2);
		perm_sp2s = createPermutation(p2, p1);


		// 4. build transition relations
		for(i = 0; i < automata.length; i++) {
			automata[i].buildRelations(this);
		}



	}


	/**
	 * cleanup and free the used memory
	 */
	public void cleanup() {
		for(int i = 0; i < automata.length; i++)
			if(automata[i] != null) automata[i].cleanup();

		super.cleanup(); // must clean up BDD stuff last
	}

	// ---- [ access functions ] ---------------------------------------
	/**
	 * return the BDD-automaton that was created for an automaton <tt>a</tt>.
	 */
	public BDDAutomaton getBDDAutomaton(Automaton a) {
		return (BDDAutomaton) automaton2bddautomaton.get(a);
	}

	/**
	 * get the PCG graph that describes
	 *
	 */
	public Graph getPCG() {
		return pcg;
	}

	/**
	 * given a node in the PCG graph, get its bdd-automaton object
	 */
	public BDDAutomaton getBDDAutomaton(Node n) {
		return (BDDAutomaton) node_to_automaton_map.get(n);
	}

	/**
	 * given a bdd-automaton object, get its corresponding node in the PCG
	 */
	public Node getPCGNode(BDDAutomaton ba) {
		return (Node) automaton_to_node_map.get(ba);
	}

	public int getSize() { return automata.length; }
	public int getBDDCubeEvents() { return bdd_cube_events; }
	public int getBDDCubeS () { return bdd_cube_s ; }
	public int getBDDCubeSp() { return bdd_cube_sp; }
	public int getBDDKeep() { return bdd_keep_states; }
	public Permutation getPermS2Sp () { return perm_s2sp; }
	public Permutation getPermSp2S () { return perm_sp2s; }

	public int getSVectorLength() { return state_bits; }
	public int getEVectorLength() { return event_bits; }

	// -----------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("BDDAutomata");

		Automata a = AutomataIO.loadXML("data/phil.xml");
		BDDAutomata ba = new BDDAutomata(a);


		// check if the cubes are not really screwed up
		Test.check( ba.getBDDCubeS() != ba.getZero() && ba.getBDDCubeS() != ba.getOne() , "cubeS ");
		Test.check( ba.getBDDCubeSp() != ba.getZero() && ba.getBDDCubeSp() != ba.getOne() , "cubeSp ");
		Test.check( ba.getBDDCubeEvents() != ba.getZero() && ba.getBDDCubeEvents() != ba.getOne() , "cubeEvent ");

		int tmp1 = ba.ref( ba.and(ba.getBDDCubeS(), ba.getBDDCubeSp()) );
		int tmp2 = ba.ref( ba.and(tmp1 , ba.getBDDCubeEvents()) );
		Test.checkEquality(ba.satCount(tmp2), 1, "correct allocation of cubes");
		ba.deref(tmp1);
		ba.deref(tmp2);

		// check the permutations:
		Test.checkEquality(ba.replace(ba.getBDDCubeS(), ba.getPermS2Sp()), ba.getBDDCubeSp(), "S2Sp");
		Test.checkEquality(ba.replace(ba.getBDDCubeSp(), ba.getPermSp2S()), ba.getBDDCubeS(), "Sp2S");
		Test.checkEquality(ba.replace(ba.getBDDCubeEvents(), ba.getPermSp2S()), ba.getBDDCubeEvents(), "permutationsnot affecting events (1)");
		Test.checkEquality(ba.replace(ba.getBDDCubeEvents(), ba.getPermS2Sp()), ba.getBDDCubeEvents(), "permutationsnot affecting events (2)");




		// we are done, cleanup and exit the test
		ba.cleanup();
		Test.end();
	}
}
