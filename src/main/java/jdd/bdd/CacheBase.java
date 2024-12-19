

package jdd.bdd;

import java.io.Serializable;

/**
 * This is the base class for all cache classes.
 * used as a common base for debugging and profiling.
 */

public abstract class CacheBase implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -4172544130695472002L;
	private String cache_name;

	protected CacheBase(String name) {
		this.cache_name = name;
	}

	// ---------------------------------------------

	/** name of the cache. supplied by its owner */
	public String getName() { return cache_name; }

	// -------------------------------------------

	/** the load factor, 0-100% */
	public abstract double computeLoadFactor();

	/** the hit rate, 0-100% */
	public abstract double computeHitRate();

	/** number of times the cache was accessed */
	public abstract long getAccessCount();

	/** size of the cache */
	public abstract int getCacheSize();

	/** number of times cache was cleared */
	public abstract int getNumberOfClears() ;

	/** number of times cache was partially */
	public abstract int getNumberOfPartialClears() ;

	/** number of times cache was growed  */
	public abstract int getNumberOfGrows();

}
