package jdd.des.automata.analysis;

import jdd.des.automata.*;
import jdd.util.*;
import jdd.graph.*;

import java.io.*;
import java.util.*;

/**
 * Utilit class:<br><br>
 *
 * converts an automata to a (set of PCG), save it to a (set of) XML files.<br><br>
 * It makes a simple call to <tt>AutomataAnalyzer.getPCG(Automata a)</tt> from main.<br><br>
 *
 *
 * @see AutomataAnalyzer
 */

public class AutomataToPCG {
	public static void main(String [] args) {
		if(args.length == 0) {
			System.err.println("Usage: java jdd.des.automata.analysis.AutomataToPCG <automata XML files>");
			System.exit(3);
		}

		for(int i = 0; i < args.length; i++) {
			try {
				InputStream is = new FileInputStream (args[i]);
				Automata a = AutomataIO.loadXML(is);
				Graph g = AutomataAnalyzer.getPCG(a);
				Vector v = SimpleAlgorithms.divide(g);

				int count = 1;
				String name = args[i];
				int n = name.lastIndexOf('.');
				if(n != -1) name = name.substring(0,n);

				for (Enumeration e = v.elements() ; e.hasMoreElements() ;) {
					Graph g2 = (Graph) e.nextElement();
					String file = name + "_PCG" + (count == 1 ? "" : "_" + count);
					System.out.println("Writing to " + file + ".xml ...");
					GraphIO.saveXML(g2,file + ".xml");


					// once we are at it, why not just dump the PCG and WTO graphs to??
					g2.showDot(file);
					file = name + "_WTO" + (count == 1 ? "" : "_" + count);
					Topology t = WeakTopologicalOrdering.bourdoncle_PCG(g2);
					t.showDot(file);

					count++;
				}

			} catch(Exception exx) {
				System.err.println("Unable to load " + args[i] + ": " + exx.getMessage() );
			}
		}
	}
}

