
package jdd.des.automata.bdd;

/**
 * this interface a symbolic search engine for BDDAutomata
 *
 */
public interface SymbolicAutomataSearch {

	/** Cleanup and free the used memory */
	public void cleanup();

	/** compute the forward reachable states from this initial point */
	public int forward(int initial);
}