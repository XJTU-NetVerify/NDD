package jdd.internal.tutorial;

import jdd.zdd.*;
import jdd.util.*;

public class SATTutorial extends TutorialHelper{
	public SATTutorial() {
		super("SATisfiablity", "SAT");
		h2("Satisfiablity (SAT) semi-datastructures  and semi-algorithms :)");

		JDDConsole.out.println("The support for SAT is currently minimal (heck, we don't even know if it is working :). "+
			"Therefore, we will only present the existing APIs in this tutorial...");

		JDDConsole.out.println("<p>Lets just state that <b>we are no experts on this subject</b>. "+
			"We have added these packages just in case <u>you</u> happen to be a SAT-guru...");


		h3("Basic data structures");
		JDDConsole.out.println("Skip this part if you don't care about the internal structure of the CNF formulas in JDD.");
		showClass("jdd.sat.Lit");
		showClass("jdd.sat.Var");
		showClass("jdd.sat.Clause");
		showClass("jdd.sat.CNF");


		h3("Basic SAT solvers");

		JDDConsole.out.println("The class <tt>Solver</tt> is the base class for all CNF solvers:");
		showClass("jdd.sat.Solver");
		JDDConsole.out.println("A solution is returned as a vector of integers (int []), where an value of " +
			"0 for position <i>i</i> means that variable <i>i</i> is assigned false, 1 means true and -1 means dont care.");

		JDDConsole.out.println("<p>The current solvers are:");
		showClass("jdd.sat.bdd.BDDSolver");

		JDDConsole.out.println("<p>The BDD solver is very inefficient. The GSAT solver are better implemented, but "+
			" , as we all know, they have some algorithmic weaknesses...");
		showClass("jdd.sat.gsat.GSATSolver");
		showClass("jdd.sat.gsat.GSAT2Solver");

		JDDConsole.out.println("<p>These two performs so bad, they are not worth using anywhere...");
		showClass("jdd.sat.gsat.WalkSATSolver");
		showClass("jdd.sat.gsat.WalkSATSKCSolver");

		JDDConsole.out.println("<p>We just got this to work: the Davis/(Putnam)/Logeman/Loveland algorithm. " +
			"There are no heuristics at all (except for random choices :), so don't expect this " +
			"solver to solve your DAC'05 benchmarks or anything even remotely hard...");

		showClass("jdd.sat.dpll.DPLLSolver");

		JDDConsole.out.println("<p>(we migh add a DP or CalcRes solver soon)");

		h3("Misc. SAT stuff");
		JDDConsole.out.println("<p>You would never guess what this class does ;) ");
		showClass("jdd.sat.DimacsReader");

	}
}
