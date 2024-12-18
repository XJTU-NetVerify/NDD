
package jdd.applet;


import java.io.*;
import java.net.*;
import java.util.zip.*;
import java.applet.*;
import java.awt.*;
import java.awt.event.*;


import jdd.sat.*;
import jdd.sat.bdd.*;
import jdd.sat.gsat.*;
import jdd.sat.dpll.*;
import jdd.util.*;


/** Applet interface for the CNF solvers */

public class SolverApplet extends Applet implements ActionListener  {
	private TextArea msg, code;
	private Button bSolve, bClear, bLoad;
	private Choice chModels, chSolver;
	private Checkbox cbVerbose;

	private String initial_text =
		"c This applet demonstrates solving CNF [Conjunctive Normal Form, aka product of sums] formulas.\n" +
		"c A solution to such formula is a series of assignments to each variables that makes the formula\n" +
		"c become logically true. If no such solution exists, the formula is UNSATisfiable.\n" +
		"c A DIMACS formula starts with the line  'p cnf num-of-variables num-of-cluases'\n" +
		"c A clause is a set of disjunctions where a number n represents variable v_n being true, while\n" +
		"c -n indicates v_n being false. Each clause end with a zero. For example the formula\n" +
		"c 'f(v1,v2,v3) = (v1 OR v2) AND (v2 or NOT v3) AND (v3 or NOT v2)' looks like this in DIMACS:\n" +
		"p cnf 3 3\n1 2 0\n2 -3 0\n3 -2 0\n"
		;

	public SolverApplet() {
		Color bgcolor = new Color(0xE0, 0xE0, 0xE0) ;
		setBackground( bgcolor );

		setLayout( new BorderLayout() );

		Panel p = new Panel( new FlowLayout( FlowLayout.LEFT) );
		p.setBackground( bgcolor );
		add(p, BorderLayout.NORTH);
		p.add( bSolve = new Button("Solve") );
		p.add( bClear = new Button("Clear") );

		p.add( new Label(" models: ") );

		p.add( chModels = new Choice() );
		p.add( bLoad = new Button("<-- load") );

		p.add( new Label(" Solver:") );
		p.add( chSolver = new Choice() );
		chSolver.add("BDD");
		chSolver.add("BDD2");
		chSolver.add("GSAT");
		chSolver.add("WalkSAT");
		chSolver.add("DPLL");



		p.add( cbVerbose = new Checkbox("verbose", false));

		add(code = new TextArea(25,80), BorderLayout.CENTER);
		add(msg = new TextArea(10,80), BorderLayout.SOUTH);



		msg.setEditable(false);
		msg.setBackground( bgcolor );

		bSolve.addActionListener( this );
		bClear.addActionListener( this );
		bLoad.addActionListener( this );


		JDDConsole.out = new TextAreaTarget(msg) ;


		code.setFont( new Font("Monospaced", 0, 12) );
		code.setBackground( Color.yellow);
		code.setForeground( Color.red);
		code.setText(initial_text);


		chModels.add("8xQueens.cnf.gz");
		chModels.add("aim-50-1_6-no-2.cnf.gz");
		chModels.add("aim-50-2_0-yes1-2.cnf.gz");
		chModels.add("aim-50-3_4-yes1-4.cnf.gz");
		chModels.add("aim-100-1_6-no-2.cnf.gz");
		chModels.add("aim-100-6_0-yes1-2.cnf.gz");
		chModels.add("aim-200-2_0-yes1-2.cnf.gz");
		chModels.add("aim-200-3_4-yes1-3.cnf.gz");
		chModels.add("dubois22.cnf.gz");
		chModels.add("par16-4.cnf.gz");
	}

	// private void load(String file) {
	private void load(BufferedReader br) {
		// now try to load a CNF:
		StringBuffer sb = new StringBuffer();
		try {
			code.setText("(reading file, please wait)");
			String str;

			while ( (str = br.readLine()) != null) {
				str = str.trim();
				int len = str.length();
				if(len > 0) {
					if(str.charAt(0) == 'c' && str.length() > 2) {
						// msg.append(str);
						// msg.append("\n");
					}

					sb.append(str);
					if(len < 2 || str.charAt(0) == 'c' || str.charAt(0) == 'p' || (str.charAt(len-1) == '0' && (str.charAt(len-2) > '9' || str.charAt(len-2) < '0') ))
						sb.append('\n');
					else
						sb.append(' ');
				}
			}
			br.close();
		} catch(IOException exx) {
			msg.append("Failed: " + exx + "\n");
			exx.printStackTrace();
			code.setText("");
			exx.printStackTrace();
			return;
		}

		code.setText( sb.toString() );

	}
	public void actionPerformed(ActionEvent e) {
		Object src = e.getSource();
		if(src == bSolve) doSolve();
		else if(src == bClear) doClear();
		else if(src == bLoad) doLoad();

	}

	// ----------------------------------
	private void doClear() {
		msg.setText("");
		code.setText("");
	}

	private void doLoad() {
		try {
			String name = "/dimacs/" + chModels.getSelectedItem() ;

			InputStream is = getClass().getResourceAsStream(name);


			if(is == null) {
				msg.append("UNABLE to load " + name + "\n");
				return;
			}
			BufferedReader br = null;
			if( name.endsWith(".gz") || name.endsWith(".Z"))
				br = new BufferedReader( new InputStreamReader(new GZIPInputStream( is )));
			else br = new BufferedReader ( new InputStreamReader(is) );
			load( br );
			is.close();
		} catch(IOException exx) { msg.append("--> " + exx + "\n"); }
	}

	private Solver getSolver() {
		JDDConsole.out.println("Loading solver '" + chSolver.getSelectedItem()  + "'...");

		int type = chSolver.getSelectedIndex();
		boolean verbose = cbVerbose.getState();

		switch(type) {
			case 0: return new BDDSolver( verbose);
			case 1: return new BDDSolver2( verbose);
			case 2: return new GSAT2Solver( 15000);
			case 3: return new WalkSATSolver( 15000, 0.05);
			case 4: return new DPLLSolver();
		}
		return null; // ERORROR
	}

	private void doSolve() {
		String model = code.getText();

		if(model == null) return;
		try {

			DimacsReader dr = new DimacsReader(model, false);
			Solver solver = getSolver();
			CNF cnf = dr.getFormula() ;
			//if(cnf == null) break;
			solver.setFormula(cnf);
			dr = null;
			int s[] = solver.solve();
			solver.cleanup();

			if(s != null) {
				for(int i = 0; i < s.length; i++){
					if( (i % 64) == 0) msg.append("\n");
					msg.append(s[i] == -1 ? "-" : ""+s[i]);
				}
				msg.append("\n");
			}
		} catch(Exception exx) {
			msg.append("\nFailed: " + exx + "\n");
			exx.printStackTrace();
		}
	}
}
