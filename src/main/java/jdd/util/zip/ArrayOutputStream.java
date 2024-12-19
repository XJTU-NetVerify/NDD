
package jdd.util.zip;

import jdd.util.*;
import java.io.*;

/**
 * An array output-stream is a single construct where bytes are written to
 * fill an integer-array (four bytes for each integer).it is used by the
 * ZipArray utility class.
 * @see ZipArray
 */

public class ArrayOutputStream {
	private static final int [] shift_amount = { 0,8,16,24 };

	private int [] output;
	private int curr_int, curr_byte, max;

	public ArrayOutputStream(int [] output) {
		this.output    = output;
		this.max       = output.length;
		this.curr_int  = 0;
		this.curr_byte = 0;

		Array.set(output, 0);
	}

	public void free() { output = null; } // HELP GC
	public int size() { return curr_int; }

	public void write(int b) throws IOException{
		if(curr_int >= max) throw new IOException("Array overflow");

		output[curr_int] |= ((b & 0xFF) << shift_amount[curr_byte]);
		curr_byte++;
		if(curr_byte == 4) {
			curr_byte = 0;
			curr_int++;
		}
	}
}

