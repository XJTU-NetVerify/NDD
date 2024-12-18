
package jdd.examples;

import jdd.sat.*;

/**
 * <pre>
 * Generate an N * Queens problem as CNF.
 *
 * This file is copied from bench.c in SATOs source disribution.
 * However, it doesnt output the same result, so I assume SATO optimizes the
 * CNF formula first...
 * </pre>
 */


public class QueensCNFGenerator {
	private int n;
	private CNF cnf;
	private Lit []var,  nvar;

	public QueensCNFGenerator(int n) {
		this.n = n;
		this.cnf = null;
	}

	public int getN() { return n; }

	public CNF cnf() {
		if(cnf == null) generate();
		return cnf;
	}

	private Lit var(int x, int y, boolean sign) {
		int offset = x * n + y;
		return sign ? var[offset] : nvar[offset];
	}

	private void generate() {
		// TODO: why does clause count differs form SATO:s ???

		int clauses = n * n + 3 * n * n * n; // upper bound ??

		this.cnf = new CNF(clauses, n * n);
		var = new Lit[n * n];
		nvar = new Lit[n * n];
		for(int i = 0; i < n * n; i++) {
			var[i] = cnf.getLit(i+1, false);
			nvar[i] = cnf.getLit(i+1, true);
		}



		// -- code stolen from SATO start here :)
		int n1 = n-1;
		int x,y,v,u;


		for (x = n1; x >= 0; x--) {
			v = n1;
			Clause c = new Clause(n);
			for ( y = 0; y < n; y++) c.insert( var(x,v--, true));
			cnf.insert(c);
		}

		// dont know if this is needed
		for (x = n1; x >= 0; x--) {
    		v = n1;
    		Clause c = new Clause(n);
	    	for (y = 0; y < n; y++) c.insert( var(v--,x, true));
	    	cnf.insert(c);
		}




		for (x = n1; x >= 0; x--)
			for (y = n1; y >= 0; y--)
				for (v = x-1; v >= 0; v--) {
					Clause c = new Clause(2);
					c.insert( var(x,y, false) );
					c.insert( var(v,y, false) );
					cnf.insert(c);

					c = new Clause(2);
					c.insert( var(y,x, false) );
					c.insert( var(y,v, false) );
					cnf.insert(c);
				}


		for (x = n1; x >= 0; x--)
			for (y = n1; y >= 0; y--)
				for (v = x-1; v >= 0; v--)
					for (u = n1; u >= 0; u--)
	  					if (x - v == Math.abs(y - u)) {
							Clause c = new Clause(2);
							c.insert( var(x,y, false) );
							c.insert( var(v,u, false) );
							cnf.insert(c);
						}

		// -- end of shameless SATO rip...

	}
}
