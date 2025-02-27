package application.wan.bdd.verifier;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import org.ants.jndd.diagram.AtomizedNDD;

import application.wan.bdd.verifier.apkeep.checker.Checker;
import application.wan.bdd.verifier.apkeep.core.Network;

public class DPVerifier_Incre {
    public static boolean getSplitNum = false;
    public static int splitNum = 0;
    public static boolean update_per_acl = false;
    private Network apkeepNetworkModel;
    private Checker apkeepVerifier;

    String network_name;
    ArrayList<String> topo;
    ArrayList<String> edge_ports;
    Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices;

    public DPVerifier_Incre(String network_name, ArrayList<String> topo, ArrayList<String> edge_ports,
            Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices)
            throws IOException {
        this.network_name = network_name;
        this.topo = topo;
        this.edge_ports = edge_ports;
        this.dpDevices = dpDevices;
    }

    FileWriter fw;
    PrintWriter pw;

    public void run(ArrayList<String> forwarding_rules, ArrayList<String> acl_rules) throws IOException {
        fw = new FileWriter("network-decision-diagram/results/WAN/incrementBDD", true);
        pw = new PrintWriter(fw);
        for (int insertNum = 0; insertNum < 2707; insertNum++) {
            runOneTest(insertNum, new ArrayList<>(forwarding_rules), new ArrayList<>(acl_rules));
        }
    }

    public void runOneTest(int insertNum, ArrayList<String> forwarding_rules, ArrayList<String> acl_rules)
            throws IOException {
        ArrayList<String> firstFW = forwarding_rules;
        ArrayList<String> secondFW = new ArrayList<>();
        ArrayList<String> firstACL = acl_rules;
        ArrayList<String> secondACL = new ArrayList<>();
        secondACL.add(firstACL.get(insertNum));
        String currACL = firstACL.get(insertNum);
        firstACL.remove(insertNum);

        apkeepNetworkModel = new Network(network_name);
        apkeepNetworkModel.initializeNetwork(topo, edge_ports, dpDevices);

        long t0 = System.nanoTime();
        apkeepNetworkModel.UpdateBatchRules(firstFW, firstACL);
        getSplitNum = true;
        splitNum = 0;
        long t1 = System.nanoTime();
        apkeepNetworkModel.UpdateBatchRules(secondFW, secondACL);
        long t2 = System.nanoTime();
        getSplitNum = false;
        apkeepVerifier = new Checker(apkeepNetworkModel);
        apkeepVerifier.PropertyCheck();
        long t3 = System.nanoTime();

        // System.out.println(insertNum+" "+(t2-t1)/1000000000.0);
        // apkeepVerifier.printReachSize();
        pw.println(insertNum + " " + (t2 - t1) / 1000000.0 + "ms" + " " + splitNum);
        pw.flush();
        pw.println(currACL);
        pw.flush();
    }
}