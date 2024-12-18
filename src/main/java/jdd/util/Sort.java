
package jdd.util;

/** Quicksort interface */
public class Sort {
	// --------[ helper functions ] ---------------------------------
	public static boolean isSorted(int [] array, boolean largest_first) {
		return isSorted(array, 0, array.length, largest_first);
	}

	/** is this array sorted? */
	public static boolean isSorted(int [] array, int offset, int len , boolean largest_first) {

		// this is too easy:
		if(len < 2) return true;

		// start with the first one
		int last = array[offset++];
		if(largest_first) {
			// and see if any elements is larger than its previous
			for(int i = 1; i < len; i++) {
				int tmp = array[offset++];
				if(tmp > last) return false;
				else last = tmp;
			}
		} else {
			// or see if any elements is smaller than its previous
			for(int i = 1; i < len; i++) {
				int tmp = array[offset++];
				if(tmp < last) return false;
				else last = tmp;
			}
		}

		return true; // yep, its sorted
	}

	// --------[ bubble sort ] --------------------------------------
	/** bubble sort for list of Sortable:s */
	public static void bubble_sort(Sortable [] objects) {
		for (int i = objects.length; --i>=0; ) {
		boolean flipped = false;
			for (int j = 0; j<i; j++) {
				if( objects[j].greater_than(objects[j+1])) {
					Sortable tmp = objects[j];
					objects[j] = objects[j+1];
					objects[j+1] = tmp;
					flipped = true;
				}
			}
			if (!flipped)
				return;
		}
	}


	/** bubble sort for list of ints */
	public static void bubble_sort(int [] objects, int len) {
		for (int i = len; --i >= 0; ) {
		boolean flipped = false;
			for (int j = 0; j<i; j++) {
				if( objects[j+1] < objects[j]) {
					int tmp = objects[j];
					objects[j] = objects[j+1];
					objects[j+1] = tmp;
					flipped = true;
				}
			}
			if (!flipped)
				return;
		}
	}


	// ----[ quicksort for Sortable ]-----------------------------------------------

	/** helper function to quicksort (quicksort partition) */
	private static final int partition(Sortable [] list, int p, int r) {
		int i = p - 1;
		Sortable tmp, x = list[r];

		for(int j = p; j < r; j++) {
			if(x.greater_than(list[j]) ) {
				i++;
				// SWAP I <-> J
				tmp = list[i];
				list[i] = list[j];
				list[j] = tmp;
			}
		}

		// SWAP I+1 <-> r
		i++;
		tmp = list[i];
		list[i] = list[r];
		list[r] = tmp;
		return i;
	}

	/** worker function for sort (quick sort function) */
	private static void quicksort(Sortable [] list, int p, int r) {
		if(p < r) {
			int q = partition(list, p, r);
			quicksort(list, p, q-1);
			quicksort(list, q+1,r);

		}
	}

	/**
	 * quicksort.
	 * @param v the array to sort
	 * @param largest_first true to sort in descending order, false to sort
	 *        in ascending order
	 */
	public static void sort(Sortable [] v, boolean largest_first) {
		sort(v, v.length, largest_first);
	}

	/**
	 * quicksort.
	 * @param v the array to sort
	 * @param size size of the array to be sorted
	 * @param largest_first true to sort in descending order, false to sort
	 *        in ascending order
	 */
	public static void sort(Sortable [] v, int size, boolean largest_first) {
		quicksort(v, 0, size-1);
		if(largest_first) Array.reverse(v, size);
	}


	// ----[ quicksort for ints ]-----------------------------------------------

	/** helper function to quicksort (quicksort partition) */
	private static final int partition(int [] list, int p, int r) {
		int i = p - 1;
		int tmp, x = list[r];

		for(int j = p; j < r; j++) {
			if(x > list[j] ) {
				i++;
				// SWAP I <-> J
				tmp = list[i];
				list[i] = list[j];
				list[j] = tmp;
			}
		}

		// SWAP I+1 <-> r
		i++;
		tmp = list[i];
		list[i] = list[r];
		list[r] = tmp;
		return i;
	}

