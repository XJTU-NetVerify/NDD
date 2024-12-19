
package jdd.bed;

import jdd.util.*;
import jdd.bdd.*;


/**
 * Boolean Expression Diagrams (BEDs), based on Poul Williams PhD thesis.
 * <p> A bed is basically a non-canonical graph representation of a binary statement.
 *
 * NOTE: THIS CODE IS NOT WORKING YET.
 */
public class BED extends NodeTable {

	// the BED variable node are coded like this:
	//  | <-- the real variable --> | <- operation, VAR_SHIFT bits --> |
	// when BDD or constant, the variable part is ignored.
	private static final int TYPE_MASK = 7;
	private static final int VAR_SHIFT = 4; // how
	public static final int
		TYPE_BDD = 0,	// after this the operations start
		TYPE_NOT = 1,
		TYPE_AND = 2,
		TYPE_OR  = 3,
		TYPE_XOR = 4,
		TYPE_IMPLY= 5 ;

	private static final String [] OP_NAMES = { "BDD" , "~", "and", "or", "xor", "->" };
	/* package */ static final String GET_OPERATION_NAME(int n) {
		return OP_NAMES[n & TYPE_MASK];
	}

	/* package */ static final int GET_OPERATION(int var) {
		return var & TYPE_MASK;
	}

	/* package */ static final int GET_VARIABLE(int var) {
		return var >>> VAR_SHIFT;
	}

	/* package */ static final boolean IS_BDD(int var) {
		return GET_OPERATION(var) == TYPE_BDD;
	}

	/* package */ static final boolean IS_CONSTANT(int bed) {
		return (bed < 2);
	}

	/* package */ static final boolean IS_OPERATION(int var) {
		int type = GET_OPERATION(var);
		return (type > TYPE_BDD);
	}

	/* package */ static final int MAKE_VAR(int var) {
		return (var << VAR_SHIFT) | TYPE_BDD;
	}


	public final boolean isBDD(int node) { return IS_BDD(getVar(node) ); }
	public final int getOperation(int node) { return GET_OPERATION( getVar(node) ); }
	public final int getVariable(int node) { return GET_VARIABLE( getVar(node) ); }

	// ------------------------------------------------
	private int num_vars;

	private NodeName nodeNames = new BEDNames();
	public BED(int nodesize, int cache_size) {
		super(nodesize);
		num_vars = 0;

		// TODO: Add caches here...
	}

	public int createVar() {
		int var_node = MAKE_VAR(num_vars);
		int var = work_stack[work_stack_tos++] = mk(var_node, 0, 1);
		int nvar = mk(var_node, 1, 0);
		work_stack_tos--;
		num_vars++;

		saturate(var);
		saturate(nvar);

		// we want to keep the work stack at least so large
		int need = 5 * num_vars + 1;
		if(work_stack.length < need)
			work_stack = Array.resize(work_stack, work_stack_tos, need);

		//FIXME: this is NOT correct, since the depth of a BDD has nothing to do with the variable count.
		//       but this is 	all we can do right now :(
		tree_depth_changed(num_vars * 4);

		return var;
	}


	// -----------------------------------------------------------

	public int mk(int i, int l, int h) {
	/*
		int ret = lookup(i, l,h);
		if(ret != -1) return ret; // alread exists

		if( IS_BDD(i)) {
			if(l == h) return l;
			return insert(i,l,h);
		} else {
			return rewrite_and_insert(i,l,h);
		}
		*/
		if(IS_BDD(i)) {
			if(l == h) return l;
			return add(i,l,h);
		} else {
			return rewrite_and_insert(i,l,h);
		}
	}

