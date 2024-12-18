package jdd.internal.tutorial;

import jdd.des.automata.*;
import jdd.util.*;

public class AutomataTutorial extends TutorialHelper{
	public AutomataTutorial() {
		super("Automata");

		h2("Automata Tutorial");
		JDDConsole.out.println(
			"The automata package contains support classes for DES automata. " +
			"It has a lightweight design, still it is fast and efficient."
		);

		br();
		br();
		JDDConsole.out.println("The main class is Automaton. However, to use this class, you " +
			"must access it via an Automata object. An Automata is (plural for Automaton) is an "+
			"object that contains all your automata objects for an specific system."+
			"This is necessary since all automata must have a well defined alphabet which is " +
			"a subset of the Automata objects alphabet."
		);

		br();
		br();
		JDDConsole.out.println("To create one or more Automaton:s, use the following code:");
		code(
			"Automata automata = new Automata();\n"+
			"Automaton automaton1 = automata.add(\"a1\");\n" +
			"Automaton automaton2 = automata.add(\"a2\");\n" +
			"[...]"
			);

		br();
		br();
		JDDConsole.out.println("Next, you must create the Event:s used in your Automaton. " +
			"Altough these events are mirrored in the parent Automata object, you must use unique "+
			"events for each Automaton:");
		code(
			"Event e1_1 = automaton1.addEvent(\"e1\");\n"+
			"Event e2_1 = automaton1.addEvent(\"e2\");\n"+
			"Event e1_2 = automaton2.addEvent(\"e1\"); // same e1, but for automaton2 this time\n"
			);

		br();
		br();
		JDDConsole.out.println("This also applies to State and Transition objects:");
		code(
			"State s0 = automaton1.addState(\"0\");\n" +
			"State s1 = automaton1.addState(\"1\");\n" +
			"...\n" +
			"Transition t01 = automaton1.addTransition(s0,s1,e2_1);"
			);

		JDDConsole.out.println("The code above creates an automaton with two states (s0,s1), " +
			"two events (e1,e2), and a transition from s0 to s1 labelled with e2.");
		br();
		br();
		JDDConsole.out.println("There are also some important attributes in State and Event " +
			"objects that you might need to change. Here are some examples, see the " +
			"API documentation for a complete reference.");
		code(
			"s0.setInitial(true);\n"+
			"s0.setWeight(.5);\n"+
			"s1.setMarked(true);\n" +
			"e1_1.setControllable(false);\n" +
			"e2_1.setWeight(2.0);"
			);

		h2("I/O");
		JDDConsole.out.println("As the rest of the JDD, the jdd.automata package has support for "+
			"loading and saving to/from XML files. This is done via the AutomataIO class:"
		);

		code(
		"Automata as = AutomataIO.loadXML(\"automata.xml\");\n" +
		"AutomataIO.saveXML(as, \"test.xml\");"
		);
		br();
		JDDConsole.out.println("Notice that JDD uses the Supremica XML format and is able to load/save from Supremica files.");

		JDDConsole.out.println("Also, to draw an automaton, you can use the Automaton.showDot() function.");
		code("automaton1.showDot(\"automaton1\");");
		Automata automata = new Automata();;
		Automaton automaton1 = automata.add("a1");
		Event e1_1 = automaton1.addEvent("e1");
		Event e2_1 = automaton1.addEvent("e2");
		State s0 = automaton1.addState("0");
		State s1 = automaton1.addState("1");
		Transition t01 = automaton1.addTransition(s0,s1,e2_1);
		s0.setInitial(true);
		s0.setWeight(.5);
		s1.setMarked(true);
		e1_1.setControllable(false);
		e2_1.setWeight(2.0);
		automaton1.showDot(filename("automaton1"));
		img("automaton1");


		h2("Common operations");
		JDDConsole.out.println(
			"The Automaton class is an extension of the more abstract Graph class. "+
			"As a result, the State class is a subclass of Node and Transition is a subclass of Edge in jdd.graph. "
		);
		br(); br();
		JDDConsole.out.println("To traverse the states or transition in an automaton, use the following code:");
		code(
			"Automaton a = ...\n" +
			"for (Enumeration e = a.getNodes().elements() ; e.hasMoreElements() ;) {\n" +
			"	State s = (State) e.nextElement();\n" +
			"	[...]\n"+
			"}\n\n"+
			"for (Enumeration it = a.getEdges().elements() ; it.hasMoreElements() ;) {\n" +
			"	Transition t = (Transition) it.nextElement();\n"+
			"	State from   = (State) t.n1; // from Edge \n" +
			"	State to     = (State) t.n2; // from Edge \n" +
			"	Event event  = t.event;\n" +
			"	[...]\n"+
			"}");

			JDDConsole.out.println("Alphabets are designed as linked lists. To traverse the alphabet of an automaton, try this:");

			code(
				"Automaton a = ...\n" +
				"for(Event e = a.getAlphabet().head(); e != null; e = e.next) { [...] }"
			);

			JDDConsole.out.println("Each automaton-event has a pointer to its unique automata-event. "+
				"This way, the global and local alphabets are separated but still maintain a clean relationship. "+
				"Access Event.parent to get the unique event counterpart in the global alphabet.\n" +
				"To access the global alphabet, do the following: \n"
				);

			code(
				"Automata a = ....\n" +
				"EventManager em = a.getEventManager();\n"+
				"for(Event e = em.head(); e != null; e = e.next) { [...] }"
			);

			JDDConsole.out.println("As an example, the following code prints LOCAL events in an automaton:");
			code(
				"public void printLocals(Automata all, Automata current) {\n"+
				"	EventManager em = all.getEventManager();\n"+
				"	for(Event e = em.head(); e != null; e = e.next) e.extra1 = 0;\n"+
				"	for (Enumeration it = all.elements() ; it.hasMoreElements() ;) {\n"+
				"		Automaton at =  (Automaton) it.nextElement();\n"+
				"		for(Event e = at.getAlphabet().head(); e != null; e = e.next) e.parent.extra1 ++;\n"+
				"	}\n"+
				"	for(Event e = current.getAlphabet().head(); e != null; e = e.next) {\n"+
				"		if(e.parent.extra1 == 1) System.out.println(\"Local event: \" + e);\n"+
				"	}\n"+
				"}"
			);


		br();
		br();
		JDDConsole.out.println(
			"Another interesting property of the JDD automata is that similar to Graph, " +
			"each state in an Automaton maintain a list of incoming and outgoing transitions. " +
			"Here is an example how to use this information:");
		code(
			"State s = ...\n" +
			"// outgoing arcs\n" +
			"for( Transition t = (Transition) s.firstOut; t != null; t = (Transition) t.next) { [...] }\n" +
			"// incoming arcs\n" +
			"for( Transition t = (Transition) s.firstIn; t != null; t = (Transition) t.prev) { [...] }"
		);

		h2("misc.");
		JDDConsole.out.println(
			"The AutomataToPCG class in the jdd.des.automata.analysis package creates " +
			"process communication graphs from your Automata. You might find it useful. " +
			"Furthermore, the jdd.des.automata.bdd package contains basic functionality for " +
			"BDD encoding of automata."
			);

	}
}

