
package jdd.des.automata.bdd;

import jdd.bdd.*;
import jdd.util.*;

import jdd.graph.*;
import jdd.des.automata.*;

/**
 * this class contains a small set of simple functions that are often used
 * in conjunction with BDDAutomata
 */


public class BDDAutomataHelper {

	/**
	 * suggest how many nodes the NodeTable should initially have for
	 * to represent this automata symbolically.
	 * <p>
	 * this is important for performance.
	 */

	 public static int suggestInitialNodes(Automata a) {
		 // there is no scientific meaning what so ever behind these lines :)
		 double ret = 10000 * (1 + Math.log( 1 + a.size() ) );
		 return (int) Math.min(ret, 800000);
	 }


	/**
	 * compute the numer of states in a BDD. The BDD must have its support only in
	 * either S or S'.
	 */
	public static double countStates(BDDAutomata a, int bdd) {
		double ret = a.satCount(bdd);
		double div = Math.pow(2, a.getSVectorLength() + a.getEVectorLength() );
		return ret / div;
	}

	/**
	 * compute the global initial function
	 *
	 */
	public static int getI(BDDAutomata a) {
		int ret = 1;
		BDDAutomaton [] as = a.automata;

		for(int i = 0; i < as.length; i++) {
			ret = a.andTo(ret, as[i].getBDDI());
		}
		return ret;
	}

	/**
	 * compute the global transition relation T: Q -> 2^Q
	 *
	 */
	public static int getT(BDDAutomata a) {
		int ret = 1;
		BDDAutomaton [] as = a.automata;

		for(int i = 0; i < as.length; i++) {
			ret = a.andTo(ret, as[i].getBDDDeltaTop());
		}

		// Remove the events first
		int tmp = a.ref( a.exists( ret, a.getBDDCubeEvents() ) );
		a.deref( ret);
		return tmp;
	}

}
