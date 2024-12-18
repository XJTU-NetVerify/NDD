package jdd.des.strings;

import jdd.util.*;
import jdd.des.automata.*;


/**
 * Node naming the ZBDD sub-cubes according to the Alphabet (possibly registred EventManager ?)
 * @see SimpleAlphabetNodeNames
 */

public class AlphabetNodeNames implements NodeName {
	private String [] names;
	private int alphabet_size;

	public AlphabetNodeNames(Alphabet alphabet) {
		this.alphabet_size = alphabet.getSize();
		names = new String[alphabet_size];
		Event e1 = alphabet.head();
		int i = 0;
		boolean need_space = false;
		while(e1 != null) {
			names[i] = e1.getLabel();
			if(names[i].length() > 3) need_space = true;
			i++;
			e1 = e1.next;
		}
		if(need_space) for( i = 0; i < alphabet_size; i++) names[i] = names[i] + " ";
	}

	public String one(){ return "epsilon"; }
	public String zero(){ return "emptyset"; }
	public String zeroShort(){ return "0"; }
	public String oneShort() { return "1"; }
	public String variable(int n) {
		int e = (n % alphabet_size);
		int k = n / alphabet_size;
		// return names[e] + (k+1);
		// return names[e] + ":" + (k+1);
		return names[e] ;
	}
}
