


package jdd.internal.profiler;

import jdd.util.*;

import java.io.*;
import java.util.*;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;


/**
 * Class for loading and saving profiler sets.
 *
 *
 */

public class ProfilerIO {

 /**
  * Save profiling data to a file.
  *
  * <p> The file is XML and can be edited by hand,
  * if you know what you are doing which you dont.
  */
	public static void saveProfilerData(String file, Collection data, Collection datainfo)
		throws IOException
	{
		PrintStream ps = new PrintStream( new FileOutputStream(file));
		ps.println("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>");
		ps.println("<!-- Profiling information for JDD. Don't edit this file.-->");
		ps.println("<ProfilerData>");

		// start by writing the dataset itself
		for(Iterator it = data.iterator(); it.hasNext(); ) {
			ProfiledData pd = (ProfiledData) it.next();
			ps.print(" <DataSet name=\"" + pd.name + "\" size=\"" +pd.count + "\" ");
			ps.print("besttime=\"" + pd.best_time + "\" bestmemory=\"" + pd.best_memory + "\" ");
			ps.println(">");
			for(int i = 0; i < pd.count; i++) {
				ps.print("  <Run ");
				ps.print("id=\"" + pd.ids[i] + "\" ");
				ps.print("time=\"" + pd.times[i] + "\" ");
				ps.print("memory=\"" + pd.memories[i] + "\" ");
				ps.println("/>");
			}
			ps.println(" </DataSet>");

		}

		// write the test situation. note that ProfilingInfo.desc can contain markup
		// characters (like "<" and line-feed) and must therefore be converted first
		for(Iterator it = datainfo.iterator(); it.hasNext(); ) {
			ProfilingInfo pi = (ProfilingInfo) it.next();
			ps.print(" <Info id=\"" + pi.id + "\" date=\"" + pi.date + "\" ");
			ps.print(" desc=\"" + XMLHelper.convertControlChars(pi.desc) + "\" ");
			ps.println("/>");
		}

		// done:
		ps.println("</ProfilerData>");
		ps.flush();
		ps.close();
	}



	/**
	 * Load profiler data from a file.
	 *
	 */
	public static void loadProfilerData(String file, Collection data, Collection datainfo)
		throws IOException
	{
		try {
			InputStream is = new FileInputStream(file);
			SAXParser saxp = XMLHelper.getParser();
			ProfilerXMLHandler handler = new ProfilerXMLHandler(data, datainfo);
			saxp.parse( is, handler);
			is.close();

			// Now, these is the kind of codeyour teached warned you for:
		}
		catch(SAXException sae) { throw new IOException(sae.getMessage() ); }
		catch(ParserConfigurationException pce) { throw new IOException(pce.getMessage() ); }
	}
}


/**
 * This helper class is used for reading XML format of the saved profile sets.
 *
 */
/* package */ class ProfilerXMLHandler extends DefaultHandler {
	/** The loaded data is stored here */
	public Collection data, datainfo;
	private ProfiledData current;

	public ProfilerXMLHandler(Collection data, Collection datainfo) {
		this.data = data;
		this.datainfo = datainfo;
		this.current = null;
	}

	public void startElement(String uri, String localName, String qName, Attributes attributes) {
		if(qName.equals("ProfilerData")) {
			// nothing to see here people...
		} else if(qName.equals("DataSet")) {
			current = new ProfiledData();

			current.name = attributes.getValue("name");
			current.best_time = Integer.parseInt( attributes.getValue("besttime") );
			current.best_memory = Integer.parseInt( attributes.getValue("bestmemory") );

			int size = Integer.parseInt( attributes.getValue("size") ) + 3; // keep extra spaces
			current.times = new int[size];
			current.memories = new long[size];
			current.ids = new int[size];

			current.count  = 0; // We have nothing yet!

			data.add(current);

		} else if(qName.equals("Run")) {
			current.times[ current.count ] = Integer.parseInt( attributes.getValue("time") );
			current.memories[ current.count ] = Long.parseLong( attributes.getValue("memory") );
			current.ids[ current.count ] = Integer.parseInt( attributes.getValue("id") );
			current.count++;
		} else if(qName.equals("Info")) {
			ProfilingInfo pi = new ProfilingInfo ();
			pi.id = Integer.parseInt( attributes.getValue("id") );
			pi.date = attributes.getValue("date");
			pi.desc = attributes.getValue("desc");
			datainfo.add(pi);
		}
	}



}

