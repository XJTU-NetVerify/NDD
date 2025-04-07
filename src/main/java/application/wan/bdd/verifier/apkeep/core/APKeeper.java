package application.wan.bdd.verifier.apkeep.core;

import java.util.*;

import application.wan.bdd.verifier.apkeep.checker.Property;
import application.wan.bdd.verifier.apkeep.element.ACLElement;
import application.wan.bdd.verifier.apkeep.element.Element;
import application.wan.bdd.verifier.apkeep.element.NATElement;
import application.wan.bdd.verifier.apkeep.utils.Parameters;
import application.wan.bdd.verifier.apkeep.utils.UtilityTools;
import application.wan.bdd.verifier.common.BDDACLWrapper;
import application.wan.bdd.verifier.common.PositionTuple;

import jdd.bdd.BDD;

/**
 * Computes Atomic Predicates using BDDs.
 */
public class APKeeper {

	public static boolean MergeAP = false;
	static boolean UpdateReachability = false;
	boolean is_FW;

	static BDDACLWrapper bddengine;
	public HashSet<Integer> AP;

	HashMap<String, Element> elements;

	HashMap<Integer, HashSet<PositionTuple>> AP_EDGE_REF;
	HashMap<String, Integer> PrefixBDDMap;

	// predicateBDD -> count
	HashMap<Integer, Integer> PredicateBDD;
	// aclbdd -> {apbdd1, apbdd2, ...}
	HashMap<Integer, HashSet<Integer>> PredicateREP;

	public HashSet<String> nat_names;

	public HashMap<Integer, HashMap<String, HashSet<String>>> ap_ports;
	HashMap<HashMap<String, HashSet<String>>, HashSet<Integer>> ports_aps;
	HashSet<HashMap<String, HashSet<String>>> ports_to_merge;

	long success_merge_time = 0;
	long fail_merge_time = 0;
	public int mergeable_aps = 0;

	public APKeeper(BDDACLWrapper bddengine, boolean is_FW) {
		this.is_FW = is_FW;
		if (Parameters.PROPERTIES_TO_CHECK.contains(Property.Reachability)) {
			UpdateReachability = true;
		}
		APKeeper.bddengine = bddengine;
		AP = new HashSet<Integer>();
		elements = new HashMap<String, Element>();
		AP_EDGE_REF = new HashMap<Integer, HashSet<PositionTuple>>();
		PrefixBDDMap = new HashMap<String, Integer>();

		nat_names = new HashSet<String>();

		ap_ports = new HashMap<Integer, HashMap<String, HashSet<String>>>();
		ports_aps = new HashMap<HashMap<String, HashSet<String>>, HashSet<Integer>>();

		ports_to_merge = new HashSet<HashMap<String, HashSet<String>>>();

		PredicateBDD = new HashMap<Integer, Integer>();
		PredicateREP = new HashMap<Integer, HashSet<Integer>>();
	}

	public void Initialize() {
		HashSet<Integer> aps = new HashSet<Integer>();
		HashMap<String, HashSet<String>> ports = new HashMap<String, HashSet<String>>();

		for (Element e : elements.values()) {
			String name = e.name;
			HashSet<String> port = new HashSet<String>();

			if (e instanceof ACLElement) {
				port.add("deny");
			} else {
				port.add("default");
			}
			ports.put(name, port);
		}
		AP.add(BDDACLWrapper.BDDTrue);
		ap_ports.put(BDDACLWrapper.BDDTrue, ports);
		aps.add(BDDACLWrapper.BDDTrue);
		ports_aps.put(ports, aps);
	}

	public void AddElement(String dname, Element e) {
		elements.put(dname, e);
	}

	public int GetPrefixBDD(long destip, int prefixlen) {
		String prefix = destip + " " + prefixlen;
		int prefixbdd = BDDACLWrapper.BDDFalse;
		if (PrefixBDDMap.containsKey(prefix)) {
			prefixbdd = PrefixBDDMap.get(prefix);
			bddengine.getBDD().ref(prefixbdd);
		} else {
			prefixbdd = bddengine.encodeDstIPPrefix(destip, prefixlen);
			PrefixBDDMap.put(prefix, prefixbdd);
		}
		return prefixbdd;
	}

