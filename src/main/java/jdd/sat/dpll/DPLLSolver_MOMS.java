
package jdd.sat.dpll;

import jdd.sat.*;
import jdd.util.*;

import java.io.*;
import java.util.*;

/**

DPLL implementationwith MOMS herusitics, taken from JSAT
*/

public class DPLLSolver_MOMS extends DPLLSolver {

	public DPLLSolver_MOMS(long maxtime) { super(maxtime);	}
	public DPLLSolver_MOMS() { }

	// ----[ MOMS heuristic ] ------------------------------------------------------
	private static final long [] MOMS_k1 = { 0,125,125,25,5,1 };
	private static final long MOMS_k2 = 1024;

	private long MOMS_getWeight(Clause c) {
		int len = c.top;
		if(len < MOMS_k1.length)  return MOMS_k1[len];
		return MOMS_k1[MOMS_k1.length-1];

	}
	private long MOMS_getWeight(Var v) {
		final int index = v.index;
		final int [] cls = occurs[v.index];
		final int len = cls.length;
		long tmp, w0 = 0, w1 = 0;

		for(int i = 0; i < len; i++) {
			Clause c = org [ cls[i] ];
			if(! isRemoved(c)) {
				for(int j = 0; j < c.top; j++)  {
					final Lit l = c.lits[j];
					if( index == l.index) {
						tmp = MOMS_getWeight(c);
						if(l.neg)	w0 +=  tmp;
						else		w1 += tmp;
						continue;
					}
				}
			}

		}
		return (w0*w1*MOMS_k2 + w1+w0);
	}
	protected Var varToBranch() {
		long next, best = 0;
		final Var [] vars = cnf.vars;
		int tos = 0;
		for(int i = 0; i < var_top; i++) {
			int k = var_stack[i];
			next = MOMS_getWeight( vars[ k] );
			if(next > best) next = best;
			if(next == best)	choice_stack2[tos++] = k;
		}

		Var v = vars[ choice_stack2[(int)( Math.random() * tos)] ];
		return v;
	}

	// -------------------------------------------------------------------------------



	public static void main(String args[]) {
		if(args.length == 0) System.err.println("Need DIMACS file as argument");
		else for(int i = 0; i < args.length;i++) {
			try {
				System.out.print("Solving " + args[i] + "\t\t");
				DimacsReader dr = new DimacsReader(args[i], true);
				Solver solver = new DPLLSolver_MOMS();
				solver.setFormula(dr.getFormula() ); dr = null;
				int [] x = solver.solve();
				solver.cleanup();
			} catch(IOException exx) { exx.printStackTrace(); }
		}
	}
}

