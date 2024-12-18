
package jdd.sat;

import jdd.util.*;

public class Clause implements Sortable {
	public Lit [] lits;
	public int curr, num_lits, index, flag, offset;
	public int top; // any lit from zero up to top-1 is included, the others are assigned/reduced ?
	/* oackage */ int hash;

	public double heat = 0;

	public Clause(int size) {
		this.index = -1; // none
		this.lits = new Lit[num_lits = size];
		this.curr = 0;
		this.top = 0;
		this.hash = 0;
	}


	public void insert(Lit l) {
		if(! l.var.occurs.contains(this) ) l.var.occurs.add( this);
		l.occurnece++;
		if(curr < num_lits) lits[curr++] = l;
		this.top = curr;
	}
	public void showDIMACS() { // DIMACS style show
		for(int i = 0; i < curr; i++) lits[i].showDIMACS();
		JDDConsole.out.println(" 0");
	}

	public  boolean greater_than(Sortable s) {
		Clause c = (Clause) s;
		return this.heat > c.heat;
	}

	public boolean satisfies(boolean [] minterm) {
		for(int i = 0; i < curr; i++)
			if( lits[i].neg != minterm[ lits[i].index])
				return true;

		return false;
	}

	public boolean satisfies(int var, boolean value)  {
		for(int i = 0; i < curr; i++)
			if(lits[i].index == var) return (lits[i].neg != value);
		return false; // literal not in this clause
	}

	/** returns treu if clause is a tautology, removes already defined literals otherwise */
	public boolean simplify() {
		for(int i = 0; i < curr; i++) lits[i].var.extra = 0;

		int pos = 0;
		for(int i = 0; i < curr; i++, pos++) {
			int sign = lits[i].neg? -1 : 1;
			if(i != pos)	lits[pos] = lits[i];
			if(lits[i].var.extra == 0) lits[i].var.extra += sign;
			else if( lits[i].var.extra * sign < 0) {
				// JDDConsole.out.println("tautology in cluase"); showDIMACS();
				return true; // both -v and v are in this clause!
			} else {
				// JDDConsole.out.println("duplicate literal "); showDIMACS();
				lits[i].occurnece--;
				pos--;
			}
		}
		curr = pos;
		return false;
	}
	public void removeFromDatabase() {
		for(int i = 0; i < curr; i++) {
			lits[i].var.occurs.remove(this);
			lits[i].occurnece++;
		}
	}
	// ----------------------------------------------------------------------
	/* package*/ void computeHash() {
		// a truely stupid hash function
		hash = 0;
		for(int i = 0; i < curr; i++) hash +=  lits[i].id;
	}
	public boolean equals(Clause c) {
		if(hash != c.hash) return false;
		if(curr != c.curr) return false;

		for(int i = 0; i < curr; i++) c.lits[i].extra = 0;
		for(int i = 0; i < curr; i++) lits[i].extra = 1;
		for(int i = 0; i < curr; i++) if(c.lits[i].extra != 1) return false;
		return true;
	}

	public boolean largerOrEquals(Clause c) {
		if(curr < c.curr) return false;

		for(int i = 0; i < c.curr; i++) c.lits[i].extra = 0;
		for(int i = 0; i < curr; i++) lits[i].extra = 1;
		for(int i = 0; i < c.curr; i++) if(c.lits[i].extra != 1) return false;
		return true;
	}
	// -----[ used in DP algorithms ]------------------------------------

	public final boolean isNull() { return top == 0; }
	public final boolean isUnit() { return top == 1; }
	public final int litsRemoved() { return curr - top; }
	public final Lit first() { return lits[0]; } // no, this has nothing to do with watched literals...

	// -----[ also used in DP algorithms ]--------------------------------
	public final boolean used(Lit l) {
		for(int i = 0; i < top; i++) if( l.id == lits[i].id) return true;
		return false;
	}
	public final boolean removed(Lit l) {
		for(int i = top; i < curr; i++) if( l.id == lits[i].id) return true;
		return false;
	}

	public final boolean removed(Var v) {
		for(int i = 0; i < top; i++) if( v.index == lits[i].index) return false;
		return true;
	}

	public final boolean active(Var v) {
		for(int i = top; i < curr; i++) if( v.index == lits[i].index) return false;
		return true;
	}



	/** removets the literal associated with this var, returns true if it was negated */
	public final boolean remove(Var v) {
		for(int i = 0; i < top; i++) if(lits[i].var == v) {
			boolean ret = lits[i].neg; // must save, it will be moved from i
			remove(i);
			return ret;
			}

		Test.check(false, "should not come here!");
		return false; // should not come here
	}

	public final void remove(int l) {
		top--;
		Lit l1 = lits[l];
		Lit l2 = lits[top];
		lits[l] = l2;
		lits[top] = l1;
	}

	/** reinserts the literal associated with this var, returns true if it was negated */
	public final boolean reinsert(Var v) {
		int idx = v.index;
		for(int i = top; i < curr; i++) if(lits[i].index == idx) {
			boolean ret = lits[i].neg; // must save, it will be moved from i
			reinsert(i);
			return ret;
			}

		Test.check(false, "should not come here!");
		return false;
	}

	public final void reinsert(int l) {
		Lit l1 = lits[l];
		Lit l2 = lits[top];
		lits[l] = l2;
		lits[top] = l1;
		top++;
	}

	// ---------------------------------------------------------------
	public void showClause() {
		JDDConsole.out.print("c" + (index + 1) + " = { ");
		for(int i = 0; i < curr; i++) {
			if(i > 0 ) JDDConsole.out.print(", ");
			JDDConsole.out.print( (lits[i].neg ? "~" : "") + "v" + (lits[i].index + 1));
		}
		JDDConsole.out.println("}");
	}

	// ---------------------------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("Clause");
		Clause c = new Clause(3);
		Var v0 = new Var(0), v1 = new Var(1), v2 = new Var(2);
		Lit l0 = new Lit(v0, false), l1 = new Lit(v1, false), l2 = new Lit(v2, false);

		c.insert(l0); c.insert(l1); c.insert(l2);
		Test.check(!c.removed(l0) );

		c.remove(v0);
		Test.check(c.removed(l0) );

		c.reinsert(v0);
		c.remove(v2);
		Test.check(c.removed(l2) );
		Test.check(!c.removed(l0) );


		// test equals and largerOrEquals
		Clause c2 = new Clause(1);
		c2.insert(l0);

		Clause c3 = new Clause(3);
		c3.insert(l0);	c3.insert(l1);	c3.insert(l2);
		Test.check(!c2.equals(c3));
		Test.check(!c3.equals(c2));
		Test.check(c2.equals(c2));
		Test.check(c3.equals(c3));
		Test.check(c2.equals(c2));
		Test.check(!c2.largerOrEquals(c3));
		Test.check(c3.largerOrEquals(c2));


		Test.end();
	}
}
