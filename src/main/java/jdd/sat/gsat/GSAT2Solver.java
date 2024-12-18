

package jdd.sat.gsat;

import jdd.sat.*;
import jdd.util.*;

import java.io.*;
import java.util.*;

/**

A little more efficient implementation of GSAT.java [nearly twice as fast ?].

- it minimizes the calls to CNF.conflicts() [ which is called instead of CNF.satisfies].
- it tries to recognize trivial assignments,
- tries to use arrays instead of using Vector:s (see variable 'occurneses')

*/

public class GSAT2Solver extends GSATSolver {

	private Clause [][]occurneses;
	private int [] assignments;
	private boolean has_assignments;
	private int ignore;

	public GSAT2Solver(long maxtime) { super(maxtime); }

	private void setup_occurneses() {
		int num_lits = cnf.num_lits;
		occurneses = new Clause[num_lits][];
		for(int i = 0; i < num_lits; i++) {
			int j = 0, size = cnf.vars[i].occurs.size();
			occurneses[i] =  new Clause[size];
			for (Enumeration e =  cnf.vars[i].occurs.elements() ; e.hasMoreElements() ;j++)
				occurneses[i][j] = (Clause) e.nextElement();
		}
	}

	/** setup assignments, returns false if it is trivially UNSAT */
	private boolean setup_assignments() {

		int num_lits = cnf.num_lits;
		assignments = new int[num_lits];
		has_assignments = false;
		Array.set(assignments, -1);
		for(int i = 0; i < cnf.curr; i++) {
			if(cnf.clauses[i].curr == 1) {
				Lit lit = cnf.clauses[i].lits[0];
				int ns =  lit.neg ? 0 : 1;
				if( assignments[lit.index] != -1 && assignments[lit.index] != ns) return false;
				else assignments[lit.index] = ns;
				has_assignments = true;
			}
		}

		if( has_assignments ) System.out.println("Formula has assignments");
		return true;
	}
	public int [] solve() { // (0,1 or -1 for dont care)

		if(!setup_assignments() ) {
			JDDConsole.out.println("UNSAT(trivial)");
			return null;
		}

		setup_occurneses();

		int num_lits = cnf.num_lits;
		int flips = Math.min(1000, 5 * num_lits);
		int j = flips; // start by initializing

		long starttime = System.currentTimeMillis();
		long endtime =  starttime + maxtime, tries = 0;

		boolean [] minterm = new boolean[num_lits];
		ignore = -1; // ignore nothing

		int conflicts = -1;
		while( endtime > System.currentTimeMillis() /* &&  tries < 30 */) {
			tries++;
			if( (j == flips) && (conflicts != 0) ) { // dont restarts if we have the solution!
				j = 0;
				ignore = -1; // we are not on the same minterm anymore!
				randomize(minterm);
				conflicts = cnf.conflicts(minterm);
			} else j++;


			if(conflicts == 0) {
				JDDConsole.out.println("SAT/" + (System.currentTimeMillis() - starttime ) + "ms");
				return toIntVector(minterm);
			}
			int n = flipAndSolve(minterm, conflicts);
			conflicts -= n;
		}


		// times up:
		JDDConsole.out.println("UNKNOWN (" + tries + " flips)/" + (System.currentTimeMillis() - starttime ) + "ms");
		return null;
	}

	// --- [GSAT internals ]----------------------------------------------------------

	protected void randomize(boolean [] b) {
		int len = b.length;
		if(has_assignments) {
			for(int i = 0; i < len; i++) {
				if(assignments[i] == -1) b[i] = (Math.random() >= 0.5);
				else b[i] = assignments[i] == 1 ? true : false;
			}
		} else {
			for(int i = 0; i < len; i++) b[i] = (Math.random() >= 0.5);
		}
	}

	/** flips a coin and returns the number of additional clauses satisfied due to this flip */
	protected int flipAndSolve( boolean [] minterm, int was) {
		int len = minterm.length;
		int most = -Integer.MAX_VALUE, tos = 0;
		for(int i = 0; i < len;i++) {
			if(i != ignore) {
				int changes = satisfiableIfFlip(i, minterm);
				if(changes == was) {
					minterm[i] = ! minterm[i];
					return was; // SAT!
				}
				if(most < changes) {
					tos = 0;
					most = changes;
				}
				if(most == changes) stack[tos++] = i;
			}
		}

		int choice = stack[ random(tos)];
		minterm[choice] = ! minterm[choice];
		ignore = choice; // dont choose it next time!
		return most;
	}

	/** number of changes in satisfiability if this var flips */
	protected int satisfiableIfFlip(int var, boolean [] minterm) {
		boolean save = minterm[var];
		int c_true = 0, c_false = 0;
		Clause [] cs = occurneses[var];
		int len = cs.length;
		for(int i = 0; i < len; i++) {
			minterm[var] = true;	if(cs[i].satisfies(minterm)) c_true++;
			minterm[var] = false;	if(cs[i].satisfies(minterm)) c_false++;
		}

		minterm[var] = save;
		return save ? (c_false - c_true) : (c_true - c_false);
	}


	// -------------------------------------------------------------------------------
	/** I have no idea what this function does ;) */
	public static void main(String args[]) {
		if(args.length == 0) System.err.println("Need DIMACS file as argument");
		else for(int i = 0; i < args.length;i++) {
			try {
				System.out.print("Solving " + args[i] + "\t\t");
				DimacsReader dr = new DimacsReader(args[i], true);
				GSAT2Solver solver = new GSAT2Solver(2000);
				solver.setFormula(dr.getFormula() );
				dr = null;
				solver.solve();
				solver.cleanup();
			} catch(IOException exx) { exx.printStackTrace(); }
		}
	}
}


