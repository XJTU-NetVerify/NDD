
package jdd.des.strings;


import jdd.zdd.*;
import jdd.util.*;


/**
 * This class is used to represent [finite] languages of finite alphabets.
 * <p>If we ever get this to work, we will use it for language based verification.
 *
 * <p>It is based on our own research and to some degree, Minatos regex paper. <br>
 * <p><b>NOTE:</b> under construction (well, kind of). DO NOT USE!
 * @see jdd.des.strings.AutomataSublanguage
 */

// TODO:
// projection and inverse-projection.
// why isnt concat canonical???
// XXX: todo: we need a cut() operations to shorten string that are two long

public class ZDDStrings extends ZDD {

	private int num_events;
	private int concat_bottom, concat_offset; // internal for concat()
	private boolean [] sigma1, sigma2; // used by compose

	/**
	 * initialize for <tt>num_events</tt> events.
	 * @see #setBound
	 */
	ZDDStrings(int num_events) {
		super(10000,1000);
		this.num_events = num_events;
		setNodeNames( new SimpleAlphabetNodeNames(num_events) );
	}

	/** return the empty string epsilon */
	public int getEpsilon() { return 1; }

	/** return the null string */
	public int getEmptySet() { return 0; }

	/** return the ZDD variable for that event, notethat 0 <= index < num_events */
	public int getEvent(int index) {
		Test.check(index >= 0 && index < num_events);
		return change(1, index);
	}

	/** sets string bound<br>makes sure enough variables are allocated (affects the size of working_stack ! */
	public void setBound(int b) {
		while( b * num_events > num_vars) createVar();
	}

	/** return the highest number of cubes uses in this tree (a cube is num_vars long) */
	public int top(int zdd) {
		if(zdd < 2) return 0;
		return 1 + getVar(zdd) / num_events;
	}


	// --------------------------------------------------------


	/**
	 * concatenation of two strings, or two langaues.
	 *
	 */
	public int concat(int str1, int str2) {
		if(str1 == 0 || str2 == 0) return 0; 	// XXX: emptyset . s  = emptyset ???
		if(str1 == 1) return str2;		// \epsilon . s = s
		if(str2 == 1) return str1;		// s . \epsilon = s

		concat_bottom = str2;
		concat_offset = (1+(getVar(str2) / num_events)) * num_events;// XXX: something fishy here!!
		return concat_rec( str1);
	}
	private int concat_rec(int s) {
		if(s < 2) {
			return (s == 0) ? 0 : concat_bottom;
		}

		int low = work_stack[work_stack_tos++] = concat_rec(getLow(s));
		int high = work_stack[work_stack_tos++] = concat_rec(getHigh(s));
		int ret = mk( getVar(s) + concat_offset, low, high);
		work_stack_tos-=2;
		return ret;

	}

	// --------------------------------------------------------
	/**
	 * reduce a language representation to its optimal form<br>
	 * Question: is this form canonical ???<br>
	 *
	 * XXX: not sure if it always works as intended, what about shared nodes??
	 * @see #expand
	 */
	public int reduce(int zdd) {
		if(zdd < 2) return zdd;

		int low  = work_stack[work_stack_tos++] = reduce(getLow(zdd));
		int high = work_stack[work_stack_tos++] = reduce(getHigh(zdd));

		int top = Math.max( getVar(low), getVar(high) );
		int var = getVar(zdd);
		while(top + num_events < var)  var -= num_events;

		int ret = mk( var, low, high);
		work_stack_tos-=2;
		return ret;
	}


	// --------------------------------------------------------
	/**
	 * expend a language representation to its maximal.<br>
	 * that is, exactly one event per sub-cube (sub-cube = block of num_event variables)<br>
	 * XXX: not sure if it always works as intended :(
	 * @see #reduce
	 */
	public int expand(int zdd) {
		if(zdd < 2) return zdd;


		int low  = work_stack[work_stack_tos++] = expand(getLow(zdd));
		int high = work_stack[work_stack_tos++] = expand(getHigh(zdd));

		int top = Math.max( getVar(low), getVar(high));
		int level = top == -1 ? top : top / num_events;
		int var = getVar(zdd);

		while(level >=  ( var / num_events) )  var += num_events;

		int ret = mk( var, low, high);
		work_stack_tos-=2;
		return ret;
	}

	// --------------------------------------------------------

	/**
	 * given a language L, compate its prefix-closure \overbar{L}<br>
	 * XXX: not working yet
	 */
	public int close(int zdd) {
		if(zdd < 2) return zdd;

		int high = work_stack[work_stack_tos++] = close(getHigh(zdd));
		// int low = work_stack[work_stack_tos++]  = (getLow(zdd) == 0) ? getHigh(zdd) : close(getLow(zdd));
		int low = work_stack[work_stack_tos++]  = (getLow(zdd) == 0) ? getHigh(zdd) : close(getLow(zdd));
		int ret = mk( getVar(zdd), low, high);
		work_stack_tos-=2;
		return ret;
	}

