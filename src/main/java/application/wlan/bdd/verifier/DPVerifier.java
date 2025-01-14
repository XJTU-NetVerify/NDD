package application.wlan.bdd.verifier;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import application.wlan.bdd.verifier.apkeep.checker.Checker;
import application.wlan.bdd.verifier.apkeep.checker.Checker_stanford;
import application.wlan.bdd.verifier.apkeep.core.Network;

public class DPVerifier {
	public static boolean update_per_acl = false;
	private Network apkeepNetworkModel;
	private Checker apkeepVerifier;

    public long dpm_time;
    public long dpv_time;

    public DPVerifier(String network_name, ArrayList<String> topo, ArrayList<String> edge_ports,
	Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices) throws IOException{
    	apkeepNetworkModel = new Network(network_name);
    	apkeepNetworkModel.initializeNetwork(topo, edge_ports, dpDevices);

    	dpm_time=0;
    	dpv_time=0;
    }

    public void run(ArrayList<String> forwarding_rules, ArrayList<String> acl_rules) throws IOException{
		long t0 = System.nanoTime();
    	HashMap<String,HashSet<Integer>> moved_aps = apkeepNetworkModel.UpdateBatchRules(forwarding_rules, acl_rules);
		long t1 = System.nanoTime();
		if(apkeepNetworkModel.name.equals("st"))
		{
			apkeepVerifier = new Checker_stanford(apkeepNetworkModel);
			apkeepVerifier.PropertyCheck();
		}
		else
		{
			apkeepVerifier = new Checker(apkeepNetworkModel);
			apkeepVerifier.PropertyCheck();
		}
    	long t2 = System.nanoTime();
		System.out.println("Update Model: " + (t1-t0)/1000000000.0);
		System.out.println("Property Check: " + (t2-t1)/1000000000.0);
		System.out.println("reachable pairs:" + apkeepVerifier.ans.size());
		// apkeepVerifier.printReachSize();
		// apkeepVerifier.Output_Result("/home/zcli/lzc/Field-Decision-Network/FDN/src/main/java/org/ants/output/"+apkeepNetworkModel.name+"/reach-APKBatch-no-ACL");
		// apkeepVerifier.Output_Result_ACL("/home/zcli/lzc/Field-Decision-Network/FDN/src/main/java/org/ants/output/"+apkeepNetworkModel.name+"/reach-APKBatch");
		// apkeepVerifier.OutputReachBDD();
    }
}
