
package jdd.des.petrinets;

import jdd.util.*;
import jdd.des.petrinets.interactive.*;

import java.io.*;
import java.util.*;


import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;


// XXX: no testbed :(
// TODO: fix the code so we can put a petrinet and a set of automata in the same file!
// todo this, we need to lookout for </TAG> too.

/**
 * Petri Net IO functionality
 */

public class PetrinetIO {


	/** just show the incident matrices */
	public static void show(Petrinet pn) {
		int[]  M_i = pn.getM();
		int curr_places = pn.numberOfPlaces();
		JDDConsole.out.print("M_i = [");
		for(int i = 0; i < curr_places; i++) {
			if(i != 0) JDDConsole.out.print(", ");
			JDDConsole.out.print( "" + M_i[i]);
		}
		JDDConsole.out.println("]");

		JDDConsole.out.println("A-"); pn.getAMinus().show();
		JDDConsole.out.println("A+"); pn.getAPlus().show();
	}

	// ----------------------------------------------------------------

	public static void saveXML(Petrinet pn, String filename) {
	try {
			PrintStream ps = new PrintStream( new FileOutputStream(filename));
			ps.println("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>");
			ps.println("\t<Petrinet>");


			ps.println("\t\t<Places>");
			int n = pn.numberOfPlaces();
			for(int i = 0; i < n; i++) {
				Place p = pn.getPlaceByIndex(i);
				ps.print("\t\t\t<Place label=\"" + p.getName() + "\"");
				if(p.getTokens() > 0)		ps.print(" token=\""+ p.getTokens() +"\"");
				ps.print("/>");
			}
			ps.println("\t\t</Places>");


			ps.println("\t\t<Transitions>");
			n = pn.numberOfTransitions();
			PlaceEnumeration pe = new PlaceEnumeration(pn);
			Place tmp;
			for(int i = 0; i < n; i++) {
				Transition t = pn.getTransitionByIndex(i);
				ps.print("\t\t\t<Transition label=\"" + t.getName() +  "\"");

				// optional flags
				if(! t.isControllable() ) ps.print(" controllable=\"false\"");
				if(! t.isObservable() ) ps.print(" observable=\"false\"");

				ps.println(" >");
				pn.incomingPlaces(pe, t);
				while( (tmp = pe.nextPlace()) != null)
					ps.println("\t\t\t<Input place=\"" + tmp.getName() + "\"/>");

				pn.outgoingPlaces(pe, t);
				while( (tmp = pe.nextPlace()) != null)
					ps.println("\t\t\t<Output place=\"" + tmp.getName() + "\"/>");

				ps.println("\t\t\t</Transition>");

			}
			ps.println("\t\t</Transitions>");

			ps.println("\t</Petrinet>");
			ps.flush();
			ps.close();
		} catch(IOException exx) {
			JDDConsole.out.println("Unable to save petrinet to " + filename + ":" + exx);
		}
	}

	// -------------------------------------------------------------------------------------

	/** load Petrinet from XML format, it used the IPetrinet loader below */
	public static Petrinet loadXML(String filename) {
		IPetrinet ipn = loadXMLInteractive(filename);
		if(ipn != null) return PetrinetTransform.convert(ipn);
		return null;
	}

	/** load IPetrinet from XML format */
	public static IPetrinet loadXMLInteractive(String filename) {
		try {
			InputStream is = new FileInputStream(filename);
			// SAXParser saxp = SAXParserFactory.newInstance().newSAXParser();
			SAXParser saxp = XMLHelper.getParser();
			PetrinetXMLHandler handler = new PetrinetXMLHandler();
			saxp.parse( new NotCloseableInputStream(is), handler);
			is.close();

			return handler.petrinet;

		} catch(Exception exx) { // IO, ParserConfiguration and SAX exceptions
			JDDConsole.out.println("Unable to load IPetrinet from " + filename + ":" + exx);
			exx.printStackTrace();
		}
		return null;
	}

