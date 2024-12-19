
package jdd.internal.profiler;



import jdd.util.*;

import java.awt.*;
import java.awt.event.*;

import java.util.*;

/**
 * This is a very simple frontend to the profiler
 */


// TODO:
// we should not blindly compare with the last test.
// the user should choose one or at least compare with the best test.
// add a second tool bar with a Choice and a "compare" button.

public class ProfilerGUI extends Frame
	implements ActionListener, WindowListener
{

	private	TextArea ta, desc;
	private Label runInfo, timeDiff, memDiff;
	private Button bRun, bSave, bClear, bQuit, bCompare, bDump;
	private TextField tfID1, tfID2;
	private Choice cSets;
	private Checkbox chProfileCache, chVerbose;
	private Profiler profiler;

	public ProfilerGUI () {
		super("JDD Profiler");

		// the important stuff
		profiler = new Profiler();
		Font myfont = new Font("Courier", Font.PLAIN, 10);

		// the text areas
		ta = new TextArea(15,100);
		desc = new TextArea(4,80);

		desc.setText( profiler.getDesc() );

		JDDConsole.out = new TextAreaTarget(ta); // direct the output to this point
		ta.setEditable(false);
		ta.setFont( myfont );


		add(ta, BorderLayout.CENTER);
		add(desc, BorderLayout.SOUTH);


		// the tool bar
		Panel toolbar = new Panel( new GridLayout(3,1) );
		add(toolbar, BorderLayout.NORTH);

		// ---- ONE
		Panel pOne = new Panel( new FlowLayout(FlowLayout.LEFT) );
		toolbar.add(pOne);
		pOne.add( bQuit = new Button("Quit") );
		bQuit.addActionListener(this);

		pOne.add( bClear = new Button("Clear output") );
		bClear.addActionListener(this);

		pOne.add( new Label("   ") ); // Space?
		pOne.add( bSave = new Button("Save") );
		bSave.addActionListener(this);

		pOne.add( bDump = new Button("Show saved data") );
		bDump.addActionListener(this);

		pOne.add( new Label("    Benchmark suite ") );
		pOne.add( cSets = new Choice() );
		cSets.add("All");
		BenchmarkSet []bs = profiler.getBenchmarkSets();
		for(int i = 0; i < bs.length; i++)  cSets.add( bs[i].toString() );
		cSets.select(1); // dont select ALL by default

		pOne.add( bRun = new Button("Run") );
		bRun.addActionListener(this);

		// ---- TWO
		Panel pTwo = new Panel( new FlowLayout(FlowLayout.LEFT) );
		toolbar.add(pTwo);


		pTwo.add( chProfileCache = new Checkbox("Profile cache") );
		pTwo.add( chVerbose = new Checkbox("Be Verbose") );


		pTwo.add( bCompare = new Button("Compare") );
		bCompare.addActionListener(this);
		pTwo.add( new Label(" run ") );
		pTwo.add( tfID1 = new TextField("" + profiler.getMyId(), 3) );
		pTwo.add( new Label(" with run ") );
		pTwo.add( tfID2 = new TextField("0", 3) );


		// THREE
		Panel pThree = new Panel( new FlowLayout(FlowLayout.LEFT) );
		toolbar.add(pThree);
		pThree.add( runInfo = new Label("                                                          ") );
		runInfo.setFont(myfont);
		pThree.add( new Label("   time " ) );
		pThree.add( timeDiff = new Label("                    ") );
		pThree.add( new Label(", memory " ) );
		pThree.add( memDiff = new Label("                    ") );



		// ------------------ fixes
		bSave.setEnabled(false);

		addWindowListener(this);

		pack();
		setVisible(true);
	}


	// -------------------------------------------------
	/** comment on a change to a Label */
	private void comment(Label output, double diff) {
		diff *= 1000; // get in %%

		String str = "?";

		if(diff == 0) str = "no changes";
		else if(Math.abs(diff) < 10) str = "very small changes";
		else if(diff > 0) {
			if(diff < 30) str = "small";
			else if( diff < 50) str = "medium";
			else str = "major";
		} else {
			if(diff > -30) str = "small improvements";
			else if(diff > -50) str = "medium improvements";
			else str = "large improvements!";
		}

		output.setText(str);
		int c = 255 - Math.min( (int)Math.abs(diff), 255);
		output.setBackground( new Color( c ,255,255) );
	}

	// -------------------------------------------------
	private void onQuit() {
		setVisible(false);
		System.exit(0);
	}

	private void onClear() {
		ta.setText("");
		timeDiff.setText("");
		memDiff.setText("");
	}

	private void onRun() {
		int choice = cSets.getSelectedIndex();
		if(choice == 0) {
			// run all
			for(int i = 0; i < cSets.getItemCount()-1; i++)
				run_one(i);
		} else {
			// run this one only
			run_one(choice -1);
		}
	}

	private void run_one(int number) {
		BenchmarkSet bs = profiler.getBenchmarkSets() [number];
		bRun.setEnabled(false); // dont touch that button while im working

		Options.profile_cache = chProfileCache.getState();
		Options.verbose = chVerbose.getState();


		// show that something is going to happen...
		JDDConsole.out.println("\n\n\n\n");

		runInfo.setText("Running " + bs + ", please stand by...");
		runInfo.setBackground(  Color.white );

		// do the magic

		profiler.run(bs);

		profiler.setDesc( desc.getText() ); // we want to show the new text!
		// profiler.dump();
		runInfo.setText("");
		bRun.setEnabled(true); 	// im done
		bSave.setEnabled(true);	// and _now_ we can save...


		// show the graphs:
		dump_one(number);
	}

	private void onCompare() {
		int id1 =  Integer.parseInt( tfID1.getText() );
		int id2 =  Integer.parseInt( tfID2.getText() );
		if(id1 == id2) {
			JDDConsole.out.println("Cannot compare test with itself");
			return;
		}

		ProfilingInfo pi1 = profiler.findInfoById(id1);
		ProfilingInfo pi2 = profiler.findInfoById(id2);
		if(pi1 == null) {
			JDDConsole.out.println("ID " + id1 + " not in the database");
		} else if(pi2 == null) {
			JDDConsole.out.println("ID " + id2 + " not in the database");
		} else {


			JDDConsole.out.println("Comparing the following tests:");
			pi1.dump();
			pi2.dump();

			double tchange = profiler.getChange(id1, id2, true);
			double mchange = profiler.getChange(id1, id2, false);

			JDDConsole.out.println("(smaller is better)");
			JDDConsole.out.println("Time change from " + id2 + " to " + id1 + ": " + ((int) ( 10000.0 * tchange)) / 100.0  + "%");
			JDDConsole.out.println("Memory change from " + id2 + " to " + id1 + ": " + ((int) ( 10000.0 * mchange)) / 100.0  + "%");
			comment(timeDiff, tchange);
			comment(memDiff, mchange);

		}



		// TODO
	}
	/** save the results to file */
	private void onSave() {
		profiler.setDesc( desc.getText() ); // update it, if it has been changed
		profiler.save();
	}

	private void onDump() {
		// profiler.dump();

		int start, end, curr = cSets.getSelectedIndex() ;

		if(curr == 0) { // all?
			start = 0;
			end = cSets.getItemCount()-1;
		} else { // or only one?
			start = curr-1;
			end = curr;
		}

		for(int i = start; i < end; i++)  dump_one(i);
	}
	private void dump_one(int index) {
		BenchmarkSet bs = profiler.getBenchmarkSets() [index];
		Collection results = profiler.getReults(bs);
		ProfilerResultsWindow prw = new ProfilerResultsWindow(results);
	}

	// ------[ action handlers ] -----------------------------------

	 public void actionPerformed(ActionEvent e) {
		 Object src = e.getSource();
			if(src == bQuit) onQuit();
			else if(src == bRun) onRun();
			else if(src == bSave) onSave();
			else if(src == bClear) onClear();
			else if(src == bDump) onDump();
			else if(src == bCompare) onCompare();
 	}

	public void windowActivated(WindowEvent e) { }
	public void windowClosed(WindowEvent e) { }
	public void windowClosing(WindowEvent e) {  onQuit(); }
	public void windowDeactivated(WindowEvent e) { }
	public void windowDeiconified(WindowEvent e) { }
	public void windowIconified(WindowEvent e) { }
	public void windowOpened(WindowEvent e) { }

	// -------------------------------------------------
	/** the profiler GUI  is started from here */
	public static void main(String [] args) { new ProfilerGUI(); }
}
