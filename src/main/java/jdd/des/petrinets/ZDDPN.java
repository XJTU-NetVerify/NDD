
package jdd.des.petrinets;


import jdd.util.*;
import jdd.bdd.*;
import jdd.zdd.*;

/**
 * adds functionality needed to work with Petri nets.
 *
 * <p>
 * Based on the paper
 * "Verification of Asynchronous circuits based on Zero-Supressed BDDs"
 * by Yoneda, Masukura and Tomisaka.
 *
 * <p><b>Note:</b>
 * This is an intermediate class so do not use it in you projects.
 * Try instead the sub-class <tt>SymbolicPetrinet</tt>.
 *
 * @see SymbolicPetrinet
 */

public class ZDDPN  extends ZDD  {

	public ZDDPN(int nodesize) {	this(nodesize, Configuration.DEFAULT_ZDD_CACHE_SIZE); }
	public ZDDPN(int nodesize, int cachesize) {
		super(nodesize, cachesize);
	}


	// ---------------------------------------------------------------------------------

	/** extended version of subset1, works on unate cubes instead of single variables */
	public int Subset1(int zdd, int cube) {
		if(cube < 2) return zdd;
		// XXX: not sure what to do when cube == 1

		int prev = work_stack[work_stack_tos++] = Subset1(zdd, getHigh(cube));
		int ret = subset1(prev, getVar(cube));
		work_stack_tos--;

		return ret;
	}


	/** extended version of subset0, works on unate cubes instead of single variables */
	public int Subset0(int zdd, int cube) {
		// XXX: not sure what to do when cube == 0
		if(cube < 2) return zdd;
		int prev = work_stack[work_stack_tos++] = Subset0(zdd, getHigh(cube) );
		int ret = subset0(prev, getVar(cube));
		work_stack_tos--;
		return ret;
	}


	/** extended version of change, works on unate cubes instead of single variables */
	public int Change(int zdd, int cube) {
		if(cube < 2) return zdd;
		int prev = work_stack[work_stack_tos++] = Change(zdd, getHigh(cube) );
		int ret = change(prev, getVar(cube));
		work_stack_tos--;
		return ret;
	}

	// [ stuff added directly from the paper ] ---------------------------------------

	// XXX: all these stuff must be tested!
	public int enabled(int M, Transition t) {
		return Subset1(M, t.zdd_ot);
	}

	public int invEnabled(int M, Transition t) {
		return Subset1(M, t.zdd_to);
	}

	public int image(int M, Transition t) {
		// computes Change( Change( Enabled(M,t), *t), t*);
		int tmp = work_stack[work_stack_tos++] = enabled(M, t);
		tmp = Change( tmp, t.zdd_to);
		work_stack_tos--;
		return tmp;
	}

	public int invImage(int M, Transition t) {
		// computes Change( Change( InvEnabled(M,t), t*), *t);
		int tmp = work_stack[work_stack_tos++] = invEnabled(M, t);
		tmp = Change( M, t.zdd_ot);
		work_stack_tos--;
		return tmp;
	}

	// ---------------------------------------------------------------------------------

	/** compute forward reachables ... */
	public int forward(int M0, Transition [] ts) {
		int len = ts.length;
		int Mp, M = M0;
		do {
			Mp = M;
			ref(M);
			for(int i = 0; i < len; i++) {
				int next = ref( image(M, ts[i]) );
				int tmp  = ref( union(next, M) );
				deref(next);
				deref(M);
				M = tmp;
			}

			deref(M);
		} while(Mp != M);
		return M;
	}
	// ---------------------------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("ZDDPN");

		ZDDPN pn = new ZDDPN(100);

		// Options.verbose = true;
		int a = pn.createVar();
		int b = pn.createVar();
		int c = pn.createVar();
		int d = pn.createVar();

		// checking sanity of some old stuff first:
		int set = pn.union( pn.union( pn.cube("111"), pn.cube("110") ), pn.union( pn.cube("1011"), pn.cube("1000") ));
		int cube = pn.cube("0011"); // ab

		int j1 = pn.subset1(set, a);
		int j2 = pn.subset1(j1, b);
		int j3 = pn.Subset1(set, cube);
		Test.checkEquality( j2, j3, "Subset1");


		j1 = pn.subset0(set, a);
		j2 = pn.subset0(j1, b);
		j3 = pn.Subset0(set, cube);
		Test.checkEquality( j2, j3, "Subset0");



		j1 = pn.change(set, a);
		j2 = pn.change(j1, b);
		j3 = pn.Change(set, cube);
		Test.checkEquality( j2, j3, "change");



		// -------------------- PN examples start here ------------------------

		// A simple PN:  d: v4, c: v3, b: v2
		// d --[t1] --> b
		// d --[t2] --> c

