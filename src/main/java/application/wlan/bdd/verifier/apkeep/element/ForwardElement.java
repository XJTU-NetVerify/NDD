package application.wlan.bdd.verifier.apkeep.element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

import application.wlan.bdd.verifier.apkeep.core.BDDRuleItem;
import application.wlan.bdd.verifier.apkeep.core.ChangeTuple;
import application.wlan.bdd.verifier.apkeep.utils.PrefixItem;
import application.wlan.bdd.verifier.apkeep.utils.TrieTree;
import application.wlan.bdd.verifier.apkeep.utils.TrieTreeNode;
import application.wlan.bdd.verifier.common.BDDACLWrapper;
import application.wlan.bdd.verifier.common.ForwardingRule;

import javafx.util.Pair;
import jdd.bdd.BDD;

public class ForwardElement extends Element{
	
	LinkedList<BDDRuleItem<ForwardingRule>> fw_rule;
	TrieTree trie;

	public ForwardElement(String ename)
	{
		super(ename);
		fw_rule = new LinkedList<BDDRuleItem<ForwardingRule>>();
		trie = new TrieTree();
	}
	public void Initialize() {
		String default_port = "default";
//		apc.AddAllTrueAP(new PositionTuple(name,default_port));
		HashSet<Integer> alltrue = new HashSet<Integer>();
//		System.out.println(apc.AP);
		if(apc.AP.isEmpty()) alltrue.add(BDDACLWrapper.BDDTrue);
		else alltrue.addAll(apc.AP);
		port_aps_raw.put(default_port, alltrue);
	}
	public ArrayList<PrefixItem> GetAffectedRules (TrieTreeNode node)
	{
		ArrayList<PrefixItem> affected_rules = new ArrayList<PrefixItem>();
		// add the descendants
		ArrayList<TrieTreeNode> descendants = node.GetDescendant();
		if (descendants != null) {
			Iterator<TrieTreeNode> it = node.GetDescendant().iterator();
			while (it.hasNext()) {
				ArrayList<PrefixItem> items = it.next().GetPrefixItems();
				affected_rules.addAll(items);
			}
		}
		// add the ancestors
		ArrayList<TrieTreeNode> ancestors = node.GetAncestor();
		if (ancestors != null) {
			Iterator<TrieTreeNode> it = node.GetAncestor().iterator();
			while (it.hasNext()) {
				ArrayList<PrefixItem> items = it.next().GetPrefixItems();
				affected_rules.addAll(items);
			}
		}
		// // add the siblings
		affected_rules.addAll(node.GetPrefixItems());
		affected_rules.sort(byPriority);
		return affected_rules;
	}
	
