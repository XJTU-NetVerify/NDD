
package jdd.util.jre;

import jdd.util.*;
import jdd.util.math.*;

import java.util.*;


/**
 * <pre>
 * this files times some operations and checks if JRE implementation
 * faster the straight forward java code
 * </pre>
 */
public class JRETest {

	public static long mem_start;

	public static void mem_start() {
		for(int i = 0; i < 6; i++) System.gc();
		mem_start = JREInfo.usedMemory();
	}

	public static long mem_end() {
		return JREInfo.usedMemory() - mem_start;
	}

	public static int rnd_size(int i) {
		if(i < 0) return 0;
		int x = (1 <<i);
		return x + (int)(Math.random() * x);
	}
	// -------------------------------------------------------
	public static void set1(int []x, int val) {
		int len = x.length;
		for(int i = 0; i < len; i++) x[i] = val;
	}

	public static void set2(int []x, int val) {
		Arrays.fill(x, val);
	}

	public static void set3(int []x, int val) {
		int size = x.length / 4, o = 0;
		for(int i = 0; i < size; i++) {
			x[o] = val;
			x[o+1] = val;
			x[o+2] = val;
			x[o+3] = val;
			o += 4;
		}

		size = x.length & 3;
		while( size-- != 0) x[o++] = val;
	}

	// -------------------------------------------------------
	public static void copy1(int []x, int []y) {
		int len = x.length;
		for(int i = 0; i < len; i++) x[i] = y[i];
	}

	public static void copy2(int []x, int []y) {
		System.arraycopy(y, 0, x, 0, x.length);
	}

	public static void copy3(int []x, int []y) {
		/*
		int len = x.length;
		int offset;

		if( (len & 3) != 0) {
			offset = len & 3;
			while(offset-- != 0) x[offset] = y[offset];
			offset = len & 3;
		} else offset = 0;

		len &= ~3;
		for(int i = 0; i < len;) {
			x[offset+i] = y[offset+i];
			x[offset+i+1] = y[offset+i+1];
			x[offset+i+2] = y[offset+i+2];
			x[offset+i+3] = y[offset+i+3];
			i += 4;
		}
		*/

		int size = x.length / 4, o = 0;
		for(int i = 0; i < size; i++) {
			x[o] = y[o];
			x[o+1] = y[o+1];
			x[o+2] = y[o+2];
			x[o+3] = y[o+3];
			o += 4;
		}

		size = x.length & 3;
		while( size-- != 0) {
			x[o] = y[o];
			o++;
		}

	}

	// -------------------------------------------------------

	private static double [] array_save;
	public static void test_rec(double []d) {
		array_save = d;
		test_rec_rec(d.length-1);
	}
	public static final double test_rec_rec(int pos) {
		if(pos != 0) {
			return array_save[pos] + test_rec_rec(pos-1);
		}
		return array_save[pos] ;
	}

	public static final void test_local(double []d) {
		double sum = 0;
		int len = d.length;
		for(int i = 0; i < len; i++)	sum += d[i];
	}

	public static void test_local2(double []d) {
		double sum = 0;
		int len = d.length;
		for(int i = 0; i < len; i++)	sum += d[i];
	}

	public void test_local3(double []d) {
		double sum = 0;
		int len = d.length;
		for(int i = 0; i < len; i++)	sum += d[i];
	}

	// -------------------------------------------------------
	public static void main(String args[]) {
		JREInfo.show();

		JRETest test = new JRETest();

		int SIZE = 10240;
		int ROUNDS = 10240;
		int [] buffer1 = new int[SIZE];
		int [] buffer2 = new int[SIZE];


		// TEST SET CODE
		long tmp, code, lib;

		for(int i = 0; i < ROUNDS; i++) set1(buffer1, i);	// warmup:

		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) set1(buffer1, i);
		code = System.currentTimeMillis() - tmp;


