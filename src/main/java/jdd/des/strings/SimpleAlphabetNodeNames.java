package jdd.des.strings;

import jdd.util.*;

/**
 * Node naming for alphabets, used to name ZDD tree and produce bounded languages ???
 * @see AlphabetNodeNames
 */

public class SimpleAlphabetNodeNames implements NodeName {
	private int alphabet_size;

	public SimpleAlphabetNodeNames(int alphabet_size) {
		this.alphabet_size = alphabet_size;
	}
	public String one(){ return "epsilon"; }
	public String zero(){ return "emptyset"; }
	public String zeroShort(){ return "0"; }
	public String oneShort() { return "1"; }
	public String variable(int n) {
		int e = (n % alphabet_size);
		int k = n / alphabet_size;
		return "" + ( (char)('a'+e)) + (k+1);
	}
}
