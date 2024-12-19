package jdd.internal.tutorial;

import jdd.zdd.*;
import jdd.util.*;

public class DotTutorial extends TutorialHelper{

	private static final String GRAPH1 =
		"graph ER {\n"+
		"	node [shape=box]; course; institute; student;\n"+
		"	node [shape=ellipse]; {node [label=\"name\"] name0; name1; name2;}\n"+
		"		code; grade; number;\n"+
		"	node [shape=diamond,style=filled,color=lightgrey]; \"C-I\"; \"S-C\"; \"S-I\";\n"+
		"\n"+
		"	name0 -- course;\n"+
		"	code -- course;\n"+
		"	course -- \"C-I\" [label=\"n\",len=1.00];\n"+
		"	\"C-I\" -- institute [label=\"1\",len=1.00];\n"+
		"	institute -- name1;\n"+
		"	institute -- \"S-I\" [label=\"1\",len=1.00];\n"+
		"	\"S-I\" -- student [label=\"n\",len=1.00];\n"+
		"	student -- grade;\n"+
		"	student -- name2;\n"+
		"	student -- number;\n"+
		"	student -- \"S-C\" [label=\"m\",len=1.00];\n"+
		"	\"S-C\" -- course [label=\"n\",len=1.00];\n"+
		"\n"+
		"	label = \"\\n\\nEntity Relation Diagram\\ndrawn by NEATO\";\n"+
		"	fontsize=20;\n"+
		"}		\n";

	private static final String GRAPH2 =
		"/*\n"+
		"The command line is\n"+
		"\n"+
		"  dot -Tps -Grankdir=LR states.dot > states.ps\n"+
		"\n"+
		"and the file is:\n"+
		"*/\n"+
		"digraph states {\n"+
		"    size=\"3,2\";\n"+
		"	rankdir=LR;\n"+
		"    node [shape=ellipse];\n"+
		"    empty [label = \"Empty\"];\n"+
		"    stolen [label = \"Stolen\"];\n"+
		"    waiting [label = \"Waiting\"];\n"+
		"    full [label = \"Full\"];\n"+
		"    empty -> full [label = \"return\"]\n"+
		"    empty -> stolen [label = \"dispatch\", wt=28]\n"+
		"    stolen -> full [label = \"return\"];\n"+
		"    stolen -> waiting [label = \"touch\"];\n"+
		"    waiting -> full [label = \"return\"];\n"+
		"  }\n";

	public DotTutorial() {
		super("AT&T DOT  utility", "DOT");
		h2("AT&T DOT semi-tutorial");
		JDDConsole.out.println("Graphvis from AT&T is a public domain package for drawing graphs.<br>");
		JDDConsole.out.println("The DOT utility is used to draw directed attributed graphs from a textual description.");
		JDDConsole.out.println("In fact, it was used to produce the pictures in this series of tutorials.");
		JDDConsole.out.println("<p>Since DOT is used by JDD to visualize all types of graph, we felt it would be important" +
			" to give some information about the dot support in JDD.");

		JDDConsole.out.println("<p>In JDD, each package that uses DOT, has a class named XXXPrinter.");
		JDDConsole.out.println("For example, there exists a ZDDPrinter and a GraphPrinter.");

		JDDConsole.out.println("<p>These classes produce, among others, graphs in DOT format which is then " +
			"converted to a graphic file by starting the DOT utility from a class in JDD called Dot:");

		showClass("jdd.util.Dot");

		JDDConsole.out.println("<p><b>It is important to know that a call to Dot.showDot(file) will remove "+
			"you textual description file (here 'file') from your system! </b>");
		JDDConsole.out.println("You can turn this off by the call to Dot.setRemoveDotFile(false); ");

		JDDConsole.out.println("<p>You can choose from a set of possible file formats, such as EPS and JPEG. " +
			"For example, Dot.setType( Dot.TYPE_TYPE_PNG) will set the output format to PNG. " +
			"Furthermore, Dot.scaleable() returns true if the requested format is scalable (such as EPS).");

		h3("Example: undirected graphs");
		JDDConsole.out.println("Here is an example of an undirected graph, taken from Graphviz distribution: <br>");
		code(GRAPH1);

		Dot.showString(filename("g1"),GRAPH1);
		img("g1");


		h3("Example: directed graphs");
		JDDConsole.out.println("Here is an example of a directed graph, again, taken from Graphviz distribution: <br>");
		code(GRAPH2);

		Dot.showString(filename("g2"),GRAPH2);
		img("g2");


	}

}

