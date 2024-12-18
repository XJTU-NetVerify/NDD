package jdd.des.automata.bdd;

import jdd.bdd.*;
import jdd.util.*;

import jdd.graph.*;
import jdd.des.automata.*;
import jdd.des.automata.analysis.*; // for the test

import java.util.*;


/**
 * compute an order for the automata, the BDD will respect this order.
 * <p>
 * How this order is computed may change from time to time. currently,
 * we are currently using iterative network minimization.
 */


public class AutomataOrder {


	// some internal constants
	private final static int MAX_ROUNDS = 20;	// number of rounds
	private final static int MIN_ITR = 50;	// min iterations in a round
	private final static double STOP_CONST_C = 4; // constant c in the paper


	private Graph pcg;
	private Automaton [] a_order;
	private Node [] nodes;
	private HashMap node2automaton;

	private double lowest_span;
	private double []weights;
	private int size;
	private int [] order;


	public AutomataOrder(Graph pcg, HashMap node2automaton) {
		this.node2automaton = node2automaton;
		this.pcg = pcg;



		// allocate arrays we will use
		size = pcg.numOfNodes();
		a_order = new Automaton[size];
		nodes = new Node[size];
		order = new int[size];
		weights = new double[size];


		// create an array of nodes for better access
		int idx = 0;
		for (Enumeration it = pcg.getNodes().elements(); it.hasMoreElements(); idx++)
		{
			nodes[idx] = (Node) it.nextElement();
		}


		// get |Ev| and |e|
		compute_cardinality();

		// suggest the number of iterations
		int max_itr = (int)(STOP_CONST_C * Math.log(1+size)); // max number of iterations
		if(max_itr < MIN_ITR) max_itr = MIN_ITR;


		lowest_span = Double.MAX_VALUE;

		for(int rounds = 0; rounds < MAX_ROUNDS; rounds ++) {

			// get inital order
			if(rounds < MAX_ROUNDS / 2) 	create_dfs_order();
			else create_random_order();

			// do a series of iterations
			double span = iterate(max_itr);

			// see how good it was
			if(lowest_span > span) {
				lowest_span = span;
				extract_order();
			}
		}

	}


	// ------------------------------------------------------------

	/**
	 * do max_itr iterations
	 *
	 */
	private double iterate(int max_itr) {

		// stop_conv is the number of time we can allow the same total_span before we terminate
		int stop_cong = max_itr / 3;
		if(stop_cong < 5) stop_cong = 5;

		double last = -1;
		int repreat = 0; // number of times the last number was repeated

		for(int itr = 0; itr < max_itr; itr++) {
			force();

			double tmp = total_span();

			// dont keep one forever if we have already converged!
			if(tmp == last) {
				repreat++;
				if(repreat >= stop_cong) return last;
			} else {
				repreat = 0;
				last = tmp;
			}
		}
		return  last;
	}



	/**
	 * one round of FORCE inner-loops
	 *
	 * assumes: card_e and card_Ev are computed, lv is the current order
	 *
	 * @returns the "cost" for the computed ordering
	 */
	private void force() {


		// 1. compute COG in extra4
		for(int i = 0; i < size; i++) {
			Node n1 = nodes[i];
			double sum = n1.extra3;

			Edge e = n1.firstOut;
			while(e != null) { sum += e.n2.extra3; 		e = e.next;		}

			e = n1.firstIn;
			while(e != null) {	sum += e.n1.extra3;		e = e.prev;		}

			n1.weight = sum / n1.extra2;
		}

		// 2. center
		for(int i = 0; i < size; i++) {
			Node n1 = nodes[i];

			n1.extra4 = n1.weight;

			Edge e = n1.firstOut;
			while(e != null) { n1.extra4 += e.n2.extra3; 		e = e.next;		}

			e = n1.firstIn;
			while(e != null) {	n1.extra4  += e.n1.extra3;		e = e.prev;		}

			n1.extra4 /= n1.extraindex;

			weights[i] = n1.extra4;
		}

		// 3. sort  tentative vertex locations. smallest first
		Sort.sort(nodes, weights, size, false);

		// 4. assign integers...
		for(int i = 0; i < size; i++) {
			nodes[i].extra3 = i;
		}

	}


