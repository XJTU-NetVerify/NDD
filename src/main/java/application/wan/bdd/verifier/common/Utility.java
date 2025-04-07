/*
 * Atomic Predicates Verifier
 * 
 * Copyright (c) 2013 UNIVERSITY OF TEXAS AUSTIN. All rights reserved. Developed
 * by: HONGKUN YANG and SIMON S. LAM http://www.cs.utexas.edu/users/lam/NRL/
 * Copyright (c) 2022 ANTS Lab, Xi'an Jiaotong University
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * with the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimers.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimers in the documentation
 * and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the UNIVERSITY OF TEXAS AUSTIN nor the names of the
 * developers may be used to endorse or promote products derived from this
 * Software without specific prior written permission.
 * 
 * 4. Any report or paper describing results derived from using any part of this
 * Software must cite the following publication of the developers: Hongkun Yang
 * and Simon S. Lam, Real-time Verification of Network Properties using Atomic
 * Predicates, IEEE/ACM Transactions on Networking, April 2016, Volume 24, No.
 * 2, pages 887-900 (first published March 2015, Digital Object Identifier:
 * 10.1109/TNET.2015.2398197).
 * 
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH
 * THE SOFTWARE.
 */

package application.wan.bdd.verifier.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.LinkedList;

public class Utility {

	/*
	 * only used to compute no more than 2^16
	 */
	public static long Power2(int exponent) {
		if (exponent <= 16) {
			switch (exponent) {
				case 0:
					return 1;
				case 1:
					return 2;
				case 2:
					return 4;
				case 3:
					return 8;
				case 4:
					return 16;
				case 5:
					return 32;
				case 6:
					return 64;
				case 7:
					return 128;
				case 8:
					return 256;
				case 9:
					return 512;
				case 10:
					return 1024;
				case 11:
					return 2048;
				case 12:
					return 4096;
				case 13:
					return 8192;
				case 14:
					return 16384;
				case 15:
					return 32768;
				case 16:
					return 65536;
				default:
					System.err.println("exponent is too large!");
					break;
			}
		} else {
			long power = 1;
			for (int i = 0; i < exponent; i++) {
				power = power * 2;
			}
			return power;
		}
		// should not be here
		return 0;
	}

	/*
	 * ex1 < ex2
	 * return 2^ex1 + 2^(ex1+1) + ... + 2^ex2
	 */
	public static long SumPower2(int ex1, int ex2) {
		long sum = 0;
		for (int i = ex1; i <= ex2; i++) {
			sum = sum + Power2(i);
		}
		return sum;
	}

	public static Object LoadObject(String filename) {

		FileInputStream fis = null;
		ObjectInputStream in = null;
		Object obj = null;
		try {
			fis = new FileInputStream(filename);
			in = new ObjectInputStream(fis);
			obj = (Object) in.readObject();
			in.close();
		} catch (IOException ex) {
			ex.printStackTrace();
		} catch (ClassNotFoundException ex) {
			ex.printStackTrace();
		}
		System.out.println(filename + " is loaded.");
		return obj;
	}

	/*
	 * usually bits = 8, or 16
	 */
	public static LinkedList<int[]> DecomposeInterval(Range r, int bits) {
		long l = r.lower;
		long u = r.upper;
		LinkedList<int[]> prefix = new LinkedList<int[]>();

		while (l <= u) {
			long res = OnePrefix(l, u, bits, prefix);
			l = res;
		}

		return prefix;
	}

	/**
	 * the input num is at most bits long
	 * find the number of tailing zeros in the binary representation of num
	 * for example, num = 6 = (110)_2, bits = 3, return 1
	 */
	public static int tailingZeros(long num, int bits) {
		if (num == 0)
			return bits;
		int tmptailing = 0;
		for (int i = 0; i < bits; i++) {
			long tester = Power2(i) - 1;
			long tn = num & tester;
			if (tn == 0) {
				tmptailing = i;
			} else {
				break;
			}

		}
		return tmptailing;
	}

