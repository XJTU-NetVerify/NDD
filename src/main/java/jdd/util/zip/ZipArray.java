
package jdd.util.zip;

import jdd.util.*;

import java.util.zip.*;
import java.io.*;

/**
 * compressed representation of an array (compressed with gzip).
 *
 * <p> This class is used to temporary save some bytes.
 * It is like swapping to, but here we keep it in the memory in a compressed format.
 *
 */

public class ZipArray {
	private static byte [] buff = new byte[4];

	/**
	 * compress an array of integers.
	 * the result can be read from the returned MemoryInputStream
	 */
	public static MemoryInputStream compressArray(int [] array) {
		return compressArray(array, array.length);
	}

	/**
	 * compress an array of integers, with the given length.
	 * the result can be read from the returned MemoryInputStream
	 */
	public static MemoryInputStream compressArray(int [] array, int len) {
		MemoryOutputStream mos = new MemoryOutputStream();

		try {
			GZIPOutputStream zos = new GZIPOutputStream(mos);
			for(int i = 0; i < len; i++) {
				int x = array[i];
				buff[0] = (byte)(x & 0xFF);
				buff[1] = (byte)(x >>> 8);
				buff[2] = (byte)(x >>> 16);
				buff[3] = (byte)(x >>> 24);
				zos.write(buff,0,4);
			}
			zos.close();

			MemoryInputStream mis = mos.convert();

			if(Options.verbose){
				int used =  mis.available();
				int need = len * 4;
				JDDConsole.out.println("Array size after compression: " + ((100 * used) / need) + "%");
			}

			return mis;
		} catch(IOException exx) {
			exx.printStackTrace();	// should not happen unless memory out??
			return null;
		}
	}


	/**
	 * decompress an array from a MemoryInputStream.
	 * if the file ends prematurely, the rest of the array is filled with zeros.
 	 *
 	 * @return -1 in case of an error, otherwise the number of ints read.
	 */
	public static int decompressArray(MemoryInputStream is, int [] array) {
		return decompressArray(is, array, array.length);
	}

	/**
	 * decompress <tt>len</tt> members of an array from a MemoryInputStream.
	 * if the file ends prematurely, the rest of the array is filled with zeros.
 	 *
 	 * @return -1 in case of an error, otherwise the number of ints read.
	 */
	public static int decompressArray(MemoryInputStream is, int [] array, int len) {
		int c = 0;

		try {
			is.reset();
			GZIPInputStream zis = new GZIPInputStream(is);

			ArrayOutputStream aos = new ArrayOutputStream(array);

			int ch;
			while( (ch = zis.read()) != -1) {
				aos.write(ch);
				c = aos.size();
				if(c == len) return c;
			}
			aos.free();
		} catch(IOException exx) {
			exx.printStackTrace();
			return -1;
		}
		// fill the rest wihs zeros
		for(int i = c; i <len; i++) array[i] = 0;
		return c;
	}

	// -------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("ZipArray");


		int max = 10000;
		int [] a1 = new int[max];
		int [] a2 = new int[max];
		for(int i = 0; i < a1.length; i++) a1[i] = (int)(Math.random() * 1234);

		MemoryInputStream is = ZipArray.compressArray(a1);


		ZipArray.decompressArray(is, a2);

		boolean failed = false;
		int i = 0;
		for(; !failed && (i < a1.length); i++)
			if(a1[i] != a2[i]) failed = true;

		Test.check(!failed, "a1[i] == a2[i], i = " + i);



		Test.end();
	}
}