	public void RemovePrefixBDD(long destip, int prefixlen) {
		String prefix = destip + " " + prefixlen;
		if (PrefixBDDMap.containsKey(prefix)) {
			PrefixBDDMap.remove(prefix);
		}
	}

	public void UpdateSplitAP(int origin, int parta, int partb) {
		if (!AP.contains(origin)) {
			System.out.println("Error: origin AP " + origin + " not found");
			System.exit(1);
		}

		AP.remove(origin);
		AP.add(parta);
		AP.add(partb);

		if (ap_ports.containsKey(origin)) {
			HashMap<String, HashSet<String>> ports_a = ap_ports.get(origin);
			ap_ports.put(parta, ports_a);
			HashMap<String, HashSet<String>> ports_b = new HashMap<String, HashSet<String>>();
			for (String str : ports_a.keySet()) {
				ports_b.put(str, new HashSet<String>(ports_a.get(str)));
			}
			ap_ports.put(partb, ports_b);

			// update each element's AP set
			for (String elementname : elements.keySet()) {
				if (elements.get(elementname) instanceof NATElement) {
					elements.get(elementname).UpdateAPSetSplit(origin, parta, partb, !is_FW);
				} else {
					elements.get(elementname).UpdateAPSetSplit(origin, parta, partb, true);
				}
			}

			ap_ports.remove(origin);

			if (MergeAP) {
				ports_aps.get(ports_a).remove(origin);
				ports_aps.get(ports_a).add(parta);
				ports_aps.get(ports_a).add(partb);
				mergeable_aps++;
			}
		}

		bddengine.getBDD().ref(parta);
		bddengine.getBDD().ref(partb);
		bddengine.getBDD().deref(origin);
	}

	public void UpdateTransferAP(PositionTuple pt1, PositionTuple pt2, int ap) {
		if (!ap_ports.containsKey(ap)) {
			System.out.println(AP);
			System.out.println(ap_ports);
			System.err.println("Error: AP edge reference not found " + ap);
		}

		HashMap<String, HashSet<String>> ports = ap_ports.get(ap);

		if (!MergeAP) {
			HashSet<String> e_ports = ports.get(pt2.getDeviceName());
			if (e_ports == null) {
				e_ports = new HashSet<String>();
			}
			e_ports.remove(pt1.getPortName());
			e_ports.add(pt2.getPortName());
			ports.put(pt2.getDeviceName(), e_ports);
		} else {
			HashSet<Integer> aps = ports_aps.get(ports);
			aps.remove(ap);

			// the ap set becomes empty, then remove the ports entry
			if (aps.isEmpty()) {
				ports_aps.remove(ports);
			}
			// the ap set is non-empty, then clone the ports
			else {
				mergeable_aps--;

				// the ap set has one ap, then do not merge it
				if (aps.size() == 1) {
					ports_to_merge.remove(ports);
				}

				HashMap<String, HashSet<String>> new_ports = new HashMap<String, HashSet<String>>();
				for (String e_name : ports.keySet()) {
					new_ports.put(e_name, new HashSet<String>(ports.get(e_name)));
				}
				ports = new_ports;
			}

			HashSet<String> e_ports = ports.get(pt2.getDeviceName());
			if (e_ports == null) {
				e_ports = new HashSet<String>();
			}
			e_ports.remove(pt1.getPortName());
			e_ports.add(pt2.getPortName());
			ports.put(pt2.getDeviceName(), e_ports);
			ap_ports.put(ap, ports);

			if (!ports_aps.containsKey(ports)) {
				ports_aps.put(ports, new HashSet<Integer>());
			}
			aps = ports_aps.get(ports);
			if (!aps.isEmpty()) {
				mergeable_aps++;
			}
			aps.add(ap);
			if (aps.size() == 2) {
				ports_to_merge.add(ports);
			}
		}
	}