	public void updateFWRuleBatch(String ip, HashSet<String> to_ports, HashSet<String> from_ports,
								  HashMap<String, ArrayList<ChangeTuple>> change_set, HashMap<String, ArrayList<ChangeTuple>> copyto_set,
								  HashMap<String, ArrayList<ChangeTuple>> remove_set) {
		// find the exact node in prefixTree
		long destip = Long.parseLong(ip.split("/")[0]);
		int prefixlen = Integer.parseInt(ip.split("/")[1]);
		int priority = prefixlen;
		ArrayList<ChangeTuple> change = new ArrayList<ChangeTuple>();
		ArrayList<ChangeTuple> copyto = new ArrayList<ChangeTuple>();
		ArrayList<ChangeTuple> remove = new ArrayList<ChangeTuple>();
		TrieTreeNode node = trie.Search(destip, prefixlen);
		if(node == null) {
			/* 
			 * no same prefix was inserted in this element before
			 * from_ports must be empty
			 */
			node  = trie.Insert(destip, prefixlen);
		// 	/*
		// 	 * 	if(!from_ports.isEmpty()) {
		// 	 * 		if(node == null){
		// 	 * 			System.err.println(name + ", node not found: "+ destip + "/" + prefixlen + "; " 
		// 	 * 				+ "outinterface: " + from_ports.toArray()[0] + "; priority: " + priority);
		// 	 * 			System.exit(1);
		// 	 * 		}
		// 	 * 	}
		// 	 */
			int rule_bdd = apc.GetPrefixBDD(destip, prefixlen);
			ArrayList<PrefixItem> affected_rules = GetAffectedRules(node);
			int residual = bdd.getBDD().ref(rule_bdd);
			int residual2 = BDDACLWrapper.BDDFalse;
			boolean inserted = false;
			int last_priority = 65535;
			int last_sum = BDDACLWrapper.BDDFalse;
			Iterator<PrefixItem> it = affected_rules.iterator();
			while (it.hasNext()) {
				PrefixItem item = it.next();
				if (item.priority > priority) {
					residual = bdd.diffto(residual, item.rule_bdd);
					if (residual == BDDACLWrapper.BDDFalse) {
			        	bdd.getBDD().deref(rule_bdd);
			        	break;
					}
				}
				/* no same prefix was inserted before
				else if(item.priority == priority) {
					System.exit(1);
				}
				*/
				else {
					if(!inserted) {
						residual2 = bdd.getBDD().ref(residual);
						inserted = true;
						last_priority = item.priority;
					}
					if(last_priority != item.priority) {
						residual2 = bdd.diffto(residual2, last_sum);
						last_sum = BDDACLWrapper.BDDFalse;
						last_priority = item.priority;
					}
					if(residual2 == BDDACLWrapper.BDDFalse) {
						break;
					}
					int delta = bdd.getBDD().ref(bdd.getBDD().and(item.matches, residual2));
					if (delta == BDDACLWrapper.BDDFalse) {
						continue;
					}
					item.matches = bdd.diffto(item.matches, delta);
					last_sum = bdd.getBDD().orTo(last_sum, delta);
			        bdd.getBDD().deref(delta);
			        HashSet<String> ports = new HashSet<String>();
			        ports.add(item.outinterface);
			        HashSet<Integer> delta_set = new HashSet<Integer>();
			        delta_set.add(bdd.getBDD().ref(delta));
			        ChangeTuple ct = new ChangeTuple(ports,to_ports,delta_set);
			        if(!ct.from_ports.equals(ct.to_ports)) change.add(ct);
				}
			}
			if(last_sum != BDDACLWrapper.BDDFalse) {
				residual2 = bdd.diffto(residual2, last_sum);
				last_sum = BDDACLWrapper.BDDFalse;
			}
			/*
			* if (residual2 != BDDACLWrapper.BDDFalse) {
			* 	System.out.println("not overriding any low-priority rule");
			* 	System.exit(0);
			* }
			*/

			int hit_bdd = residual;
			for(String port : to_ports) {
				PrefixItem insert_item = new PrefixItem(priority, port, bdd.getBDD().ref(rule_bdd), bdd.getBDD().ref(hit_bdd));
				// check whether the forwarding port exists, if not create it, 
				// and initialize the AP set of the port to empty
				if(!port_aps_raw.containsKey(port)) {
					HashSet<Integer> aps_raw = new HashSet<Integer>();
					port_aps_raw.put(port, aps_raw);
				}
				// insert the rule
				node.AddPrefixItem(insert_item);
			}
			bdd.getBDD().deref(rule_bdd);
			bdd.getBDD().deref(hit_bdd);
		}
		else {
			// System.out.println(name+" "+destip+" "+prefixlen);
			// we have pre-rules, just use matches field to give a short cut
			ArrayList<PrefixItem> node_rules = node.GetPrefixItems();
			ArrayList<PrefixItem> insert_items = new ArrayList<PrefixItem>();
			ArrayList<PrefixItem> delete_items = new ArrayList<PrefixItem>();
			int rule_bdd = 0;
			int hit_bdd = 0;
			if(!node_rules.isEmpty()) {
				rule_bdd = bdd.getBDD().ref(node_rules.get(0).rule_bdd);
				hit_bdd = bdd.getBDD().ref(node_rules.get(0).matches);
				if(node_rules.size() == 1 && node_rules.get(0).priority == -1) {
					// we hit the default rule
					String outinterface = "default";
					int delta = node_rules.get(0).matches;
					node_rules.get(0).matches = BDDACLWrapper.BDDFalse;
			        bdd.getBDD().deref(delta);
			        HashSet<String> ports = new HashSet<String>();
			        ports.add(outinterface);
			        HashSet<Integer> delta_set = new HashSet<Integer>();
			        delta_set.add(bdd.getBDD().ref(delta));
			        ChangeTuple ct = new ChangeTuple(ports,to_ports,delta_set);
			        if(!ct.from_ports.equals(ct.to_ports)) change.add(ct);
			        
				}
				else if(node_rules.size() != 1 && node_rules.get(0).priority == -1) {
					rule_bdd = bdd.getBDD().ref(node_rules.get(1).rule_bdd);
					hit_bdd = bdd.getBDD().ref(node_rules.get(1).matches);
					if(from_ports.isEmpty()) {
						String outinterface = node_rules.get(1).outinterface;
						HashSet<String> ports = new HashSet<String>();
						ports.add(outinterface);
						HashSet<Integer> delta_set = new HashSet<Integer>();
				        delta_set.add(bdd.getBDD().ref(hit_bdd));
				        ChangeTuple ct = new ChangeTuple(ports,to_ports,delta_set);
				        if(!ct.from_ports.containsAll(ct.to_ports)) copyto.add(ct);
					}
				}
				else {
					// node_rules.get(0).priority != -1 case
					if(from_ports.isEmpty()) {
						String outinterface = node_rules.get(0).outinterface;
						HashSet<String> ports = new HashSet<String>();
						ports.add(outinterface);
						HashSet<Integer> delta_set = new HashSet<Integer>();
				        delta_set.add(bdd.getBDD().ref(hit_bdd));
				        ChangeTuple ct = new ChangeTuple(ports,to_ports,delta_set);
				        if(!ct.from_ports.containsAll(ct.to_ports)) copyto.add(ct);
					}
				}
			}
			/*
			else {
				System.err.println("node is invalid!");
				System.exit(1);
			}
			*/
			for(String port : to_ports) {
				insert_items.add(new PrefixItem(priority, port, bdd.getBDD().ref(rule_bdd), bdd.getBDD().ref(hit_bdd)));
				if(!port_aps_raw.containsKey(port)) {
					HashSet<Integer> aps_raw = new HashSet<Integer>();
					port_aps_raw.put(port, aps_raw);
				}
			}
			for(String port : from_ports) {
				PrefixItem delete_rule = new PrefixItem(priority, port, rule_bdd, hit_bdd);
				if(!node.HasPrefixItem(delete_rule)) {
					System.exit(1);
				}
				delete_items.add(delete_rule);
				bdd.getBDD().deref(rule_bdd);
				bdd.getBDD().deref(hit_bdd);
			}
			node_rules.removeAll(delete_items);
			node_rules.addAll(insert_items);
			
			if(node.IsInValid()) {
				ArrayList<PrefixItem> affected_rules = GetAffectedRules(node);
				int residual = hit_bdd;
				int last_priority = 65535;
				int last_sum = BDDACLWrapper.BDDFalse;
				boolean inserted = false;
				Iterator<PrefixItem> it = affected_rules.iterator();
				while (it.hasNext() && residual != BDDACLWrapper.BDDFalse){
					PrefixItem item = it.next();	
					if (item.priority > priority){
						continue;
					}
					else if(item.priority == priority) {
						System.exit(1);
					}
					else {
						if(!inserted) {
							inserted = true;
						}
						if(last_priority != item.priority) {
							residual = bdd.diffto(residual, last_sum);
							last_sum = BDDACLWrapper.BDDFalse;
							last_priority = item.priority;
						}
						if(residual == BDDACLWrapper.BDDFalse) {
							break;
						}
						int delta = bdd.getBDD().ref(bdd.getBDD().and(residual, item.rule_bdd));
						if (delta == BDDACLWrapper.BDDFalse) {
							continue;
						}
						item.matches = bdd.getBDD().orTo(item.matches, delta);
						last_sum = bdd.getBDD().orTo(last_sum, delta);
				        bdd.getBDD().deref(delta);
				        HashSet<String> ports = new HashSet<String>();
				        ports.add(item.outinterface);
				        HashSet<Integer> delta_set = new HashSet<Integer>();
				        delta_set.add(bdd.getBDD().ref(delta));
				        ChangeTuple ct = new ChangeTuple(from_ports,ports,delta_set);
				        if(!ct.from_ports.equals(ct.to_ports)) change.add(ct);
					}
				}
				if(last_sum != BDDACLWrapper.BDDFalse) {
					residual = bdd.diffto(residual, last_sum);
					last_sum = BDDACLWrapper.BDDFalse;
				}
				if (residual != BDDACLWrapper.BDDFalse) {
		    		System.err.println("not fully deleted");
		    		System.exit(1);
				}
				apc.RemovePrefixBDD(destip, prefixlen);
	    		node.Delete();
			}
			else if(node_rules.size() == 1 && node_rules.get(0).priority == -1) {
				// we hit the default rule
				String outinterface = "default";
				node_rules.get(0).matches = hit_bdd;
				int delta = node_rules.get(0).matches;
				HashSet<String> ports = new HashSet<String>();
		        ports.add(outinterface);
		        HashSet<Integer> delta_set = new HashSet<Integer>();
		        delta_set.add(bdd.getBDD().ref(delta));
		        ChangeTuple ct = new ChangeTuple(from_ports,ports,delta_set);
		        if(!ct.from_ports.equals(ct.to_ports)) change.add(ct);
		        bdd.getBDD().ref(delta);
			}
			else {
				if(from_ports.isEmpty()) {
					// only insert
					// do nothing
				}
				else if(to_ports.isEmpty()) {
					// only delete
					HashSet<String> ports = new HashSet<String>();
			        HashSet<Integer> delta_set = new HashSet<Integer>();
			        delta_set.add(bdd.getBDD().ref(hit_bdd));
			        ChangeTuple ct = new ChangeTuple(from_ports,ports,delta_set);
			        remove.add(ct);
				}
				else {
			        HashSet<Integer> delta_set = new HashSet<Integer>();
			        delta_set.add(bdd.getBDD().ref(hit_bdd));
			        ChangeTuple ct = new ChangeTuple(from_ports,to_ports,delta_set);
			        if(!ct.from_ports.equals(ct.to_ports)) change.add(ct);
				}
			}
			bdd.getBDD().deref(rule_bdd);
			bdd.getBDD().deref(hit_bdd);
		}
		if(!change_set.containsKey(name)) {
			change_set.put(name, change);
		}
		else {
			change_set.get(name).addAll(change);
		}
		if(!copyto_set.containsKey(name)) {
			copyto_set.put(name, copyto);
		}
		else {
			copyto_set.get(name).addAll(copyto);
		}
		if(!remove_set.containsKey(name)) {
			remove_set.put(name, remove);
		}
		else {
			remove_set.get(name).addAll(remove);
		}
	}
}