	/**
	 * 
	 * @param IP a string of ip address
	 * @return an array of the binary expression of the ip address. 0th element is
	 *         the minimal bit
	 *         octetbin length is 8
	 */
	public static int[] IPBinRep(String IP) {
		String[] octets = IP.split("\\.");
		// System.out.println(octets.length);
		if (octets.length != BDDACLWrapper.ipBits / 8) {
			System.err.println("Not a valid IP address: " + IP);
			return null;
		}

		int[] octetsnum = new int[octets.length];
		for (int i = 0; i < octets.length; i++) {
			octetsnum[i] = Integer.parseInt(octets[i]);
		}

		int[] ipbin = new int[BDDACLWrapper.ipBits];

		for (int i = 0; i < octets.length; i++) {
			int[] octetbin = CalBinRep(octetsnum[octets.length - i - 1], 8);
			for (int j = 0; j < octetbin.length; j++) {
				ipbin[j + i * octetbin.length] = octetbin[j];
			}
		}
		return ipbin;
	}

	/**
	 * return the binary representation of num
	 * e.g. num = 10, bits = 4, return an array of {0,1,0,1}
	 */
	public static int[] CalBinRep(long num, int bits) {
		if (bits == 0)
			return new int[0];

		int[] binrep = new int[bits];
		long numtemp = num;
		for (int i = bits; i > 0; i--) {
			long abit = numtemp & Power2(i - 1);
			if (abit == 0) {
				binrep[i - 1] = 0;
			} else {
				binrep[i - 1] = 1;
			}
			numtemp = numtemp - abit;
		}
		return binrep;
	}

	/**
	 * 
	 * @param ar an array
	 * @return number of nonzero element in the array
	 */
	public static int NumofNonZeros(int[] ar) {
		int sum = 0;
		for (int i = 0; i < ar.length; i++) {
			if (ar[i] != 0) {
				sum++;
			}
		}
		return sum;
	}

	/**
	 * creat one prefix from [l, u]
	 */
	public static long OnePrefix(long l, long u, int bits, LinkedList<int[]> prefixs) {
		int zeros = tailingZeros(l, bits);
		if (zeros == 0) {
			prefixs.add(CalBinRep(l, bits));
			return l + 1;
		} else {
			while (l + Power2(zeros) > u + 1) {
				zeros--;
			}
			prefixs.add(CalBinRep(l / Power2(zeros), bits - zeros));
			return l + Power2(zeros);
		}
	}

	public static int[] ArrayListToArray(ArrayList<Integer> intarylist) {
		int[] intary = new int[intarylist.size()];
		for (int i = 0; i < intarylist.size(); i++) {
			intary[i] = intarylist.get(i);
		}
		return intary;
	}

	public static long IPStringToLong(String IP) {
		long ip_long = 0;
		long power = 1;

		String[] octets = IP.split("\\.");
		for (int i = octets.length - 1; i >= 0; i--) {
			ip_long += Long.parseLong(octets[i]) * power;
			power *= 256;
		}

		return ip_long;
	}

	public static void main(String[] args) {
		/**
		 * 
		 */
		/*
		 * System.out.println("2^0 = " + Power2(0));
		 * System.out.println("2^3 = " + Power2(3));
		 * System.out.println("2^0+2^1+2^2 = "+ SumPower2(0,2));
		 * System.out.println(SumPower2(6,5));
		 */

		/**
		 * 
		 */
		/*
		 * System.out.println(tailingZeros(23,5));
		 * 
		 * int[] binrep = CalBinRep(6, 6);
		 * for(int i = 0; i < binrep.length; i ++)
		 * {
		 * System.out.print(binrep[i] + " ");
		 * }
		 * System.out.println();
		 * LinkedList<int []> prefix = new LinkedList<int[]>();
		 * long res = OnePrefix(18, 18, 5, prefix);
		 * System.out.println(res);
		 * for(int i = 0; i < prefix.get(0).length; i ++)
		 * {
		 * System.out.print(prefix.get(0)[i] + " ");
		 * }
		 * System.out.println();
		 * 
		 * prefix.clear();
		 * prefix = DecomposeInterval(new Range(152,221), 8);
		 * for(int i = 0; i < prefix.size(); i ++)
		 * {
		 * int[] aprefix = prefix.get(i);
		 * for (int j = 0; j < aprefix.length; j ++)
		 * {
		 * System.out.print(aprefix[j] + " ");
		 * }
		 * System.out.println();
		 * }
		 */

		/**
		 * test IPBinRep
		 */
		int[] bin = IPBinRep("0.0.1.255");
		for (int i = 0; i < bin.length; i++) {
			System.out.print(bin[i] + " ");
		}
		System.out.println();
		System.out.println(NumofNonZeros(bin));

	}
}
