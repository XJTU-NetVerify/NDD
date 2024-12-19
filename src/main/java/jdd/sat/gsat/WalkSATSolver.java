

package jdd.sat.gsat;

import jdd.sat.*;
import jdd.util.*;

import java.io.*;
import java.util.*;


/**

WalkSAT, not working very good yet.

XXX: current implementation is _inefficient_ :(
*/

// ok, this is not GSAT, but thing works easier if we can reuse GSATSolver:s internal functions
public class WalkSATSolver extends GSATSolver{

	private double p;
	public WalkSATSolver(long maxtime, double p) { super(maxtime);  this.p = p;}



	public void cleanup() {
		super.cleanup();
		this.stack = null;
	}

	public int [] solve() { // (0,1 or -1 for dont care)
		int rounds = 0, num_lits = cnf.num_lits;


		long starttime = System.currentTimeMillis();
		long endtime =  starttime + maxtime;

		boolean [] minterm = new boolean[ num_lits ];
		int flips = Math.min(1000, 3 * num_lits);

		if(stack == null){
			stack = new int[num_lits];
		}

		randomize(minterm);

		while( endtime > System.currentTimeMillis() ) {
			rounds++;
			if(cnf.satisfies(minterm)) {
				JDDConsole.out.println("SAT/" + (System.currentTimeMillis() - starttime ) + "ms");
				return toIntVector(minterm);
			}


			if(( rounds % flips) == 0) {
				randomize(minterm);
			} else {
				int choice = (Math.random() < p) ? choice = random(num_lits) : findLiteral(minterm);
				minterm[choice] = !minterm[choice];
			}
		}

		JDDConsole.out.println("UNKNOWN(" + rounds + " rounds)/" + (System.currentTimeMillis() - starttime ) + "ms");
		return null;
	}


	// -------------------------------------------------------------------------------
	// find the literal that minimizes the number of UNSAT clauses
	private int findLiteral(boolean [] minterm) {
		int num_lits = cnf.num_lits, best = - Integer.MAX_VALUE, tos = 0;

		for(int i = 0; i < cnf.curr; i++)
			cnf.clauses[i].flag = cnf.clauses[i].satisfies(minterm) ? 1 : 0;

		for(int i = 0; i < num_lits; i++) {
			int sat = 0;
			for (Enumeration e = cnf.vars[i].occurs.elements() ; e.hasMoreElements() ;) {
				Clause c = (Clause) e.nextElement();
				boolean was_sat = (c.flag == 1);
				minterm[ i] = !minterm[i];
				boolean new_sat = c.satisfies(minterm);
				minterm[ i] = !minterm[i];
				if(was_sat && ! new_sat) sat--;
				if(!was_sat && new_sat) sat++;
			}
			if(sat > best) {
				tos = 0;
				best = sat;
			}
			if(best == sat) stack[tos++] = i;
		}
		return (tos == 0) ? random(num_lits) : stack[ random(tos) ];
	}

	// -------------------------------------------------------------------------------
	/** I have no idea what this function does ;) */
	public static void main(String args[]) {
		if(args.length == 0) System.err.println("Need DIMACS file as argument");
		else for(int i = 0; i < args.length;i++) {
			try {
				System.out.print("Solving " + args[i] + "\t\t");
				DimacsReader dr = new DimacsReader(args[i], true);
				WalkSATSolver solver = new WalkSATSolver(2000, 0.02);
				solver.setFormula(dr.getFormula() );
				dr = null;
				solver.solve();
				solver.cleanup();
			} catch(IOException exx) { exx.printStackTrace(); }
		}
	}
}


