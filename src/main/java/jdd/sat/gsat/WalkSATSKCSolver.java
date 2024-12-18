

package jdd.sat.gsat;

import jdd.sat.*;
import jdd.util.*;

import java.io.*;
import java.util.*;


/**

WalkSAT-SKC, sucks even more than WalkSAT.
*/

// ok, this is not GSAT, but thing works easier if we can reuse GSATSolver:s internal functions
public class WalkSATSKCSolver extends GSATSolver{

	private int [] stack2 = null;
	private double p;
	public WalkSATSKCSolver(long maxtime, double p) { super(maxtime);  this.p = p;}



	public void cleanup() {
		super.cleanup();
		this.stack2 = null;
	}

	public int [] solve() { // (0,1 or -1 for dont care)
		int rounds = 0, num_lits = cnf.num_lits;


		long starttime = System.currentTimeMillis();
		long endtime =  starttime + maxtime;
		int flips = Math.min(1000, 3 * num_lits);
		boolean [] minterm = new boolean[ num_lits ];

		if(stack2 == null) stack2 = new int[cnf.curr];

		randomize(minterm);

		while( endtime > System.currentTimeMillis() ) {
			rounds++;
			if(cnf.satisfies(minterm)) {
				JDDConsole.out.println("SAT/" + (System.currentTimeMillis() - starttime ) + "ms");
				return toIntVector(minterm);
			}

			if(( rounds % flips) == 0){ // restart
				randomize(minterm);
				continue;
			}

			Clause c = cnf.clauses[ random(cnf.curr) ];
			int choice = findLiteral(c, minterm);

			if(choice == -1) {
				if(Math.random() < p)	choice = random(num_lits);
				else					choice = findLiteral2(minterm);
			}
			minterm[choice] = !minterm[choice];
		}

		JDDConsole.out.println("UNKNOWN(" + rounds + " rounds)/" + (System.currentTimeMillis() - starttime ) + "ms");
		return null;
	}


	// -------------------------------------------------------------------------------
	// returns a literal in c whos value can be changed without causing any new clauses
	// to become unsat or -1 if none

	private int findLiteral(Clause c, boolean [] minterm) {
		int len = c.curr, tos = 0;
		for(int i = 0; i < len; i++)
			if(canBeChanged( c.lits[i].var, minterm) ) stack[tos++] = c.lits[i].var.index;
		return (tos  == 0) ? -1 : stack[random(tos)];
	}
	// returns true if its value can be chagned without making a new clause unsat
	private boolean canBeChanged(Var v, boolean [] minterm) {
		int index = v.index;
		for (Enumeration e = v.occurs.elements() ; e.hasMoreElements() ;) {
			Clause c = (Clause) e.nextElement();
			if(c.satisfies(minterm)) {
				minterm[index] = ! minterm[index];
				boolean still_sat = c.satisfies(minterm);
				minterm[index] = ! minterm[index];
				if(!still_sat) return false;
			}
		}
		return true;
	}

	// returns literal such that when its value is changed, the smallest number of sattisfied clauses
	// become unsatisfied
	private int findLiteral2(boolean [] minterm) {
		int best = -Integer.MAX_VALUE, tos = 0, tos2 = 0; // now, find all SATs
		for(int i = 0; i < cnf.curr; i++)  if(cnf.clauses[i].satisfies(minterm)) stack2[tos2++] = i;
		if(tos2 == 0) return random(cnf.num_lits); // nothing satisfied :( ??


		for(int i = 0; i < cnf.num_lits; i++) {
			minterm[i] = !minterm[i];
			int still_sat = subsetOfClausesStisfied(minterm, stack2, tos2);
			minterm[i] = !minterm[i];
			if(still_sat > best) {
				best = still_sat;
				tos = 0;
			}
			if(still_sat == best) {
				stack[tos++] = i;
			}
		}

		return stack[random(tos)];
	}

	private int subsetOfClausesStisfied(boolean [] minterm, int [] subset, int size) {
		int c = 0;
		for(int i = 0; i < size; i++)
			if(cnf.clauses[ subset[i] ].satisfies(minterm)) c++;
		return c;
	}


	// -------------------------------------------------------------------------------
	/** I have no idea what this function does ;) */
	public static void main(String args[]) {
		if(args.length == 0) System.err.println("Need DIMACS file as argument");
		else for(int i = 0; i < args.length;i++) {
			try {
				System.out.print("Solving " + args[i] + "\t\t");
				DimacsReader dr = new DimacsReader(args[i], true);
				WalkSATSKCSolver solver = new WalkSATSKCSolver(2000, 0.02);
				solver.setFormula(dr.getFormula() );
				dr = null;
				solver.solve();
				solver.cleanup();
			} catch(IOException exx) { exx.printStackTrace(); }
		}
	}
}


