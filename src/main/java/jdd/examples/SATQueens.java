
package jdd.examples;


import jdd.sat.*;
import jdd.util.*;

/**
 * Adapter to run a CNF formula representing a N * Queens problem.
 * Not very efficient, but it will still do [ thanks god NPC < #P ].
 */

public class SATQueens implements Queens {

	private int N;
	private CNF cnf;
	private double sols;
	private long time;
	private boolean [] solvec;

	public SATQueens(int N, Solver solver) {
		this.N = N;

		time = System.currentTimeMillis() ;
		QueensCNFGenerator gen = new QueensCNFGenerator(N);
		cnf = gen.cnf();
		gen = null; // not needed anymoew

		solver.setFormula(cnf);
		int [] tmp = solver.solve();
		solver.cleanup();
		time = System.currentTimeMillis()  - time;

		if(tmp != null) {
			sols = 1.0;
			solvec = new boolean[ tmp.length];
			for(int x = 0; x < solvec.length; x++) solvec[x] = (tmp[x] == 1);
		} else {
			solvec = null;
			sols = 0.0;
		}
	}

	// ---------------------------------------
	public int getN() { return N; }
	public double numberOfSolutions() { return sols; }
	public long getTime() { return time; }
	public boolean [] getOneSolution() { return solvec;	}
}
