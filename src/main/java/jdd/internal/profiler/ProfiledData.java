

package jdd.internal.profiler;

import jdd.util.*;

/**
 * Data gathered by profiling a single problem.
 *
 */

/* package */ class ProfiledData {

	public int count; /** number of profiled items. the array soften hold extra spaces */
	public String name; 				/** name of the problem */
	public int [] times;				/** running times. low is better */
	public long [] memories;		/** memory usage. low is better */
	public int [] ids;				/** ID of the profiling */

	public int best_time; /** lowest running time*/
	public int best_memory; /** lowest memory usage */


	// ------------------------------------------------------
	/** empty constructor, everything is initialized by the caller */
	public ProfiledData() { }

	/** create an empty data set with place for <tt>n</tt> extra fields */
	public ProfiledData(String name, int n){
		this.name = name;
		this.times = new int[n];
		this.memories = new long[n];
		this.ids = new int[n];

		this.best_time = this.best_memory = -1; // invalid
		this.count = 0; //still empty
	}

	// ------------------------------------------------------------

	/** is this number better than any other numbers (if any) in the set ? */
	public boolean hasBetterTime(int time) {
		if(count == 0 || best_time < 0) return true;
		return time < times[best_time];
	}

	/** is this number better than any other numbers (if any) in the set ? */
	public boolean hasBetterMemory(long mem) {
		if(count == 0 || best_memory < 0) return true;
		return mem < memories[best_memory];
	}

	/** return the number of data rows */
	public int getSize() { return count; }
	// ------------------------------------------------------------

	/** insert a new raw */
	public void insert(int id, int time, long mem) {

		int index = getIndexFor(id);
		times[index] = time;
		memories[index] = mem;

		if(count == 1 || best_memory == -1 || ( best_memory != -1 && memories[best_memory] > mem))
			best_memory = index;

		if(count == 1 || best_time == -1 || ( best_time != -1 && times[best_time] > time))
			best_time = index;
	}

	private int getIndexFor(int id) {
		int x = findIndexForId(id);
		if(x == -1) {
			// create a new one:
			// XXX: must resize array if it wont fit!
			ids[count] = id;
			return count++;
		} else {
			return x;
		}
	}

	/** find the index (in times and memories) for this <tt>id</tt>. returns -1 if not found */
	public int findIndexForId(int id) {
		for(int i = 0; i < count; i++)
		if(ids[i] == id) return i;
		return -1;
	}



	// ------------------------------------------------------------

	/** write the data to stdout for debugging */
	public void dump() {
		JDDConsole.out.println("\n\n---------------------------------------------------------");
		JDDConsole.out.println("  dataset " + name);
		if(count == 0) {
			JDDConsole.out.println("  (the dataset is empty)");
		} else {
			for(int i = 0; i < count; i++) {
				JDDConsole.out.print("    " + ids[i] + "\t" + times[i] + " [ms]\t" + memories[i] + " bytes\t");
				if(i == best_time) JDDConsole.out.print("(best time) ");
				if(i == best_memory) JDDConsole.out.print("(best memory) ");
				JDDConsole.out.println();
			}
		}

	}
}
