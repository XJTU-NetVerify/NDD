
package jdd.sat;

import java.io.*;
import java.util.*;

import jdd.util.*;

/** XXX: the CNF clause-optimization is WRONG, it removes the wrong clause :( */

public class CNF {

	public Clause [] clauses;
	public Lit [] lits;
	public Var [] vars;
	public int curr, num_clauses, num_lits;


	public static CNF parseDimacsText(String txt) throws IOException {
		DimacsReader dr = new DimacsReader(txt, false);
		return dr.getFormula();
	}

	public static CNF loadDimacs(String file) throws IOException {
		DimacsReader dr = new DimacsReader(file, true);
		return dr.getFormula();
	}

	public CNF (int clauses_, int lits_) {
		clauses = new Clause[num_clauses = clauses_];
		lits = new Lit[2 * (num_lits = lits_)];
		vars = new Var[num_lits];
		for(int i = 0; i < num_lits; i++) {
			vars[i] = new Var(i);
			lits[i * 2 ] = new Lit(vars[i], false);
			lits[i * 2 + 1] = new Lit(vars[i], true);
		}
		curr = 0;
	}

	/** get literal, negated if var <0 note: var != 0 [0 is reserved for something else]  */
	public Lit getSignedLit(int var) {
		Test.check(var != 0);

		int add = var > 0 ? 0 : 1;
		var = Math.abs(var) - 1;
		if(var < 0 || var >= num_lits) return null;
		return lits[var * 2 + add];

	}

	/** get [negated] literal, note: var > 0 [0 is reserved for something else] */
	public Lit getLit(int var, boolean neg) {
		Test.check(var != 0);

		var--;
		if(var < 0 || var >= num_lits) return null;
		return neg ? lits[var * 2 + 1] : lits[var * 2];
	}
	public void insert(Clause c) {
		if(c.curr <= 0) {
			JDDConsole.out.println("ERROR: ignoring EMPTY clause");
		} else if(curr < num_clauses) {
			if(!c.simplify()) {
				c.computeHash();
				Clause c2 = findLargerOrEqualClause(c);
				if(c2 == null) {
					c.index = curr;
					clauses[curr++] = c;
					return;
				} else JDDConsole.out.println("Clause ignored: equal or larger clause already exists");
			} JDDConsole.out.println("Clause ignored: simple tautology");
		} else JDDConsole.out.println("ERROR: ignoring clause due to overflow");
		c.removeFromDatabase();
	}
	public void adjustNumClauses() {
		num_clauses = curr;
	}

	public boolean satisfies(boolean [] minterm) {
		for(int i = 0; i < curr; i++)	if(!clauses[i].satisfies(minterm)) return false;
		return true;
	}


	public int satisfies(int var, boolean val) {
		int ret = 0;
		for(int i = 0; i < curr; i++)	if( clauses[i].satisfies(var,val)) ret++;
		return ret;
	}

	public int conflicts(boolean [] minterm) {
		int ret = 0;
		for(int i = 0; i < curr; i++)	if(!clauses[i].satisfies(minterm)) ret++;
		return ret;
	}


	public void showDIMACS() {
		JDDConsole.out.println("p cnf " + num_lits + " " + num_clauses);
		for(int i = 0; i < curr; i++) clauses[i].showDIMACS();
	}



	public Clause findEqualClause(Clause c) {
		for(int i = 0; i < curr; i++) if(clauses[i].equals(c)) return clauses[i];
		return null;
	}
	public Clause findLargerOrEqualClause(Clause c) {
		for(int i = 0; i < curr; i++) if(clauses[i].largerOrEquals(c)) return clauses[i];
		return null;
	}

	// -------------------------------------------------------------------

	public void order() {
		/* this functions is empty untill i figure it out ... */
	}