	private final int rewrite_and_insert(int op, int l, int h) {

		if(op == TYPE_NOT) { // simplify NOT
			// terminal
			if( l < 2) return (l ^ 1);

			// double negation
			if( getOperation(l) == TYPE_NOT) return getLow(l);


			// a negated variable (var, 1, 0) ???
			int var = getVar(l);
			if( IS_BDD(var) ) {
				int low = getLow(l);
				int high= getHigh(l);
				if(low < 2 && high < 2) {
					return mk(var, high, low);
				}
			}
		}  else { // simplify a binary operation.

			if(l < 2 || h < 2 || l == h) { // at least one constant, or both are equal
				switch(op) {
					case TYPE_AND:
						if(l == 0 || h == 0) return 0;
						else if(l == 1) return h;
						else if(h == 1 || l == h) return l;
						break; // // not reached
					case TYPE_OR:
						if(l == 1 || h == 1) return 1;
						else if(l == 0) return h;
						else if(h == 0 || l == h) return l;
						break; //// not reached
					case TYPE_XOR:
						if(l == 1) return not(h);
						else if(l == 0) return h;
						else if(h == 1) return not(l);
						else if(h == 0) return l;
						else if(l == h) return 0;
						break; // not reached
					case TYPE_IMPLY:
						if(l == 0 ||l == h) return 1;
						else if(l == 1)  return h;
						else if(h == 1) return 1;
						else if(h == 0) return not(l);
						break; // not reached
				}
			}
		}

		// TODO: swap l and h if it can be done to follow the variable order

		//  nothing we could do, just insert it
		return add(op, l,h);
	}

	// ----------------------------------------------------------------------
	public static final long MIX(int a, int b) { return ((long)a) << 32 |b; }
	public static final int SPLIT1(long x) { return (int)( x & 0xFFFFFFFF); }
	public static final int SPLIT2(long x) { return (int)( (x >>> 32) & 0xFFFFFFFF); }



	public int up_one(int variable, int bed) {
		int var = getVariable(variable); // probably its a bdd, i.e. variable = (var, 0, 1);
		long tmp = up_one_rec(var, bed);

		int l = work_stack[work_stack_tos++] =  SPLIT1(tmp);
		int h = work_stack[work_stack_tos++] =  SPLIT2(tmp);

		// TODD:
		// if( IS_BDD(l) && (var == getVariable(l))) l = getLow(l);
		// if( IS_BDD(h) && (var == getVariable(h))) h = getHigh(l);

		l = mk(MAKE_VAR(var), l, h);
		work_stack_tos -= 2;
		return l;

	}

	// XXX: variable is a real variable number, not a tree (v,0,1) !
	private final long up_one_rec(int variable, int bed) {

		if(bed < 2) return MIX(bed, bed);

		int var = getVar(bed);
		int op = GET_OPERATION(var);

		if(op == TYPE_BDD) {
			int myvar = GET_VARIABLE(var);
			if(myvar == variable)  {
				return MIX( getLow(bed), getHigh(bed) );
			}
		}

		long tmp = up_one_rec(variable, getLow(bed));
		int ll = work_stack[work_stack_tos++] =  SPLIT1(tmp);
		int lh = work_stack[work_stack_tos++] =  SPLIT2(tmp);

		tmp = up_one_rec(variable, getHigh(bed));
		int hl = work_stack[work_stack_tos++] =  SPLIT1(tmp);
		int hh = work_stack[work_stack_tos++] =  SPLIT2(tmp);

		int rl = work_stack[work_stack_tos++] = mk( var, ll, hl);
		int rh = mk( var, lh, hh);
		work_stack_tos -= 5;

		return MIX(rl, rh);
	}

