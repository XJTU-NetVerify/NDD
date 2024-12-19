
package jdd.util.pool;

import jdd.util.*;

/**
 * ObjectPool is a simple data structure that saves objects in order to avoid to much
 * object creation by resuing the objectsit has in its pool
 *
 * <br><br>
 *
 * <b>Beware</b> that the java garbage collector often does better job than this :(
 *
 * @see ObjectCreator
 */

public class ObjectPool {
	private static final int DEFAULT_SIZE = 8;
	private static final int MAX_SIZE = 1024 * 4;

	private Object [] stack;
	private int curr;
	private ObjectCreator creator;
	private long ins, outs, losts;

	/** fill the pool up to the point */

	private void fillup() {
		while(curr < DEFAULT_SIZE) stack[curr++]= creator.createNew();
	}
	private void resize() {
		int new_size = Math.min(stack.length * 2 +1, MAX_SIZE);
		Object []o2 = new Object[new_size];
		for(int i = 0; i < curr; i++) o2[i] = stack[i];
		stack = o2;
	}


	// -------------------------------------------

	/** create a pool with the given "creator" factory */
	public ObjectPool(ObjectCreator creator) {
		this.creator = creator;

		this.stack = new Object[DEFAULT_SIZE];
		this.curr = 0;
		this.ins = 0;
		this.outs = 0;
		this.losts = 0;

		fillup();

	}

	// ---------------------------------------------------

	/** empty the pool. this is good when you want to garbage collect the items it holds */
	public void clear() {
		for(int i = 0; i < stack.length; i++) stack[i] = null;
		curr = 0;
	}

	/** get an object from the pool */
	public Object get() {
		outs++;
		if(curr == 0) fillup();
		return stack[--curr];
	}

	/** insert an object back to the pool */
	public void put(Object o) {
		if(curr == stack.length) {
			if(curr == MAX_SIZE) {
				losts++;
				return; // ingore this object
			} else resize();
		}
		stack[curr++] = o;
		ins++;
	}

	// ---------------------------------------------------
	/** get the capacity of the pool (max number of available at once)*/
	public int getCapacity() { return stack.length; }

	/** get the size of the pool (number of objects available)*/
	public int getSize() { return curr; }

	/** print some statistics */
	public void showStats() {
		JDDConsole.out.println("Object pool statistics: capacity " + getCapacity() + ", size " + getSize() );
		JDDConsole.out.println("   Recorded " + ins + " insert, " + outs + " gets, " +
			losts + " objects lost.");
	}
}
