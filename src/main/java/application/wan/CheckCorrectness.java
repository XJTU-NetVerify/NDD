package application.wan;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;

public class CheckCorrectness {
    private static void compareFile(String fileBDD, String fileNDD) throws IOException {
        FileInputStream fis = new FileInputStream(fileBDD);
        BufferedReader br = new BufferedReader(new InputStreamReader(fis));
        HashSet<String> linesBDD = new HashSet<>();
        String line = null;
        while ((line = br.readLine()) != null) {
            linesBDD.add(line);
        }
        br.close();

        fis = new FileInputStream(fileNDD);
        br = new BufferedReader(new InputStreamReader(fis));
        HashSet<String> linesNDD = new HashSet<>();
        while ((line = br.readLine()) != null) {
            linesNDD.add(line);
        }
        br.close();

        System.out.println("BDD reachable pairs: " + linesBDD.size());
        System.out.println("NDD reachable pairs: " + linesNDD.size());

        System.out.println("BDD - NDD");
        for (String lineBDD : linesBDD) {
            if (!linesNDD.contains(lineBDD)) {
                System.out.println(lineBDD);
            }
        }

        System.out.println("NDD - BDD");
        for (String lineNDD : linesNDD) {
            if (!linesBDD.contains(lineNDD)) {
                System.out.println(lineNDD);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        compareFile("network-decision-diagram/results/WAN/reachableBDD",
                "network-decision-diagram/results/WAN/reachableNDD");
    }
}
