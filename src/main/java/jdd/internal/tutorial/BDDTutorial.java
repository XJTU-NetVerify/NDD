

package jdd.internal.tutorial;

import jdd.bdd.*;
import jdd.util.*;

public class BDDTutorial extends TutorialHelper{
	public BDDTutorial() {
		super("Binary Decision Diagrams","BDD");

		h2("Basic BDD tutorial");

		JDDConsole.out.println("This tutorial explains the basic BDD operations.");
		JDDConsole.out.println("It assumes however, that you are familiar with BDDs & co.");



		h3("Creating a BDD object");

		JDDConsole.out.println(
			"The first thing to do, is to create a BDD object. " +
			"This BDD object is your base for BDD operations. You may have several BDD objects "+
			"in the same applications, you may however not exchange information between these. " +
			"To create your a BDD object, you must specify the size of initial nodetable and cache. " +
			"In this example we will use the values 10000 and 1000:");
			code("BDD bdd= new BDD(1000,1000);");

		BDD bdd = new BDD(1000,1000);

		h3("Allocating variables");

		JDDConsole.out.println(
			"Before you can use you BDD object any further, you must create some BDD variables. " +
			"It is recommended that you create BDD variables only in the beginning of your work. "+
			"BDD variables are in JDD represented by Java integer:");
			code(
				"int v1 = bdd.createVar();\n" +
				"int v2 = bdd.createVar();\n" +
				"int v3 = bdd.createVar();"
			);

		int v1 = bdd.createVar();
		int v2 = bdd.createVar();
		int v3 = bdd.createVar();

		JDDConsole.out.println("Also, there are two special BDD variables that you do not need to allocate. "+
			"These two are the boolean TRUE and FALSE. They are given the Java integer values 1 and 0.");

		h3("BDD operations");
		JDDConsole.out.println("BDD operations are carried out by simply calling the corresponding function in BDD:<br>");

		code(
			"int x = bdd.and(v1,v2);\n" +
			"int y = bdd.xor(v1,v3);\n" +
			"int z = bdd.not(v2);"
		);

		JDDConsole.out.println("<br>You have now created three BDD trees.");

		int x = bdd.and(v1,v2);
		int y = bdd.xor(v1,v3);
		int z = bdd.not(v2);

		h3("Reference counting");
		JDDConsole.out.println("Each BDD tree has a reference count. if this number is zero, your BDD tree may " +
			"become removed by the internal garbage collector.The rule of thumb when working " +
			"with BDDs is to reference your trees as soon as you get them, then de-reference them when you don "+
			"need them anymore and they will be removed by the garbage collector at some future point: ");
			code(
			"bdd.ref(x);\n" +
			"bdd.ref(y);\n" +
			"bdd.ref(z);"
			);
			bdd.ref(x);
			bdd.ref(y);
			bdd.ref(z);

		JDDConsole.out.println("<p>And when you are done with them, you just do this");
		code("bdd.deref(i_dont_need_this_bdd_anymore);");

		h3("Examining BDD trees");
		JDDConsole.out.println("It might be useful to actually <u>see</u> your BDDs. For that, JDD contains " +
			"a set of functions. You can print the BDD as a set or a cube:");



		code(
			"bdd.printSet(y);\n" +
			"bdd.printCubes(y);"
		);

		JDDConsole.out.println("<pre>");
		bdd.printSet(y);
		bdd.printCubes(y);
		JDDConsole.out.println("</pre>");

		JDDConsole.out.println("However, the best way to visualize a BDD is to draw its graph.<br>" +
			"To do this, JDD uses AT&T dot, which must be installed in your system and available from your shell prompt [i.e. in your $PATH].<br><br>");


		JDDConsole.out.println("<center><table border=5><tr><td>");
		JDDConsole.out.println("bdd.printDot(\"x\", x);<br>");
		JDDConsole.out.println("</td><td>");
		JDDConsole.out.println("bdd.printDot(\"y\", y);<br>");
		JDDConsole.out.println("</td><td>");
		JDDConsole.out.println("bdd.printDot(\"v1\", v1);<br>");

		JDDConsole.out.println("</td></tr><td>");
		bdd.printDot(filename("x"), x);
		img("x");
		JDDConsole.out.println("</td><td>");
		bdd.printDot(filename("y"), y);
		img("y");

		JDDConsole.out.println("</td><td>");
		bdd.printDot(filename("v1"), v1);
		img("v1");
		JDDConsole.out.println("</td></tr></table></center>");





		h3("Quantification");
		JDDConsole.out.println("You are allowed to apply <i>exists</i> and <i>forall</i> to a BDD tree. ");
		JDDConsole.out.println("<p>The first thing you need to do is to create the cube of variables to be quantified. ");
		JDDConsole.out.println("For example, if you would like to compute (forall x(v1v2) ), you may do this:");
		code("int cube = jdd.ref( jdd.and(v1,v2) );");
		JDDConsole.out.println("Then you can carry out the quantification:");
		code("int x2 = jdd.ref( jdd.forall(x,cube) );");
		JDDConsole.out.println("Note that we demonstrated the proper use of ref() here. ");


		JDDConsole.out.println("<p>The <i>exists()</i> operators work similarly. Furthermore, there is a <i>relProd</i> " +
			"operator that computes the relational product, i.e. <i>exists C. X and Y = relProd(X,Y,C)</i>. "+
			"This operations is very useful during <i>image computation</i> in model checking, for example.");

		JDDConsole.out.println("<p>There also exists a <i>createCube</i> function that you might find useful.");

		h3("Variable substitution");
		JDDConsole.out.println("It is sometimes desired to substitute variables in a tree. To do this, you first need a JDD <i>permutation</i>:");
		code(
			"int []p1 = new int[]{ v1 };\n"+
			"int []p2 = new int[]{ v2 };\n"+
			"Permutation perm1 = bdd.createPermutation(p1, p2);\n"+
			"Permutation perm2 = bdd.createPermutation(p2, p1);"
		);
		int []p1 = new int[]{ v1 };
		int []p2 = new int[]{ v2 };
		Permutation perm1 = bdd.createPermutation(p1, p2);
		Permutation perm2 = bdd.createPermutation(p2, p1);

		JDDConsole.out.println("Now we have two permutation to change from v1 to v2 and vice versa. "+
			"To use it, just call the <i>replace()</i> operations:");

		JDDConsole.out.println("<center><table border=5><tr><td><pre>int v12 = bdd.replace( v1, perm1);</pre");
		JDDConsole.out.println("</td><td><pre>int v21 = bdd.replace( v2, perm2);</pre>");

		JDDConsole.out.println("</td></tr><td>");
		int v12 = bdd.replace( v1, perm1);
		bdd.printDot(filename("v12"), v12);
		img("v12");

		JDDConsole.out.println("</td><td>");
		int v21 = bdd.replace( v2, perm2);
		bdd.printDot(filename("v21"), v21);
		img("v21");
		JDDConsole.out.println("</td></tr></table></center>");

		JDDConsole.out.println("As you can see, we have swapped v1 and v2 in these tress...");



	}
}
