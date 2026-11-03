package application.nqueen;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class NQueensExp {
    public static void main(String[] args) throws IOException {
        Path output = Paths.get("results", "NQueens", "performance");
        Files.createDirectories(output.getParent());
        try (PrintWriter printWriter = new PrintWriter(Files.newBufferedWriter(output))) {
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
        }
    }
}
