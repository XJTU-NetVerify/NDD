package experiment;

import application.nqueen.BDDSolution;
import application.nqueen.NDDSolution;

import java.io.FileNotFoundException;
import java.io.PrintWriter;

public class NQueensExp {
    public static void main(String[] args) throws FileNotFoundException {
        PrintWriter printWriter = new PrintWriter("results\\NQueens\\performance");
        printWriter.println("BDD Solution");
        printWriter.println("N\tTime\tSatCount");
        for (int n = 1; n <= 12; n++) {
            printWriter.println(n + BDDSolution.Solution(n));
        }
        printWriter.println();
        printWriter.println("NDD Solution");
        printWriter.println("N\tTime\tSatCount");
        for (int n = 1; n <= 12; n++) {
            printWriter.println(n + NDDSolution.Solution(n));
        }
        printWriter.flush();
    }
}
