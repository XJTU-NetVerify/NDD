
package jdd.graph;

import jdd.util.*;

import java.util.*;
import java.io.*;

/**
 * Topology class.
 * <p>used to store a topological tree over nodes, for example:<br>
 * V = {a,b,c,d}   ==>   a, (b ,  (c) ), d
 */

public class Topology {
	/* package */ Vector nodes, children;
	/* package */ boolean disjoint;

	public Topology() {
		this.nodes = null;
		this.children = null;
		this.disjoint = false;
	}

	public void add(Node n) {
		if(nodes == null) nodes = new Vector();
		nodes.add(n);
	}
	public void add(Topology t) {
		if(children == null) children = new Vector();
		children.add(t);
	}

	/** nodes in the same topology, NULL if none */
	public Vector getNodes() { return nodes; }

	/** our child topologies, NULL if none */
	public Vector getChildren() { return children; }


	/** simplify at top level. retruns NULL if the topology is empty */
	public Topology simplify() {
		if(nodes == null) {
			if(children == null) return null;
			if(children.size() == 1) {
				Enumeration e = children.elements();
				Topology t = (Topology) e.nextElement();
				return t;
			}
		}
		return this;
	}
	// -------------------------------------------------------------------
	public void show() {
		if(nodes != null) {
			for (Enumeration e = nodes.elements() ; e.hasMoreElements() ;) {
				Node n = (Node) e.nextElement();
				JDDConsole.out.print(" " + n.label);
			}
		}

		if(children != null) {
			for (Enumeration e = children.elements() ; e.hasMoreElements() ;) {
				Topology t = (Topology) e.nextElement();
				JDDConsole.out.print(" (");
				t.show();
				JDDConsole.out.print(") ");
			}
		}
	}


	// -------------------------------------------
	static int dot_internal = 0;
	public void showDot(String filename) {
		try {
			PrintStream ps = new PrintStream( new FileOutputStream(filename));

			ps.println("digraph G {" );
			ps.println("\tgraph [splines=false overlap=false];");
			if(Dot.scaleable()) ps.println("\tsize = \"7.5,20\";");
			ps.println("\tcenter = true;");
			// ps.println("\tnodesep = 0.05;");

			dot_internal = 0;
			int first = show_dot_rec( ps);
			ps.println("\t intialXXX [label=\"\", style=invis, height=0, width=0];");
			ps.println("\t intialXXX -> internal_" + first + "[style=bold];");

			ps.println("}\n");
			ps.close();
			Dot.showDot(filename);
		} catch(IOException exx) {
			JDDConsole.out.println("Topology.showDot failed: " + exx);
		}
	}

	private int show_dot_rec(PrintStream ps) {
		int id = dot_internal++;
		if(disjoint)  ps.println("\t internal_" + id+ " [label=\"(disjoint)\", shape=box,color=red];");
		else ps.println("\t internal_" + id+ " [label=\"LL\", shape=point];");

		if(nodes != null) {
			for (Enumeration e = nodes.elements() ; e.hasMoreElements() ;) {
				Node n = (Node) e.nextElement();
				int id2 = dot_internal++;
				ps.println("\t " + id2+ " [label=\"" + n.label+ "\"];");
				ps.println("\t internal_" + id+ " -> " + id2 + ";");

			}
		}

		if(children != null) {
			for (Enumeration e = children.elements() ; e.hasMoreElements() ;) {
				Topology t = (Topology) e.nextElement();
				int id2 = t.show_dot_rec(ps);
				ps.println("\t internal_" + id+ " -> internal_" + id2 + ";");
			}
		}

		return id;
	}
}