	public void UpdateTransferAPBatch(int ap, HashMap<String, HashSet<String>> remove_pts,
			HashMap<String, HashSet<String>> add_pts) {
		if (!ap_ports.containsKey(ap)) {
			System.out.println(AP);
			System.out.println(ap_ports);
			System.err.println("Error: AP edge reference not found " + ap);
		}

		HashMap<String, HashSet<String>> ports = ap_ports.get(ap);
		if (!MergeAP) {
			for (String device : remove_pts.keySet()) {
				HashSet<String> e_ports = ports.get(device);
				if (e_ports == null) {
					e_ports = new HashSet<String>();
				}
				e_ports.removeAll(remove_pts.get(device));
				ports.put(device, e_ports);
				if (add_pts.containsKey(device)) {
					e_ports.addAll(add_pts.get(device));
				}
			}
			for (String device : add_pts.keySet()) {
				if (remove_pts.containsKey(device))
					continue;
				HashSet<String> e_ports = ports.get(device);
				if (e_ports == null) {
					e_ports = new HashSet<String>();
				}
				e_ports.addAll(add_pts.get(device));
				ports.put(device, e_ports);
			}
		} else {
			HashSet<Integer> aps = ports_aps.get(ports);
			aps.remove(ap);

			// the ap set becomes empty, then remove the ports entry
			if (aps.isEmpty()) {
				ports_aps.remove(ports);
			}
			// the ap set is non-empty, then clone the ports
			else {
				mergeable_aps--;
				// the ap set has one ap, then do not merge it
				if (aps.size() == 1) {
					ports_to_merge.remove(ports);
				}
				HashMap<String, HashSet<String>> new_ports = new HashMap<String, HashSet<String>>();
				for (String e_name : ports.keySet()) {
					new_ports.put(e_name, new HashSet<String>(ports.get(e_name)));
				}
				ports = new_ports;
			}

			for (String device : remove_pts.keySet()) {
				HashSet<String> e_ports = ports.get(device);
				if (e_ports == null) {
					e_ports = new HashSet<String>();
				}
				e_ports.removeAll(remove_pts.get(device));
				ports.put(device, e_ports);
				if (add_pts.containsKey(device)) {
					e_ports.addAll(add_pts.get(device));
				}
			}
			for (String device : add_pts.keySet()) {
				if (remove_pts.containsKey(device))
					continue;
				HashSet<String> e_ports = ports.get(device);
				if (e_ports == null) {
					e_ports = new HashSet<String>();
				}
				e_ports.addAll(add_pts.get(device));
				ports.put(device, e_ports);
			}

			ap_ports.put(ap, ports);

			if (!ports_aps.containsKey(ports)) {
				ports_aps.put(ports, new HashSet<Integer>());
			}
			aps = ports_aps.get(ports);
			if (!aps.isEmpty()) {
				mergeable_aps++;
			}
			aps.add(ap);
			if (aps.size() == 2) {
				ports_to_merge.add(ports);
			}
		}
	}

	public void UpdateCopyAP(PositionTuple pt1, PositionTuple pt2, int ap) {
		if (!ap_ports.containsKey(ap)) {
			System.out.println(AP);
			System.out.println(ap_ports);
			System.err.println("Error: AP edge reference not found " + ap);
		}

		HashMap<String, HashSet<String>> ports = ap_ports.get(ap);

		if (!MergeAP) {
			HashSet<String> e_ports = ports.get(pt2.getDeviceName());
			if (e_ports == null) {
				e_ports = new HashSet<String>();
			}
			e_ports.add(pt2.getPortName());
			ports.put(pt2.getDeviceName(), e_ports);
		} else {
			HashSet<Integer> aps = ports_aps.get(ports);
			aps.remove(ap);

			// the ap set becomes empty, then remove the ports entry
			if (aps.isEmpty()) {
				ports_aps.remove(ports);
			}
			// the ap set is non-empty, then clone the ports
			else {
				mergeable_aps--;

				// the ap set has one ap, then do not merge it
				if (aps.size() == 1) {
					ports_to_merge.remove(ports);
				}

				HashMap<String, HashSet<String>> new_ports = new HashMap<String, HashSet<String>>();
				for (String e_name : ports.keySet()) {
					new_ports.put(e_name, new HashSet<String>(ports.get(e_name)));
				}
				ports = new_ports;
			}

			HashSet<String> e_ports = ports.get(pt2.getDeviceName());
			if (e_ports == null) {
				e_ports = new HashSet<String>();
			}
			e_ports.add(pt2.getPortName());
			ports.put(pt2.getDeviceName(), e_ports);
			ap_ports.put(ap, ports);

			if (!ports_aps.containsKey(ports)) {
				ports_aps.put(ports, new HashSet<Integer>());
			}
			aps = ports_aps.get(ports);
			if (!aps.isEmpty()) {
				mergeable_aps++;
			}
			aps.add(ap);
			if (aps.size() == 2) {
				ports_to_merge.add(ports);
			}
		}
	}

