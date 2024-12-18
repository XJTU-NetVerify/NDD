
package jdd.sat;

import java.util.*;

import jdd.util.*;

public class Var implements Sortable {

	public int index, offset, extra;
	public double activity = 0;
	public Lit var, negvar;

	public Vector occurs; // filled by Clause

	public Var(int index){
		this.index = index;
		this.occurs = new Vector();
	}


	public boolean greater_than(Sortable s) {
		Var v = (Var) s;
		return this.activity > v.activity;
	}

	public String toString() { return "" + (index + 1); }

	public void showSupport() {
		JDDConsole.out.print("support-v" + index + " = {");
		for (Enumeration e = occurs.elements() ; e.hasMoreElements() ;)
			JDDConsole.out.print(" c" + ( 1 + ((Clause) e.nextElement()).index));
		JDDConsole.out.println("}");

		for (Enumeration e = occurs.elements() ; e.hasMoreElements() ;)
			((Clause) e.nextElement()).showClause();
	}
}

