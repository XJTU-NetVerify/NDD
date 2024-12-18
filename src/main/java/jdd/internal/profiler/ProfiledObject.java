


package jdd.internal.profiler;

/**
 * a profiled object is an object that can be run and saves information about
 * memory usage and execution time.
 *
 */

public interface ProfiledObject {

	public void run(); 							/** run the profiler once, before calling this all other values are invalid */
	public String getProfileName(); /** returns the name of the unique profiled object */
	public int getRunningTime();		/** returns the execution time in [ms] */
	public long getMemoryUsage();		/** returns the memory usage in bytes */
}
