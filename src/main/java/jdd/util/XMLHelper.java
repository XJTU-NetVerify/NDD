
package jdd.util;

/**
 * Some helpers for the XML stuff. nothing for the end users.
 *
 */

import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;

public class XMLHelper {

	private static SAXParserFactory parserFactory;

	/**
	 * find the parsers that works.
	 *
	 * <p>
	 * we use dynamic loading to avoid including java objects that may not be in the classpath :)
	 */
	static {

		final String [] KNOWN_PARSERS= new String[] {
			"org.apache.crimson.jaxp.SAXParserFactoryImpl",
			"org.apache.xerces.jaxp.SAXParserFactoryImpl",
			"com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl"
		};

		parserFactory = null;

		// try the configured parser first
		try {
			parserFactory = javax.xml.parsers.SAXParserFactory.newInstance();
		} catch(FactoryConfigurationError exx) {  }


		// try the known parsers now
		for(int i = 0; (i < KNOWN_PARSERS.length) && (parserFactory == null); i++) {
			try {
				Class klass = Class.forName(KNOWN_PARSERS[i]);
				parserFactory = (SAXParserFactory) klass.newInstance();
			} catch(Exception exx) {
				// System.err.println("could not load " +KNOWN_PARSERS[i] + ", reason : " + exx );
			}
		}



		// no working parsers found. bale out
		if(parserFactory == null) {
			System.err.println("Could not create a SAXParser object");
			System.exit(20);
		}


	}

	/**
	 * since ANT for some stupid reason changes the default xml parser to apache xerces,
	 * we must make sure it is available and if not, use the jdk parser
	 */

	public static SAXParser getParser() throws SAXException, ParserConfigurationException {
		return parserFactory.newSAXParser();
	}

	/**
	 * HTML:fies the control chars such as enter
	 */
	public static String convertControlChars(String str) {
		StringBuffer sb = new StringBuffer(str.length() ) ;

		byte [] bytes = str.getBytes();

		for(int i = 0;i < bytes.length; i++) {
			switch( bytes[i]) {
				case '\r':
				case '\n':
				case '\t':
				case '<':
				case '>':
				case '&':
					sb.append("&#" + ((int)bytes[i]) + ";");
					break;
				default:
				sb.append((char)bytes[i]);
			}
		}
		return sb.toString();
	}
}