	// --------------------------------------------------------

	/**
	 * compute the intersection of the inverse-projection of two languages p and q<br>
	 * XXX: not working yet
	 */
	public final int compose(int p, int q, boolean []sigma1, boolean []sigma2) {
		this.sigma1 = sigma1;
		this.sigma2 = sigma2;
		return compose_rec(p,q);
	}


	// XXX: compose_rec not working yet
	private final int compose_rec(int p, int q) {
		if(p == 0 || q == 0) return 0;
		if(q == p) return p;
		if(p == 1) return follow_low(q);
		if(q == 1) return follow_low(p);


		int pevent = getVar(p) % num_events;
		int qevent = getVar(q) % num_events;

		if(!sigma1[qevent]  && !sigma2[pevent] ) {
			int tmp1 = work_stack[work_stack_tos++] = concat(p, change(1,qevent));
			int tmp2 = work_stack[work_stack_tos++] = union(p, tmp1);
			work_stack_tos --;
			tmp1 = work_stack[work_stack_tos++] = concat(q, change(1,pevent));
			int tmp3 = work_stack[work_stack_tos++] = union(q, tmp1);
			work_stack_tos -= 3;
			work_stack[work_stack_tos++]  = tmp3;
			work_stack[work_stack_tos++]  = tmp2;

			JDDConsole.out.print("*q was: "); printSet(q);
			JDDConsole.out.print("*p was: "); printSet(p);
			JDDConsole.out.print("*q is : "); printSet(tmp2);
			JDDConsole.out.print("*p is : "); printSet(tmp3);

			tmp1 = compose_rec(tmp2, tmp3);
			work_stack_tos -= 2;

			JDDConsole.out.print("* ret : "); printSet(tmp1);
			return tmp1;
		}

		JDDConsole.out.print("q was: "); printSet(q);
		JDDConsole.out.print("p was: "); printSet(p);
		p = work_stack[work_stack_tos++] = (sigma1[qevent] ? p : concat(p, change(1,qevent)));
		q = work_stack[work_stack_tos++] = (sigma2[pevent] ? q : concat(q, change(1,pevent)));

		JDDConsole.out.print("q is: "); printSet(q);
		JDDConsole.out.print("p is: "); printSet(p);
		JDDConsole.out.println("\n");

		int l = 0;
		if(getVar(p) > getVar(q)) 		l = compose_rec( getLow(p), q);
		else if(getVar(p) < getVar(q))	l =  compose_rec( p, getLow(q));
		else {
			l = work_stack[work_stack_tos++] = compose_rec( getLow(p), getLow(q));
			int h = work_stack[work_stack_tos++] = compose_rec(getHigh(p), getHigh(q));
			l = mk( getVar(p), l, h);
			work_stack_tos -= 2;
		}
		work_stack_tos -= 2;
		return l;
	}

	// --------------------------------------------------------
	public static void main(String [] string) {

		/*
		ZDDStrings zdd = new ZDDStrings(3);
		zdd.setBound(3);

		int a = zdd.getEvent(0);
		int b = zdd.getEvent(1);
		int c = zdd.getEvent(2);


		int ab = zdd.concat(a,b);
		int ac = zdd.concat(a,c);
		boolean [] sigma1 =  new boolean[3];	sigma1[0] = sigma1[1] = true;
		boolean [] sigma2 =  new boolean[3];	sigma2[0] = sigma2[2] = true;

		int comp = zdd.compose(ab,ac, sigma1, sigma2);

		zdd.printSet(comp);
		zdd.printSet(ab);
		zdd.printSet(ac);
		*/



		// zdd.printSet( zdd.close( zdd.cube("1110") ) );
		// zdd.printDot("dcx", dcx);
		// zdd.printDot("dcxC", dcxC);

		// int dumb = zdd.cube("1100");
		// int dumb2 = zdd.union( zdd.union( dumb,  zdd.cube("0110")), zdd.cube("0010"));
		// zdd.printDot("dumb", dumb);
		// zdd.printDot("dumb2", dumb2);

/*
		int z1 = zdd.concat( zdd.union(1,c), zdd.union(1,a) );
		int z2 = zdd.concat( zdd.union(1,c), zdd.union(1,d) );
		zdd.printSet( z1 );
		zdd.printSet( z2 );
		*/


		// zdd.printSet( zdd.reduce(z1) );
		// zdd.printSet( zdd.reduce(z2) );




	}
}
