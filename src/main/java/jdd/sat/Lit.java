

package jdd.sat;

import java.util.*;

import jdd.util.*;

public class Lit {
	public int id, index, bdd, occurnece, extra;
	public boolean neg;
	public Var var;

	protected Lit(Var var, boolean neg) {
		this.var = var;
		this.index = var.index;
		this.neg = neg;
		this.occurnece = 0;
		this.id = index * 2; if(neg) this.id++;
		if(neg) var.negvar = this;
		else	var.var = this;
	}

	public Lit negate() { return neg ? var.var : var.negvar; }

	public String toString() { return (neg ? "-" : "") + (index+1) + " "; }
	public void showDIMACS() { JDDConsole.out.print( toString() );	}
	public void showSupport() {		var.showSupport();	}
}
