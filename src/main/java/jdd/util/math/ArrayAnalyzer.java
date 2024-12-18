package jdd.util.math;

import jdd.util.*;

/**
 * Analyzes an array of numbers
 *
 */

public class ArrayAnalyzer {
	public double min, max, sum, average;
	public double variance, std_deveiation;
	public int size, index_min, index_max;


	public void analyze(double [] array, int size) {
		if(size > array.length) size = array.length;
		if(size <= 0) return;

		this.size = size;
		this.sum = 0;
		this.index_min = 0;
		this.index_max = 0;
		this.max = Double.NEGATIVE_INFINITY;
		this.min = Double.POSITIVE_INFINITY;
		variance = 0;

		for(int i = 0; i < size; i++) {
			if(min > array[i]) min = array[index_min = i];
			if(max < array[i]) max = array[index_max = i];
			sum += array[i];
			variance += array[i] * array[i];
		}

		average = sum / size;
		variance /= size;
		std_deveiation = Math.sqrt(  variance - average * average );

	}
}
