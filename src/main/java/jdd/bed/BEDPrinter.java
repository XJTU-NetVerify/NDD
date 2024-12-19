
package jdd.bed;

import jdd.util.*;
import jdd.bdd.NodeTable;
import java.io.*;

/** Printer for BED trees */

public class BEDPrinter {

	private static NodeTable nt;
	private static PrintStream ps;
	private static NodeName nn;
	private static BED bed_;
	private static boolean had_one, had_zero;


	private static void helpGC() { // make thins easier for the garbage collector
		nt= null;
		ps = null;
		nn = null;
		bed_ = null;
	}


	// ----------------------------------------------------------------------
	/** print the part of node-table that describes this BDD */
	public static void print(int bed, NodeTable nt, NodeName nn) {

		if(bed <2) {
			JDDConsole.out.println("\nBED " + bed + ": " + (bed == 0 ? "FALSE" : "TRUE") );
		} else {
			BEDPrinter.nt = nt;
			BEDPrinter.nn = nn;
			JDDConsole.out.println("\nBED " + bed);
			print_rec(bed);
			nt.unmark_tree(bed);
			helpGC();
		}
	}
	private static void print_rec(int i) {
		if(i < 2) return;
		if( nt.isNodeMarked(i)) return;

		int var = nt.getVar(i);

		JDDConsole.out.println("" + i + "\t" + nn.variable(var) + "(" + var + ")\t" + nt.getLow(i) + "\t" + nt.getHigh(i));

		nt.mark_node(i);
		print_rec(nt.getLow(i));
		print_rec(nt.getHigh(i));
	}

	// ----------------------------------------------------------------------
	/** print an expression as text. XXX: currently not  working */
	public static void printFormula(BED bed_, int bed, NodeTable nt, NodeName nn) {

		if(bed <2) 	JDDConsole.out.println(bed == 0 ? nn.zero() : nn.one() );
		else {
			BEDPrinter.nt = nt;
			BEDPrinter.nn = nn;
			BEDPrinter.bed_ = bed_;
			printFormula_rec(bed);
			JDDConsole.out.println();
			helpGC();
		}
	}

	// XXX_ todo
	private static void printFormula_rec(int i) {
		/*
		if(i <2) 	return;
		else if(bed_.isVariable(i)) {
			JDDConsole.out.print(nn.variable(nt.getVar(i)));
		} else if(bed_.isInverseVariable(i)) {
			JDDConsole.out.print("~"+nn.variable(nt.getVar(i)));
		} else {
			int op  = (nt.getVar(i) & BED.MASK_OP);

			JDDConsole.out.print(" (");
			if (op == BED.TYPE_NOT) {
				JDDConsole.out.print(" ~");
				printFormula_rec(nt.getLow(i));
				JDDConsole.out.print(" ");
			} else if(op == 0) {
				if(nt.getLow(i) < 2) {
					if(nt.getLow(i) == 1)  JDDConsole.out.print("~" + nn.variable(nt.getVar(i)) + " | ");
					JDDConsole.out.print(nn.variable(nt.getVar(i)) + " & ");
					printFormula_rec(nt.getHigh(i));
				} else if(nt.getHigh(i) < 2) {
					JDDConsole.out.print("~" + nn.variable(nt.getVar(i)) + " & ");
					printFormula_rec(nt.getLow(i));
					if(nt.getHigh(i) == 1) JDDConsole.out.print("| " + nn.variable(nt.getVar(i)) );

				} else {
					JDDConsole.out.print("~" + nn.variable(nt.getVar(i)) + " & ");
					printFormula_rec(nt.getLow(i));
					JDDConsole.out.print(" | " + nn.variable(nt.getVar(i)) + "&  ");
					printFormula_rec(nt.getHigh(i));
					JDDConsole.out.print("");
				}
			} else {

				JDDConsole.out.print(" ");
				printFormula_rec(nt.getLow(i));
				JDDConsole.out.print(" " + BED.op_name(nt.getVar(i)) + " ");
				printFormula_rec(nt.getHigh(i));
				JDDConsole.out.print(" ");
			}
			JDDConsole.out.print(") ");
		}
		*/
	}
	// ---------------------------------------------------------------------------------------

	public static void printDot(String filename, int bdd, NodeTable nt, NodeName nn)  {
		try {
			ps = new PrintStream( new FileOutputStream(filename));

			ps.println("digraph G {");
   		BEDPrinter.nn = nn;
			BEDPrinter.nt = nt;

			had_one=  had_zero = false;

			ps.println("\tinit__ [label=\"\", style=invis, height=0, width=0];");
			ps.println("\tinit__ -> "  + bdd + ";");

			printDot_rec(bdd);
			if(had_one && had_zero)	ps.println("\t{ rank = same; 0; 1;}");
			if(had_zero)	ps.println("\t0 [shape=box, label=\"" + nn.zeroShort() + "\", style=filled, height=0.3, width=0.3];");
			if(had_one)	ps.println("\t1 [shape=box, label=\"" + nn.oneShort() + "\", style=filled, height=0.3, width=0.3];\n");;
			ps.println("}\n");
			nt.unmark_tree(bdd);
			ps.close();
			Dot.showDot(filename);
			helpGC();
		} catch(IOException exx) {
			JDDConsole.out.println("BEDPrinter.printDOT failed: " + exx);
		}
	}

	private static void printDot_rec(int bdd) {

		if(bdd == 0) { had_zero = true; return; }
		if(bdd == 1) { had_one  = true; return; }

		if(nt.isNodeMarked(bdd)) return;

		int var = nt.getVar(bdd);
		int low = nt.getLow(bdd);
		int high = nt.getHigh(bdd);
		int type = BED.GET_OPERATION(var);

		ps.print("" + bdd + " [label=\"" + nn.variable(var) + ":" + bdd+ "\"];");
		ps.println("" + bdd + " -> " + low + (BED.IS_BDD(var) ? " [style=dotted];" : " [style=filled];" ));
		ps.println("" + bdd + " -> " + high + " [style=filled];");

		nt.mark_node(bdd);
		printDot_rec(low);
		printDot_rec(high);
	}
}
