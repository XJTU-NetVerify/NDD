

package jdd.internal.profiler;

// import jdd.internal.profiler.stubs.*;
// import jdd.util.*;
import java.util.*;

/**
 * A set of profiled objects are gathered in a collection as a benchmark set
 *
 */
public class BenchmarkSet {
	private Collection objects; /** objects to be profiled */
	private String name; /** name of this set of benchmarks */

	/**
	 * create an empty benchmark set with the given name
	 */
	public BenchmarkSet(String name) {
		this.name = name;
		this.objects = new LinkedList();
	}

	// ------------------------------------------

	public void add(ProfiledObject obj) {
		objects.add(obj);
	}

	public Iterator iterator() {
		return objects.iterator();
	}

	public String toString() {
		return name;
	}

}