	public void TryMergeAPBatch(HashMap<String, HashSet<Integer>> moved_batch_aps) {
		if (!MergeAP)
			return;
		if (ports_to_merge.isEmpty())
			return;

		for (HashMap<String, HashSet<String>> ports : new ArrayList<>(ports_to_merge)) {
			HashSet<Integer> aps = ports_aps.get(ports);
			int merged_ap = bddengine.OrInBatch(UtilityTools.HashSetToArray(aps));
			mergeable_aps = mergeable_aps - aps.size() + 1;

			for (String key : moved_batch_aps.keySet()) {
				if (moved_batch_aps.get(key).removeAll(aps)) {
					moved_batch_aps.get(key).add(merged_ap);
				}
			}
			UpdateMergeAPBatch(merged_ap, aps);
			ports_to_merge.remove(ports);
		}
	}

	public void TryMergeAPBatchNAT() {
		if (ports_to_merge.isEmpty())
			return;

		for (HashMap<String, HashSet<String>> ports : new ArrayList<>(ports_to_merge)) {
			HashSet<Integer> aps = ports_aps.get(ports);
			if (aps == null) {
				System.out.println(ports);
				for (HashMap<String, HashSet<String>> port : ports_aps.keySet()) {
					System.out.println(port);
					System.out.println(ports_aps.get(port));
				}
			}
			if (!CheckRWMergable(aps)) {
				continue;
			}
			int merged_ap = bddengine.OrInBatch(aps);
			mergeable_aps = mergeable_aps - aps.size() + 1;
			UpdateMergeAPBatch(merged_ap, aps);
			ports_to_merge.remove(ports);
		}
	}

	// public void TryMergeAPBatchNAT(HashMap<String, HashSet<Integer>>
	// moved_batch_aps) {
	// if (!MergeAP)
	// return;
	// if (ports_to_merge.isEmpty())
	// return;

	// boolean remain = false;
	// while(!remain)
	// {
	// int pa = 0;
	// int pb = 0;
	// remain = true;
	// for (HashMap<String, HashSet<String>> ports : new
	// ArrayList<>(ports_to_merge))
	// {
	// HashSet<Integer> aps = ports_aps.get(ports);
	// HashSet<Integer> aps1 =new HashSet<>(aps);
	// for(int ap : aps)
	// {
	// for(int another_ap : aps1)
	// {
	// if(another_ap == ap)continue;
	// if(!CheckRWMergable(ap, another_ap))continue;
	// remain = false;
	// pa = ap;
	// pb = another_ap;
	// break;
	// }
	// if(!remain)break;
	// }
	// if(!remain)break;
	// }
	// if(!remain)
	// {
	// int merged_ap = bddengine.getBDD().ref(bddengine.getBDD().or(pa, pb));
	// UpdateMergeAP(pa, pb, merged_ap);
	// }
	// }
	// }

