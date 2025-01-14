package application.wlan.ndd.verifier.apkeep.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.io.FileNotFoundException;
import java.io.PrintWriter;

import application.wlan.ndd.verifier.apkeep.checker.TranverseNode;
import application.wlan.ndd.verifier.apkeep.element.FieldNode;
import application.wlan.ndd.verifier.apkeep.utils.UtilityTools;
import application.wlan.ndd.verifier.common.ACLRule;
import application.wlan.ndd.verifier.common.BDDACLWrapper;
import application.wlan.ndd.verifier.common.PositionTuple;
import org.ants.jndd.diagram.NDD;

import javafx.util.Pair;

public class NetworkNDDPred {

	public String name; // network name

	public HashMap<PositionTuple, HashSet<PositionTuple>> topology; // network topology
	public HashMap<String, HashSet<String>> edge_ports;
	public HashSet<String> end_hosts;

	public HashMap<String, FieldNode> FieldNodes;

	public HashSet<String> acl_node_names;
	public HashMap<String, HashSet<String>> acl_application;

	public HashMap<String, HashMap<String, HashSet<String>>> vlan_phy;

	/*
	 * The BDD data structure for encoding packet sets with Boolean formula
	 */
	public BDDACLWrapper bdd_engine;

	public NetworkNDDPred(String name) throws IOException {
		this.name = name;

		topology = new HashMap<PositionTuple, HashSet<PositionTuple>>();
		edge_ports = new HashMap<String, HashSet<String>>();
		end_hosts = new HashSet<>();

		FieldNodes = new HashMap<String, FieldNode>();

		acl_node_names = new HashSet<String>();
		acl_application = new HashMap<String, HashSet<String>>();

		vlan_phy = new HashMap<String, HashMap<String, HashSet<String>>>();

		bdd_engine = new BDDACLWrapper(NDD.getBDDEngine());

		FieldNode.network = this;
		FieldNode.bdd = bdd_engine;
		TranverseNode.net = this;
	}