	/* this ordering algo sucks :( */
	void sucks_order() {

		// have the variables in must number of clauses come first
		for(int i = 0; i < num_lits; i++) vars[i].activity = vars[i].occurs.size();
		Sort.bubble_sort( vars );

		for(int i = 0; i < num_lits; i++) vars[i].activity = i; // dist to top

		for(int i = 0; i < num_clauses; i++) {
			Clause c = clauses[i];
			double sum = 0.0;
			for(int j = 0; j < c.curr; j++) sum+= c.lits[j].var.activity;
			c.heat = sum / c.curr;
		}
		Sort.bubble_sort( clauses );
		Inverse.inverse( clauses);

	}

	/** this is SATZOO static ordering algorithm (for a DP SAT solver!) by Niklas */
	void SATZOO_order() {
		// clear activity
		for(int i = 0; i < num_lits; i++) vars[i].activity = 0;

		//  do simple variable activity heuristic
		for(int i = 0; i < num_clauses; i++) {
			Clause c = clauses[i];
			double add = Math.pow(2, -c.curr);
			for(int j = 0; j < c.curr; j++) {
				c.lits[j].var.activity += add;
			}
		}

		// calculate the intial "heat" of all clauses
		for(int i = 0; i < num_clauses; i++) {
			Clause c = clauses[i];
			double sum = 0.0;
			for(int j = 0; j < c.curr; j++)
				sum+= c.lits[j].var.activity;
			c.heat = sum;
		}

		// bump heat for classes whose variables occur in other hot clausses
		double iter_size = 0;
		for(int i = 0; i < num_clauses; i++) {
			Clause c = clauses[i];
			for(int j = 0; j < c.curr; j++) iter_size += c.lits[j].var.occurs.size();
		}

		int iterations = Math.min( (int)(((double) num_lits/ iter_size) * 100), 10);
		double disapation = 1.0 / iterations;
		for(int c = 0; c < iterations; c++) {
			for(int i = 0; i < num_clauses; i++) {
				Clause cl = clauses[i];
				for(int j = 0; j < cl.curr; j++) {
					Enumeration k = cl.lits[j].var.occurs.elements();
					 while(k.hasMoreElements())  {
						 Clause c2 = (Clause) k.nextElement();
						 cl.heat += c2.heat * disapation;
					 }
				}
			}
		}

		// set activity according to the hot clauses
		Sort.bubble_sort( clauses); // largest first
		for(int i = 0; i < num_lits; i++) vars[i].activity = 0;

		double extra = 1e200;
		for(int i = 0; i < num_clauses; i++) {
			Clause c = clauses[i];
			for(int j = 0; j < c.curr; j++) {
				if(c.lits[j].var.activity == 0)	{
					c.lits[j].var.activity = extra;
					extra *= 0.955;
				}
			}
		}

		Sort.bubble_sort( vars ); // largest first

		// Inverse.inverse( vars);
		// Inverse.inverse( clauses);
	}

	// ------------------------------------------------------------
	/** simple validity check, more stuff to be added... */
	public void check() {
		for(int i = 0; i < num_lits; i++) {
			Test.checkEquality(vars[i].index , vars[i].var.index, "Li.index = Vi.index");
			Test.checkEquality(vars[i].index , vars[i].negvar.index, "~Li.index = Vi.index");
		}
	}
	// ------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {

		Test.start("CNF");
		String filename = "data/dimacs50a.cnf";

		try {
			CNF cnf = loadDimacs(filename);
			int [] had = new int[ Math.max( cnf.curr, cnf.num_lits) ];

			// get number of times each literal has been included
			Array.set(had, 0);
			for(int i = 0; i < cnf.num_lits * 2; i++) {
				int lit = cnf.lits[i].index;
				had[lit] ++;
			}

			// check duplicate literals:
			boolean bad_cnf = false;
			for(int i = 0; !bad_cnf && (i < cnf.num_lits); i++)
				if(had[i] != 2) bad_cnf = true;
			Test.check(!bad_cnf, "Needs exactly two literals of index");


		} catch(IOException exx) { Test.check(false, exx.toString() ); }
		Test.end();
	}
}


