

package jdd.internal.profiler;

import jdd.util.*;

/**
 * The information about a profiling in the database
 *
 */

/* package */ class ProfilingInfo {
	public int id; /** the ID for this particular profiling time */
	public String desc;	/** the description supplied by the user */
	public String date;	/** the date of profiling */

	public void dump(){
		JDDConsole.out.println("" + id + ", " + date + ":");
		JDDConsole.out.println(desc);
		JDDConsole.out.println();
	}
}
