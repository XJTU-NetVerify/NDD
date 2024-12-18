
package jdd.internal.profiler;



import jdd.util.*;

import java.awt.*;
import java.awt.event.*;
import java.util.*;

/**
 * The frame where the results are shown.
 */

// TODO: when we click on a bar, we would like to show its description

// TODO: draw a line along the best value!

public class ProfilerResultsWindow extends Frame
	implements WindowListener, ActionListener
{

	private static final int DEFAULT_WIDTH = 10;
	private static final int DEFAULT_HGAP = 5;
	private static final int DEFAULT_HEIGHT = 200;
	private static final int DEFAULT_VGAP = 20;

	private ProfilerResults pr; // current result
	private Collection list;		// list of the results
	private Iterator pr_it; // current position in the list

	private Canvas canvas;
	private Label name;
	private Button bNext;

	public ProfilerResultsWindow(Collection list) {
		super("Profiler results" );
		this.pr = pr;
		this.list = list;

		// get the largest:
		int max = 0;
		for(Iterator it = list.iterator(); it.hasNext(); ) {
			ProfilerResults tmp = (ProfilerResults) it.next();
			max = Math.max( max, tmp.getSize());
		}


		// get the canvas
		add( canvas = new DrawCanvas(), BorderLayout.CENTER );
		canvas.setSize( 2 * DEFAULT_HGAP + (DEFAULT_WIDTH + DEFAULT_HGAP) * max, 2 * DEFAULT_HEIGHT + 2 * DEFAULT_VGAP );
		canvas.setBackground(Color.lightGray);


		// get the panel
		Panel panel = new Panel(new FlowLayout(FlowLayout.LEFT) );
		add(panel, BorderLayout.NORTH);


		panel.add( name = new Label("                      ") ); // dont know itsname yet
		panel.add( bNext = new Button("Next") );
		bNext.addActionListener(this);


		pr_it = list.iterator();
		if(pr_it.hasNext()) {
			onNext();
		} else {
			pr = null;
			name.setText("");
			bNext.setEnabled(false);
		}

		// done
		addWindowListener(this);
		pack();
		setVisible(true);
	}


	// -------------------------------------------------
	private void onClose() {
		setVisible(false);
	}


	private void onNext() {
		if(! pr_it.hasNext()) pr_it = list.iterator(); // start over

		pr = (ProfilerResults) pr_it.next();
		name.setText(pr.getName() );
		canvas.repaint();
	}

	// ------[ handlers ] -----------------------------------

	public void windowActivated(WindowEvent e) { }
	public void windowClosed(WindowEvent e) { }
	public void windowClosing(WindowEvent e) {  onClose(); }
	public void windowDeactivated(WindowEvent e) { }
	public void windowDeiconified(WindowEvent e) { }
	public void windowIconified(WindowEvent e) { }
	public void windowOpened(WindowEvent e) { }

 	public void actionPerformed(ActionEvent e) {
		Object src = e.getSource();
		if(src == bNext) onNext();
	}

	//	 -------------------------------------------------------
	class DrawCanvas extends Canvas {
		public void paint(Graphics g) {

			// see if there is anything to show first
			if(pr == null) {
				g.setColor(Color.white);
				g.drawString("No data to be shown", 10,DEFAULT_HEIGHT);
				return;
			}


			// draw the graph...
			for(int i = 0; i < pr.getSize(); i++) {
				if(pr.isCurrent(i)) g.setColor( Color.red);
				else if( pr.getTime(i) == pr.getMinTime()) g.setColor(Color.blue);
				else g.setColor(Color.black);

				int y0 = DEFAULT_VGAP / 2 + DEFAULT_HEIGHT;
				int x0 = DEFAULT_HGAP / 2 + (DEFAULT_WIDTH + DEFAULT_HGAP) * i;


				if( pr.getMaxTime()  != 0) { // may happen :(
					int y1 = (int)(pr.getTime(i) * DEFAULT_HEIGHT / pr.getMaxTime() );
					g.fillRect(x0,y0-y1, DEFAULT_WIDTH, y1);
				}

				if( pr.getMaxMemory() != 0) { // should never happen
					y0 = 2  * DEFAULT_HEIGHT + DEFAULT_VGAP + DEFAULT_VGAP / 2;
					int y2 = (int)(pr.getMemory(i) * DEFAULT_HEIGHT / pr.getMaxMemory() );
					g.fillRect(x0,y0-y2, DEFAULT_WIDTH, y2);
				}
			}

			g.setColor(Color.white);
			g.drawString("Time", DEFAULT_HGAP * 2, DEFAULT_HEIGHT / 2);
			g.drawString("MEMORY ", DEFAULT_HGAP * 2, DEFAULT_HEIGHT + DEFAULT_VGAP + DEFAULT_HEIGHT / 2);
		}
	}

}