		for(int i = 0; i < ROUNDS; i++) set2(buffer1, i);	// warmup:
		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) set2(buffer1, i);
		lib = System.currentTimeMillis() - tmp;

		if(code < lib) System.out.println("SET: Java code is faster than Arrays.fill() [" +code + " vs " + lib + "]");
		else					System.out.println("SET: Arrays.fill() is faster than Java code [" +lib + " vs " + code + "]");


		for(int i = 0; i < ROUNDS; i++) set3(buffer1, i);	// warmup:
		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) set3(buffer1, i);
		code = System.currentTimeMillis() - tmp;

		if(code < lib) System.out.println("SET: unrollced java code is faster than Arrays.fill() [" +code + " vs " + lib + "]");
		else					System.out.println("SET: Arrays.fill() is faster than unrolled java code [" +lib + " vs " + code + "]");





		// TEST COPY CODE
		for(int i = 0; i < ROUNDS; i++) copy1(buffer1, buffer2);	// warmup:

		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) copy1(buffer1, buffer2);
		code = System.currentTimeMillis() - tmp;


		for(int i = 0; i < ROUNDS; i++) copy2(buffer1, buffer2);	// warmup:
		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) copy2(buffer1, buffer2);
		lib = System.currentTimeMillis() - tmp;

		if(code < lib) System.out.println("COPY: Java code is faster than System.arraycopy() [" +code + " vs " + lib + "]");
		else					System.out.println("COPY: System.arraycopy() is faster than Java code [" +lib + " vs " + code + "]");

		for(int i = 0; i < ROUNDS; i++) copy3(buffer1, buffer2);	// warmup:
		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) copy3(buffer1, buffer2);
		code = System.currentTimeMillis() - tmp;

		if(code < lib) System.out.println("COPY: unrolled loop is faster than System.arraycopy() [" +code + " vs " + lib + "]");
		else					System.out.println("COPY: System.arraycopy() is faster than unrolled loop [" +lib + " vs " + code + "]");



		// TEST recursive bound check:

		double [] junk = new double[1024 * 4];
		for(int i = 0; i < junk.length; i++) junk[i] = Math.random();


		for(int i = 0; i < ROUNDS; i++) test_rec(junk);	// warmup:

		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) test_rec(junk);
		code = System.currentTimeMillis() - tmp;


		for(int i = 0; i < ROUNDS; i++) test_local(junk);		// warmup:
		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) test_local(junk);
		lib = System.currentTimeMillis() - tmp;

		double ratio = ((int)(10000.0 * code / lib)) / 100.0;
		System.out.println("CALL: recursive array-access speed is " + ratio + "% of the final local code");


		for(int i = 0; i < ROUNDS; i++) test_local2(junk);		// warmup:
		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) test_local2(junk);
		lib = System.currentTimeMillis() - tmp;

		ratio = ((int)(10000.0 * code / lib)) / 100.0;
		System.out.println("CALL: recursive array-access speed is " + ratio + "% of the non-final local code");


		for(int i = 0; i < ROUNDS; i++) test.test_local3(junk);		// warmup:
		tmp = System.currentTimeMillis();
		for(int i = 0; i < ROUNDS; i++) test.test_local3(junk);
		lib = System.currentTimeMillis() - tmp;

		ratio = ((int)(10000.0 * code / lib)) / 100.0;
		System.out.println("CALL: recursive array-access speed is " + ratio + "% of the non-final member local code");



		// memory test:
		mem_start();
		Object obj = new Object();
		long obj_size = mem_end();
		System.out.println("MEMORY: Object size = " + obj_size);


		long int_o = 0, short_o = 0, byte_o = 0;

		for(int i = -1; i < 22; i++) {
			int size = rnd_size(i);
			mem_start(); int [] array = new int[size]; long mem = mem_end();
			int_o = Math.max( int_o,  (mem - 4 * size - obj_size));
			mem_start();short [] array2 = new short[size]; mem = mem_end();
			short_o = Math.max( short_o,  (mem - 2 * size - obj_size));
			mem_start();byte [] array3 = new byte[size];mem = mem_end();
			byte_o = Math.max( byte_o,  (mem - 1 * size - obj_size));
		}


		JDDConsole.out.println("MEMORY: int array memory overhead: " + int_o);
		JDDConsole.out.println("MEMORY: short array memory overhead: " + short_o);
		JDDConsole.out.println("MEMORY: byte array memory overhead: " + byte_o);


		// PRNG speed test:
		int y, MAX = 10000; // we just need some number
		Random rnd = new Random();
		long t1 = System.currentTimeMillis();
		for(int i = 0; i < 1000000; i++) {
			y = FastRandom.mtrand() % MAX;  y = FastRandom.mtrand() % MAX;
			y = FastRandom.mtrand() % MAX; y = FastRandom.mtrand() % MAX;
			y = FastRandom.mtrand() % MAX; y = FastRandom.mtrand() % MAX;
		}
		t1 = System.currentTimeMillis() - t1;


		long t2 = System.currentTimeMillis();
		for(int i = 0; i < 1000000; i++) {
			y = rnd.nextInt(MAX);	y = rnd.nextInt(MAX);	y = rnd.nextInt(MAX);
			y = rnd.nextInt(MAX);	y = rnd.nextInt(MAX);	y = rnd.nextInt(MAX);
		}
		t2 = System.currentTimeMillis() - t2;
		if(t1 < t2)
			JDDConsole.out.println("LPRNG: FastRandom.mtrand() is " + ( 100 * t2 / t1 - 100) + "% faster that Java code");
		else
			JDDConsole.out.println("LPRNG: Java is " + ( 100 * t1 / t2 - 100) + "% faster that FastRandom.mtrand()");


	}
}