	// -------------------------------------------
	/** testbench. do not call */
	public static void internal_test() {
		Test.start("PetrinetIO");
		Petrinet pn = PetrinetIO.loadXML("data/agv.xml");
		Test.checkEquality(pn.numberOfPlaces(), 64, "# of places loaded");
		Test.checkEquality(pn.numberOfTransitions(), 53, "# of transition loaded");

		// XXX: we need more tests !
		Test.end();
	}
}






// -------------------------------------------------------------------------------
/** internal class for loading petrinet XML stuff, to an interactive petrinet  */
/* package */ class PetrinetXMLHandler extends DefaultHandler {
	private static final int
		STATE_NONE = 0,
		STATE_DOCUMENT = 1,
		STATE_PN = 2,
		STATE_PLACES = 3,
		STATE_TRANSITIONS = 4,
		STATE_TRANSITION = 5;

	/* package */ IPetrinet petrinet = null;
	private ITransition current = null;
	private HashMap placemap = new HashMap();
	private int state = STATE_NONE;

	private String safe(Attributes a, String key, String default_) {
		String ret = a.getValue(key);
		return (ret != null) ? ret : default_;
	}

	public void endElement(String uri, String localName, String qName)  {
		if(qName.equals("Document")) {
			if(state == STATE_NONE) state = STATE_DOCUMENT;
		} else if(qName.equals("Petrinet")) {
			if(state == STATE_PN) state = STATE_DOCUMENT;
		} else if(qName.equals("Transitions")) {
				if(state == STATE_TRANSITIONS) state = STATE_PN;
		} else if(qName.equals("Transition")) {
			if(state == STATE_TRANSITION) state = STATE_TRANSITIONS;
		} else if(qName.equals("Places")) {
			if(state == STATE_PLACES) state = STATE_PN;
		}
	}

	public void startElement(String uri, String localName, String qName, Attributes attributes) {
		if(qName.equals("Document")) {
			if(state == STATE_NONE) state = STATE_DOCUMENT; else return;
		} else if(qName.equals("Petrinet")) {
			// STATE_NONE for the older documents
			if(state == STATE_DOCUMENT || state == STATE_NONE) state = STATE_PN; else return;

			if(petrinet  != null) JDDConsole.out.println("Multiple petrinets in XML file");
			else {
				petrinet = new IPetrinet();
				placemap.clear();
			}

		} else if(qName.equals("Places")) {
			if(state == STATE_PN) state = STATE_PLACES; else return;

		} else if(qName.equals("Place")) {
			if(state != STATE_PLACES) return;

			String label = attributes.getValue("label");
			String tokens = safe(attributes, "token", "0");

			int tok = 0;
			if(tokens.equals("false"))  tok = 0;
			else if(tokens.equals("true"))  tok = 1;
			else tok = Integer.parseInt(tokens);

			IPlace p= new IPlace(label, tok);
			petrinet.add(p);
			placemap.put(label, p);

		} else if(qName.equals("Transitions")) {
			if(state == STATE_PN) state = STATE_TRANSITIONS; else return;

		} else if(qName.equals("Transition")) {
			if(state == STATE_TRANSITIONS) state = STATE_TRANSITION; else return;

			String label = attributes.getValue("label");
			current = new ITransition(label);

			String tmp = attributes.getValue("controllable"); // default: true
			current.setControllable(tmp == null || tmp.equals("true") );

			tmp = attributes.getValue("observable"); // default: true
			current.setObservable(tmp == null || tmp.equals("true") );

			petrinet.add(current);

		} else if(qName.equals("Input")) {
			if(state != STATE_TRANSITION) return;

			String place = attributes.getValue("place");
			IPlace p = (IPlace) placemap.get(place);
			petrinet.add(p, current);

		} else if(qName.equals("Output")) {
			if(state != STATE_TRANSITION) return;
			String place = attributes.getValue("place");
			IPlace p = (IPlace) placemap.get(place);
			petrinet.add(current, p);
		}
	}
}
