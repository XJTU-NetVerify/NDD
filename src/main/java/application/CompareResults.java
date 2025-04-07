package application;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class CompareResults {
    public static String basePath;
    public static String bddResultFile;
    public static String nddResultFile;

    private static void storeIntoMap(BufferedReader bufferedReader, HashMap<String, String> map) throws IOException {
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            String [] tokens = line.split(" ");
            String lastToken = tokens[tokens.length - 1];
            int lengthOfLastToken = lastToken.length();
            String otherTokens = line.substring(0, line.length() - lengthOfLastToken - 1);
            map.put(otherTokens, lastToken);
        }
    }

    private static void compareContentInMap(HashMap<String, String> bddMap, HashMap<String, String> nddMap) {
        System.out.println("In bdd but not in ndd:");
        for (Map.Entry<String, String> entry : bddMap.entrySet()) {
            if (!nddMap.containsKey(entry.getKey())) {
                System.out.println(entry.getKey() + " " + entry.getValue());
            }
        }
        System.out.println("In ndd but not in bdd:");
        for (Map.Entry<String, String> entry : nddMap.entrySet()) {
            if (!bddMap.containsKey(entry.getKey())) {
                System.out.println(entry.getKey() + " " + entry.getValue());
            }
        }
        System.out.println("Different values:");
        for (Map.Entry<String, String> entry : bddMap.entrySet()) {
            if (nddMap.containsKey(entry.getKey()) && !entry.getValue().equals(nddMap.get(entry.getKey()))) {
                System.out.println(entry.getKey() + " " + entry.getValue());
                System.out.println(entry.getKey() + " " + nddMap.get(entry.getKey()));
            }
        }
    }

    public static void compareByKeyValue() throws IOException {
        BufferedReader bddReader = new BufferedReader(new FileReader(basePath + "\\" + bddResultFile));
        BufferedReader nddReader = new BufferedReader(new FileReader(basePath + "\\" + nddResultFile));
        HashMap<String, String> bddMap = new HashMap<>();
        HashMap<String, String> nddMap = new HashMap<>();
        storeIntoMap(bddReader, bddMap);
        storeIntoMap(nddReader, nddMap);
        compareContentInMap(bddMap, nddMap);
    }

    public static void compareByLine() throws IOException {
        BufferedReader bddReader = new BufferedReader(new FileReader(basePath + "\\" + bddResultFile));
        BufferedReader nddReader = new BufferedReader(new FileReader(basePath + "\\" + nddResultFile));
        int lineNumber = 0;
        String bddLine;
        while ((bddLine = bddReader.readLine()) != null) {
            lineNumber++;
            String nddLine = nddReader.readLine();
            if (!bddLine.equals(nddLine)) {
                System.out.println(lineNumber);
                System.out.println(bddLine);
                System.out.println(nddLine);
                break;
            }
        }
    }

    public static void main(String[] args) throws IOException {
        basePath = "results\\pd";
        bddResultFile = "ACLPredicateSatCountBDD.txt";
        nddResultFile = "ACLPredicateSatCountNDD.txt";

//        basePath = "results\\pd";
//        bddResultFile = "config842_JW5an8sL3BDD.txt";
//        nddResultFile = "config842_JW5an8sL3NDD.txt";
        compareByKeyValue();

//        basePath = "results\\pd";
//        bddResultFile = "config842_JW5an8sL3BDDOperation.txt";
//        nddResultFile = "config842_JW5an8sL3NDDOperation.txt";
//        compareByLine();
    }
}
