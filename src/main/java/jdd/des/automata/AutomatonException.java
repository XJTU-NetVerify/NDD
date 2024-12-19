
package jdd.des.automata;

/**
 * Indicates an error during an operation on an automaton structure
 * @see Automaton
 */

public class AutomatonException extends Exception {
	/**
	 *    Constructs a new automaton exception with the specified detail message.
	 */
	public AutomatonException(String message) {
		super(message);
	}
}