	// ----------------------------------------------------------------------------
	public int up_all(int bed) {
		if(bed < 2) return bed;

		int l = work_stack[work_stack_tos++] = up_all( getLow(bed) );
		int h= work_stack[work_stack_tos++]  = up_all( getHigh(bed) );
		int var = getVar(bed);

		if((l < 2 && h < 2) || IS_BDD(var) ) {
			l = mk( var, l, h);
			work_stack_tos -= 2;
		} else {
			int vl = getVariable(l);
			int vh = getVariable(h);

			// Test.check( IS_BDD(vl), "DEBUG: vl is a bdd");
			// Test.check( IS_BDD(vh), "DEBUG: vh is a bdd");

			if(vl == vh) {
				int tmp1 = work_stack[work_stack_tos++] = mk(var, getLow(l), getLow(h) );
				work_stack[work_stack_tos-1] = up_all(tmp1);

				int tmp2 = work_stack[work_stack_tos++] = mk(var, getHigh(l), getHigh(h) );
				work_stack[work_stack_tos-1] = up_all(tmp2);
				l = mk(vl, tmp1, tmp2);

			} else if(vl < vh) {
				int tmp1 = work_stack[work_stack_tos++] = mk(var, getLow(l), h );
				work_stack[work_stack_tos-1] = up_all(tmp1);
				int tmp2 = work_stack[work_stack_tos++] = mk(var, getHigh(l), h );
				work_stack[work_stack_tos-1] = up_all(tmp2);
				l = mk(vl, tmp1, tmp2);
			} else {
				int tmp1 = work_stack[work_stack_tos++] = mk(var, l, getLow(h) );
				work_stack[work_stack_tos-1] = up_all(tmp1);

				int tmp2 = work_stack[work_stack_tos++] = mk(var, l, getHigh(h));
				work_stack[work_stack_tos-1] = up_all(tmp2);
				l = mk(vh, tmp1, tmp2);
			}

			work_stack_tos -= 4;
		}

		return l;
	}

	// -------- [ operation stubs ] ------------------------------------------------

	protected final int bed_apply(int op, int bed1, int bed2) {
		return mk( op, bed1, bed2);
	}

	public final int not(int bed1) { return bed_apply(TYPE_NOT, bed1, 0); }
	public final int and(int bed1, int bed2) { return bed_apply( TYPE_AND, bed1, bed2); }
	public final int or(int bed1, int bed2) { return bed_apply( TYPE_OR, bed1, bed2); }
	public final int xor(int bed1, int bed2) { return bed_apply( TYPE_XOR, bed1, bed2); }
	public final int imply(int bed1, int bed2) { return bed_apply( TYPE_IMPLY, bed1, bed2); }


	// ---- [ printing ] -----------------------------------------------------------

	public void print(int bed) { BEDPrinter.print(bed, this,nodeNames); }
	public void printFormula(int bed) { BEDPrinter.printFormula(this, bed, this, nodeNames); }
	public void printDot(String fil, int bed) {	BEDPrinter.printDot(fil, bed, this, nodeNames);	}

	// --- [ testbed ] ------------------------------------------------------------

