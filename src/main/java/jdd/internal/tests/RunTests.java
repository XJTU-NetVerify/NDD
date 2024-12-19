



package jdd.internal.tests;

import jdd.util.*;

/**
 * test calls are invoked from here.
 *
 */

public class RunTests {

	public static void internal_test() {
		// TODO
	}

	// ------------------------------------

	public static void main(String [] args) {

		// dont have time for this kinda stuff:
		Options.profile_cache = false;
		Options.verbose = false;

		try {

			// first, my own tests:
			RunTests.internal_test();


			// most used utilities here
			jdd.util.Array.internal_test();
			jdd.util.DisjointSet.internal_test();
			jdd.util.Sort.internal_test();
			jdd.util.Flags.internal_test();


			// math utils
			jdd.util.math.Prime.internal_test();
			jdd.util.math.Digits.internal_test();
			jdd.util.math.BitMatrix.internal_test();
			jdd.util.math.FastRandom.internal_test();
			jdd.util.math.HashFunctions.internal_test();



			// compressed array
			jdd.util.zip.MemoryOutputStream.internal_test();
			jdd.util.zip.ZipArray.internal_test();


			// BDDs, yikes!
			jdd.bdd.NodeTable.internal_test();
			/// REMOVED jdd.bdd.NodeHT.internal_test();
			// jdd.bdd.Cache.internal_test();
			jdd.bdd.SimpleCache.internal_test();
			jdd.bdd.OptimizedCache.internal_test();
			jdd.bdd.DoubleCache.internal_test();
			jdd.bdd.BDD.internal_test();
			jdd.bdd.BDDIO.internal_test();

			// Quasi-reduced BDDs (not finished yet)
			// jdd.qbdd.QBDD.internal_test();


			// Zero-supressed BDDs
			jdd.zdd.ZDD.internal_test();
			jdd.zdd.ZDD2.internal_test();
			jdd.zdd.ZDDGraph.internal_test();
			jdd.zdd.ZDDCSP.internal_test();


			// Boolean Expression Diagrams (under development)
			jdd.bed.BED.internal_test();



			// graph theory
			jdd.graph.Graph.internal_test();
			jdd.graph.GraphIO.internal_test();
			jdd.graph.Factory.internal_test();
			jdd.graph.GraphOperation.internal_test();
			jdd.graph.SimpleAlgorithms.internal_test();
			jdd.graph.ApproximationAlgorithms.internal_test();
			jdd.graph.ShortestPath.internal_test();
			jdd.graph.MaximumFlow.internal_test();
			jdd.graph.MinimumSpanningTree.internal_test();
			jdd.graph.StronglyConnectedComponent.internal_test();
			jdd.graph.TopologicalSort.internal_test();
			jdd.graph.WeakTopologicalOrdering.internal_test();
			jdd.graph.BreadthFirstSearch.internal_test();
			jdd.graph.DepthFirstSearch.internal_test();


			// CNF SAT (where are BDD/GSAT/etc tests ???)
			jdd.sat.CNF.internal_test();
			jdd.sat.Clause.internal_test();



			// Petri nets
			jdd.des.petrinets.Petrinet.internal_test();
			jdd.des.petrinets.PetrinetIO.internal_test();
			jdd.des.petrinets.interactive.IPetrinet.internal_test();
			jdd.des.petrinets.ZDDPN.internal_test();
			jdd.des.petrinets.SymbolicPetrinet.internal_test();

			// DFA stuff
			jdd.des.automata.Automaton.internal_test();
			jdd.des.automata.ReachabilityTool.internal_test();
			jdd.des.automata.AutomataComposer.internal_test();
			jdd.des.automata.AutomataIO.internal_test();
			jdd.des.automata.AutomataOperations.internal_test();
			jdd.des.automata.bdd.BDDAutomata.internal_test();

			// DES language stuff
			jdd.des.strings.ZDDStrings.internal_test();
			// jdd.des.strings.AutomataSublanguage.internal_test();

			// the examples are used as tests too :(
			jdd.examples.BDDQueens.internal_test();
			jdd.examples.ZDDQueens.internal_test();
			jdd.examples.ZDDCSPQueens.internal_test();


			// BDD sets
			jdd.bdd.sets.BDDUniverse.internal_test();
			jdd.bdd.sets.BDDSet.internal_test();


			// bitset/mixedradix sets
			jdd.util.mixedradix.MRUniverse.internal_test();
			jdd.util.mixedradix.MRSet.internal_test();





			// *** hurrayy, we did it ***
			JDDConsole.out.println("\nALL " + Test.total + " TESTS WERE SUCCESSFUL!");
		} catch(Throwable exx) {
			exx.printStackTrace();
		}

	}
}
