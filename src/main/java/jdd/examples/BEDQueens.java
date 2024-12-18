
package jdd.examples;


import jdd.bed.*;
import jdd.util.*;

/**
 * Once we get the BEDs working, this will solve the N Queens problem with
 * BEDs instead of BDDs...
 *
 */

public class BEDQueens
// TODO:
/* extends BED implements Queens */
{
	/*
	// TODO:
	private int [] bdds;
	private int N, queen;
	private double sols;
	private long time;
	private boolean [] solvec;

	private int X(int x, int y)  { return bdds[ y + x * N]; }

	private int orTo(int b1, int b2) {
		int tmp = apply(TYPE_OR, b1, b2);
		ref(tmp);
		deref(b1);
		return tmp;
	}

	private int andTo(int b1, int b2) {
		int tmp = apply(TYPE_AND, b1,b2);
		ref(tmp);
		deref(b1);
		return tmp;
	}

	public BEDQueens(int N) {
		super(10000 + 1000 * N, 10000);

		this.N = N;

		time = System.currentTimeMillis() ;

		int all = N * N;
		bdds = new int[all];
		for(int i = 0; i < all; i++)
			bdds[i] = createVar();

		queen = 1;

		for (int i=0 ; i<N ; i++) {
			int e = 0;
			for(int j = 0; j < N; j++)
				e = orTo(e, X(i,j) );
		    queen = andTo(queen, e);
		    deref(e);
		}




		for (int i=0 ; i<N ; i++)
			for(int j = 0; j < N; j++) {
				build(i,j);
			}


		// DEBUG CODE STARTS HERE
		printDot("xx1", queen);
		int tmp = queen;
		printFormula(queen);
		queen = ref( upAll(queen) );
		deref(tmp);
		printDot("xx2", queen);
		// DEBUG CODE ENDS HERE


		// sols = satCount(queen);
		time = System.currentTimeMillis()  -time;


		// if(queen == 0) solvec =  null; // no solutions
		//
		// int [] tmp = oneSat(queen, null);
		// solvec = new boolean[ tmp.length];
		// for(int x = 0; x < solvec.length; x++) solvec[x] = (tmp[x] == 1);
		// deref(queen);
		if(Options.verbose) showStats();

		cleanup();
	}



	private void build(int i, int j) {
		int a, b, c, d;
		a = b = c = d = 1;

   		int k,l;

		  // No one in the same column
	   for (l=0 ; l<N ; l++)
		 	if (l != j) {
				int nx = ref( not( X(i,l)) );
				int mp = ref( apply(TYPE_IMP, X(i,j), nx));
				a = andTo(a, mp);
				deref(mp);
				deref(nx);
			}

		  // No one in the same row
		for (k=0 ; k<N ; k++)
			if (k != i) {
				int nx = ref( not(X(k,j) ));
				int mp = ref( apply(TYPE_IMP,X(i,j), nx) );
				b = andTo(b, mp);
				deref(mp);
				deref(nx);
			}

		 // No one in the same up-right diagonal
		for (k=0 ; k<N ; k++){
			int ll = k-i+j;
			if (ll>=0 && ll<N)
				if (k != i) {
					int nx = ref( not(X(k,ll) ));
					int mp = ref( apply(TYPE_IMP,X(i,j), nx) );
					c = andTo(c, mp);
					deref(mp);
					deref(nx);
				}
		}

		  // No one in the same down-right diagonal
		for (k=0 ; k<N ; k++) {
			int ll = i+j-k;
			if (ll>=0 && ll<N)
				if (k != i) {
					int nx = ref( not(X(k,ll) ));
					int mp = ref( apply(TYPE_IMP, X(i,j), nx) );
					d = andTo(d, mp);
					deref(mp);
					deref(nx);
				}
		}


        c = andTo(c, d);
        deref(d);
        b = andTo(b,c);
        deref(c);
        a = andTo(a,b);
        deref(b);
		queen = andTo(queen, a);
		deref(a);

	}

	// ---------------------------------------
	public int getN() { return N; }
	public double numberOfSolutions() { return sols; }
	public long getTime() { return time; }
	public boolean [] getOneSolution() { return solvec;	}

	// -------------------------------------------
	public static void main(String [] args) {
		if(args.length == 1) {
			// Options.verbose = true;
			BEDQueens q = new BEDQueens( Integer.parseInt( args[0] ) );
			// q.showOneSolution();
			Console.out.println("There are " + q.numberOfSolutions() + " solutions (time: " + q.getTime() + ")");
		}
	}

	*/
	/** testbench. do not call */
	public static void internal_test() {
		// TODO
	}
}
