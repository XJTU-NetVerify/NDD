

package jdd.des.automata;

import jdd.util.*;
import jdd.graph.*;
import java.util.*;
import java.io.*;

import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;


/**
 * loading and saving automata from XML
 * @see GraphIO
 * @see Automaton
 * @see Automata
 */
public class AutomataIO {
	// -------------------------------------------------------------------------------
	/** save automaton in XML */
	public static void saveXML(Automaton a, String filename) {
			saveXML_internal(a,null,filename, null);
	}

	/** save several automata in XML */
	public static void saveXML(Automata  a, String filename) {
		saveXML_internal(null, a.automata,filename, a.getName() );
	}

	private static void saveXML_internal(Automaton a, Vector v, String filename, String name) {
		try {
			PrintStream ps = new PrintStream( new FileOutputStream(filename));
			ps.println("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>");
			ps.print("\t<Automata");
			if(name != null ) ps.print(" name=\"" + name+ "\"");
			ps.println(" >");

			if(a != null)  save_one_automaton(a, ps);
			if(v != null) {
				for(Enumeration e = v.elements() ; e.hasMoreElements() ;) {
					Automaton b = (Automaton) e.nextElement();
					save_one_automaton(b, ps);
				}
			}
			ps.println("\t</Automata>");
			ps.flush();
			ps.close();
		} catch(IOException exx) {
			JDDConsole.out.println("Unable to save automaton to " + filename + ":" + exx);
		}
	}
	private static void save_one_automaton(Automaton a, PrintStream ps) {
		ps.print("\t<Automaton name=\"" + a.getName() + "\"");

		if(a.getType() != null) ps.print(" type=\"" + a.getType() + "\"");
		ps.println(">");

		ps.println("\t\t<Events>");
		Alphabet alp = a.getAlphabet();
		Event e1 = alp.root;
		while(e1 != null) {
			ps.print("\t\t\t<Event id=\"" + e1.id + "\" label=\"" + e1.getLabel() + "\" ");

			// save only if they differ from default!
			if(! e1.isControllable() ) ps.print("controllable=\"false\" ");
			if(! e1.isObservable() ) ps.print("observable=\"false\" ");
			if(e1.getWeight() != 1.0) ps.print(" cost=\"" + e1.getWeight() + "\"");
			ps.println(" />");
			e1 = e1.next;
		}
		ps.println("\t\t</Events>");

		ps.println("\t\t<States>");
		for (Enumeration e = a.getNodes().elements() ; e.hasMoreElements() ;) {
			State s = (State) e.nextElement();
			ps.print("\t\t\t<State id=\"" + s.id + "\" name=\"" + s.getLabel() + "\"");
			if(s.isInitial()) ps.print(" initial=\"true\"");
			if(s.isMarked()) ps.print(" accepting=\"true\"");
			if(s.isForbidden()) ps.print(" forbidden=\"true\"");

			if(s.getWeight() != 0.0) ps.print(" cost=\"" + s.getWeight() + "\"");

			ps.println("/>");
		}
		ps.println("\t\t</States>");



		ps.println("\t\t<Transitions>");
		for (Enumeration e = a.getEdges().elements() ; e.hasMoreElements() ;) {
			Transition t = (Transition) e.nextElement();
			ps.println("\t\t\t<Transition source=\"" + t.n1.id + "\" dest=\"" + t.n2.id + "\" " +
						" event=\"" + t.event.id+ "\"/>");
		}
		ps.println("\t\t</Transitions>");
		ps.println("\t</Automaton>");
	}

	// -------------------------------------------------------------------------------

	/** load Automata from XML format */
	public static Automata loadXML(String filename) {
		try {
			InputStream is = new FileInputStream(filename);
			Automata ret = loadXML(is);
			is.close();
			return ret;
		} catch(Exception exx) { // IO, ParserConfiguration and SAX exceptions
			JDDConsole.out.println("Unable to load automaton from " + filename + ":" + exx);
			exx.printStackTrace();
		}
		return null;
	}


	public static Automata loadXML(InputStream is) throws IOException, SAXException, ParserConfigurationException {
		// SAXParser saxp = SAXParserFactory.newInstance().newSAXParser();
		SAXParser saxp = XMLHelper.getParser();
		AutomataXMLHandler handler = new AutomataXMLHandler();
		saxp.parse( new NotCloseableInputStream(is), handler);
		return handler.automata;
	}

