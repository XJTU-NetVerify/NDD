package application.wlan.bdd.verifier;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import application.wlan.bdd.verifier.apkeep.checker.CheckerNAT;
import application.wlan.bdd.verifier.apkeep.core.Network_NAT;

public class DPVerifierNAT {
    private Network_NAT apkeepNetworkModel;
    private CheckerNAT apkeepVerifier;

    public DPVerifierNAT(String network_name, ArrayList<String> topo, ArrayList<String> edge_ports,
            Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices, ArrayList<String> NAT_rules)
            throws IOException {
        apkeepNetworkModel = new Network_NAT(network_name);
        apkeepNetworkModel.initializeNetwork(topo, edge_ports, dpDevices, NAT_rules);
    }

    public void run(ArrayList<String> forwarding_rules, ArrayList<String> acl_rules, ArrayList<String> NAT_rules) throws IOException{
        long t0 = System.nanoTime();

        apkeepNetworkModel.UpdateBatchRules(forwarding_rules, acl_rules, NAT_rules);

        long t1 = System.nanoTime();

        apkeepVerifier = new CheckerNAT(apkeepNetworkModel);
        apkeepVerifier.PropertyCheck();

        long t2 = System.nanoTime();
        System.out.println("Update Model: " + (t1 - t0) / 1000000000.0);
        System.out.println("Property Check: " + (t2 - t1) / 1000000000.0);
        System.out.println("Total: " + (t2 - t0) / 1000000000.0);

        System.out.println("FW AP:"+apkeepNetworkModel.apk.AP.size());
        System.out.println("ACL AP:"+apkeepNetworkModel.ACL_apk.AP.size());
        System.out.println("reachable pairs:"+apkeepVerifier.ans.size());
        // DotInBatch(apkeepNetworkModel.apk.AP, "FW");
        // DotInBatch(apkeepNetworkModel.ACL_apk.AP, "ACL");
        // apkeepVerifier.printReachSize();
        // apkeepVerifier.Output_Result_ACL("/home/zcli/lzc/Field-Decision-Network/apkatra-main/output/test/APKeepNAT");
        // apkeepVerifier.OutputReachBDD();
    }

    public void DotInBatch(HashSet<Integer> aps, String middleStr)
    {
        int count=0;
        for(int ap : aps)
        {
            count++;
            System.out.println(count+" "+ap);
            apkeepNetworkModel.bdd_engine.getBDD().printDot("/home/zcli/lzc/Field-Decision-Network/apkatra-main/output/test/"+middleStr+"_"+count, ap);
        }
    }
}