	/** worker function for sort (quick sort function) */
	private static void quicksort(int [] list, int p, int r) {
		if(p < r) {
			int q = partition(list, p, r);
			quicksort(list, p, q-1);
			quicksort(list, q+1,r);

		}
	}

	/**
	 * quicksort.
	 * @param v the array to sort
	 * @param largest_first true to sort in descending order, false to sort
	 *        in ascending order
	 */
	public static void sort(int [] v, boolean largest_first) {
		sort(v, v.length, largest_first);
	}

	/**
	 * quicksort.
	 * @param v the array to sort
	 * @param size size of the array to be sorted
	 * @param largest_first true to sort in descending order, false to sort
	 *        in ascending order
	 */
	public static void sort(int [] v, int size, boolean largest_first) {
		if(size < 16) bubble_sort(v,size);
		else		quicksort(v, 0, size-1);
		if(largest_first) Array.reverse(v, size);
	}


	// ----[ quicksort for Object-array types ]---------------------------------------------
	private static Object[] oarray_;
	private static double[] cost_;

	/**
	 * quicksor of arrays of Objects + costs
	 * @param reverse reverse the sort
	 */
	public static void sort(Object[] array, double[] cost, int size, boolean reverse)
	{


		oarray_ = array;
		cost_ = cost;

		oquicksort_(0, size - 1);


		if (reverse)
		{
			Array.reverse(oarray_, size);
			Array.reverse(cost_, size);
		}
	}

	private static void oswap_(int a, int b)
	{
		double tmp1;
		Object tmp2;

		tmp1 = cost_[a];
		cost_[a] = cost_[b];
		cost_[b] = tmp1;
		tmp2 = oarray_[a];
		oarray_[a] = oarray_[b];
		oarray_[b] = tmp2;
	}

	/** helper function to quicksort (quicksort partition) */
	private static int opartition_(int p, int r)
	{
		double x = cost_[r];
		int i = p - 1;

		for (int j = p; j < r; j++)
		{
			if (cost_[j] <= x)
			{
				i++;

				oswap_(i, j);
			}
		}

		i++;

		oswap_(i, r);

		return i;
	}

	/** worker function for sort (quick sort function) */
	private static void oquicksort_(int p, int r)
	{
		if (p < r)
		{
			int q = opartition_(p, r);

			oquicksort_(p, q - 1);
			oquicksort_(q + 1, r);
		}
	}

	// ----------- [ the testbed ] -------------------------------

	public static void internal_test() {
		Test.start("Sort");

		// test the INT interface
		for(int i = 4; i < 102400; i= i * 3 + 1) {
			int [] array = Array.permutation(i);


			sort(array, true);
			Test.check(isSorted(array, true), "int sort (1)");


			Array.disturb(array, array.length);	// the array is sorted in reverse, "too" sorted!
			sort(array, false);
			Test.check(isSorted(array, false), "int sort (2)");

			if(i < 1000) {
				Array.disturb(array, array.length); // not needed for bubble_sort, but anyway...
				bubble_sort(array, array.length);
				Test.check(isSorted(array, false), "int sort (3)");
			}
		}

/*
		// test the SORTABLE interface
		for(int i = 4; i < 102400; i= i * 3 + 1) {
			Sortable [] array = new Sortable[i];
			for(int k = 0; k < array.length; k++)
				array[i] = ???


			sort(array, true);
			Test.check(isSorted(array, true), "int sort (1)");


			Array.disturb(array, array.length);	// the array is sorted in reverse, "too" sorted!
			sort(array, false);
			Test.check(isSorted(array, false), "int sort (2)");

			if(i < 1000) {
				Array.disturb(array, array.length); // not needed for bubble_sort, but anyway...
				bubble_sort(array, array.length);
				Test.check(isSorted(array, false), "int sort (3)");
			}
		}
		*/

	Test.end();
	}

}


