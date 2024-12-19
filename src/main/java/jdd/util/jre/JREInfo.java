
package jdd.util.jre;

import jdd.util.*;
import jdd.util.math.*;

import java.util.*;


/**
 * <pre>
 * print some info about the JRE...
 * </pre>
 */
public class JREInfo {
	public static Runtime rt = Runtime.getRuntime();

	public static long usedMemory() { return  rt.totalMemory() - rt.freeMemory(); }
	public static long totalMemory() { return  rt.totalMemory(); }
	public static long freeMemory() { return rt.freeMemory(); }
	public static long maxMemory() { return rt.maxMemory(); }


	/** print out some info about the system and JVM etc. */
	public static void show() {
		Properties prop = System.getProperties();

		JDDConsole.out.print("Using " + prop.getProperty("java.vendor") + " JRE " + prop.getProperty("java.version"));
		String jit = prop.getProperty("java.compiler");
		if(jit != null)  JDDConsole.out.print(", " + jit +  " JIT in");
		JDDConsole.out.println(" "+ prop.getProperty("java.vm.name") );

		JDDConsole.out.println("OS " + prop.getProperty("os.name") + " on " + rt.availableProcessors() + " " + prop.getProperty("os.arch") + " CPU(s) (one used), speed " + speed() + " JDD-MIPS");
		JDDConsole.out.print("Total memory: " ); Digits.printNumber1024(rt.maxMemory());
		JDDConsole.out.print(", memory currently reserved by the JVM: " ); Digits.printNumber1024(usedMemory());
		JDDConsole.out.println( );
		JDDConsole.out.println("Using JDD build " + jdd.Version.build +" on " + (new Date()).toString() + "\n\n");
	}




	/** Compute some sort of speed for this CPU, very non-theoretic. Compare to BogoMIPS on Linux */
	public static double speed() {
		speed_(); // warm up
		return speed_();
	}

	private static double speed_() {
		long start, finish, tmp;



		tmp = System.currentTimeMillis();

		while( (start = System.currentTimeMillis()) == tmp) ;

		int i,j,k;

		long x = 0;
		for(i = 0; i < 1000; i++) {
			for(j = 0; j < i; j++) {
				for(k = 0; k < j; k++) {
					x = x + i + j * k;
				}
			}
		}
		finish = System.currentTimeMillis();
		double duration = (double)(finish - start);

		if(duration == 0) {
			 // MACHINE TO FAST??
			 return -1;
		}

		return ((int)(3e8/duration)) / 100.0;
	}


	public static void main(String [] args) {
		show();
	}
}



