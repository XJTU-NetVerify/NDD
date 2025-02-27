package application.wan.bdd.verifier.apkeep.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Element;

import application.wan.bdd.exp.EvalDataplaneVerifier;
import application.wan.bdd.verifier.apkeep.element.ForwardElement;
import application.wan.bdd.verifier.apkeep.element.NATElement;
import application.wan.bdd.verifier.common.RewriteRule;
import application.wan.bdd.verifier.common.Utility;
import javafx.util.*;

public class Network_NAT extends application.wan.bdd.verifier.apkeep.core.Network {
	public HashMap<String, HashSet<String>> startEdge;
	public HashMap<String, HashSet<String>> endEdge;

	public Network_NAT(String name) throws IOException {
		super(name);
	}

	public void setEdge() {
		startEdge = new HashMap<>(edge_ports);
		endEdge = new HashMap<>(edge_ports);
	}

	public void UpdateBatchRules(ArrayList<String> rules, ArrayList<String> acl_rules,
			ArrayList<String> NAT_rules) throws IOException {

		long t0 = System.nanoTime();
		HashMap<String, HashSet<Integer>> moved_aps = new HashMap<String, HashSet<Integer>>();
		HashMap<String, HashMap<String, HashSet<Pair<String, String>>>> fwd_rules = new HashMap<String, HashMap<String, HashSet<Pair<String, String>>>>();
		for (String linestr : rules) {
			addFWDRule(fwd_rules, linestr);
		}

		System.out.println("insert FW");
		long t1 = System.nanoTime();

		updateFWDRuleBatch(fwd_rules, moved_aps);
		apk.TryMergeAPBatch(moved_aps);

		System.out.println("insert ACL");
		long t2 = System.nanoTime();

		int count = 0;
		for (String linestr : acl_rules) {
			count++;
			// System.out.println(count);
			UpdateACLRule(linestr, moved_aps);
		}

		for (String acl_name : acl_application.keySet()) {
			for (String acl_app : acl_application.get(acl_name)) {
				ACLelements_application.get(acl_app).port_aps_raw = ACLelements.get(acl_name).port_aps_raw;
			}
		}
		ACL_apk.TryMergeAPBatch(moved_aps);

		System.out.println("insert NAT");
		long t3 = System.nanoTime();

		updateNATRules(NAT_rules);
		// apk.TryMergeAPBatchNAT();
		// ACL_apk.TryMergeAPBatchNAT();

		long t4 = System.nanoTime();

		System.out.println("FW:" + (t2 - t1) / 1000000000.0);
		System.out.println("ACL:" + (t3 - t2) / 1000000000.0);
		System.out.println("NAT:" + (t4 - t3) / 1000000000.0);
	}

	public void initializeNetwork(ArrayList<String> l1_links, ArrayList<String> edge_ports,
			Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices,
			ArrayList<String> NAT_rules) throws IOException {
		InitializeAPK();
		constructTopology(l1_links);
		if (name.equalsIgnoreCase("pd")) {
			for (int num = 1; num <= 1646; num++) {
				String device_name = "config" + num;
				if (!FWelements.containsKey(device_name)) {
					ForwardElement e = new ForwardElement(device_name);
					FWelements.put(device_name, e);
					e.SetAPC(apk);
					e.Initialize();
				}
			}
		}
		setEdgePorts(edge_ports);
		setEndHosts(edge_ports);
		// addHostsToTopology();
		parseACLConfigs(dpDevices);
		if (name.equals("st")) {
			parseVLAN("/data/zcli-data/st/vlan_ports");
		}
		setEdge();
		AddNATNodes(NAT_rules);
		apk.Initialize();
		if (EvalDataplaneVerifier.divideACL) {
			ACL_apk.Initialize();
		}
	}

