
package jdd.des.petrinets;

import jdd.util.*;

import java.util.*;


/* package */ class PNEnumeration {
	protected int [] vector;
	protected int size, last;

	public PNEnumeration() {
		vector = new int[16];
		size = last = 0;
	}

	/* package */ void init(int index, int jump, int max, BitSet b) {
		int cap = b.size();
		if(max > vector.length) vector = new int[max];
		size = 0;

		for(int i = 0; i < max; i++) {
			int j = index + i * jump;
			if(b.get(j)) vector[size++] = i;
		}
		last = size;
	}

	public int getSize() {  return size; }
	public int [] getVector() { return vector; }
	public boolean empty() { return last == 0; }
	public void reset() { last = size;	}
	public int next() { return (last == 0) ? -1 : vector[--last]; }

	public void show() {
		for(int i = 0; i < size; i++)
			JDDConsole.out.print(" " + vector[i]);
		JDDConsole.out.println();
	}

}

