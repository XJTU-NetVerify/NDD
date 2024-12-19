package jdd.internal.tutorial;

import jdd.zdd.*;
import jdd.util.*;

public class ZDDTutorial extends TutorialHelper{
	public ZDDTutorial() {
		super("Zero-suppressed Decision Diagrams", "ZDD");

		h2("Z-BDD Tutorial");
		JDDConsole.out.println("This tutorial demonstrates basic Z-BDD operations");

		h3("Z-BDD Initialization");
		JDDConsole.out.println("There are several Z-BDD objects in JDD, they are however all created "+
			"in a similar fashion to the BDD object. Consult BDD tutorial for more info.");

		JDDConsole.out.println("<p>The basic Z-BDD class is <i>ZDD</i>. It uses BDD-style initialization");
		code("ZDD zdd = new ZDD(1000,100);");

		JDDConsole.out.println("<p>It contains all the basic operations, which are:<ul>"+
		 	"<li> base()" +
		 	"<li> empty()" +
		 	"<li> subset1()" +
		 	"<li> subset0()" +
		 	"<li> union()" +
		 	"<li> intersect()" +
		 	"<li> diff()" +
		 	"<li> change()" +
		 	"</ul>");


		JDDConsole.out.println("<p>This sequence of code builds all the examples found in Minato original paper:");
		code(
		"ZDD zdd = new ZDD(1000,100);\n"+
		"int v1 = zdd.createVar();\n"+
		"int v2 = zdd.createVar();\n"+
		"\n" +
		"int a = zdd.empty();\n"+
		"int b = zdd.base();\n"+
		"int c = zdd.change(b, v1);\n"+
		"int d = zdd.change(b, v2);\n"+
		"int e = zdd.union(c,d);\n"+
		"int f = zdd.union(b,e);\n"+
		"int g = zdd.diff(f,c);"
		);


		ZDD zdd = new ZDD(1000,100);
		int x1 = zdd.createVar();
		int x2 = zdd.createVar();

		int a = zdd.empty();
		int b = zdd.base();
		int c = zdd.change(b, x1);
		int d = zdd.change(b, x2);
		int e = zdd.union(c,d);
		int f = zdd.union(b,e);
		int g = zdd.diff(f,c);


		JDDConsole.out.println("<p>Note that in contrast to BDDs, Z-BDD variables (here v1 and v2) are just " +
			"number and no Z-BDD trees. You can't do stuff like <i>int a = zdd.union(v1,v2)</i>!!!");

		JDDConsole.out.println("<p>As with BDDs, you can visualize Z-BDD trees by single calls in JDD:");

		code(
			"zdd.print(g);\n" +
			"zdd.printSet(g);\n" +
			"zdd.printCubes(g);"
			);

		JDDConsole.out.println("<pre>");
		zdd.print(g);
		zdd.printSet(g);
		zdd.printCubes(g);

		JDDConsole.out.println("</pre>");

		JDDConsole.out.println("<p>But you will probably prefer the DOT printer <i>printDot()</i>. "+
			"It was used to produce the following images:");



		JDDConsole.out.println("<center><table BORDER=5 ><tr>");
		JDDConsole.out.println("<td>a</td><td>b</td><td>c</td><td>d</td><td>e</td><td>f</td><td>g</td></tr>");
		JDDConsole.out.println("</td></tr><td>");
		zdd.printDot(filename("a"), a); img("a"); JDDConsole.out.println("</td><td>");
		zdd.printDot(filename("b"), b); img("b"); JDDConsole.out.println("</td><td>");
		zdd.printDot(filename("c"), c); img("c"); JDDConsole.out.println("</td><td>");
		zdd.printDot(filename("d"), d); img("d"); JDDConsole.out.println("</td><td>");
		zdd.printDot(filename("e"), e); img("e"); JDDConsole.out.println("</td><td>");
		zdd.printDot(filename("f"), f); img("f"); JDDConsole.out.println("</td><td>");
		zdd.printDot(filename("g"), g); img("g");
		JDDConsole.out.println("</td></tr></table></center>");



		JDDConsole.out.println("<br><br><br><hr><br><br>");
		h2("Additional Z-BDD operators");

		JDDConsole.out.println("<p>The ZDD class has some sub-classes with additional operators. ");
		JDDConsole.out.println("These operators are used in more advanced applications. ");
		JDDConsole.out.println("In some cases, the new operators outperform the basic Z-BDD operators, ");
		JDDConsole.out.println("see for example the N Queens applet where a Z-BDD/CSP algorithms is included.");

		h3("The ZDD2 object");
		JDDConsole.out.println("<p><i>ZDD2</i> contains additional operations for unate cube set algebra. "+
			"These operations are shown below");
		showClass("jdd.zdd.ZDD2");

		h3("The ZDDCSP object");
		JDDConsole.out.println("<p><i>ZDDCSP</i> adds extra ZDD operations for CSP problems. " +
			"it is 	based on 'On the properties of combination set operations', "+
			"by Okuno, Minato and Isozaki.");

		JDDConsole.out.println("<p>The new operations are:");
		showClass("jdd.zdd.ZDDCSP");

		h3("The ZDDGraph object");
		JDDConsole.out.println("<p><i>ZDDGraph</i> is intended to [in near future] include common ZDD operations used in graph algorithms, " +
			"as explained in Coudert's paper.");

		JDDConsole.out.println("<p>The graph-operations we are working on are:");
		showClass("jdd.zdd.ZDDGraph");

		h3("Additional Z-BDD objects");
		JDDConsole.out.println("<p>There are several additional Z-BDD objects that are currently not documented in the API.");
	}
}

