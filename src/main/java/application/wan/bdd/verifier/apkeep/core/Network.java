package application.wan.bdd.verifier.apkeep.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import java.io.FileNotFoundException;
import java.io.PrintWriter;

import application.wan.bdd.exp.EvalDataplaneVerifier;
import application.wan.bdd.verifier.DPVerifier;
import application.wan.bdd.verifier.apkeep.checker.TranverseNode;
import application.wan.bdd.verifier.apkeep.element.ACLElement;
import application.wan.bdd.verifier.apkeep.element.Element;
import application.wan.bdd.verifier.apkeep.element.ForwardElement;
import application.wan.bdd.verifier.apkeep.element.NATElement;
import application.wan.bdd.verifier.apkeep.utils.UtilityTools;
import application.wan.bdd.verifier.common.ACLRule;
import application.wan.bdd.verifier.common.BDDACLWrapper;
import application.wan.bdd.verifier.common.PositionTuple;

import application.wan.ndd.exp.EvalDataplaneVerifierNDDAP;
import javafx.util.Pair;

public class Network {

	public String name; // network name

	public HashMap<PositionTuple, HashSet<PositionTuple>> topology; // network topology
	public HashMap<String, HashSet<String>> edge_ports;
	public HashSet<String> end_hosts;

	/*
	 * The Port Predicate Map Each element has a set of ports, each of which is
	 * guarded by a set of predicates
	 */
	public HashMap<String, ForwardElement> FWelements;
	public HashMap<String, ACLElement> ACLelements;
	public HashMap<String, ACLElement> ACLelements_application;
	public HashMap<String, NATElement> NATelements;
	/*
	 * The BDD data structure for encoding packet sets with Boolean formula
	 */
	public BDDACLWrapper bdd_engine;

	/*
	 * The key data structure that handles the split and merge of predicates
	 */
	public APKeeper apk;
	public APKeeper ACL_apk;

	protected HashSet<String> acl_node_names;
	public HashMap<String, HashSet<String>> acl_application;

	public HashMap<String, HashMap<String, HashSet<String>>> vlan_phy;

	int last_merge_AP_num = 1;

	public Network(String name) throws IOException {
		this.name = name;

		topology = new HashMap<PositionTuple, HashSet<PositionTuple>>();
		edge_ports = new HashMap<String, HashSet<String>>();
		end_hosts = new HashSet<>();

		FWelements = new HashMap<String, ForwardElement>();
		ACLelements = new HashMap<String, ACLElement>();
		ACLelements_application = new HashMap<String, ACLElement>();
		NATelements = new HashMap();

		bdd_engine = new BDDACLWrapper();
		apk = null;
		ACL_apk = null;

		acl_node_names = new HashSet<>();
		acl_application = new HashMap<String, HashSet<String>>();

		vlan_phy = new HashMap<String, HashMap<String, HashSet<String>>>();

		Element.setBDDWrapper(bdd_engine);
		TranverseNode.net = this;
	}

