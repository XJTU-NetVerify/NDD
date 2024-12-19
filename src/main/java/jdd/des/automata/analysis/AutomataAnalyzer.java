package jdd.des.automata.analysis;

import jdd.des.automata.*;
import jdd.util.*;
import jdd.util.math.*;
import jdd.graph.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * this class does some test on the automata structure
 */

public class AutomataAnalyzer {

	/**
	 * create a PCG graph from an automata.
	 * Node n => Automaton n in both vectors.
	 * Node.weight is the number of states in the original automaton
	 */

	public static Graph getPCG(Automata a) {
		return getPCG(a, null, null);
	}

	/**
	 * create a PCG graph from an automata.
	 * <p>Node n => Automaton n in both vectors.
	 * <p> node2automaton and automaton2node describe the bijective automata<->node relation.
	 *
	 * <p>Node.weight is the number of states in the original automaton
	 */
	public static Graph getPCG(Automata a, HashMap node2automaton, HashMap automaton2node) {
		WeightedGraph g = new WeightedGraph(false);
		Object [] as =  a.toArray();

		if(automaton2node == null) automaton2node = new HashMap(as.length);

		// you dont know where these have been...
		if(node2automaton != null) node2automaton.clear();

		for(int i = 0; i < as.length; i++) {
			Automaton at = (Automaton) as[i];
			Node n = g.addNode();
			n.weight = at.numOfNodes();
			n.setLabel(at.getName() );

			automaton2node.put(at,n);
			if(node2automaton!= null)	node2automaton.put(n, at);
		}

		for(int i = 0; i < as.length; i++) {
			Automaton a1 = (Automaton) as[i];
			Node n1 = (Node) automaton2node.get(a1);
			for(int j = 0; j < i; j++) {
				Automaton a2 = (Automaton) as[j];
				int w = a1.sharedEvents(a2);
				if(w > 0) {
					Node n2 = (Node) automaton2node.get(a2);
					g.addEdge(n1,n2,w);
				}
			}
		}
		return g;
	}


	// ----------------------------------------------------------------------------------

	/** compute automata directly dependent of this automaton [level-1 dependency group] */
	public static boolean [] dependencyGroup(Automata project, Automaton a, boolean [] vector) {
		if(vector == null) vector = new boolean[ project.size() ];
		Array.set(vector, false);

		int i = 0;
		for (Enumeration it = project.elements() ; it.hasMoreElements() ;) ((Automaton) it.nextElement()).extra1 = i++;

		Alphabet alphabet = a.getAlphabet();
		for(Event e = a.getAlphabet().head(); e != null; e = e.next) {
			for (Enumeration it = e.parent.users.elements() ; it.hasMoreElements() ;) vector[((Automaton) it.nextElement()).extra1] = true;
		}
		return vector;
	}


	// ----------------------------------------------------------------------------------

	/** compute the number of events an event is used (in a transition) in an automaton */
	public static int countEventUsage(Automaton a, Event e) {
		int count = 0;
		for (Enumeration it = a.getEdges().elements() ; it.hasMoreElements() ;) {
			Transition t = (Transition) it.nextElement();
			if(t.event == e) count++;
		}
		return count;
	}

	/**
	 * compute the number of events an event is used (in a transition) in an automaton.
	 * sets Event.probability in the Alphabet
	 */
	public static void computeEventProbability(Automaton a) {
		double all = a.numOfNodes();
		Test.check(all != 0);

		for(Event e = a.getAlphabet().head(); e != null; e = e.next) e.extra1 = 0;
		for (Enumeration it = a.getEdges().elements() ; it.hasMoreElements() ;) {
			Transition t = (Transition) it.nextElement();
			t.event.extra1 ++;
		}
		for(Event e = a.getAlphabet().head(); e != null; e = e.next) e.probability = e.extra1 / all;
	}