	public void UpdateMergeAPBatch(int merged_ap, HashSet<Integer> aps) {
		if (!AP.containsAll(aps)) {
			System.out.println(AP);
			System.err.println("Error: origin APs " + aps + " not found");
			System.exit(1);
		}

		AP.removeAll(aps);
		AP.add(merged_ap);

		// for(int ap : ap_ports.keySet())
		// {
		// for(String device : ap_ports.get(ap).keySet())
		// {
		// if(!device.equals("config1591_EHLL6k1ps7neHAAHnw4Eh"))continue;
		// System.out.print(ap+" "+device);
		// for(String port : ap_ports.get(ap).get(device))
		// {
		// System.out.print(" "+port);
		// }
		// System.out.println();
		// }
		// }
		HashMap<String, HashSet<String>> ports = ap_ports.get(aps.toArray()[0]);
		for (String elementname : elements.keySet()) {
			HashSet<String> e_ports = ports.get(elementname);
			if (e_ports == null) {
				if (elements.get(elementname) instanceof ACLElement) {
					ACLElement acle = (ACLElement) elements.get(elementname);
					e_ports = acle.getAPPorts((int) aps.toArray()[0]);
				} else {
					e_ports = new HashSet<String>();
					e_ports.add("default");
				}
			}
			if (elements.get(elementname) instanceof NATElement) {
				elements.get(elementname).UpdateAPSetMergeBatch(e_ports, merged_ap, aps, !is_FW);
			} else {
				elements.get(elementname).UpdateAPSetMergeBatch(e_ports, merged_ap, aps, true);
			}
		}
		ap_ports.put(merged_ap, ports);
		for (int ap : aps) {
			bddengine.getBDD().deref(ap);
			ap_ports.remove(ap);
		}

		aps.clear();
		aps.add(merged_ap);
	}

	// public void CheckCorrectness() {
	// int sum = BDDACLWrapper.BDDFalse;
	// for (int ap1 : AP) {
	// int intersect = bddengine.getBDD().ref(bddengine.getBDD().and(sum, ap1));
	// if (intersect != BDDACLWrapper.BDDFalse) {
	// System.err.println("Some APs overlap");
	// bddengine.getBDD().deref(intersect);
	// System.exit(0);
	// }
	// sum = bddengine.getBDD().orTo(sum, ap1);
	// for (int ap2 : AP) {
	// if (ap1 != ap2 && AP_EDGE_REF.get(ap1).equals(AP_EDGE_REF.get(ap2))) {
	// System.err.println("APs are not merged correctly");
	// System.exit(0);
	// }
	// }
	// }
	// if (sum != BDDACLWrapper.BDDTrue) {
	// System.err.println("APs do not sum to true");
	// System.exit(0);
	// }
	// }

	public long getAPNum() {
		return AP.size();
	}

	public HashSet<String> getAPPrefixes(HashSet<Integer> aps) {
		HashSet<String> ip_prefixs = new HashSet<String>();
		int[] header = new int[bddengine.total_bits];
		int[] dstip = new int[32];

		for (int ap_origin : aps) {
			int ap = ap_origin;
			while (ap != BDDACLWrapper.BDDFalse) {
				bddengine.getBDD().oneSat(ap, header);
				int offset = 32;
				int prefix_len = 32;
				for (int i = 0; i < 32; i++) {
					if (header[offset + i] == -1) {
						dstip[i] = 0;
						prefix_len--;
					} else {
						dstip[i] = header[offset + i];
					}
				}
				String ip_prefix = UtilityTools.IpBinToString(dstip);
				long ip_prefix_long = UtilityTools.IPStringToLong(ip_prefix);
				ip_prefixs.add(ip_prefix + "/" + prefix_len);
				int prefix_bdd = bddengine.encodeDstIPPrefix(ip_prefix_long, prefix_len);
				ap = bddengine.diff(ap, prefix_bdd);
			}
		}
		return ip_prefixs;
	}

	// new added
	public void AddAllTrueAP(PositionTuple pt) {
		if (AP.add(BDDACLWrapper.BDDTrue)) {
			AP_EDGE_REF.put(BDDACLWrapper.BDDTrue, new HashSet<PositionTuple>());
		}
		AP_EDGE_REF.get(BDDACLWrapper.BDDTrue).add(pt);
	}

