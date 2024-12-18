package jdd.des.strings;


import jdd.zdd.*;
import jdd.des.automata.*;
import jdd.util.*;

import java.util.*;

/**
 * computes the bounded sublanguage of an Automaton, stores it as a Z-BDD tree ?
 *
 * <p><b>NOTE:</b> under construction (well, kind of). DO NOT USE!
 */

public class AutomataSublanguage {
	private ZDDStrings zdd;
	private Automata automata;
	private EventManager em;
	private int events;
	private Vector to_add = new Vector(); // for temporary computation
	private HashSet workset = new HashSet(64); // more temp stuff

	public AutomataSublanguage(Automata a) {
		this.automata = a;
		this.em       = a.getEventManager();
		this.events   = em.getSize();
		this.zdd      = new ZDDStrings(events);

		zdd.setBound( a.maxNumOfLocalStates() );
		zdd.setNodeNames( new AlphabetNodeNames(em) );

		// allocate ZDD vars to variables
		Event e1 = em.head();
		for(int i = 0; e1 != null; e1 = e1.next) e1.extra1 = zdd.getEvent(i++);

		// calc bounded language of each automaton
		for (Enumeration e = automata.elements() ; e.hasMoreElements() ;) {
			Automaton at = (Automaton)e.nextElement();
			calc_language(at);
		}
	}

	private void calc_language(Automaton a) {
		int nodes = a.numOfNodes();
		int bound = nodes;
		int language = 0;
		int []curr = new int[nodes];
		int []work = new int[nodes];

		// set initial language to epsilon
		int i = 0;
		workset.clear();
		for (Enumeration e = a.getNodes().elements() ;e.hasMoreElements() ;i++) {
			State s = (State) e.nextElement();
			s.extra1 = i;
			if(s.isInitial()) {
				curr[i] = 1; // \epsilon
				workset.add(s);
			} else  curr[i] = 0;
		}

		// now, compute the strings seen in each state:

		for(i = 0; i < bound; i++) {
			Array.set(work, 0);
			to_add.removeAllElements() ;

			for (Iterator e = workset.iterator() ; e.hasNext() ;) {
				State s = (State) e.next();
				Transition t = (Transition) s.firstOut;
				while(t != null) {
					State to = (State) t.n2;

					if(curr[to.extra1] == 0) to_add.add(to); // now we are ready...
					int tmp1 = zdd.ref( zdd.concat( curr[s.extra1], t.event.parent.extra1) );
					int tmp2 = zdd.ref( zdd.union( work[to.extra1], tmp1) );
					zdd.deref(tmp1);
					zdd.deref(work[to.extra1]);
					work[to.extra1] = tmp2;

					t = (Transition) t.next;
				}
			}

			// commit changes
			for(int j = 0; j < nodes; j++) {
				if(work[j] != 0) {
					int tmp1 = zdd.ref( zdd.union( work[j], curr[j]));
					zdd.deref(work[j]);	// not needed anymore
					zdd.deref(curr[j]);	// to be replaced below
					curr[j] = tmp1;
				}
			}

			// insert the new stuff now:
			for (Enumeration e = to_add.elements() ; e.hasMoreElements() ;)
				workset.add( e.nextElement());
		}


		// compute the union of each states visisted strings
		for(i = 0; i < nodes; i++) {
			int tmp = zdd.ref( zdd.union(language, curr[i]) );
			zdd.deref(language);
			zdd.deref(curr[i]);
			language = tmp;
		}


		// XXX: todo: save language somewhere :)
		zdd.printSet( language);
		System.out.println("|L| = " + zdd.count(language) + ", nodes = " + zdd.nodeCount(language)  + ", top = " + zdd.top(language));

		a.extra3 = language; // SAVE the computed language
	}

	// -----------------------------------------

	public static void main(String [] args) {
		// TEMP REMOVED  Test.start("AutomatonSublanguage");

		Automata as  = AutomataIO.loadXML("data/phil.xml");
/*
		Automata as = new Automata();

		Automaton a = as.add("test");
		State s0 = a.addState("0");
		State s1 = a.addState("1");
		State s2 = a.addState("2");
		State s3 = a.addState("3");
		Event e1 = a.addEvent("a");
		Event e2 = a.addEvent("b");
		Event e3 = a.addEvent("c");
		s0.setInitial(true);
		Transition t1 = a.addTransition(s0,s1,e1);
		Transition t2 = a.addTransition(s1,s2,e2);
		Transition t3 = a.addTransition(s2,s3,e3);
		Transition t4 = a.addTransition(s3,s0,e1);
		a.showDot("a");


		Automaton a2 = as.add("fest");
		State s4 = a2.addState("4");
		State s5 = a2.addState("5");
		Event e4 = a2.addEvent("w");
		Event e5 = a2.addEvent("q");
		s4.setInitial(true);
		a2.addTransition(s4,s5,e4);
		a2.addTransition(s5,s4,e5);
		a2.showDot("a2");
*/
		AutomataSublanguage asl = new AutomataSublanguage(as);

		// TEMP REMOVED Test.end();
	}
}
