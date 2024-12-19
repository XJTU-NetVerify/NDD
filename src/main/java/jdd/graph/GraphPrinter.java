

package jdd.graph;

import jdd.util.*;

import java.util.*;
import java.io.*;

/**
 * all functions for showing/printing graphs are gathered here.
 *
 */
public class GraphPrinter {

	/**
	 * show the status of a node in a graph, as text.
	 */
	public static void show(Node n) {
		JDDConsole.out.println("\nNode " + n.getLabel());

		Edge ed = n.firstIn;
		if(ed != null) {
			JDDConsole.out.print("Incoming arcs: ");
			while(ed != null) {	JDDConsole.out.print(" " + ed.n1.getLabel());	ed = ed.prev;	}
			JDDConsole.out.println();
		}

		ed = n.firstOut;
		if(ed != null) {
			JDDConsole.out.print("Outgoing arcs: ");
			while(ed != null) {	JDDConsole.out.print(" " + ed.n2.getLabel());	ed = ed.next;	}
			JDDConsole.out.println();
		}
	}

	/**
	 * show a graph, as text.
	 */
	public static void show(Graph g) {
		if(g.isDirected() ) {
			JDDConsole.out.println();
			Vector v = g.getNodes();
			for (Enumeration e = v.elements() ; e.hasMoreElements() ;) {
				Node n = (Node) e.nextElement();
				JDDConsole.out.print("" + n.getLabel() + ":");

				Edge ed = n.firstOut;
				while(ed != null) {
					JDDConsole.out.print(" " + ed.n2.getLabel());
					if( (ed.flags & Edge.FLAGS_WEIGTHED) != 0) JDDConsole.out.print("(" + ed.weight + ")");
					ed = ed.next;
				}
				JDDConsole.out.println();
			}
		} else {
			JDDConsole.out.print("G = < (");

			boolean first = true;
			Vector v = g.getNodes();
			for (Enumeration e = v.elements() ; e.hasMoreElements() ;) {
				Node n = (Node) e.nextElement();
				if(first) first = false; else JDDConsole.out.print(", ");
				JDDConsole.out.print(""+n.getLabel());

			}

			JDDConsole.out.print(") ,  (");

			v = g.getEdges();
			first = true;
			for (Enumeration e = v.elements() ; e.hasMoreElements() ;) {
				Edge n = (Edge) e.nextElement();
				if(first) first = false; else JDDConsole.out.print(", ");
				JDDConsole.out.print("[" + n.n1.getLabel() + "," + n.n2.getLabel() + "]");
			}
			JDDConsole.out.println(") >");
		}
	}

	// --------------------------------------------------------------------
	/**
	 * create a picture for this graph, via DOT.
	 *
	 */

	public static void showDot(String filename, Graph g, boolean directed) {
		int init_id = 0;

		try {
			PrintStream ps = new PrintStream( new FileOutputStream(filename));

			ps.println(directed ? "digraph G {" : "graph G {");
			ps.println("\tgraph [splines=true overlap=false];");
			if(Dot.scaleable()) ps.println("\tsize = \"7.5,20\";");
			ps.println("\tcenter = true;");
			// ps.println("\tnodesep = 0.05;");

			Vector v = g.getNodes();
			for (Enumeration e = v.elements() ; e.hasMoreElements() ;) {
				Node n = (Node) e.nextElement();

				ps.print("\t" + n.id + " [label=\"" + n.getLabel() + "\",");
				if((n.flags & Node.FLAGS_TERMINAL) != 0) ps.print("shape=box, ");
				if((n.flags & Node.FLAGS_MARKED) != 0) ps.print("style=filled, ");
				ps.println("height=0.3, width=0.3];");

				if(directed && (n.flags & Node.FLAGS_ROOT) != 0) { // is it initial??
					ps.println("\tinit" + init_id + "[label=\"\", style=invis, height=0, width=0];");
					ps.println("\tinit" + init_id + " -> "  + n.id + ";");
					init_id++;
				}
			}

			v = g.getEdges();

			for (Enumeration e = v.elements() ; e.hasMoreElements() ;) {
				Edge n = (Edge) e.nextElement();
				if(directed)	ps.print("\t" + n.n1.id + " -> " + n.n2.id + " [");
				else		ps.print("\t" + n.n1.id + " -- " + n.n2.id + " [");

				if( (n.flags & Edge.FLAGS_STRONG) != 0) ps.print("style=bold,");
				if( (n.flags & Edge.FLAGS_MARKED) != 0) ps.print("color=gray,");

				// label and weight
				ps.print("label=\"" + n.getLabel());
				if( (n.flags & Edge.FLAGS_WEIGTHED) != 0) ps.print(":" + n.weight );
				ps.println("\"];");
			}

			ps.println("}\n");
			ps.close();
			Dot.showDot(filename);
		} catch(IOException exx) {
			JDDConsole.out.println("GraphPrinter.showDot failed: " + exx);
		}
	}

}
