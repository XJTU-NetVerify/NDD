
package jdd.internal.profiler.tests;

import jdd.internal.profiler.*;
import jdd.util.*;
import jdd.util.math.*;
import jdd.bdd.*;
import jdd.bdd.debug.*;


public class QuantTest implements ProfiledObject {
	private final static int NUM_SAMPLES = 10;

	private int n, time;
	private long mem;



	public QuantTest(int n) {
		this.n = n;
		this.time = -1;
		this.mem = -1;
	}



	public void run() {

		FastRandom.mtseed(n); // must be repeatable


		BDD bdd = new ProfiledBDD2(1000 * n , 100 * n);

		int cube1 = bdd.ref( 1) ;
		int cube2 = bdd.ref( 1) ;
		int [] vars1 = new int[n];
		int [] vars2 = new int[n];
		int [] vars1_neg = new int[n];
		int [] vars2_neg = new int[n];
		for(int i = 0; i < n; i++) {
			vars1[i] = bdd.createVar();
			vars2[i] = bdd.createVar();

			vars1_neg[i] = bdd.ref( bdd.not( vars1[i] ) );
			vars2_neg[i] = bdd.ref( bdd.not( vars2[i] ) );

			cube1 = bdd.andTo(cube1, vars1[i]);
			cube2 = bdd.andTo(cube2, vars2[i]);
		}


		// Permutation p12 = bdd.createPermutation( vars1, vars2);
		// Permutation p21 = bdd.createPermutation( vars2, vars1);

		int [] samples = new int[NUM_SAMPLES];

		for(int s = 0; s < samples.length; s++) {

			samples[s] = bdd.ref(0);

			int count = n * 2;

			for(int c = 0; c < count; c++) {

				int x = bdd.ref(1);

				for(int i = n; i != 0;) {
					i--;
					x = bdd.andTo(x, (( FastRandom.mtrand() & 1) == 0) ? vars1[i]: vars1_neg[i]);
					x = bdd.andTo(x, (( FastRandom.mtrand() & 1) == 0) ? vars2[i]: vars2_neg[i]);
				}

				samples[s] = bdd.orTo(samples[s], x);
				bdd.deref(x);
			}
		}


		// ------------------------------------------------------

		long tmp =System.currentTimeMillis();
		// ------------------------------------------------------

		for(int i = 0; i < NUM_SAMPLES; i++) {
			bdd.exists(samples[i], cube1);
			bdd.exists(samples[i], cube2);
		}


		// ------------------------------------------------------
		time = (int)( System.currentTimeMillis() - tmp);
		mem = bdd.getMemoryUsage();
		bdd.cleanup();
	}
	public String getProfileName() { return "QuantTest:" + n; }
	public int getRunningTime() { return time; }
	public long getMemoryUsage() { return mem; }

	// ----------------------------------------------------
	public static void main(String [] args) {

		Options.verbose = true;
		int N = 200;
		QuantTest qs = new QuantTest(N);
		qs.run();
		System.out.println("N = " + N +", mem = " + (qs.getMemoryUsage() / 1024)  + ", time = " + qs.getRunningTime() );
	}
}