		int t1_from = pn.change(1, d);
		int t1_to   = pn.change(1, b);
		int t2_from = pn.change(1, d);
		int t2_to   = pn.change(1, c);

		int m0 = pn.cube("1100");			// cd
		int m01 = pn.Subset1(m0, t1_from);	// c
		int m02 = pn.Change(m01, t1_to);	// cb


		int real_m0  = pn.change(pn.change(1,c),d);
		int real_m01 = pn.change(1, c);
		int real_m02 = pn.change(pn.change(1,c),b);
		Test.checkEquality( real_m0, m0, "m0 ok");
		Test.checkEquality( real_m01, m01, "m01 ok");
		Test.checkEquality( real_m02, m02, "m02 ok");



		// todo: more tests are needed!!

		Test.end();
	}
}




/*
OLD STUFF

	public int image(int M, int dot_t, int t_dot) {
		int tmp = work_stack[work_stack_tos++] = Subset1(M, dot_t);
		tmp = Change( tmp, t_dot);
		work_stack_tos--;
		return tmp;
	}

	// XXX: returns incorrect results!
	public int preImage(int M, int dot_t, int t_dot, int dot_t_but_not_t_dot) {
		int tmp1 = work_stack[work_stack_tos++] = Subset1(M, t_dot);
		int tmp2 = work_stack[work_stack_tos++] = Subset0(M, dot_t_but_not_t_dot);
		int tmp3 = work_stack[work_stack_tos++] = intersect(tmp1, tmp2);
		tmp3 = Change(tmp3, dot_t);
		work_stack_tos -= 3;
		return tmp3;
	}
	private int [] TrSetSP_list = null;
	static final int TR_NONE = 0, TR_FROM = 1, TR_TO = 2;

	public int TrSetSP(int f, int [] list) {
		TrSetSP_list = list;
		return TrSP(f, 0);
	}

	int x = 0;
	private int TrSP(int f, int level) {

		printDot("f_x="+(x++) + "_level="+level + ".dot", f);
		if(level == TrSetSP_list.length) return f;
		int t = TrSetSP_list[level];

		// JDDConsole.out.println("level = " + level + ", t = " + t);
		if(t == TR_FROM) return V10_tr(f, level);
		if(t == (TR_TO|TR_FROM)) return V11_tr(f, level);
		if(t == TR_TO) return V01_tr(f, level);



		return TrSP(f,level+1);
	}

	private int V10_tr(int f, int level) {
		if(t_var[f] < level) return 0;
		if(t_var[f] == level) return TrSP( t_high[f], level+1);

		int tmp1 = work_stack[work_stack_tos++] = V10_tr(t_low[f], level);
		int tmp2 = work_stack[work_stack_tos++] = V10_tr(t_high[f],level);


				printDot("V10_tr_"+(x) + "_level="+level + "_LOW.dot", tmp1);
				printDot("V10_tr_"+(x) + "_level="+level + "_HIGH.dot", tmp2);


		tmp1 = mk( t_var[f], tmp1, tmp2);
		printDot("V10_tr_"+(x) + "_level="+level + ".dot", tmp1); x++;

		work_stack_tos -= 2;
		return tmp1;
	}

	private int V11_tr(int f, int level) {
		if(t_var[f] < level) return 0;
		if(t_var[f] == level) {
			int tmp1 = work_stack[work_stack_tos++] = TrSP(t_high[f], level+1);
			tmp1 = mk(t_var[f], 0, tmp1);
			work_stack_tos--;
			return tmp1;
		}

		int tmp1 = work_stack[work_stack_tos++] = V11_tr(t_low[f], level);
		int tmp2 = work_stack[work_stack_tos++] = V11_tr(t_high[f],level);
		tmp1 = mk( t_var[f], tmp1, tmp2);
		work_stack_tos -= 2;
		return tmp1;
	}

	private int V01_tr(int f, int level) {
		if(t_var[f] < level) {
			int tmp1 = work_stack[work_stack_tos++] = TrSP(t_high[f], level+1);
			tmp1 = mk(level, 0, tmp1);
			work_stack_tos--;
			return tmp1;
		}
		if(t_var[f] == level) {
			int tmp1 = work_stack[work_stack_tos++] = TrSP(t_low[f], level+1);
			tmp1 = mk(t_var[f], 0, tmp1);
			work_stack_tos--;
			return tmp1;
		}

		int tmp1 = work_stack[work_stack_tos++] = V01_tr(t_low[f], level);
		int tmp2 = work_stack[work_stack_tos++] = V01_tr(t_high[f],level);

		tmp1 = mk( t_var[f], tmp1, tmp2);
		work_stack_tos -= 2;
		return tmp1;

	}
*/
