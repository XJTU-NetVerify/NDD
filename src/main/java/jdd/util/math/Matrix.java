
package jdd.util.math;

import jdd.util.*;

/**
 * simple matrix implementation used mostly in the graph algorithms
 * (floyd_warshalls shorest-path for example)
 */

public class Matrix {
	private double [] data;
	private int rows, cols, total;

	/**
	 * create a <tt>r</tt> times <tt>c</tt> matrix, fill it with the value <tt>initial</tt>
	 */
	public Matrix(int r, int c, double initial) {
		this(r,c);
		setAll( initial );
	}

	/**
	 * create a <tt>r</tt> times <tt>c</tt> matrix, initial value undefined (zero ?)
	 */
	public Matrix(int r, int c) {
		this.rows = r;
		this.cols = c;
		this.total = r * c;
		this.data = new double[total];
	}

	/**
	 * copy the matrix <tt>m</tt>
	 */
	public Matrix(Matrix m) {
		this.rows = m.rows;
		this.cols = m.cols;
		this.total = m.total;
		this.data = new double[total];
		System.arraycopy(m.data, 0, data, 0, data.length);
	}

	// -------------------------------------------

	public int numOfCols() { return cols; }
	public int numOfRows() { return rows; }

	/** get the raw data (linear representation) of this matrix */
	public double [] getRaw() { return data; }

	/** get element (x,y) */
	public double get(int x, int y) { return data[ x + y * cols]; }

	/** set element (x,y) to v*/
	public void set(int x, int y, double v) { data[ x + y * cols] = v; }

	/** sey all elements to v */
	public void setAll(double v) { for(int i = 0; i < total; i++) data[i] = v; }

	/**
	 * compare to matrices
	 * @return true if the to matrices are identical
	 */
	public boolean equals(Matrix m) {
		if(m.rows != rows || m.cols != cols) return false;
		for(int i = 0; i < total; i++) if(m.data[i] != data[i]) return false;
		return true;
	}

	// -------------------------------------------
	public void add(double d) { 	for(int i = 0; i < total; i++) data[i] += d; }
	public void sub(double d) {		for(int i = 0; i < total; i++) data[i] -= d; }
	public void add(Matrix m) { 	for(int i = 0; i < total; i++) data[i] += m.data[i]; }
	public void sub(Matrix m) {		for(int i = 0; i < total; i++) data[i] -= m.data[i]; }

	// -------------------------------------------

	// XXX: not tested:
	public Matrix multiply(Matrix m2) {
		if(m2.cols != rows || m2.rows != cols) return null;
		Matrix ret = new Matrix(rows, cols);
		for(int i = 0; i < cols; i++) {
			for(int j = 0; j < rows; j++) {
				int offset = i + j * cols;
				ret.data[ offset] = 0;
				for(int k = 0; k < rows; k++)
					ret.data[offset] +=  this.get(i,k) * m2.get(k,j);
			}
		}
		return ret;
	}
	// -------------------------------------------
	/** dump the matrix to console */
	public void show() {
		int offset = 0;
		JDDConsole.out.println();
		for(int y = 0; y < rows; y++) {
			for(int x = 0; x < cols; x++) {
				if(x != 0) JDDConsole.out.print("\t" );
				JDDConsole.out.print("" + data[offset++]);
			}
			JDDConsole.out.println();
		}
	}
}