	public void initializeNetwork(ArrayList<String> l1_links, ArrayList<String> edge_ports,
			Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices)
			throws IOException {
		System.out.println(name);
		constructTopology(l1_links);
		if (name.equalsIgnoreCase("pd")) {
			for (int num = 1; num <= 1646; num++) {
				String device_name = "config" + num;
				if (!FieldNodes.containsKey(device_name)) {
					addForwardNode(device_name);
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
	}

	/**
	 * add ForwardElement from layer ONE topology
	 * 
	 * @param l1_link
	 */
	public void constructTopology(ArrayList<String> l1_link) {
		for (String linestr : l1_link) {
			String[] tokens = linestr.split(" ");
			if (!FieldNodes.containsKey(tokens[0])) {
				addForwardNode(tokens[0]);
			}
			if (!FieldNodes.containsKey(tokens[2])) {
				addForwardNode(tokens[2]);
			}
			AddOneWayLink(tokens[0], tokens[1], tokens[2], tokens[3]);
			if (name.equals("internet2")) {
				AddOneWayLink(tokens[2], tokens[3], tokens[0], tokens[1]);
			}
		}
	}

	public void addForwardNode(String element) {
		if (!FieldNodes.containsKey(element)) {
			FieldNodes.put(element, new FieldNode(element, this, 0));
		}
	}

	public void addACLNode_deny(String element) {
		if (!FieldNodes.containsKey(element)) {
			FieldNodes.put(element, new FieldNode(element, this, -1));
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
			// System.out.println(aclElement+" "+fwdElement+" "+port+" "+direction);
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
			for (Map.Entry<String, List<Map<String, Map<String, List<Map<String, String>>>>>> contents : entry
					.getValue().entrySet()) {
				if (contents.getKey().equals("acl")) {
					for (Map<String, Map<String, List<Map<String, String>>>> content : contents.getValue()) {
						for (Map.Entry<String, Map<String, List<Map<String, String>>>> aclContent : content
								.entrySet()) {
							String aclname = aclContent.getKey();
							String element = deviceName + UtilityTools.split_str + aclname;
							addACLNode_deny(element);
							for (Map.Entry<String, List<Map<String, String>>> acl : (aclContent.getValue())
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
										addACLNode_deny(element_app);
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

	// public static int count = 0;

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

		for (String linestr : acl_rules) {
			// count++;
			// System.out.println(count);
			// System.out.println(linestr);
			UpdateACLRule(linestr);
		}

		for (String acl_name : acl_application.keySet()) {
			for (String acl_app : acl_application.get(acl_name)) {
				// FieldNodes.get(acl_app).start = FieldNodes.get(acl_name).start;
				FieldNodes.get(acl_app).ports_pred = FieldNodes.get(acl_name).ports_pred;
				FieldNodes.get(acl_app).ports = FieldNodes.get(acl_name).ports;
			}
		}

		long t2 = System.nanoTime();

		updateFWDRuleBatch(fwd_rules, moved_aps);

		long t3 = System.nanoTime();

		System.out.println("FW:" + (t3 - t2) / 1000000000.0);
		System.out.println("ACL:" + (t2 - t1) / 1000000000.0);

		// for(String name : FieldNodes.keySet())
		// {
		// 	FieldNode device = FieldNodes.get(name);
		// 	for(String port : device.ports_pred.keySet())
		// 	{
		// 		NDD pred = device.ports_pred.get(port);
		// 		if(pred.edges != null && pred.edges.size()>1)
		// 		{
		// 			System.out.println(name+" "+port+" "+pred.edges.size());
		// 		}
		// 	}
		// }

		return moved_aps;
	}

	public ArrayList<String> parseDumpedJson(
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
		// if (!FieldNodes.containsKey(element_name)) {
		// 	return;
		// }
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
		// if (actions == null) {
		// actions = new HashSet<Pair<String, String>>();
		// }
		// else
		// {
		// return;
		// }

		if (true) { // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
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

	protected String UpdateACLRule(String linestr) {
		String[] tokens = linestr.split(" ");

		FieldNode e = FieldNodes.get(tokens[2]);
		if (e == null) {
			// System.out.println(tokens[2]);
			return null;
		}

		String[] tempVec = tokens[2].split(UtilityTools.split_str);
		String ACLstr;

		// + acl config10_9UJfHYB6Z6ILnzjpTIKf permit null null 183.15.80.169 null null
		// null null null null null -1 65535
		// + acl pozb_rtr_199 deny 0 255 171.64.201.44 null null null any null null null
		// -1 65535
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
			e.update_ACL(change_set);
		} else if (tokens[0].equals("-")) {
			System.out.println("Remove not implement !");
			// change_set = e.RemoveACLRule(r);
		}
		return tokens[2];
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
				FieldNode e = FieldNodes.get(element_name);
				if(e == null)
                {
                    // System.out.println(element_name);
                    continue;
                }
				e.updateFWRuleBatch(ip, to_ports, from_ports, change_set, copyto_set, remove_set);
				updated_elements.add(element_name);
			}
		}

		long t2 = System.nanoTime();

		for (String element_name : updated_elements) {
			FieldNode e = FieldNodes.get(element_name);
			if (e == null) {
				System.err.println("Forwarding element " + element_name + " not found");
				System.exit(1);
			}
			e.update_FW(change_set.get(element_name), copyto_set.get(element_name));
		}
		long t3 = System.nanoTime();
		// System.out.println((t1 - t0) / 1000000000.0);
		System.out.println((t2 - t1) / 1000000000.0);
		System.out.println((t3 - t2) / 1000000000.0);
	}

	public HashMap<PositionTuple, HashSet<PositionTuple>> getTopology() {
		return topology;
	}

	public HashSet<String> getEndHosts() {
		return end_hosts;
	}

	public HashSet<String> getACLNodes() {
		return acl_node_names;
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