	/**
	 * compute the global event probability, assuming that all global states are reachable.
	 * sets Event.probability in the EventManager
	 */
	public static void computeEventProbability(Automata a) {
		EventManager em = a.getEventManager();
		for(Event e = em.head(); e != null; e = e.next) e.probability = 1; // start with probability 1

		for (Enumeration it = a.elements() ; it.hasMoreElements() ;) {
			Automaton at =  (Automaton) it.nextElement();
			computeEventProbability(at);
			for(Event e = at.getAlphabet().head(); e != null; e = e.next)
				e.parent.probability *= e.probability ;
		}
	}

	public static void showEventProbability(Automaton a) {
		computeEventProbability(a);
		JDDConsole.out.println("Transition probability for " + a.getName());
		JDDConsole.out.println("Probability\tEvent");
		JDDConsole.out.println("------------------------------");
		for(Event e = a.getAlphabet().head(); e != null; e = e.next) {
			double p = ( (int) ( e.probability  * 1000) ) / 1000.0;
			JDDConsole.out.println("" + p + "\t\t" + e.getLabel() );
		}
		JDDConsole.out.println();
	}

	// ----------------------------------------------------------------------------------



	public static AutomataAnalyzerData analyze(Automata a, String file) {
		AutomataAnalyzerData ret = new AutomataAnalyzerData();
		// get the simplified name:
		String name = ret.file = file;
		int strp = file.lastIndexOf(':');
		if(strp != -1) name = name.substring(strp+1, name.length());
		strp = name.lastIndexOf('.');
		if(strp != -1) name = name.substring(0, strp);
		name = name.replace( '/', '_').replace( '\\', '_').replace( File.pathSeparatorChar, '_');
		ret.internal_name = name;



		int i;
		Graph g = ret.pcg = getPCG(a);
		ret.wto = WeakTopologicalOrdering.bourdoncle(g);

		// create some graphs to watch, just for the fun of it...


		EventManager em = a.getEventManager();
		ArrayAnalyzer aa = new ArrayAnalyzer ();
		Vector as = g.getNodes();



		double max_states = a.maxNumOfGlobalStates();
		int num_islands = SimpleAlgorithms.number_of_islands(g);
		double wt     = AttributeExplorer.sumEdgeWeights(g);
		double size   = a.size();
		double a_size = a.numOfEvents();
		double e_size = g.numOfEdges();
		double [] d = new double[ (int)Math.max(a_size, size) ];

		ret.num_automata = g.numOfNodes();
		ret.num_events = em.getSize();
		ret.max_states = max_states;
		ret.g_num_edges = g.numOfEdges();
		ret.g_num_island = num_islands;
		ret.g_sum_weight = wt;


		// state distribution:
		i = 0;
		for (Enumeration it = a.elements() ; it.hasMoreElements() ;) {
			Automaton at =  (Automaton) it.nextElement();
			d[i++] = at.numOfNodes();
		}
		aa.analyze(d, i);

		ret.state_dist_min = aa.min;
		ret.state_dist_max = aa.max;
		ret.state_dist_avg = aa.average;
		ret.state_dist_dev = aa.std_deveiation;



		// event distribution:
		i = 0;
		for (Enumeration it = a.elements() ; it.hasMoreElements() ;) {
			Automaton at =  (Automaton) it.nextElement();
			d[i++] = at.getAlphabet().getSize() / a_size;
		}
		aa.analyze(d, i);

		ret.event_dist_min = aa.min;
		ret.event_dist_max = aa.max;
		ret.event_dist_avg = aa.average;
		ret.event_dist_dev = aa.std_deveiation;



		// MST stuff
		MinimumSpanningTree.kruskal(g);
		ret.mst = GraphOperation.clone(g);

		double edges = AttributeExplorer.countEdgeFlag(g, Edge.FLAGS_STRONG) / e_size;
		double ws = AttributeExplorer.sumEdgeWeightsIf(g, Edge.FLAGS_STRONG) / wt;
		ret.g_mst_edges  = edges;
		ret.g_mst_weight = ws;
		AttributeExplorer.resetEdgeFlag(g, Edge.FLAGS_STRONG); // remove this flag set by kruskal


		// vertex-cover stuff
		ApproximationAlgorithms.approx_vertex_cover_ED(g);
		double ed_nodes = AttributeExplorer.countNodeFlag(g, Node.FLAGS_MARKED) / size;
		ApproximationAlgorithms.approx_vertex_cover_MDG(g);
		double mdg_nodes = AttributeExplorer.countNodeFlag(g, Node.FLAGS_MARKED) / size;
		AttributeExplorer.resetNodeFlag(g, Node.FLAGS_MARKED); // cleanup!
		ret.g_vcnodes_ed  = ed_nodes;
		ret.g_vcnodes_mdg = mdg_nodes;


		// level-1 dependency
		i = 0;
		for (Enumeration it = g.getNodes().elements() ; it.hasMoreElements() ;) {
			Node n = (Node) it.nextElement();
			d[i++] = (1 + n.getDegree()) / size;
		}
		aa.analyze(d, i);

		ret.level1_dep_min = aa.min;
		ret.level1_dep_max = aa.max;
		ret.level1_dep_avg = aa.average;
		ret.level1_dep_dev = aa.std_deveiation;
		ret.level1_dep_min_name = ((Node) as.elementAt(aa.index_min)).getLabel();
		ret.level1_dep_max_name = ((Node) as.elementAt(aa.index_max)).getLabel();


		// level-2 dependency
		i = 0;
		HashSet hs = new HashSet();
		for (Enumeration it = g.getNodes().elements() ; it.hasMoreElements() ;) {
			Node n = (Node) it.nextElement();
			d[i++] = (1 + SimpleAlgorithms.level_n_degree(n, 2, hs)) / size;
		}
		aa.analyze(d, i);

		ret.level2_dep_min = aa.min;
		ret.level2_dep_max = aa.max;
		ret.level2_dep_avg = aa.average;
		ret.level2_dep_dev = aa.std_deveiation;
		ret.level2_dep_min_name = ((Node) as.elementAt(aa.index_min)).getLabel();
		ret.level2_dep_max_name = ((Node) as.elementAt(aa.index_max)).getLabel();

		// event probability and estimated branching factor
		computeEventProbability(a);
		i = 0;
		ret.estimated_branching_factor = 0.0;
		for(Event e = em.head(); e != null; e = e.next) {
			d[i++] = e.probability;
			ret.estimated_branching_factor += e.probability;
		}
		aa.analyze(d, i);

		ret.event_prob_min = aa.min;
		ret.event_prob_max = aa.max;
		ret.event_prob_avg = aa.average;
		ret.event_prob_dev = aa.std_deveiation;
		ret.event_prob_min_name = em.findByOrder(aa.index_min).getLabel();
		ret.event_prob_max_name = em.findByOrder(aa.index_max).getLabel();

		return ret;
	}

