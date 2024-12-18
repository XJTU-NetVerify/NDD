
package jdd.util.pool;

/**
 * ObjectCrator is the factory class that creates new objects to be inserted into the
 * object pool.
 *
 * @see ObjectPool
 */
public interface ObjectCreator {

	/** create a new objects, what ever that is */
	Object createNew();
}