	/**
	 * get |Ev| and |e|
	 */
	private void compute_cardinality() {

		// start with one instead of zero to include itself!
		for(int idx = 0; idx < size; idx++) {
			nodes[idx].extra2 = 1;
			nodes[idx].extraindex= 1;
		}

		for(int idx = 0; idx < size; idx++) {

			Edge e = nodes[idx].firstOut;
			while(e != null) {
				e.n1.extra2++;
				e.n2.extraindex++;
				e = e.next;
			}

			e = nodes[idx].firstIn;
			while(e != null) {
				e.n2.extra2++;
				e.n1.extraindex++;
				e = e.prev;
			}
		}
	}


	/**
	 * extract the order of the current order
	 */
	private void extract_order() {
		for(int i = 0; i < size; i++) {
			a_order[ i ] = (Automaton) node2automaton.get(nodes[i]);
		}
	}

	// ------------------------------------------------
	/** get the best computed order */
	public Automaton [] getBestOrder() {
		return a_order;
	}

	/** get the cost of the computed order */
	public double getCost() {
		return lowest_span;
	}

	// ------------------------------------------------

	/**
	 * we must start with some initial order ...
	 */
	private void create_random_order() {
		int [] perm = Array.permutation(size);
		for(int idx = 0; idx < size; idx++) {
			nodes[idx].extra3 = perm[idx];
		}

	}

	// ------------------------------------------
	/* DFS order */
	private int dfs_count;
	private void create_dfs_order() {
		int initial = (int)(Math.random() * size);

		dfs_count = 0;

		for(int i = 0; i< size; i++)  nodes[i].extra3 = -1;
		dfs_rec(nodes[initial]);

		// label the rest:
		for(int i = 0; i< size; i++)
			if(nodes[i].extra3 == -1) dfs_rec(nodes[initial]);
	}
	private void dfs_rec(Node nod) {
		nod.extra3 = dfs_count ++;

		Edge e = nod.firstOut;
		while(e != null) { if(e.n2.extra3 == -1) dfs_rec(e.n2);	e = e.next;		}

		e = nod.firstIn;
		while(e != null) {	if(e.n1.extra3 == -1) dfs_rec(e.n1); e = e.prev;		 }

	}
	// ------------------------------------------------

	/**
	 *  compute the toal span.
	 *
	 * Span of hyperedge:
	 * difference between the greatest and smallest vertices connected by the same hyperedge
	 *
	 */
	private double total_span() {
		double span = 0;

		for(int i = 0; i < size; i++) {
			Node n1 = nodes[i];

			double min = n1.extra3;
			double max = n1.extra3;

			n1.extra4 = n1.weight;

			Edge e = n1.firstOut;
			while(e != null) {
				min = Math.min( min, e.n2.extra3);
				max = Math.max( max, e.n2.extra3);
				e = e.next;
			}

			e = n1.firstIn;
			while(e != null) {
				min = Math.min( min, e.n1.extra3);
				max = Math.max( max, e.n1.extra3);
				e = e.prev;
			}

			span += (max - min);
		}

		return span;

	}
	// ------------------------------------------------
	/** this is just to test the code. read an automata and print the order and cost */
	public static void main(String [] args) {
		try {
			for(int i= 0; i < args.length; i++) {
				Automata automata = AutomataIO.loadXML(args[i]);

				HashMap h1 = new HashMap();
				HashMap h2 = new HashMap();
				Graph pcg_ = AutomataAnalyzer.getPCG( automata, h1, h2 );
				AutomataOrder ao = new AutomataOrder(pcg_, h1);
				Automaton []ordered_automata = ao.getBestOrder();

				JDDConsole.out.println("The automata order is:");
				for(int j = 0; j < ordered_automata.length; j++)
					JDDConsole.out.print(ordered_automata[j].getName() + "\t");
				JDDConsole.out.println("\n TOTAL COST " + ao.getCost());
			}

		} catch(Exception exx) {
			exx.printStackTrace();
		}
	}
}