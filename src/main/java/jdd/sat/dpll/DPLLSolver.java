
package jdd.sat.dpll;

import jdd.sat.*;
import jdd.util.*;

import java.io.*;
import java.util.*;

/**

DPLL implementation
*/

public class DPLLSolver implements Solver {

	protected CNF cnf = null;
	protected int top, max, var_top, assign_top;
	protected Clause [] cs, org;
	protected Lit [] assign_stack; // this will also be our certificate
	protected int [] var_stack, assignment, choice_stack, choice_stack2;
	protected int [][] occurs;
	protected long maxtime, endtime, branches;

	protected int choice_tos;

	public DPLLSolver() { this(5000); }
	public DPLLSolver(long maxtime) { this.maxtime = maxtime;	}

	public void setFormula( CNF cnf) {
		this.cnf = cnf;
		assignment = new int[ cnf.num_lits];
	}
	public void cleanup() {
		this.cnf = null;
		this.cs = null;
		this.assignment = null;
	}

	private void copy_cnf() {
		top = max = cnf.curr;
		org = cnf.clauses;
		cs = new Clause[max];
		for(int i = 0; i < top; i++) {
			cs[i] = cnf.clauses[i];
			cs[i].offset = i;
			cs[i].top = cs[i].curr;
		}

		var_top = cnf.num_lits;
		var_stack = new int[var_top];
		for(int i = 0; i < var_top; i++) {
			var_stack[i] = i;
			cnf.vars[i].offset = i;
		}

		assign_top = 0;
		assign_stack = new Lit[cnf.num_lits];

		choice_stack = new int[ Math.max( cnf.curr, cnf.num_lits) ];
		choice_stack2 = new int[ Math.max( cnf.curr, cnf.num_lits) ];


		occurs = new int[var_top][];
		for(int i = 0; i < var_top; i++) {
			Var v = cnf.vars[i];
			Test.checkEquality(v.index, i, "Var.index must be from 0..num_lits-1, otherwise this wont work!");
			occurs[i] = new int[v.occurs.size()];
			int j = 0;
			for (Enumeration e = v.occurs.elements() ; e.hasMoreElements() ;j++) {
				Clause c = (Clause) e.nextElement();
				occurs[i][j] = c.index;
			}
		}
	}

	public int [] solve() { // (0,1 or -1 for dont care)

		long time = System.currentTimeMillis();
		endtime = time + maxtime;
		branches = 0;

		copy_cnf();

		try {
			boolean ret = dpll();
			time = System.currentTimeMillis() - time;
			if(!ret) {
				JDDConsole.out.println("UNSAT/" + time + "ms");
				return null;
			} else {
				// DEBUG: test minterm
				boolean [] test = new boolean[assignment.length];
				for(int i = 0; i < assignment.length; i++) test[i] = assignment[i] == 1;
				if(!cnf.satisfies(test)) JDDConsole.out.println("MINTERM _NOT_ VALID!!!");

				JDDConsole.out.println("SAT/"+time + "ms");
				return assignment;
			}

		} catch(InterruptedException ignored) {
			time = System.currentTimeMillis() - time;
			JDDConsole.out.println("UNKNOWN(" + branches + " branches)/" + time + "ms");
			return null;
		}

	}

	// -------------------------------------------------------------------------------

	/** chose a unit clause, return NULL if none exists, searchClauses must have been caled before! */
	protected Clause getUnitClause() {
		if(choice_tos == 0) return null;
		return cs[ choice_stack[ (int)( Math.random() * choice_tos) ] ];

	}
	// Check for NULL and unit clauses, return false if a NULL-clause were found
	protected boolean searchClauses() {
		choice_tos = 0;
		for(int i = 0; i < top; i++) {
			if(cs[i].isNull()) return false;
			if(cs[i].isUnit())  choice_stack[choice_tos++] = i;
		}
		return true;
	}


	// ----[ random "heuristic" ] ------------------------------------------------------
	/** chose a var to branch on, the important part of DPLL ... */
	protected Var varToBranch() {
		if(var_top == 0) return null;
		return cnf.vars[ var_stack[(int)( Math.random() * var_top)] ];
	}

	// -------------------------------------------------------------------------------

	protected boolean dpll() throws InterruptedException{ // top-level solver
		// simple stuff, cannot backtrack these:
		while(true) {
			if(!searchClauses()) return false;// NULL clause found
			if(allAssigned()) return true; // OMEGA empty,
			Clause c = getUnitClause();
			if(c != null) { // it exits, do unit propogation
				Lit l = c.first();
				if(!prop(l)) return false;
			} else break;
		}
		return dpll_prop();
	}

