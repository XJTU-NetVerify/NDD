package jdd.graph;

import jdd.util.*;
import java.util.*;

/**
 * A tree in a graph is "E' \subseteq E" such that for any "(u,v) \in E'"
 * "v" is unique. That is, each vertex is reached from only ONE another vertex.
 *
 * <p>internally, it is constrcuted like this:
 * use addPath(u,v) to add edges, this objects makes sure the uniqueness is preserved.
 * before returning to the user, call extractTree to setup E' from the given edges
 *
 */


// XXX: we get this wierd error in FundBugs, nothing important but still...
//
// (it has to do with Graph g, fix: Tree shouldnt be a subclass of Vector)
//
// Non-transient non-serializable instance field in serializable class
// This Serializable class defines a non-primitive instance field which is
// neither transient, Serializable, or java.lang.Object, and does not appear to
// implement the Externalizable interface or the readObject() and writeObject() methods.
// Objects of this class will not be deserialized correctly if a non-Serializable object
// is stored in this field.
//


public class Tree extends Vector {
	private HashMap hm;
	private Graph g;
	public Tree(Graph g) {
		this.hm = new HashMap();
		this.g = g;
	}

	public Graph graph() { return g; }

	// --------------------------------------------------------

	/* package */ void add(Node from, Node to) {
		hm.put(to, from);
	}

	/*package */ void extractTree() {
		this.removeAllElements();

		Iterator it = hm.entrySet().iterator();
		while(it.hasNext()) {
			Map.Entry kp = (Map.Entry) it.next();
			insertEdge( (Node) kp.getValue(), (Node) kp.getKey());
		}
	}
	// --------------------------------------------------------

	private void insertEdge(Node from, Node to) {
		Edge ed = from.firstOut;
		while(ed != null) {
			if(ed.n1 == from && ed.n2 == to) add( ed);
			ed = ed.next;
		}
	}

	public void show() {
		JDDConsole.out.print("Tree, E' = {");
  		for (Enumeration e = elements() ; e.hasMoreElements() ;) {
			Edge ed = (Edge) e.nextElement();
			JDDConsole.out.print(" " + ed.getLabel() );
     	}
     	JDDConsole.out.println(" }");
	}
}
