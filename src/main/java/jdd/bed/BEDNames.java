
package jdd.bed;

import jdd.util.*;

/** node-naming for BED nodes:  v1..vn, OP, True or False */

public class BEDNames implements NodeName {
	public BEDNames() { }

	public String zero() { return "FALSE"; }
	public String one() { return "TRUE"; }
	public String zeroShort() { return "0"; }
	public String oneShort() { return "1"; }

	public String variable(int n) {
		if(n < 0) return "(none)";
		if(BED.IS_BDD(n)) return "v" + (BED.GET_VARIABLE(n) + 1);
		else return BED.GET_OPERATION_NAME(n);
	}
}
