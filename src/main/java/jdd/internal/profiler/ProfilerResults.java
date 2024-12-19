

package jdd.internal.profiler;


import jdd.util.*;
import java.util.*;

/**
 * This is the result of a series of profiling on a single problem.
 * This class is used as an stub to the ProfilerData, with better data hiding.
 */

public class ProfilerResults {
	private String name;
	private long [] memories;
	private int []ids, times;
	private int count, current, min_times, max_times;
	private long min_mems, max_mems;
	private ProfilerDB db;

	/**
	 * get the profiling results for "name" from this database.
	 */
	public ProfilerResults(ProfilerDB db, String name) {
		this.name = name;
		this.db = db;

		// XXX: if "name" is non existing, an empty data will be created
		ProfiledData data = db.getDataset(name);

		this.count = data.getSize();
		this.memories = data.memories;
		this.ids = data.ids;
		this.times = data.times;

		this.current = -1;

		// calc min/max:
		min_times = Integer.MAX_VALUE;
		max_times = Integer.MIN_VALUE;
		min_mems = Long.MAX_VALUE;
		max_mems = Long.MIN_VALUE;

		for(int i = 0; i < count; i++) {
			min_mems = Math.min(min_mems, memories[i]);
			max_mems = Math.max(max_mems, memories[i]);
			min_times = Math.min(min_times, times[i]);
			max_times = Math.max(max_times, times[i]);
		}
	}

	// --------------------------------------------

	public String getName() { return name; }
	public void setCurrent(int i) { current = i; }
	public boolean isCurrent(int i) {
		if( i < 0 || i>= count) return false;
		return (current == ids[i]);
	}
	public int getSize() { return count; }

	public int getMinTime() { return min_times; }
	public int getMaxTime() { return max_times; }
	public long getMinMemory() { return min_mems; }
	public long getMaxMemory() { return max_mems; }

	public int getTime(int i) {
		if( i < 0 || i>= count) return 0;
		return times[i];
	}

	public long getMemory(int i) {
		if( i < 0 || i>= count) return 0;
		return memories[i];
	}

	public String getDesc(int i) {
		if( i < 0 || i>= count) return null;

		ProfilingInfo pi = db.findInfoById(ids[i]);
		if(pi != null) return pi.desc;
		return "(not found)";
	}

}
