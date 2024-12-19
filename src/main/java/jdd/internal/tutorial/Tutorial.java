

package jdd.internal.tutorial;

import java.io.*;


public class Tutorial {
	private static PrintStream ps = null;
	private static void register(TutorialHelper th) {
		ps.println("<li> Tutorial book <a href=" + th.index + ">" +th.tutorial+ "</a><br>");
	}


	public static void main(String args []) {
		System.out.println("Creating tutorials...");
		try {
			ps = new PrintStream( new FileOutputStream("tutorial/index.html"));
			ps.println("<html>");
			ps.println("<link rel=\"stylesheet\" href=\"../bdd.css\">");
			ps.println("<br><br><h2>Auto-generated JDD tutorials</h2><br><br>");
		} catch(IOException exx) {
			exx.printStackTrace();
			System.exit(20);
		}

		// create the books
		register( new BDDTutorial() );
		register( new ZDDTutorial() );
		register( new GraphTutorial() );
		register( new AutomataTutorial() );
		register( new DotTutorial() );
		register( new SATTutorial() );


		// now cleanup
		ps.println("<br></html>");
	}
}
