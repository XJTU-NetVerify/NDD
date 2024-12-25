package application.wlan.ndd.verifier;

import java.io.IOException;
import java.util.*;

import application.wlan.ndd.verifier.apkeep.checker.CheckerNDDPred;
import application.wlan.ndd.verifier.apkeep.core.NetworkNDDPred;
import ndd.jdd.diagram.AtomizedNDD;
import ndd.jdd.diagram.NDD;

public class DPVerifierNDDPred {
	public static int check_method = 1; // 0 use set 1 no cache 2 use limited cache
	public static boolean update_per_acl = false;
    public NetworkNDDPred apkeepNetworkModel;
	public CheckerNDDPred apkeepVerifier;
	public ArrayList<String> policies;

	public DPVerifierNDDPred(){
		AtomizedNDD.initAtomizedNDD(0, 1000000, 100000000, 10000000);
    }

    public DPVerifierNDDPred(String network_name, ArrayList<String> topo, ArrayList<String> edge_ports,
	Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices) throws IOException{
		this();
    	apkeepNetworkModel = new NetworkNDDPred(network_name);
    	apkeepNetworkModel.initializeNetwork(topo, edge_ports, dpDevices);
    }

    public void run(ArrayList<String> forwarding_rules, ArrayList<String> acl_rules) throws IOException{
		long t0 = System.nanoTime();
		// forwarding_rules = new ArrayList<String>();
		HashMap<String,HashSet<Integer>> moved_aps = apkeepNetworkModel.UpdateBatchRules(forwarding_rules, acl_rules);
		long t1 = System.nanoTime();

		// System.out.println("used node:"+apkeepNetworkModel.bdd_engine.getBDD().countUsedNode());

		apkeepVerifier = new CheckerNDDPred(apkeepNetworkModel, false);
		apkeepVerifier.PropertyCheck();
		// apkeepVerifier.printReachSize();
		System.out.println(apkeepVerifier.ans.size());

		// apkeepVerifier = new CheckerNDDPred(apkeepNetworkModel, true);
		// apkeepVerifier.CheckPerEdge();
		// System.out.println(apkeepVerifier.ans.size());

		long t2 = System.nanoTime();
		System.out.println("Property Check: " + (t2 - t1) / 1000000000.0);
    }

	public ArrayList<String> getPolicies() {
		return policies;
	}

    public void dumpResults(String outputPath) throws IOException{
    	// apkeepNetworkModel.writeReachabilityChanges(outputPath, apkeepVerifier.getChanges());
    }

}