	public void initializeNetwork(ArrayList<String> l1_links, ArrayList<String> edge_ports,
			Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices)
			throws IOException {
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
			parseVLAN("datasets\\wan\\stanford\\st\\vlan_ports");
		}
		apk.Initialize();
		if (EvalDataplaneVerifier.divideACL) {
			ACL_apk.Initialize();
		}
	}

	/**
	 * add ForwardElement from layer ONE topology
	 * 
	 * @param l1_link
	 */
	public void constructTopology(ArrayList<String> l1_link) {
		for (String linestr : l1_link) {
			String[] tokens = linestr.split(" ");
			if (!FWelements.containsKey(tokens[0])) {
				ForwardElement e = new ForwardElement(tokens[0]);
				FWelements.put(e.name, e);
				e.SetAPC(apk);
				e.Initialize();
			}
			if (!FWelements.containsKey(tokens[2])) {
				ForwardElement e = new ForwardElement(tokens[2]);
				FWelements.put(e.name, e);
				e.SetAPC(apk);
				e.Initialize();
			}
			AddOneWayLink(tokens[0], tokens[1], tokens[2], tokens[3]);
			if (name.equals("internet2")) {
				AddOneWayLink(tokens[2], tokens[3], tokens[0], tokens[1]);
			}
		}
	}

	public void setEdgePorts(ArrayList<String> edge_port) {
		edge_ports.clear();
		for (String linestr : edge_port) {
			String[] tokens = linestr.split(" ");

			if (edge_ports.containsKey(tokens[0]) == false) {
				HashSet<String> ports = new HashSet<String>();
				ports.add(tokens[1]);

				edge_ports.put(tokens[0], ports);
			} else {
				HashSet<String> ports = edge_ports.get(tokens[0]);
				ports.add(tokens[1]);
			}
		}
	}

	/**
	 * every device who contains an edge port is an end host, as the network
	 * entrance of packets every edge port is an end host itself, as the network
	 * exit of packets
	 * 
	 * @param edge_ports
	 */
	public void setEndHosts(ArrayList<String> edge_ports) {
		end_hosts = new HashSet<String>();
		for (String port : edge_ports) {
			end_hosts.add(port.split(" ")[0]);
			end_hosts.add(port.split(" ")[0] + "," + port.split(" ")[1]);
		}
	}

	/**
	 * link edge port to its end host in topology
	 */
	public void addHostsToTopology() {
		for (String host : end_hosts) {
			String[] tokens = host.split(",");
			if (tokens.length != 2)
				continue;
			String d1 = host.split(",")[0];
			String p1 = host.split(",")[1];
			AddOneWayLink(d1, p1, host, "inport");
		}
	}

	public void attachACLNodeToTopology(String aclElement, String fwdElement, String port, String direction) {
		String nodeName = aclElement + UtilityTools.split_str + port + UtilityTools.split_str + direction;
		acl_node_names.add(nodeName);
		PositionTuple inpt = new PositionTuple(aclElement, "inport");
		if (direction.equals("in")) {
			PositionTuple pt2 = new PositionTuple(fwdElement, port);
			for (PositionTuple pt1 : topology.keySet()) {
				if (topology.get(pt1).contains(pt2)) {
					topology.get(pt1).remove(pt2);
					topology.get(pt1).add(inpt);
				}
			}
			AddOneWayLink(aclElement, "permit", fwdElement, port);
		} else if (direction.equals("out")) {
			PositionTuple pt1 = new PositionTuple(fwdElement, port);
			if (!topology.containsKey(pt1)) {
				topology.put(pt1, new HashSet<PositionTuple>());
			}
			for (PositionTuple pt2 : topology.get(pt1)) {
				AddOneWayLink(aclElement, "permit", pt2.getDeviceName(), pt2.getPortName());
			}
			topology.get(pt1).clear();
			topology.get(pt1).add(inpt);
		}
	}

	public void parseACLConfigs(
			Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices) {
		if (dpDevices == null)
			return;
		for (Map.Entry<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> entry : dpDevices
				.entrySet()) {
			String deviceName = entry.getKey();
			// System.out.println(nodeName);
			for (Map.Entry<String, List<Map<String, Map<String, List<Map<String, String>>>>>> contents : entry
					.getValue().entrySet()) {
				if (contents.getKey().equals("acl")) {
					for (Map<String, Map<String, List<Map<String, String>>>> content : (List<Map<String, Map<String, List<Map<String, String>>>>>) contents
							.getValue()) {
						for (Map.Entry<String, Map<String, List<Map<String, String>>>> aclContent : content
								.entrySet()) {
							// System.out.println(aclContent.getKey());
							String aclname = aclContent.getKey();
							String element = deviceName + UtilityTools.split_str + aclname;
							ACLElement e = new ACLElement(element);
							if (EvalDataplaneVerifier.divideACL) {
								ACLelements.put(e.name, e);
								e.SetAPC(ACL_apk);
								e.Initialize();
							} else {
								ACLelements.put(e.name, e);
								e.SetAPC(apk);
								e.Initialize();
							}

							for (Map.Entry<String, List<Map<String, String>>> acl : ((Map<String, List<Map<String, String>>>) aclContent
									.getValue())
									.entrySet()) {
								if (acl.getKey().equals("applications")) {
									for (Map<String, String> binding : (List<Map<String, String>>) acl.getValue()) {
										String intf = binding.get("interface");
										String dir = binding.get("direction");
										String element_app = deviceName + UtilityTools.split_str + aclname
												+ UtilityTools.split_str + intf + UtilityTools.split_str + dir;
										HashSet<String> subset = acl_application.get(element);
										if (subset == null) {
											subset = new HashSet<String>();
										}
										subset.add(element_app);
										acl_application.put(element, subset);
										ACLElement acl_element = new ACLElement(element_app);
										ACLelements_application.put(acl_element.name, acl_element);
										// acl_element.SetAPC(null);
										// acl_element.Initialize();
										attachACLNodeToTopology(element_app, deviceName, intf, dir);
									}
								}
							}
						}
					}
				}
			}
			// System.out.println();
		}
	}

	/*
	 * Network topology
	 */
	public void AddOneWayLink(String d1, String p1, String d2, String p2) {
		PositionTuple pt1 = new PositionTuple(d1, p1);
		PositionTuple pt2 = new PositionTuple(d2, p2);
		// links are one way
		if (topology.containsKey(pt1)) {
			topology.get(pt1).add(pt2);
		} else {
			HashSet<PositionTuple> newset = new HashSet<PositionTuple>();
			newset.add(pt2);
			topology.put(pt1, newset);
		}
	}

	public void parseVLAN(String input_path) throws IOException {
		ArrayList<String> lines = UtilityTools.readFile(input_path);
		for (String line : lines) {
			String[] tokens = line.split(" ");
			String device = tokens[0];
			String vlan = tokens[1];
			HashMap<String, HashSet<String>> subMap = vlan_phy.get(device);
			if (subMap == null)
				subMap = new HashMap<String, HashSet<String>>();
			HashSet<String> subSet = subMap.get(vlan);
			if (subSet == null)
				subSet = new HashSet<String>();
			for (int curr = 2; curr < tokens.length; curr++) {
				subSet.add(tokens[curr]);
			}
			subMap.put(vlan, subSet);
			vlan_phy.put(device, subMap);
		}
	}

	/*
	 * Initialize one instance of APKeeper
	 */
	public void InitializeAPK() {
		apk = new APKeeper(bdd_engine, true);
		if (EvalDataplaneVerifier.divideACL) {
			ACL_apk = new APKeeper(bdd_engine, false);
		}

		for (ForwardElement e : FWelements.values()) {
			e.SetAPC(apk);
			e.Initialize();
		}

		if (EvalDataplaneVerifier.divideACL) {
			for (ACLElement e : ACLelements.values()) {
				e.SetAPC(ACL_apk);
				e.Initialize();
			}
		} else {
			for (ACLElement e : ACLelements.values()) {
				e.SetAPC(apk);
				e.Initialize();
			}
		}

		apk.AP.add(bdd_engine.BDDTrue);
		if (EvalDataplaneVerifier.divideACL) {
			ACL_apk.AP.add(bdd_engine.BDDTrue);
		}

		apk.MergeAP = true;
		if (EvalDataplaneVerifier.divideACL) {
			ACL_apk.MergeAP = true;
		}
	}

	/*
	 * Process different types of rule update
	 */
	public HashMap<String, HashSet<Integer>> UpdateBatchRules(ArrayList<String> rules, ArrayList<String> acl_rules)
			throws IOException {
		long t0 = System.nanoTime();
		HashMap<String, HashSet<Integer>> moved_aps = new HashMap<String, HashSet<Integer>>();
		HashMap<String, HashMap<String, HashSet<Pair<String, String>>>> fwd_rules = new HashMap<String, HashMap<String, HashSet<Pair<String, String>>>>();
		for (String linestr : rules) {
			addFWDRule(fwd_rules, linestr);
		}

		long t1 = System.nanoTime();

		int acl_count = 0;
		if (!DPVerifier.update_per_acl) {
			for (String linestr : acl_rules) {
				acl_count++;
				UpdateACLRule(linestr, moved_aps);
			}
		} else {
			for (String linestr : acl_rules) {
				acl_count++;
				UpdateACLRule(linestr, moved_aps);
			}
		}

		for (String acl_name : acl_application.keySet()) {
			for (String acl_app : acl_application.get(acl_name)) {
				ACLelements_application.get(acl_app).port_aps_raw = ACLelements.get(acl_name).port_aps_raw;
			}
		}
		if (EvalDataplaneVerifier.divideACL) {
			ACL_apk.TryMergeAPBatch(moved_aps);
		} else {
			apk.TryMergeAPBatch(moved_aps);
		}

		long t2 = System.nanoTime();

		/*
		 * batched model update
		 */
		updateFWDRuleBatch(fwd_rules, moved_aps);
		apk.TryMergeAPBatch(moved_aps);

		long t3 = System.nanoTime();

		System.out.println("Atoms of forwarding rules: " + apk.AP.size());
		if (EvalDataplaneVerifier.divideACL) {
			System.out.println("Atoms of ACL rules: " + ACL_apk.AP.size());
		}
		System.out.println("FW Time:" + (t3 - t2) / 1000000000.0);
		System.out.println("ACL Time:" + (t2 - t1) / 1000000000.0);

		return moved_aps;
	}

	private void OutputACLPredicate() throws IOException {
		FileWriter fw = new FileWriter("results\\" + name + "\\ACLPredicateSatCountBDD.txt", false);
		PrintWriter pw = new PrintWriter(fw);
		for (String elementName : acl_application.keySet()) {
			ACLElement aclElement = ACLelements.get(elementName);
			for (Map.Entry<String, HashSet<Integer>> entry : aclElement.port_aps_raw.entrySet()) {
				String portName = entry.getKey();
				HashSet<Integer> atoms = entry.getValue();
				int predicate = 0;
				for (int atom : atoms) {
					predicate = bdd_engine.orto(predicate, atom);
				}
				pw.println(elementName + " " + portName + " " + bdd_engine.getBDD().satCount(predicate));
				bdd_engine.getBDD().deref(predicate);
			}
		}
		pw.flush();
	}

	public static ArrayList<String> parseDumpedJson(
			Map<String, Map<String, List<Map<String, Map<String, List<String>>>>>> dpDevices) {
		ArrayList<String> parsed_rules = new ArrayList<>();
		for (Map.Entry<String, Map<String, List<Map<String, Map<String, List<String>>>>>> entry : dpDevices
				.entrySet()) {
			String deviceName = entry.getKey();
			// System.out.println(nodeName);
			for (Map.Entry<String, List<Map<String, Map<String, List<String>>>>> contents : entry.getValue()
					.entrySet()) {
				if (contents.getKey().equals("acl")) {
					for (Map<String, Map<String, List<String>>> content : contents.getValue()) {
						for (Map.Entry<String, Map<String, List<String>>> aclContent : content.entrySet()) {
							// System.out.println(aclContent.getKey());
							String aclname = aclContent.getKey();
							String element = deviceName + UtilityTools.split_str + aclname;
							for (Map.Entry<String, List<String>> acl : (aclContent.getValue()).entrySet()) {
								if (acl.getKey().equals("rules")) {
									for (String rule : (List<String>) acl.getValue()) {
										// System.out.println(rule);
										String aclrule = "+ acl " + element + " " + rule;
										parsed_rules.add(aclrule);
									}
								}
							}
						}
					}
				}
			}
			// System.out.println();
		}
		return parsed_rules;
	}

	/**
	 * Reorganize forwarding rules by IP prefix, element, operation and interface
	 * 
	 * @param fwd_rules
	 * @param linestr
	 */
	protected void addFWDRule(HashMap<String, HashMap<String, HashSet<Pair<String, String>>>> fwd_rules,
			String linestr) {
		String token = " ";
		String[] tokens = linestr.split(token);

		String op = tokens[0];
		String element_name = tokens[2];
		if (!FWelements.containsKey(element_name)) {
			return;
		}
		String ipInt = tokens[3];
		String prefixlen = tokens[4];
		String outport = tokens[5];
		String prio = tokens[6];
		String nexthop = tokens[7];

		// /*
		// * filter control plane IP prefix
		// */
		if (nexthop.equals("0.0.0.0") || nexthop.toLowerCase().startsWith("loopback") || nexthop.equals("null")) {
			return;
		}

		/*
		 * firstly, categorize rules by IP prefix
		 */
		String ip = ipInt + "/" + prio;
		HashMap<String, HashSet<Pair<String, String>>> rules = fwd_rules.get(ip);
		if (rules == null) {
			rules = new HashMap<String, HashSet<Pair<String, String>>>();
		}
		/*
		 * secondly, categorize rules by Device
		 */
		HashSet<Pair<String, String>> actions = rules.get(element_name);

		if (true) {
			actions = new HashSet<Pair<String, String>>();
		}
		/*
		 * finally, categorize rule by operation/interface
		 */
		Pair<String, String> pair = new Pair<String, String>(op, outport);
		actions.add(pair);
		rules.put(element_name, actions);
		fwd_rules.put(ip, rules);
	}

	protected ArrayList<ChangeItem> UpdateACLRule(String linestr, HashMap<String, HashSet<Integer>> moved_aps) {
		String[] tokens = linestr.split(" ");

		ACLElement e = (ACLElement) ACLelements.get(tokens[2]);
		if (e == null) {
			return null;
		}
		String[] tempVec = tokens[2].split(UtilityTools.split_str);
		String ACLstr;

		ACLstr = "accessList " + tempVec[1] + " " + tokens[3] + " " + tokens[4] + " " + tokens[5] + " " + tokens[6]
				+ " " + tokens[7] + " " + tokens[8] + " " + tokens[9] + " " + tokens[10] + " " + tokens[11] + " "
				+ tokens[12] + " " + tokens[13] + " " + tokens[15];

		ACLRule r = new ACLRule(ACLstr);

		/*
		 * compute change tuple
		 */
		ArrayList<ChangeItem> change_set = null;
		if (tokens[0].equals("+")) {
			change_set = e.InsertACLRule(r);
		} else if (tokens[0].equals("-")) {
			change_set = e.RemoveACLRule(r);
		}

		/*
		 * update PPM
		 */
		e.UpdatePortPredicateMap(change_set, linestr, moved_aps);

		ACL_apk.TryMergeAPBatch(moved_aps);

		return change_set;
	}

	protected void updateFWDRuleBatch(HashMap<String, HashMap<String, HashSet<Pair<String, String>>>> fwd_rules,
			HashMap<String, HashSet<Integer>> moved_aps) {
		long t0 = System.nanoTime();
		ArrayList<String> updated_prefix = new ArrayList<String>();
		HashSet<String> updated_elements = new HashSet<String>();
		for (String ip : fwd_rules.keySet()) {
			updated_prefix.add(ip);
		}

		/*
		 * from longest to shortest
		 */
		Collections.sort(updated_prefix, new sortRulesByPriority());

		long t1 = System.nanoTime();
		// first step - get the change_set & copy_set & remove_set
		HashMap<String, ArrayList<ChangeTuple>> change_set = new HashMap<String, ArrayList<ChangeTuple>>();
		HashMap<String, ArrayList<ChangeTuple>> remove_set = new HashMap<String, ArrayList<ChangeTuple>>();
		HashMap<String, ArrayList<ChangeTuple>> copyto_set = new HashMap<String, ArrayList<ChangeTuple>>();
		for (String ip : updated_prefix) {
			for (String element_name : fwd_rules.get(ip).keySet()) {
				HashSet<Pair<String, String>> actions = fwd_rules.get(ip).get(element_name);

				HashSet<String> to_ports = new HashSet<String>();
				HashSet<String> from_ports = new HashSet<String>();

				for (Pair<String, String> pair : actions) {
					if (pair.getKey().equals("+")) {
						to_ports.add(pair.getValue());
					} else if (pair.getKey().equals("-")) {
						from_ports.add(pair.getValue());
					}
				}

				// filter ports remained unchange
				HashSet<String> retained = new HashSet<String>(to_ports);
				retained.retainAll(from_ports);
				if (!retained.isEmpty()) {
					to_ports.removeAll(retained);
					from_ports.removeAll(retained);
				}
				ForwardElement e = (ForwardElement) FWelements.get(element_name);
				// if(e == null) {
				// System.err.println("Forwarding element " + element_name + " not found");
				// System.exit(1);
				// }
				e.updateFWRuleBatch(ip, to_ports, from_ports, change_set, copyto_set, remove_set);
				updated_elements.add(element_name);
			}
		}

		long t2 = System.nanoTime();

		HashMap<Integer, HashSet<Integer>> pred_aps = new HashMap<Integer, HashSet<Integer>>();
		HashMap<Integer, HashMap<String, HashSet<String>>> remove_ports = new HashMap<Integer, HashMap<String, HashSet<String>>>();
		HashMap<Integer, HashMap<String, HashSet<String>>> add_ports = new HashMap<Integer, HashMap<String, HashSet<String>>>();
		for (String element_name : updated_elements) {
			ForwardElement e = (ForwardElement) FWelements.get(element_name);
			if (e == null) {
				System.err.println("Forwarding element " + element_name + " not found");
				System.exit(1);
			}
			e.UpdatePortPredicateMap(change_set.get(element_name), copyto_set.get(element_name),
					remove_set.get(element_name), moved_aps, pred_aps, remove_ports, add_ports);
		}
		long t3 = System.nanoTime();
		for (int ap : remove_ports.keySet()) {
			if (add_ports.containsKey(ap)) {
				apk.UpdateTransferAPBatch(ap, remove_ports.get(ap), add_ports.get(ap));
			} else {
				apk.UpdateTransferAPBatch(ap, remove_ports.get(ap), new HashMap<String, HashSet<String>>());
			}
		}
		for (int ap : add_ports.keySet()) {
			if (remove_ports.containsKey(ap))
				continue;
			apk.UpdateTransferAPBatch(ap, new HashMap<String, HashSet<String>>(), add_ports.get(ap));
		}
		long t4 = System.nanoTime();
	}

	public HashMap<PositionTuple, HashSet<PositionTuple>> getTopology() {
		return topology;
	}

	public HashMap<String, ForwardElement> getFWElements() {
		return FWelements;
	}

	public HashMap<String, ACLElement> getACLElements() {
		return ACLelements;
	}

	public HashSet<String> getEndHosts() {
		return end_hosts;
	}

	public HashSet<String> getACLNodes() {
		return acl_node_names;
	}

	public long getAPNum() {
		return apk.getAPNum();
	}

	public void writeReachabilityMatrix(String file,
			Hashtable<String, Hashtable<String, HashSet<Integer>>> reachable_matrix) throws IOException {
		File output_file = new File(file);
		FileWriter output_writer = new FileWriter(output_file);
		for (String srcNode : reachable_matrix.keySet()) {
			for (String dstNode : reachable_matrix.get(srcNode).keySet()) {
				if (srcNode.equals(dstNode))
					continue;
				// if(srcNode.split(",").length == 2) continue;
				output_writer.write(srcNode + "->" + dstNode + ":\n");
				output_writer.write(apk.getAPPrefixes(reachable_matrix.get(srcNode).get(dstNode)) + "\n");
			}
		}
		output_writer.close();
	}

	public void writeReachabilityChanges(String file,
			HashMap<Pair<String, String>, HashMap<String, HashSet<Integer>>> reach_change) throws IOException {
		File output_file = new File(file);
		FileWriter output_writer = new FileWriter(output_file);
		for (Pair<String, String> host_pair : reach_change.keySet()) {
			HashSet<Integer> added_aps = reach_change.get(host_pair).get("+");
			HashSet<Integer> removed_aps = reach_change.get(host_pair).get("-");

			if (added_aps != null && removed_aps != null) {
				HashSet<Integer> retained_aps = new HashSet<>(added_aps);
				retained_aps.retainAll(removed_aps);

				added_aps.removeAll(retained_aps);
				removed_aps.removeAll(retained_aps);
			}
			if (added_aps != null && !added_aps.isEmpty()) {
				output_writer.write("+ " + host_pair.getKey() + "->" + host_pair.getValue() + ": "
						+ apk.getAPPrefixes(added_aps) + "\n");
			}
			if (removed_aps != null && !removed_aps.isEmpty()) {
				output_writer.write("- " + host_pair.getKey() + "->" + host_pair.getValue() + ": "
						+ apk.getAPPrefixes(removed_aps) + "\n");
			}
		}
		output_writer.close();
	}

	public ArrayList<String> getReachabilityChanges(
			HashMap<Pair<String, String>, HashMap<String, HashSet<Integer>>> reach_change) {
		ArrayList<String> changes = new ArrayList<>();
		for (Map.Entry<Pair<String, String>, HashMap<String, HashSet<Integer>>> entry : reach_change.entrySet()) {
			Pair<String, String> host_pair = entry.getKey();
			HashSet<Integer> added_aps = entry.getValue().get("+");
			HashSet<Integer> removed_aps = entry.getValue().get("-");

			if (added_aps != null && removed_aps != null) {
				HashSet<Integer> retained_aps = new HashSet<>(added_aps);
				retained_aps.retainAll(removed_aps);

				added_aps.removeAll(retained_aps);
				removed_aps.removeAll(retained_aps);
			}
			if (added_aps != null && !added_aps.isEmpty()) {
				changes.add(
						"+ " + host_pair.getKey() + "->" + host_pair.getValue() + ": " + apk.getAPPrefixes(added_aps));
			}
			if (removed_aps != null && !removed_aps.isEmpty()) {
				changes.add("- " + host_pair.getKey() + "->" + host_pair.getValue() + ": "
						+ apk.getAPPrefixes(removed_aps));
			}
		}
		return changes;
	}
}

class sortRulesByPriority implements Comparator<Object> {
	@Override
	public int compare(Object o1, Object o2) {
		String ip1 = (String) o1;
		String ip2 = (String) o2;
		int p1 = Integer.valueOf(ip1.split("/")[1]);
		int p2 = Integer.valueOf(ip2.split("/")[1]);
		return p2 - p1;
	}
}
