package application.nqueens;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.ants.jndd.diagram.NDD;

public class NQuenensBenchmark {
    public static void main(String[] args) throws IOException {
        Path output = Paths.get("results", "nqueens_results");
        Files.createDirectories(output.getParent());
        try (PrintWriter printWriter = new PrintWriter(Files.newBufferedWriter(output))) {
            printWriter.println("BDD Solution");
            printWriter.println("N\tTime (s)\tSatCount\tNodes");
            System.out.println("BDD Solution");
            System.out.println("N\tTime (s)\tSatCount\tNodes");
            for (int n = 8; n <= 12; n++) {
                String row = n + BDDSolution.Solution(n);
                printWriter.println(row);
                System.out.println(row);
            }
            NDD.LabelMode[] modes = {
                NDD.LabelMode.BDD, NDD.LabelMode.COMPLEMENTED_BDD, NDD.LabelMode.ZDD
            };
            for (NDD.LabelMode mode : modes) {
                String title = "NDD Solution (" + mode + ")";
                String header = "N\tTime (s)\tSatCount\tNodes";
                printWriter.println();
                printWriter.println(title);
                printWriter.println(header);
                System.out.println();
                System.out.println(title);
                System.out.println(header);
                for (int n = 8; n <= 12; n++) {
                    String row = n + NDDSolution.Solution(n, mode);
                    printWriter.println(row);
                    System.out.println(row);
                }
            }
        }
    }
}
