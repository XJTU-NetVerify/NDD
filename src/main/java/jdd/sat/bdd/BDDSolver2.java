
package jdd.sat.bdd;

import jdd.bdd.*;
import jdd.sat.*;
import jdd.util.*;

import java.io.*;

public class BDDSolver2  extends BDDSolver {

	private boolean [] clause_taken;
	private int [] clause_list, extra_list;


	public BDDSolver2(boolean verbose) { super(verbose); }


	public int [] solve() {
		clause_taken = new boolean[cnf.curr];
		clause_list = new int[cnf.curr];
		extra_list = new int[cnf.curr];
		Array.set(clause_taken, false);

		return super.solve();

	}

// ------------------------------------------------------------------

	public Clause nextClause() {
		// return choose_largest();
		// return choose_smallest();
		// return choose_first();
		// return choose_most_relevant();
		return choose_smallest_random();
	}

	// ------------------------------------------------------------------
	private Clause choose_smallest_random() {
		int tries = 3;
		int best_index = -1, best = Integer.MAX_VALUE;

		for(int i = 0; i < tries; i++) {
			int choice = -1;
			do {
				choice = (int)(Math.random() * cnf.curr);
			} while(clause_taken[choice]);

			int x = jdd.and(bdd_all, clauseBDD( cnf.clauses[choice]) );
			int size = jdd.nodeCount(x);
			jdd.deref(x);
			if(size < best) {
				best_index = choice;
				best = size;
			}
		}

		clause_taken[ best_index ] = true;
		return cnf.clauses[ best_index ];
	}
	// ------------------------------------------------------------------
	private Clause choose_first() {
		for(int i = 0; i < cnf.curr; i++) {
			if(!clause_taken[i]) {
				clause_taken[i] = true;
				return cnf.clauses[i];
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	private Clause choose_most_relevant() {
		int cluases = 0;
		int min_news = Integer.MAX_VALUE;

		for(int i = 0; i < cnf.curr; i++) {
			if(!clause_taken[i]) {
				Clause c = cnf.clauses[i];
				int news = c.curr - count_allocation(c);
				if( min_news > news ) {
					cluases = 0;
					min_news = news;
				}
				if(min_news == news) {
					clause_list[cluases++] = i;
				}

			}
		}


		int choice = clause_list[ (int)(Math.random() * cluases)];

		clause_taken[choice] = true;
		return cnf.clauses[choice];
	}

	// ------------------------------------------------------------------
	private Clause choose_largest() {
		int cluases = 0;
		int max_size = 0;

		for(int i = 0; i < cnf.curr; i++) {
			if(!clause_taken[i]) {
				Clause c = cnf.clauses[i];
				if(max_size < c.curr) {
					cluases = 0;
					max_size =  c.curr;
				}
				if(max_size == c.curr) {
					clause_list[cluases++] = i;
				}

			}
		}

		// int choice = choose_most_used(clause_list, cluases);
		int choice = choose_least_new(clause_list, cluases);

		clause_taken[choice] = true;
		return cnf.clauses[choice];
	}
	// ------------------------------------------------------------------
	private Clause choose_smallest() {
		int cluases = 0;
		int min_size = Integer.MAX_VALUE;

		for(int i = 0; i < cnf.curr; i++) {
			if(!clause_taken[i]) {
				Clause c = cnf.clauses[i];
				if(min_size > c.curr) {
					cluases = 0;
					min_size =  c.curr;
				}
				if(min_size == c.curr) {
					clause_list[cluases++] = i;
				}

			}
		}

		int choice = choose_most_used(clause_list, cluases);
		// int choice = choose_least_new(clause_list, cluases);

		clause_taken[choice] = true;
		return cnf.clauses[choice];
	}
	// ------------------------------------------------------------------

	/** chosoe the clause with largest allocated-BDD _ratio_ between its used literals*/
	private int choose_most_used(int [] list, int count) {
		double most_allocated = 0;
		int alloc_count = 0;

		for(int i = 0; i < count;i++) {
			Clause c = cnf.clauses[ list[i] ];
			double used = ((double)count_allocation(c)) / c.curr;

			if(most_allocated < used) {
				alloc_count = 0;
				most_allocated = used;
			}
			if(used == most_allocated)	extra_list[alloc_count++] = list[i];
		}
		return extra_list[(int)(Math.random() * alloc_count) ];
	}

	/** chose the clause with the least new BDDs reduced */
	private int choose_least_new(int [] list, int count) {
		int least_used = Integer.MAX_VALUE;
		int alloc_count = 0;

		for(int i = 0; i < count;i++) {
			Clause c = cnf.clauses[ list[i] ];
			int news = c.curr - count_allocation(c);

			if(least_used > news) {
				alloc_count = 0;
				least_used = news;
			}
			if(least_used == news)	extra_list[alloc_count++] = list[i];
		}
		return extra_list[(int)(Math.random() * alloc_count) ];
	}

	/** number of BDD variables already allocated in this Clause */
	private int count_allocation(Clause c) {
		int count = 0;
		for(int i = 0; i < c.curr; i++) if(c.lits[i].bdd != -1) count++;
		return count;
	}

	// ----------------------------------------------------
	/** I have no idea what this function does ;) */
	public static void main(String args[]) {
		if(args.length == 0) System.err.println("Need DIMACS file as argument");
		else for(int i = 0; i < args.length;i++) {
			try {
				System.out.print("Solving " + args[i] + "\t\t");
				DimacsReader dr = new DimacsReader(args[i], true);
				BDDSolver2 solver = new BDDSolver2(false);
				solver.setFormula(dr.getFormula() );
				dr = null;
				solver.solve();
				solver.cleanup();
			} catch(IOException exx) { exx.printStackTrace(); }
		}
	}
}
