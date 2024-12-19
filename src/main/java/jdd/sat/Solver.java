

package jdd.sat;

public interface Solver {
	void setFormula( CNF cnf);
	void cleanup();
	int [] solve(); // (0,1 or -1 for dont care)
}


