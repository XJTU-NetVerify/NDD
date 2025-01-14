package application.wlan.ndd.verifier;

import application.wlan.ndd.verifier.apkeep.checker.CheckerNDDAP;
import application.wlan.ndd.verifier.apkeep.checker.Checker_stanford_AP;
import application.wlan.ndd.verifier.apkeep.core.NetworkNDDAP;
import ndd.jdd.diagram.AtomizedNDD;
import ndd.jdd.diagram.NDD;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class DPVerifierNDDAP extends DPVerifierNDDPred {
    public NetworkNDDAP apkeepNetworkModel;
	public CheckerNDDAP apkeepVerifier;
	public Checker_stanford_AP apkeepVerifierST;

	public DPVerifierNDDAP()
	{
		AtomizedNDD.initAtomizedNDD(1000000, 1000000, 1000000, 100000);
	}

    public DPVerifierNDDAP(String network_name, ArrayList<String> topo, ArrayList<String> edge_ports,
	Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices) throws IOException{
        this();
    	apkeepNetworkModel = new NetworkNDDAP(network_name);
    	apkeepNetworkModel.initializeNetwork(topo, edge_ports, dpDevices);
    }

    public void run(ArrayList<String> forwarding_rules, ArrayList<String> acl_rules) throws IOException{
		long t0 = System.nanoTime();
		// forwarding_rules = new ArrayList<String>();
		HashMap<String,HashSet<Integer>> moved_aps = apkeepNetworkModel.UpdateBatchRules(forwarding_rules, acl_rules);
		long t1 = System.nanoTime();

		// System.out.println("used node:"+apkeepNetworkModel.bdd_engine.getBDD().countUsedNode());

		AtomizedNDD.enableCaches();

		if(apkeepNetworkModel.name.equals("st"))
		{
			apkeepVerifierST = new Checker_stanford_AP(apkeepNetworkModel, false);
			apkeepVerifierST.PropertyCheck();
			System.out.println(apkeepVerifierST.ans.size());
		}
		else
		{
			apkeepVerifier = new CheckerNDDAP(apkeepNetworkModel, false);
			apkeepVerifier.PropertyCheck();
			// apkeepVerifier.printReachSize();
			System.out.println(apkeepVerifier.ans.size());
	
			// apkeepVerifier = new CheckerNDDAP(apkeepNetworkModel, true);
			// apkeepVerifier.CheckPerEdge();
			// System.out.println(apkeepVerifier.ans.size());
		}

		AtomizedNDD.disableCaches();

		long t2 = System.nanoTime();
        System.out.println("Property Check: " + (t2 - t1) / 1000000000.0);
		System.out.println("Total atoms:"+AtomizedNDD.getAtomsCount());
    }
}