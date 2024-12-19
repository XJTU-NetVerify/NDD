
package jdd.sat.gsat;

import jdd.sat.*;
import jdd.util.*;

import java.io.*;
import java.util.*;

/**

Simple GSAT implementation, as explained in:

"A New Method for Solving Hard Satisfiability Problems" by Selmen, Levesque and Mitchell

*/

public class GSATSolver implements Solver {

	protected  CNF cnf = null;
	protected long maxtime;
	protected int [] stack;

	public GSATSolver(long maxtime) {
		this.maxtime = Math.max(1000, maxtime);
	}

	public void setFormula( CNF cnf) {
		this.cnf = cnf;
		this.stack = new int[cnf.num_lits];
	}
	public void cleanup() {
		this.cnf = null;
		this.stack = null;
	}
	public int [] solve() { // (0,1 or -1 for dont care)
		int num_lits = cnf.num_lits;

		boolean [] minterm = new boolean[num_lits];


		int ignore = -1; // ignore nothing
		int flips = Math.min(1000, 5 * num_lits);

		long starttime = System.currentTimeMillis();
		long endtime = starttime + maxtime, tries = 0;

		int j = flips; // start by initializing
		while( endtime > System.currentTimeMillis() ) {
			tries++;
			if(j == flips) {
				j = 0;
				ignore = -1; // we are not on the same minterm anymore!
				randomize(minterm);
			} else j++;

			if(cnf.satisfies(minterm)) {
				JDDConsole.out.println("SAT/" + (System.currentTimeMillis() - starttime ) + "ms");
				return toIntVector(minterm);
			}

			int best = chooseFlip(minterm, ignore);
			minterm[best] = ! minterm[best];
			ignore = best; // ignore this var next time!
		}

		// times up:
		JDDConsole.out.println("UNKNOWN (" + tries + " tries)/" + (System.currentTimeMillis() - starttime ) + "ms");
		return null;
	}

	// --- [GSAT internals ]----------------------------------------------------------
	protected int[] toIntVector(boolean []minterm) {
		int l = minterm.length;
		int [] ret = new int[l];
		for(int i = 0; i < l; i++) ret[i] = minterm[i] ? 1 : 0;
		return ret;
	}

	protected void randomize(boolean [] b) {
		int len = b.length;
		for(int i = 0; i < len; i++) b[i] = (Math.random() >= 0.5);
	}


	protected int chooseFlip( boolean [] minterm, int ignore) {
		int len = minterm.length;
		int most = -Integer.MAX_VALUE, tos = 0;
		int was = cnf.conflicts(minterm);
		for(int i = 0; i < len;i++) {
			if(i != ignore) {
				// The stupid way:
				// minterm[i] = !minterm[i];
				// int changes = was - cnf.conflicts(minterm);
				// minterm[i] = !minterm[i];
				int changes = satisfiableIfFlip(i, minterm);
				if(changes == was) // SAT!
					return i;

				if(most < changes) {
					tos = 0;
					most = changes;
				}
				if(most == changes) stack[tos++] = i;
			}
		}

		Test.check(tos != 0);

		return stack[random(tos) ];
	}

	protected int satisfiableIfFlip(int var, boolean [] minterm) {
		Var v = cnf.vars[var];

		boolean save = minterm[var];
		int c_true = 0, c_false = 0;
	  	for (Enumeration e = v.occurs.elements() ; e.hasMoreElements() ;) {
			Clause c = (Clause) e.nextElement();
			minterm[var] = true;
			if(c.satisfies(minterm)) c_true++;
			minterm[var] = false;
			if(c.satisfies(minterm)) c_false++;
		}

		minterm[var] = save;

		return save ? (c_false - c_true) : (c_true - c_false);
	}


	protected final int random(int max) {		return (int)( Math.random() * max);	}


	// int dum = 0;  protected final int random(int max) {return (dum++) % max;} // DEBUG VERSION

	// -------------------------------------------------------------------------------

	/** I have no idea what this function does ;) */
	public static void main(String args[]) {
		if(args.length == 0) System.err.println("Need DIMACS file as argument");
		else for(int i = 0; i < args.length;i++) {
			try {
				System.out.print("Solving " + args[i] + "\t\t");
				DimacsReader dr = new DimacsReader(args[i], true);
				GSATSolver solver = new GSATSolver(2000);
				solver.setFormula(dr.getFormula() );
				dr = null;
				solver.solve();
				solver.cleanup();
			} catch(IOException exx) { exx.printStackTrace(); }
		}
	}
}