	// -------------------------------------------------------------------------------

	/** testbench. do not call */
	public static void internal_test() {
		Test.start("AutomataIO");

		Automata as = AutomataIO.loadXML("data/phil.xml");
		Test.checkEquality(as.size(), 4, "4 automata loaded");

		// XXX: we need more tests !

		Test.end();
	}
}




// -------------------------------------------------------------------------------
/** internal class for loading XML stuff */
/* package */ class AutomataXMLHandler extends DefaultHandler {
	private static final int
		STATE_NONE = 0,
		STATE_DOCUMENT = 1,
		STATE_AUTOMATA = 2,
		STATE_AUTOMATON = 3;

	/* package */ Automata automata = null;
	private Automaton curr = null;
	private HashMap statemap = new HashMap();
	private HashMap eventmap = new HashMap();
	private int state = STATE_NONE;

	private String safe(Attributes a, String key, String default_) {
		String ret = a.getValue(key);
		return (ret != null) ? ret : default_;
	}

	public void endElement(String uri, String localName, String qName)  {
		if(qName.equals("Document") ) {
			if(state == STATE_DOCUMENT) state = STATE_NONE;
		} else if(qName.equals("Automata") || qName.equals("SupremicaProject")) {
			if(state == STATE_AUTOMATA) state = STATE_DOCUMENT;
		} else if(qName.equals("Automaton")) {
			if(state == STATE_AUTOMATON) state = STATE_AUTOMATA;
		}
	}

	public void startElement(String uri, String localName, String qName, Attributes attributes) {

		if(qName.equals("Document") ) {
			if(state == STATE_NONE) state = STATE_DOCUMENT;
		} else if(qName.equals("Automata") || qName.equals("SupremicaProject")) {
			// if this is an supremica document, the Document part wont be avialable
			if(state == STATE_DOCUMENT || state == STATE_NONE) state = STATE_AUTOMATA; else return;

			automata = new Automata(attributes.getValue("name"));

		} else if(qName.equals("Automaton")) {
			if(state == STATE_AUTOMATA) state = STATE_AUTOMATON; else return;

			String str = attributes.getValue("name");
			curr = automata.add(str);

			str = attributes.getValue("type");
			if(str != null) curr.setType(str);
			statemap.clear();
			eventmap.clear();

		} else if(qName.equals("Event")) {
			if(state != STATE_AUTOMATON) return;

			String tmp;
			String id = attributes.getValue("id");
			String label = safe(attributes, "label", id);
			String flags = attributes.getValue("flags");

			Event e = curr.addEvent( label);
			tmp = attributes.getValue("cost");
			if(tmp != null) e.setWeight( Double.parseDouble(tmp) );


			tmp = attributes.getValue("controllable"); // default: true
			e.setControllable(tmp == null || tmp.equals("true") );

			tmp = attributes.getValue("observable"); // default: true
			e.setObservable(tmp == null || tmp.equals("true") );

			// ... and we are done. just put it into the map for later
			eventmap.put(id, e);

		} else if(qName.equals("State")) {
			if(state != STATE_AUTOMATON) return;

			String id = attributes.getValue("id");
			String name = safe(attributes, "name", id);
			State s = curr.addState(name);

			String tmp = attributes.getValue("initial");
			if(tmp != null)	s.setInitial(tmp.equals("true") );


			tmp = attributes.getValue("accepting");
			if(tmp != null)		s.setMarked(tmp.equals("true") );

			tmp = attributes.getValue("forbidden");
			if(tmp != null)		s.setForbidden(tmp.equals("true") );

			tmp = attributes.getValue("cost");
			if(tmp != null) s.setWeight( Double.parseDouble(tmp) );

			statemap.put(id, s);



		} else if(qName.equals("Transition")) {
			if(state != STATE_AUTOMATON) return;

			String from = attributes.getValue("source");
			String to = attributes.getValue("dest");
			String event = attributes.getValue("event");
			State s1 = (State) statemap.get(from);
			State s2 = (State) statemap.get(to);
			Event e = (Event) eventmap.get(event);
			if(s1 == null || s2 == null || e == null)
				System.err.println("BAD transition in " +curr.getName() + ": (" + from + "," + event+ ") -> " + to);
			else	curr.addTransition( s1, s2, e);
		}
	}
}
