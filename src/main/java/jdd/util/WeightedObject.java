

package jdd.util;


/**
 * An object that is associated by some weight (cost/score/whatever).
 * Primary used in ordered containers such as binary heaps.
 * @see BinaryHeap
 */

public interface WeightedObject {
	/** get the actual object associated by this weight */
	Object object();

	/** get the weight of this object, must be larger than Double.NEGATIVE_INFINITY :) */
	double weight();
}