	// this is the heart of the DPLL algorithm
	protected final boolean dpll_prop() throws InterruptedException{

		if(System.currentTimeMillis() > endtime) throw new InterruptedException("");

		if(!searchClauses()) return false;// NULL clause found
		if(allAssigned()) return true; // OMEGA empty, should not happen

		boolean result = false;
		Clause c = getUnitClause();
		if(c != null) { // it exits, do unit propogation
			Lit l = c.first(); // the one and only literal
			result = prop(l);
			if(result) result = dpll_prop();
			unprop(l);
		} else { 		// no unit clauses, branch!
			branches++;
			Var v = varToBranch(); // v cannot be null here
			Lit l = v.var;
			result = prop(l);

			if(result) result = dpll_prop();
			unprop(l);

			if(!result) { // try -l
				l = l.negate();
				result = prop(l);
				if(result) result = dpll_prop();
				unprop(l);
			}
		}
		return result;
	}


	// ----[ debugging stuff ]-------------------------------------------------

	protected void showC(int j) {
		Clause c= cs[j];
		System.out.print("" + (1+c.index) + "\t:");
		for(int i = 0; i < c.top; i++) c.lits[i].showDIMACS();
		if(c.top != c.curr) {
			System.out.print(" | ");
			for(int i = c.top; i < c.curr; i++) c.lits[i].showDIMACS();
		}
		System.out.println();
	}

	protected void showAssignments() {
		System.out.print("Assigned vars:");
		for(int i = var_top; i < cnf.num_lits; i++) {
			Var v = cnf.vars[ var_stack[i] ];
			boolean val = (assignment[v.index] == 0) ? false : true; // assigned to what??
			System.out.print(" " + (val ? "" : "-") + (v.index +1));
		}
		System.out.println();
	}

	// -- [Dvais Putnam internals] ----------------------------------------------

	protected final boolean isSatisfied(Clause c) {
		for(int i = 0; i < c.curr; i++) {
			Lit l = c.lits[i];
			Var v = l.var;
			if( isRemoved(v)) { // = is assigned
				boolean val = (assignment[l.index] == 0) ? false : true; // assigned to what??
				if(l.neg != val){
					return true;
				}
			}
		}
		return false;
	}

	protected final boolean isRemoved(Clause c) { return c.offset >= top; }
	protected final boolean isRemoved(Var v) { return v.offset >= var_top; }


	protected final void remove(Clause c) {
		if(c.offset < top) { swap(c, cs[--top]); }
	}
	protected final void reinsert(Clause c) {
		if(c.offset >= top) { swap(c, cs[top++]);  }
	}

	protected final void swap(Clause c1, Clause c2) {
		if(c1.offset != c2.offset) {
			int tmp = c1.offset; c1.offset = c2.offset; c2.offset = tmp;
			cs[c1.offset] = c1;
			cs[c2.offset] = c2;
		}
	}

	protected final boolean allAssigned() { return var_top == 0; }


	protected final void remove(Var v) {
		if(v.offset < var_top) { swap(v.offset, --var_top); }
	}

	protected final void reinsert(Var v) {
		if(v.offset >= var_top) swap(v.offset, var_top++);
	}

	protected final void swap(int c1, int c2) {
		if(c1 != c2) {
			int tmp = var_stack[c1];
			var_stack[c1] = var_stack[c2];
			var_stack[c2] = tmp;
			cnf.vars[ var_stack[c2] ].offset = c2;
			cnf.vars[ var_stack[c1] ].offset = c1;
		}
	}

	protected final boolean prop(Lit l) {
		assign_stack[assign_top++] = l;

		Var v = l.var;
		remove(v);
		assignment[ l.index] = l.neg ? 0: 1;

		boolean ret = true;

		int []list = occurs[v.index];
		int len = list.length;
		for(int i = 0; i < len; i++) {
			Clause c =  org[ list[i] ];
			if(c.remove(v) == l.neg) {
				remove(c);
			} else {
				if(!isRemoved(c) && c.isNull()) ret = false;	// did we just made an active clause NULL ?
			}
		}
		return ret;
	}
	protected final void unprop(Lit l) {
		assign_top--;
		Var v = l.var;
		reinsert(v);

		int []list = occurs[v.index];
		int len = list.length;
		for(int i = 0; i < len; i++) {
			Clause c =  org[ list[i] ];
			if(c.reinsert(v) == l.neg) { // same sign
				if(isRemoved(c))
					if(!isSatisfied(c))
						reinsert(c);
			}
		}
	}
	// -------------------------------------------------------------------------------


	/** I have no idea what this function does ;) */
	public static void main(String args[]) {
		if(args.length == 0) System.err.println("Need DIMACS file as argument");
		else for(int i = 0; i < args.length;i++) {
			try {
				System.out.print("Solving " + args[i] + "\t\t");
				DimacsReader dr = new DimacsReader(args[i], true);
				Solver solver = new DPLLSolver();
				solver.setFormula(dr.getFormula() );
				dr = null;

				int [] x = solver.solve();
				// DEBUG:
				// if(x != null) for(int j = 0; j < x.length; j++) System.out.print("" + x[j]) ; System.out.println();

				solver.cleanup();
			} catch(IOException exx) { exx.printStackTrace(); }
		}
	}
}