	// --------------------------------------------------------------------

	public static void loadAndAnalyzeZip(String filename) throws Exception {
		InputStream  is = new FileInputStream (filename);
		ZipInputStream zis = new ZipInputStream(is);

		AutomataAnalyzerData.printHeader();
		ZipEntry ze = zis.getNextEntry();
		while(zis.available()!= 0) {
			if(!ze.isDirectory() ) {
				Automata a = AutomataIO.loadXML(zis);
				if(a != null) {
					AutomataAnalyzerData dat = analyze(a, ze.getName() );
					dat.print();
				}
			}
			zis.closeEntry();
			ze = zis.getNextEntry();
		}
		zis.close();
		is.close();
	}

	public static void loadAndAnalyzeXML(String filename) throws Exception {
		InputStream  is = new FileInputStream (filename);
		Automata a = AutomataIO.loadXML(is);
		if(a != null) {
			AutomataAnalyzerData data = analyze(a, filename );
			data.printOne();
		} else JDDConsole.out.println("No automata found in " + filename);
		is.close();
	}

	public static void main(String [] args) {
		if(args.length != 1) {
			System.err.println("Usage: Java jdd.des.automata.AutomataAnalyzer [ Supremica file.xml> | <Supremica XML-files.zip>]");
			System.exit(20);
		}


		try {
			if( args[0].endsWith(".zip"))	loadAndAnalyzeZip(args[0]);
			else							loadAndAnalyzeXML(args[0]);
		} catch(Exception exx) {
			exx.printStackTrace();
		}

	}
}