	public int TryMergeAP(int ap) {

		if (!MergeAP)
			return ap;
		HashMap<String, HashSet<String>> ports = ap_ports.get(ap);
		HashSet<Integer> aps = ports_aps.get(ports);
		if (aps.size() > 1) {
			for (int one_ap : aps) {
				if (one_ap == ap)
					continue;
				if (!CheckRWMergable(one_ap, ap))
					continue;
				int merged_ap = bddengine.getBDD().ref(bddengine.getBDD().or(ap, one_ap));
				UpdateMergeAP(one_ap, ap, merged_ap);
				return merged_ap;
			}
		}
		return ap;
	}

	public boolean CheckRWMergable(int ap1, int ap2) {
		if (nat_names.isEmpty())
			return true;
		for (String nat_name : nat_names) {
			NATElement nat = (NATElement) elements.get(nat_name);
			if (!nat.IsMergable(ap1, ap2, !is_FW))
				return false;
		}
		return true;
	}

	public boolean CheckRWMergable(HashSet<Integer> aps) {
		if (nat_names.isEmpty())
			return true;
		for (String nat_name : nat_names) {
			NATElement nat = (NATElement) elements.get(nat_name);
			if (!nat.IsMergable(aps, !is_FW))
				return false;
		}
		return true;
	}

	public void UpdateMergeAP(int ap1, int ap2, int merged_ap) {
		if (!AP.contains(ap1) || !AP.contains(ap2)) {
			System.out.println(AP);
			System.err.println("Error: origin AP " + ap1 + " or " + ap2 + " not found");
			System.exit(1);
		}

		AP.remove(ap1);
		AP.remove(ap2);
		AP.add(merged_ap);

		HashMap<String, HashSet<String>> ports = ap_ports.get(ap1);
		for (String elementname : elements.keySet()) {
			for (String port : ports.get(elementname)) {
				if (port == null) {
					if (elements.get(elementname) instanceof ACLElement) {
						ACLElement acle = (ACLElement) elements.get(elementname);
						port = acle.getAPPort(ap1);
					} else {
						port = "default";
					}
				}
				if (elements.get(elementname) instanceof NATElement) {
					elements.get(elementname).UpdateAPSetMerge(port, merged_ap, ap1, ap2, !is_FW);
				} else {
					elements.get(elementname).UpdateAPSetMerge(port, merged_ap, ap1, ap2, true);
				}
			}
		}
		ap_ports.put(merged_ap, ports);
		ap_ports.remove(ap1);
		ap_ports.remove(ap2);

		HashSet<Integer> aps = ports_aps.get(ports);
		aps.remove(ap1);
		aps.remove(ap2);
		aps.add(merged_ap);

		bddengine.getBDD().deref(ap1);
		bddengine.getBDD().deref(ap2);
	}

	public boolean HasAP(int ap) {
		return AP.contains(ap);
	}

	public void AddPredicate(int pred) {
		BDD thebdd = bddengine.getBDD();

		int predneg = thebdd.not(pred);
		thebdd.ref(predneg);

		HashSet<Integer> oldList = new HashSet<Integer>(AP);

		for (int oldap : oldList) {
			// System.out.print("pred:");
			// bddengine.getBDD().printNode(pred);
			// System.out.print("oldap:");
			// bddengine.getBDD().printNode(oldap);
			int parta = thebdd.ref(thebdd.and(pred, oldap));
			// System.out.print("parta:");
			// bddengine.getBDD().printNode(parta);
			if (parta != BDDACLWrapper.BDDFalse) {
				int partb = thebdd.ref(thebdd.and(predneg, oldap));
				if (partb != BDDACLWrapper.BDDFalse) {
					UpdateSplitAP(oldap, parta, partb);
				}
			}
		}
	}

	public HashSet<Integer> getAPExp(int PredicateBDD) {
		HashSet<Integer> apexp = new HashSet<Integer>();
		// get the expression
		if (PredicateBDD == BDDACLWrapper.BDDFalse) {
			return apexp;
		} else if (PredicateBDD == BDDACLWrapper.BDDTrue) {
			return new HashSet<Integer>(AP);
		}

		for (int oneap : AP) {
			if (bddengine.getBDD().and(oneap, PredicateBDD) != BDDACLWrapper.BDDFalse) {
				apexp.add(bddengine.getBDD().ref(oneap));
			}
		}
		return apexp;
	}
}