	/** testbench, do not call */
	public static void internal_test() {
		Test.start("BED");

		BED bed = new BED(1000,100);
		int v1 = bed.createVar();
		int v2 = bed.createVar();
		int v3 = bed.createVar();


		int nv1 = bed.ref( bed.not(v1) );
		int nv2 = bed.ref( bed.not(v2) );


		// create some BEDs:
		int tmp1 = bed.ref( bed.xor(v1, v2) );
		int tmp2 = bed.ref( bed.imply(v3, nv1) );
		int x = bed.ref( bed.or(tmp1, tmp2) );

		int or12 = bed.ref( bed.or(v1,v2) );
		int and12 = bed.ref( bed.and(v1,v2) );


		// TEST NOT:
		Test.check( bed.not(0) == 1, "NOT 0 = 1");
		Test.check( bed.not(1) == 0, "NOT 1 = 0");
		int n0 = bed.ref( bed.not(0) );
		int n1 = bed.ref( bed.not(1) );
		Test.check( bed.not(n0) == 0, "NOT NOT 0 = 0");
		Test.check( bed.not(n1) == 1, "NOT NOT 1 = 1");

		// TEST AND:
		Test.check( bed.and(v1,0) == 0, "a & 0 = 0");
		Test.check( bed.and(0,0) == 0, "0 & 0 = 0");
		Test.check( bed.and(1,0) == 0, "1 & 0 = 0");
		Test.check( bed.and(0,1) == 0, "0 & 1 = 0");

		Test.check( bed.and(0,v1) == 0, "0 & a = 0");
		Test.check( bed.and(v1,1) == v1, "a & 1 = a");
		Test.check( bed.and(v1,v1) == v1, "a & a = a");


		// TEST OR:
		Test.check( bed.or(v1,0) == v1, "a OR 0 = a");
		Test.check( bed.or(0,0) == 0, "0 OR 0 = 0");
		Test.check( bed.or(1,0) == 1, "1 OR 0 = 1");
		Test.check( bed.or(0,1) == 1, "0 OR 1 = 1");

		Test.check( bed.or(0,v1) == v1, "0 OR a = a");
		Test.check( bed.or(v1,1) == 1, "a OR 1 = 1");
		Test.check( bed.or(v1,v1) == v1, "a OR a = a");

		// TEST XOR:
		Test.check( bed.xor(0,0) == 0, "0 XOR 0 = 0");
		Test.check( bed.xor(0,1) == 1, "0 XOR 1 = 1");
		Test.check( bed.xor(1,0) == 1, "1 XOR 0 = 1");
		Test.check( bed.xor(1,1) == 0, "1 XOR 1 = 0");

		Test.check( bed.xor(v1,0) == v1, "a XOR 0 = a");
		Test.check( bed.xor(0,v1) == v1, "0 XOR a = a");
		Test.check( bed.xor(v1,1) == nv1, "a XOR 1 = ~a");
		Test.check( bed.xor(1,v1) == nv1, "1 XOR a = ~a");
		Test.check( bed.xor(v1,v1) == 0, "a XOR a = 0");

		// TEST impy:
		Test.check( bed.imply(0,0) == 1, "0 imply 0 = 1");
		Test.check( bed.imply(0,1) == 1, "0 imply 1 = 1");
		Test.check( bed.imply(1,0) == 0, "1 imply 0 = 0");
		Test.check( bed.imply(1,1) == 1, "1 imply 1 = 1");

		Test.check( bed.imply(0,v1) == 1, "0 imply a = 1");
		Test.check( bed.imply(v1,1) == 1, "a imply 1 = 1");
		Test.check( bed.imply(1,v1) == v1, "1 imply a = a");
		Test.check( bed.imply(v1,0) == nv1, "a imply 0 = ~a");


		// UP ONE: test on two-variable BED trees that will become BDDs after one up_ube
		// create BDD answers directly:
		int var1 = MAKE_VAR( bed.getVariable(v1) );
		int var2 = MAKE_VAR( bed.getVariable(v2) );
		int bdd_or12 = bed.ref( bed.mk(var1, v2, 1) );	// var1 at top
		int bdd_or21 = bed.ref( bed.mk(var2, v1, 1) );	// var2 at top
		int bdd_and12 = bed.ref( bed.mk(var1, 0, v2) );	// var1 at top
		int bdd_and21 = bed.ref( bed.mk(var2, 0, v1) );	// var2 at top

		Test.checkEquality(bdd_or12, bed.up_one(v1, or12), "up_one (1)");
		Test.checkEquality(bdd_or21, bed.up_one(v2, or12), "up_one (2)");
		Test.checkEquality(bdd_and12, bed.up_one(v1, and12), "up_one (3)");
		Test.checkEquality(bdd_and21, bed.up_one(v2, and12), "up_one (4)");


		/*
		// UP ALL
		bed.printDot("x", x );
		bed.printDot("xup", bed.up_all(x) );


		int t1 = bed.up_one(v3, x);
		int t2 = bed.up_one(v2, t1);
		int t3 = bed.up_one(v1, t2);
		bed.printDot("t3", t3 );
		bed.printDot("t2", t2 );
		bed.printDot("t1", t1 );
		*/

		Test.end();
	}

	public static void main(String [] args) { internal_test(); }
}
