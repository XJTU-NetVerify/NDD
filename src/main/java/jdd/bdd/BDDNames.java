
package jdd.bdd;

import java.io.Serializable;

import jdd.util.*;

/** BDD-style node naming: v1..vn */
public class BDDNames implements NodeName, Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1259800968438602513L;

	public BDDNames() { }

	public String zero() { return "FALSE"; }
	public String one() { return "TRUE"; }
	public String zeroShort() { return "0"; }
	public String oneShort() { return "1"; }

	public String variable(int n) {
		if(n < 0) return "(none)";
		return "v" + (n + 1);
	}
}