	public void AddNATNodes(ArrayList<String> NAT_rules) {
		for (String rule : NAT_rules) {
			String[] tokens = rule.split(" ");
			String dname = tokens[2];
			String port = tokens[3];
			String NATname = "NAT" + dname + "_" + tokens[4];
			addNATNode(NATname);
			if (tokens[4].equals("in")) {
				AddOneWayLink(NATname, "default", dname, port);
				startEdge.get(dname).remove(port);
				if (startEdge.get(dname).size() == 0)
					startEdge.remove(dname);
				HashSet<String> portSet = new HashSet<>();
				portSet.add("inport");
				startEdge.put(NATname, portSet);
			} else {
				AddOneWayLink(dname, port, NATname, "inport");
				endEdge.get(dname).remove(port);
				if (endEdge.get(dname).size() == 0)
					endEdge.remove(dname);
				HashSet<String> portSet = new HashSet<>();
				portSet.add("default");
				endEdge.put(NATname, portSet);
			}
		}
	}

	public void addNATNode(String device_name) {
		if (!NATelements.containsKey(device_name)) {
			NATElement e = new NATElement(device_name);
			NATelements.put(device_name, e);
			e.SetAPC(apk, ACL_apk);
			e.Initialize();
		}
	}

	public void updateNATRules(ArrayList<String> NAT_rules) {
		int count = 0;
		for (String rule : NAT_rules) {
			count++;
			// System.out.println("nat rule "+count);
			// printPredicate();
			UpdateNATRule(rule);
			apk.TryMergeAPBatchNAT();
			ACL_apk.TryMergeAPBatchNAT();
		}
	}

	public void UpdateNATRule(String linestr) {
		String[] tokens = linestr.split(" ");
		NATElement e = NATelements.get("NAT" + tokens[2] + "_" + tokens[4]);

		long old_src_prefix;
		int old_src_len;
		long old_dst_prefix;
		int old_dst_len;
		long new_src_prefix;
		int new_src_len;
		long new_dst_prefix;
		int new_dst_len;

		if (tokens[5].equals("any")) {
			old_src_prefix = Utility.IPStringToLong("0.0.0.0");
			old_src_len = 0;
		} else {
			old_src_prefix = Utility.IPStringToLong(tokens[5]);
			old_src_len = 32;
		}

		if (tokens[6].equals("any")) {
			old_dst_prefix = Utility.IPStringToLong("0.0.0.0");
			old_dst_len = 0;
		} else {
			old_dst_prefix = Utility.IPStringToLong(tokens[6]);
			old_dst_len = 32;
		}

		if (tokens[7].equals("any")) {
			new_src_prefix = Utility.IPStringToLong("0.0.0.0");
			new_src_len = 0;
		} else {
			new_src_prefix = Utility.IPStringToLong(tokens[7]);
			new_src_len = 32;
		}

		if (tokens[8].equals("any")) {
			new_dst_prefix = Utility.IPStringToLong("0.0.0.0");
			new_dst_len = 0;
		} else {
			new_dst_prefix = Utility.IPStringToLong(tokens[8]);
			new_dst_len = 32;
		}

		RewriteRule r = new RewriteRule(old_src_prefix, old_src_len, old_dst_prefix, old_dst_len, new_src_prefix,
				new_src_len, new_dst_prefix, new_dst_len, tokens[5] + "_" + tokens[6], 65535, bdd_engine);

		ArrayList<ChangeItem> change_set = new ArrayList<>();

		if (tokens[0].equals("+")) {
			change_set = e.InsertRewriteRule(r);
		}

		// for(ChangeItem c : change_set)
		// {
		// System.out.println(c.from_port+" "+c.to_port+" "+c.delta);
		// }

		e.UpdatePortPredicateMap(change_set, null, null);

		// System.out.println(e.rewrite_table);
		// System.out.println(e.ACL_rewrite_table);
	}

	public void printPredicate() {
		String device = "config567";
		String port = "TenGigabitEthernet3/8";
		ForwardElement d = FWelements.get(device);
		HashSet<Integer> aps = d.get_port_aps(port);
		int sum = bdd_engine.OrInBatch(aps);
		System.out.println(bdd_engine.getBDD().satCount(sum));
	}
}