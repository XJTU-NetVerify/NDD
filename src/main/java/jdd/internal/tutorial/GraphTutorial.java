package jdd.internal.tutorial;

import jdd.graph.*;
import jdd.util.*;

public class GraphTutorial extends TutorialHelper{
	public GraphTutorial() {
		super("Graph");

		h2("Graph Tutorial");
		JDDConsole.out.println("This tutorial explains basics of the Graph API, which is found in jdd.graph.*");

		h3("Introduction");
		JDDConsole.out.println("Graph API contains a simple set of data structures and algorithms for graph based operations.");
		JDDConsole.out.println("This API is provided since graph algorithms are often used in conjunction with BDDs <i>et. al.</i> in,  for example, model checking.");

		JDDConsole.out.println("<p>The Graph API is very simple. To represent a Graph G =(V,E), you create a <i>Graph</i> object " +
			"and either add <i>Node</i> and <i>Edge</i> objects to it, or use the <i>GraphIO</i> class to "+
			"to read a graph file.");


		JDDConsole.out.println("<p>A sequence for creating an undirected graphs may look like this:");
		code(
			"Graph g1 = new Graph(false);\n"+
			"Node n11 = g1.addNode();\n"+
			"Node n12 = g1.addNode();\n"+
			"Node n13 = g1.addNode();\n"+
			"\n"+
			"Edge ex1 = g1.addEdge(n11, n12);\n"+
			"Edge ex2 = g1.addEdge(n12, n13);\n"+
			"Edge ex3 = g1.addEdge(n11, n13);\n"+
			"g1.removeNode(n11);"
			);

		Graph g1 = new Graph(false);
		Node n11 = g1.addNode();
		Node n12 = g1.addNode();
		Node n13 = g1.addNode();

		Edge ex1 = g1.addEdge(n11, n12);
		Edge ex2 = g1.addEdge(n12, n13);
		Edge ex3 = g1.addEdge(n11, n13);
		g1.removeNode(n11);


		JDDConsole.out.println("<p>Once more, visualization is done via DOT:");
		code("g1.showDot( \"g1\" );");

		g1.showDot( filename("g1"));
		img("g1");

		JDDConsole.out.println("<p>Notice that removing <i>n11</i> also removed the two edges attached to it.<br><br");

		JDDConsole.out.println("<p>Here is a directed graph:");
		code(
			"Graph g2 = new Graph(true);\n"+
			"Node n21 = g2.addNode();\n"+
			"Node n22 = g2.addNode();\n"+
			"Node n23 = g2.addNode();\n"+
			"Edge e1 = g2.addEdge(n21, n22);\n"+
			"Edge e3 = g2.addEdge(n22, n21);	// NOT duplicate edge (directed graph)"
		);


		Graph g2 = new Graph(true);
		Node n21 = g2.addNode();
		Node n22 = g2.addNode();
		Node n23 = g2.addNode();
		Edge e1 = g2.addEdge(n21, n22);
		Edge e3 = g2.addEdge(n22, n21);	// NOT duplicate
		g2.showDot( filename("g2"));
		img("g2");


		JDDConsole.out.println("<br><br><hr><br><br>");
		h3("Traversing graphs");
		JDDConsole.out.println("<p>Nodes and edges are stored in java Vectors:");
		code(
			"Graph g = ....\n" +
			"for (Enumeration e = <b>g.getEdges()</b>.elements() ; e.hasMoreElements() ;) {\n"+
			"   Edge edge = (Edge) e.nextElement();\n" +
			"   if(edge.n1 == edge.n2 ) System.out.println(\"self-loop found\");\n"+
			"}"
		);


	JDDConsole.out.println("<p>In each node, incoming and outgoing arcs are stored as two linked lists:");
	code(
		"Node n  = ....\n" +
		"Edge e = <b>n.firstOut</b>; // outgoing arcs\n" +
		"while(e != null) { do_somthing(e); e = <b>e.next</b>; }\n" +
		"\n" +
		"e = <b>n.firstIn</b>; // incoming arcs\n" +
		"while(e != null) { do_somthing(e); e = <b>e.prev</b>; }"
		);

		JDDConsole.out.println("<br><br><hr><br><br>");
		h3("Extra elements");
		JDDConsole.out.println("<p>The Node and Edge object have some extra member variables for storing\n" +
			" your intermediate data during execution of algorithms. These members are called extra<i>n</i>\n" +
			" and maybe overwritten by other algorithms (your or JDD's internal algorithms), so be carefull.");
		JDDConsole.out.println("There are also other usefull members such as\n" +
			"<i>flags, weight</i> and <i>id</i>.\n" +
			"See also the AttributeExplorer class\n"
			);

	JDDConsole.out.println("<p>The following implementation of a well-known algorithm\n" +
		" demonstrates the correct use of these members:");
	code(
		"public static void kruskal(Graph g) {\n"+
		"	EdgeHeap eh = new EdgeHeap(g, true);\n"+
		"	DisjointSet ds = new DisjointSet( g.numOfNodes() );\n"+
		"	int offset = 0, set_flag = Edge.FLAGS_STRONG, remove_flag = ~Edge.FLAGS_STRONG;\n"+
		"\n"+
		"	for (Enumeration e = g.getNodes().elements() ; e.hasMoreElements() ;)\n" +
		"		((Node) e.nextElement()).extra1 = offset++;\n"+
		"\n"+
		"	for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;)\n"+
		"		((Edge) e.nextElement()).flags &= remove_flag;\n"+
		"\n"+
		"	while(!eh.isEmpty() ) {\n"+
		"		Edge e = (Edge) eh.pop();\n"+
		"		int r1 = ds.find(e.n1.extra1);\n"+
		"		int r2 = ds.find(e.n2.extra1);\n"+
		"		if( r1 != r2 ) {\n"+
		"			ds.union(r1, r2);\n"+
		"			e.flags |= set_flag;\n"+
		"		}\n"+
		"	}\n"+
		"}"
		);


	JDDConsole.out.println("<p>Here, instead of");
	code(
		"	for (Enumeration e = g.getEdges().elements() ; e.hasMoreElements() ;)\n"+
		"		((Edge) e.nextElement()).flags &= remove_flag;"
	);
	JDDConsole.out.println("we could have used");
	code("AttributeExplorer.resetEdgeFlag(g, Edge.FLAGS_STRONG);");

	JDDConsole.out.println("Notice also how we avoid using a hashtable/map by using Node.extra1 in this algorithm!\n");


	JDDConsole.out.println("<br><br><hr><br><br>");
	h3("Simple graph algorithms");

	JDDConsole.out.println("<i>SimpleAlgorithms</i> contains a set of basic graph operations, such as<br>");
	showClass("jdd.graph.SimpleAlgorithms");


	JDDConsole.out.println("<p>Some other simple operations are found in <i>GraphOperation</i>. " +
		"Currently we support:");
	showClass("jdd.graph.GraphOperation");





	h3("Approximative algorithms");
	JDDConsole.out.println("<i>ApproximationAlgorithms</i> contains a set of graph algorithms that are fast but not optimal."+
		"Following algorithms is currently present:<br>");
	showClass("jdd.graph.ApproximationAlgorithms");




	h3("Shortest-path algorithms");
	JDDConsole.out.println("<i>ShortestPath</i> contains the following shortest-path algorithms");
	showClass("jdd.graph.ShortestPath");


	h3("Minimum spanning tree algorithms");
	JDDConsole.out.println("<i>MinimumSpanningTree</i> contains the following minimum spanning-tree algorithms:");
	showClass("jdd.graph.MinimumSpanningTree");

	h3("Maximum-flow algorithms");
	JDDConsole.out.println("<i>MaximumFlow</i> will contain max-flow algorithms in near future");


	h3("Strongly connected component");
	JDDConsole.out.println("<i>StronglyConnectedComponent</i> implements the SCC algorithms of Tarjan and (soon) Nuutila.");
	showClass("jdd.graph.StronglyConnectedComponent");


	h3("Weak topological ordering ");
	JDDConsole.out.println("<i>WeakTopologicalOrdering</i> implements the WTO algorithm of Bourdoncle.<br>" +
		"It is very similar to the SCO algorithm of Tarjan");
	showClass("jdd.graph.WeakTopologicalOrdering");


	h3("GraphIO");
	JDDConsole.out.println("The class <i>GraphIO</i> contains function to load/save graphs to disk.");
	JDDConsole.out.println("Three formats are supported:<ul>");
	JDDConsole.out.println("<li>EdgeList<br>This is the simple (u,v,weight) edge-list format");
	JDDConsole.out.println("<li>DIMACS<br>This is a subset of the DIMACS format");
	JDDConsole.out.println("<li>XML<br>This is JDD:s internal XML format and is recommended for normal use.");
	JDDConsole.out.println("</ul><br>");
	JDDConsole.out.println("<p>Here is a example of a sequence that uses all three formats:" );
	code(
	"Graph g = GraphIO.loadEdgeList(\"x.pcg\");\n"+
	"GraphIO.saveDIMACS(g, \"x.DIMACS\");\n"+
	"GraphIO.saveXML(g2,\"x.xml\");"
	);



	h3("Graph Factory");

	JDDConsole.out.println("The class <i>Factory</i> is used to create a set of 'interesting' graphs:");

	JDDConsole.out.println("<p>A complete graph <tt>K5</tt> is created by <i>Factory.complete(5)</i>:<br>");
	Graph c5 = Factory.complete(5);
	c5.showDot(filename("c5")); img("c5");

	JDDConsole.out.println("<p>A directed tree is created by <i>Factory.tree()</i>:<br>");
	Graph t32 = Factory.tree(3,2);
	t32.showDot(filename("t32")); img("t32");

	JDDConsole.out.println("<p>A permutation tree is created by <i>Factory.permutation()</i>:<br>");
	Graph p34 = Factory.permutation(3,3);
	p34.showDot(filename("p34")); img("p34");


	JDDConsole.out.println("<p>Here is a path of length 5, created by <i>Factory.path(5)</i>:<br>");
	Graph pa5 = Factory.path(5);
	pa5.showDot(filename("pa5")); img("pa5");

	JDDConsole.out.println("<p>Here is a circle of length 5, created by <i>Factory.circle(5)</i>:<br>");
	Graph cr5 = Factory.circle(5);
	cr5.showDot(filename("cr5")); img("cr5");

	JDDConsole.out.println("<p>A finally, the complete bipartie graph K2,3 , created by the call <i>Factory.complete_bipartie(2,3)</i>:<br>");
	Graph k23 = Factory.complete_bipartie(2,3);
	k23.showDot(filename("k23")); img("k23");



	}
}

