

package jdd.util;


/**
 * Simple and fast implementation of binary heaps for by weight ordered objects
 *
 * @see WeightedObject
 */

public class BinaryHeap {

	private static BottomWeightedObject bottom = new BottomWeightedObject();

	private int max, curr;
	private boolean order_ok;
	private WeightedObject [] array;

	public BinaryHeap(/* WeightedObject bottom */ /* double min_value */ ) {
		this.max = 32; // some default value
		this.curr = 0;
		this.order_ok = true;

		array = new WeightedObject[ this.max +1];

		array[0] = bottom;
	}


	public void insert(WeightedObject wo) {

		if(!order_ok) {
			toss(wo);
		} else {
			check_size();

			int hole = ++curr;
			for(; wo.weight() < array[hole >> 1].weight() ; hole >>= 1)
				array[hole] = array[hole >> 1];
			array[hole] = wo;
		}
	}


	public WeightedObject min() {
		if(!order_ok) fix_heap();
		return array[1];
	}

	public WeightedObject deleteMin() {
		WeightedObject x = min();

		// GC-unfriendly:
		// array[1] = array[curr--];

		// GC-friendly:
		array[1] = array[curr]; array[curr--] = null;


		percolate_down(1);
		return x;
	}

	public boolean empty() {
		return curr == 0;
	}

	public void clear() {
		curr = 0;
	}

	/** number of elements in the heap now */
	public int size() {
		return curr;
	}

	/** number of elements we have reserved space for */
	public int capacity() {
		return max;
	}

	// --[ internal stuff ]-----------------------------------------------
	private void fix_heap() {
		for(int i = curr / 2; i > 0; i++)
			percolate_down(i);
		order_ok = true;
	}

	private void percolate_down(int hole) {
		int child;
		WeightedObject tmp = array[hole];

		for( ; hole * 2 <= curr; hole = child) {
			child = hole << 1;
			if(child != curr && array[ child + 1].weight() < array[child].weight() )
				child++;

			if(array[ child ].weight() < tmp.weight())
				array[hole] = array[child];
			else
				break;
		}
		array[ hole ] = tmp;
	}

	private void toss(WeightedObject wo) {
		check_size();
		array[++curr] = wo;

		if(wo.weight() < array[curr >> 1].weight() )
			order_ok = false;
	}

	private void check_size() {
		if( curr == max) {
			int new_max = max * 2 + 1;
			WeightedObject [] tmp = new WeightedObject[new_max + 1];
			for(int i = 0; i <= curr; i++)
				tmp[i] = array[i];

			max = new_max;
			array = tmp;
		}
	}


	// --[ testbench ]-----------------------------------------------


/*
	public static void main(String [] args) {
		BinaryHeap	bh = new BinaryHeap();

		for(int i = 0; i < 10; i++) {
			double x = 100 * Math.random();
			TestWO n = new TestWO("wo"+i, x);
			bh.insert(n);
			Options.out.println( "" + n.object()  + " --> " + x);
		}

		while(!bh.empty()) {
			String x= (String) ((TestWO)bh.deleteMin()).object();
			Options.out.println(x);
		}
	}
*/
}



/*
// for the test bench
class TestWO implements WeightedObject {
	String s;
	double w;
	public TestWO(String str, double weight) {
		this.s = str;
		this.w = weight;
	}
	public Object object() { return s; }
	public double weight() { return w; }
}
*/


