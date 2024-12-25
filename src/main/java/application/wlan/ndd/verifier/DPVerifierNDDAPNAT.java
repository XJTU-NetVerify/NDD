package application.wlan.ndd.verifier;

import application.wlan.ndd.verifier.apkeep.checker.CheckerNDDAPNAT;
import application.wlan.ndd.verifier.apkeep.core.NetworkNDDAPNAT;
import ndd.jdd.diagram.AtomizedNDD;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DPVerifierNDDAPNAT extends DPVerifierNDDAP {
    private NetworkNDDAPNAT apkeepNetworkModel;
    private CheckerNDDAPNAT apkeepVerifier;

    public DPVerifierNDDAPNAT(String network_name, ArrayList<String> topo, ArrayList<String> edge_ports,
            Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices, ArrayList<String> NAT_rules)
            throws IOException {
        super();
        apkeepNetworkModel = new NetworkNDDAPNAT(network_name);
        apkeepNetworkModel.initializeNetwork(topo, edge_ports, dpDevices, NAT_rules);
    }

    public void run(ArrayList<String> forwarding_rules, ArrayList<String> acl_rules, ArrayList<String> NAT_rules) throws IOException{
        long t0 = System.nanoTime();

        apkeepNetworkModel.UpdateBatchRules(forwarding_rules, acl_rules, NAT_rules);

        long t1 = System.nanoTime();

        AtomizedNDD.enableCaches();

        apkeepVerifier = new CheckerNDDAPNAT(apkeepNetworkModel, false);
        apkeepVerifier.PropertyCheck();

        AtomizedNDD.disableCaches();

        long t2 = System.nanoTime();
        System.out.println("Update Model: " + (t1 - t0) / 1000000000.0);
        System.out.println("Property Check: " + (t2 - t1) / 1000000000.0);
        System.out.println("Total: " + (t2 - t0) / 1000000000.0);

		System.out.println("Total atoms:"+ AtomizedNDD.getAtomsCount());
        System.out.println("reachable pairs:"+apkeepVerifier.ans.size());
    }
}