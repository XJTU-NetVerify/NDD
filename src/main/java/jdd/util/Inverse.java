
package jdd.util;

/** We have no idea where this came from (or even if it is used anywhere */
public class Inverse {
	public static void inverse(Object [] obj) {
		int end = obj.length;
		int half = end / 2;
		end--;
		for(int i = 0; i < half; i++) {
			Object tmp = obj[i];
			obj[i] = obj[end];
			obj[end] = tmp;
			end--;
		}
	}
}
