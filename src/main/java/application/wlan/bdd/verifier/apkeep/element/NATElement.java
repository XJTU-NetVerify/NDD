package application.wlan.bdd.verifier.apkeep.element;

import java.util.*;

import application.wlan.bdd.verifier.apkeep.core.APKeeper;
import application.wlan.bdd.verifier.apkeep.core.BDDRuleItem;
import application.wlan.bdd.verifier.apkeep.core.ChangeItem;
import application.wlan.bdd.verifier.common.BDDACLWrapper;
import application.wlan.bdd.verifier.common.PositionTuple;
import application.wlan.bdd.verifier.common.RewriteRule;
import jdd.bdd.BDD;

public class NATElement extends Element {

	APKeeper ACL_apc;
	LinkedList<BDDRuleItem<RewriteRule>> rewrite_rules;
	public HashMap<Integer, HashSet<Integer>> rewrite_table;
	HashSet<Integer> output_aps;
	public HashMap<Integer, HashSet<Integer>> ACL_rewrite_table;
	HashSet<Integer> ACL_output_aps;
	HashMap<String, RewriteRule> rule_map;

	public HashMap<String, HashSet<Integer>> ACL_port_aps_raw;

	public NATElement(String ename) {
		super(ename);

		rewrite_rules = new LinkedList<BDDRuleItem<RewriteRule>>();
		rewrite_table = new HashMap<Integer, HashSet<Integer>>();
		output_aps = new HashSet<Integer>();
		rule_map = new HashMap<String, RewriteRule>();

		ACL_port_aps_raw = new HashMap<>();
		ACL_rewrite_table = new HashMap<>();
		ACL_output_aps = new HashSet<>();
	}

	public void SetAPC(APKeeper theapc, APKeeper ACL_apc) {
		apc = theapc;
		apc.AddElement(name, this);
		apc.nat_names.add(name);
		this.ACL_apc = ACL_apc;
		ACL_apc.AddElement(name, this);
		ACL_apc.nat_names.add(name);
	}

	public void Initialize() {
		// initialize the AP set for default rewrite rule
		String default_rule_name = "default";
		RewriteRule default_rule = new RewriteRule(BDDACLWrapper.BDDTrue, BDDACLWrapper.BDDTrue, BDDACLWrapper.BDDTrue,
				default_rule_name, 0);
		BDDRuleItem<RewriteRule> default_item = new BDDRuleItem<RewriteRule>(default_rule, BDDACLWrapper.BDDTrue,
				BDDACLWrapper.BDDTrue);
		rewrite_rules.add(default_item);
		rule_map.put(default_rule_name, default_rule);

		// apc.AddAllTrueAP(new PositionTuple(name, default_rule_name));
		// //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11
		HashSet<Integer> alltrue = new HashSet<Integer>();
		alltrue.add(BDDACLWrapper.BDDTrue);
		port_aps_raw.put(default_rule_name, alltrue);
		ACL_port_aps_raw.put(default_rule_name, new HashSet<>(alltrue));
	}

	public boolean IsMergable(HashSet<Integer> aps, boolean isACL) {
		if (isACL) {
			HashSet<Integer> temp = new HashSet<Integer>(aps);
			// temp.removeAll(ACL_output_aps);
			// if (!temp.equals(aps) && !temp.isEmpty()) {
			// 	return false;
			// }

			for (HashSet<Integer> rewrited_aps : ACL_rewrite_table.values()) {
				temp = new HashSet<Integer>(aps);
				temp.removeAll(rewrited_aps);
				if (!temp.equals(aps) && !temp.isEmpty()) {
					return false;
				}
			}
			return true;
		} else {
			HashSet<Integer> temp = new HashSet<Integer>(aps);
			// temp.removeAll(output_aps);
			// if (!temp.equals(aps) && !temp.isEmpty()) {
			// 	return false;
			// }

			for (HashSet<Integer> rewrited_aps : rewrite_table.values()) {
				temp = new HashSet<Integer>(aps);
				temp.removeAll(rewrited_aps);
				if (!temp.equals(aps) && !temp.isEmpty()) {
					return false;
				}
			}
			return true;
		}
	}

	public boolean IsMergable(int ap1, int ap2, Boolean isACL) {
		if (isACL) {
			// if (!ACL_output_aps.contains(ap1) && !ACL_output_aps.contains(ap2))
			// 	return true;
			// if (ACL_output_aps.contains(ap1) && !ACL_output_aps.contains(ap2))
			// 	return false;
			// if (!ACL_output_aps.contains(ap1) && ACL_output_aps.contains(ap2))
			// 	return false;

			for (HashSet<Integer> aps : ACL_rewrite_table.values()) {
				if (aps.contains(ap1) && !aps.contains(ap2)) {
					return false;
				}
				if (!aps.contains(ap1) && aps.contains(ap2)) {
					return false;
				}
			}
			return true;
		} else {
			// if (!output_aps.contains(ap1) && !output_aps.contains(ap2))
			// 	return true;
			// if (output_aps.contains(ap1) && !output_aps.contains(ap2))
			// 	return false;
			// if (!output_aps.contains(ap1) && output_aps.contains(ap2))
			// 	return false;

			for (HashSet<Integer> aps : rewrite_table.values()) {
				if (aps.contains(ap1) && !aps.contains(ap2)) {
					return false;
				}
				if (!aps.contains(ap1) && aps.contains(ap2)) {
					return false;
				}
			}
			return true;
		}
	}

	public ArrayList<ChangeItem> InsertRewriteRule(RewriteRule rule) {

		ArrayList<ChangeItem> changeset = new ArrayList<ChangeItem>();

		int rule_bdd = rule.old_pkt_bdd;
		BDD thebdd = bdd.getBDD();
		int priority = rule.priority;
		int residual = bdd.getBDD().ref(rule_bdd);
		int residual2 = BDDACLWrapper.BDDFalse;
		int cur_position = 0;
		boolean inserted = false;
		// changes.clear();

		BDDRuleItem<RewriteRule> default_item = rewrite_rules.getLast();

		Iterator<BDDRuleItem<RewriteRule>> it = rewrite_rules.iterator();
		while (it.hasNext()) {
			BDDRuleItem<RewriteRule> item = it.next();
			// TODO: fast check whether the rule is not affected by any rule
			if (item.rule.priority >= priority) {
				if (thebdd.and(residual, item.rule_bdd) != BDDACLWrapper.BDDFalse) {
					// System.out.println("high-priority rule exists");
					residual = bdd.diffto(residual, item.rule_bdd);
				}
				cur_position++;
			} else {
				if (!inserted) {
					// fast check whether the default rule is the only rule affected
					int temp = bdd.diff(residual, default_item.matches);
					if (temp == BDDACLWrapper.BDDFalse) {
						// System.out.println("Break directly");

						default_item.matches = bdd.diffto(default_item.matches, residual);
						ChangeItem change_item = new ChangeItem(default_item.rule.name, rule.name, residual);
						changeset.add(change_item);

						break;
					}

					bdd.getBDD().deref(temp);
					residual2 = bdd.getBDD().ref(residual);
					inserted = true;
				}

				if (residual2 == BDDACLWrapper.BDDFalse) {
					break;
				}

				int delta = bdd.getBDD().ref(bdd.getBDD().and(item.matches, residual2));
				if (delta != BDDACLWrapper.BDDFalse) {
					item.matches = bdd.diffto(item.matches, delta);
					residual2 = bdd.diffto(residual2, delta);
					ChangeItem change_item = new ChangeItem(item.rule.name, rule.name, delta);
					changeset.add(change_item);
				}
			}
		}

		// add the new rule into the installed forwarding rule list
		BDDRuleItem<RewriteRule> new_rule = new BDDRuleItem<RewriteRule>(rule, rule_bdd);
		new_rule.matches = residual;
		// System.out.println("matches " + new_rule.matches + ": " +
		// bdd.getBDD().getRef(new_rule.matches));
		rewrite_rules.add(cur_position, new_rule);
		rule_map.put(rule.name, rule);

		// check whether the forwarding port exists, if not create it,
		// and initialize the AP set of the port to empty
		if (!port_aps_raw.containsKey(rule.name) || !ACL_port_aps_raw.containsKey(rule.name)) {
			HashSet<Integer> aps_raw = new HashSet<Integer>();
			port_aps_raw.put(rule.name, aps_raw);
			ACL_port_aps_raw.put(rule.name, new HashSet<>(aps_raw));
		}

		if (residual2 != BDDACLWrapper.BDDFalse) {
			bdd.getBDD().deref(residual2);
		}

		return changeset;
	}

	public ArrayList<ChangeItem> RemoveRewriteRule(RewriteRule rule) {

		ArrayList<ChangeItem> changeset = new ArrayList<ChangeItem>();
		// System.out.println(default_item.rule.getiname());

		Iterator<BDDRuleItem<RewriteRule>> it = rewrite_rules.iterator();
		BDDRuleItem<RewriteRule> delete_item = null;
		int remove_position = 0;
		boolean isFound = false;
		// long t1 = System.nanoTime();
		while (it.hasNext()) {
			delete_item = it.next();
			if (delete_item.rule.equals(rule)) {
				isFound = true;
				break;
			}
			remove_position++;
		}
		if (!isFound) {
			System.err.println("Rule not found: " + rule);
			System.exit(0);
			return changeset;
		}

		// long t2 = System.nanoTime();
		// System.out.println("higher priority rule: " + (t2-t1)/1000000.0 + "ms");

		int residual = bdd.getBDD().ref(delete_item.matches);
		while (it.hasNext() && residual != BDDACLWrapper.BDDFalse) {
			BDDRuleItem<RewriteRule> item = it.next();
			// System.out.println(item.matches + ": " + bdd.getBDD().getRef(item.matches));
			int delta = bdd.getBDD().ref(bdd.getBDD().and(residual, item.rule_bdd));
			if (delta != BDDACLWrapper.BDDFalse) {
				item.matches = bdd.orto(item.matches, delta);
				residual = bdd.diffto(residual, delta);
				// item.UpdateCover(bdd.diff(item.cover, residual));
				ChangeItem change_item = new ChangeItem(rule.name, item.rule.name, delta);
				changeset.add(change_item);
			}
		}

		// long t3 = System.nanoTime();
		// System.out.println("lower priority rule: " + (t3-t2)/1000000.0 + "ms");

		bdd.getBDD().deref(delete_item.matches);
		bdd.getBDD().deref(delete_item.rule_bdd);
		rewrite_rules.remove(remove_position);

		return changeset;
	}

	@Override
	public HashSet<Integer> UpdatePortPredicateMap(ArrayList<ChangeItem> changeset, String rule,
			HashMap<String, HashSet<Integer>> moved_batch_aps) {
		HashSet<Integer> moved_aps = new HashSet<Integer>();

		if (changeset.size() == 0) {
			System.out.println("no moved APs");
			return moved_aps;
		}

		Iterator<ChangeItem> it = changeset.iterator();
		while (it.hasNext()) {
			ChangeItem item = it.next();
			String from_port = item.from_port;
			String to_port = item.to_port;
			int delta = bdd.getBDD().ref(item.delta);

			if (port_aps_raw.get(from_port).contains(delta)) {
				TranferOneAP(from_port, to_port, delta, false);
				// int trans = apc.TryMergeAP(delta);
				// moved_aps.add(trans);
				// if (trans != delta) {
				// 	moved_aps.remove(delta);
				// 	int another_ap = bdd.diff(trans, delta);
				// 	moved_aps.remove(another_ap);
				// 	bdd.getBDD().deref(another_ap);
				// }
				continue;
			}
			int add_time = 0;
			HashSet<Integer> apset = new HashSet<Integer>(port_aps_raw.get(from_port));
			Iterator<Integer> ap_it = apset.iterator();
			
			while (ap_it.hasNext() && delta != BDDACLWrapper.BDDFalse) {
				int ap = ap_it.next();
				add_time++;
				int intersect = bdd.getBDD().ref(bdd.getBDD().and(delta, ap));
				if (intersect != BDDACLWrapper.BDDFalse) {
					if (intersect != ap) {
						int dif = bdd.diff(ap, intersect);
						// split the AP in AP Set
						apc.UpdateSplitAP(ap, dif, intersect);
						if (moved_aps.contains(ap)) {
							moved_aps.remove(ap);
							moved_aps.add(dif);
							moved_aps.add(intersect);
							System.out.println("updated a moved AP");
						}
						// bdd.getBDD().deref(dif);//!!!!!!!!!!!!!!!!!!!!!!!!!!
					}
					// locally transfer the AP from from_port to to_port
					TranferOneAP(from_port, to_port, intersect, false);
					// int trans = apc.TryMergeAP(intersect);
					// moved_aps.add(trans);
					// // intersect, another_ap -> trans, need to update the set of moved aps
					// if (trans != intersect) {
					// 	moved_aps.remove(intersect);
					// 	int another_ap = bdd.diff(trans, intersect);
					// 	moved_aps.remove(another_ap);
					// 	bdd.getBDD().deref(another_ap);
					// }
					delta = bdd.diffto(delta, intersect);
					// bdd.getBDD().deref(intersect);//!!!!!!!!!!!!!!!!!!!!!!!!!!
				}
			}

			// LOG.log(String.valueOf(add_time));
			apset = null;
			bdd.getBDD().deref(delta);
		}

		boolean updated = true;
		// boolean updated = false;
		while (updated) {
			updated = UpdateRewriteTable(false);
		}

		// ACL_apc
		it = changeset.iterator();
		while (it.hasNext()) {
			ChangeItem item = it.next();
			String from_port = item.from_port;
			String to_port = item.to_port;
			int delta = bdd.getBDD().ref(item.delta);

			if (ACL_port_aps_raw.get(from_port).contains(delta)) {
				TranferOneAP(from_port, to_port, delta, true);
				// int trans = ACL_apc.TryMergeAP(delta);
				// moved_aps.add(trans);
				// if (trans != delta) {
				// 	moved_aps.remove(delta);
				// 	int another_ap = bdd.diff(trans, delta);
				// 	moved_aps.remove(another_ap);
				// 	bdd.getBDD().deref(another_ap);
				// }
				continue;
			}
			int add_time = 0;
			HashSet<Integer> apset = new HashSet<Integer>(ACL_port_aps_raw.get(from_port));
			Iterator<Integer> ap_it = apset.iterator();
			while (ap_it.hasNext() && delta != BDDACLWrapper.BDDFalse) {
				int ap = ap_it.next();
				add_time++;
				int intersect = bdd.getBDD().ref(bdd.getBDD().and(delta, ap));
				if (intersect != BDDACLWrapper.BDDFalse) {
					if (intersect != ap) {
						int dif = bdd.diff(ap, intersect);
						// split the AP in AP Set
						ACL_apc.UpdateSplitAP(ap, dif, intersect);
						if (moved_aps.contains(ap)) {
							moved_aps.remove(ap);
							moved_aps.add(dif);
							moved_aps.add(intersect);
							System.out.println("updated a moved AP");
						}
						// bdd.getBDD().deref(dif);//!!!!!!!!!!!!!!!!!!!!!!!!!!
					}
					// locally transfer the AP from from_port to to_port
					TranferOneAP(from_port, to_port, intersect, true);
					// int trans = ACL_apc.TryMergeAP(intersect);
					// // int trans = TryMergeAP(intersect, to_port);
					// moved_aps.add(trans);
					// // intersect, another_ap -> trans, need to update the set of moved aps
					// if (trans != intersect) {
					// 	moved_aps.remove(intersect);
					// 	int another_ap = bdd.diff(trans, intersect);
					// 	moved_aps.remove(another_ap);
					// 	bdd.getBDD().deref(another_ap);
					// }
					delta = bdd.diffto(delta, intersect);
					// bdd.getBDD().deref(intersect);//!!!!!!!!!!!!!!!!!!!!!!!!!!
				}
			}

			// LOG.log(String.valueOf(add_time));
			apset = null;
			bdd.getBDD().deref(delta);
		}

		updated = true;
		// updated = false;
		while (updated) {
			updated = UpdateRewriteTable(true);
		}

		return moved_aps;
	}

	public boolean UpdateRewriteTable(boolean isACL) {
		if(isACL)
		{
			HashMap<Integer, HashSet<Integer>> old_rewrite_table = new HashMap<Integer, HashSet<Integer>>(ACL_rewrite_table);
			for (int ap : old_rewrite_table.keySet()) {
				HashSet<Integer> rewrited_aps = ACL_rewrite_table.get(ap);
				for (int rewrited_ap : rewrited_aps) {
					if (!ACL_apc.HasAP(rewrited_ap)) {
						ACL_apc.AddPredicate(rewrited_ap);
						rewrited_aps.remove(rewrited_ap);
						ACL_output_aps.remove(rewrited_ap);
						HashSet<Integer> new_rewited_ap = ACL_apc.getAPExp(rewrited_ap);
						rewrited_aps.addAll(new_rewited_ap);
						ACL_output_aps.addAll(new_rewited_ap);
						return true;
					}
				}
			}
			return false;
		}
		else
		{
			HashMap<Integer, HashSet<Integer>> old_rewrite_table = new HashMap<Integer, HashSet<Integer>>(rewrite_table);
			for (int ap : old_rewrite_table.keySet()) {
				HashSet<Integer> rewrited_aps = rewrite_table.get(ap);
				for (int rewrited_ap : rewrited_aps) {
					if (!apc.HasAP(rewrited_ap)) {
						apc.AddPredicate(rewrited_ap);
						rewrited_aps.remove(rewrited_ap);
						output_aps.remove(rewrited_ap);
						HashSet<Integer> new_rewited_ap = apc.getAPExp(rewrited_ap);
						rewrited_aps.addAll(new_rewited_ap);
						output_aps.addAll(new_rewited_ap);
						return true;
					}
				}
			}
			return false;
		}
	}

	public HashSet<Integer> LookupRewriteTable(HashSet<Integer> old_aps, boolean isACL) {
		if(isACL)
		{
			HashSet<Integer> new_aps = new HashSet<Integer>();
			for (int ap : old_aps) {
				if (ACL_rewrite_table.containsKey(ap)) {
					new_aps.addAll(ACL_rewrite_table.get(ap));
				} else {
					new_aps.add(ap);
				}
			}
			return new_aps;
		}
		else
		{
			HashSet<Integer> new_aps = new HashSet<Integer>();
			for (int ap : old_aps) {
				if (rewrite_table.containsKey(ap)) {
					new_aps.addAll(rewrite_table.get(ap));
				} else {
					new_aps.add(ap);
				}
			}
			return new_aps;
		}
	}

	@Override
	public void UpdateAPSetSplit(String rulename, int origin, int parta, int partb, boolean isACL) {
		if(isACL)
		{
			HashSet<Integer> apset = ACL_port_aps_raw.get(rulename);
			if (!apset.contains(origin)) {
				System.err.println("Error2: " + apset);
				System.exit(1);
			}
			apset.remove(origin);
			apset.add(parta);
			apset.add(partb);
	
			for (HashSet<Integer> aps : ACL_rewrite_table.values()) {
				if (aps.contains(origin)) {
					aps.remove(origin);
					aps.add(parta);
					aps.add(partb);
					if(ACL_output_aps.contains(origin))
					{
						ACL_output_aps.remove(origin);
						ACL_output_aps.add(parta);
						ACL_output_aps.add(partb);
					}
				}
			}
	
			if (!ACL_rewrite_table.containsKey(origin))
				return;
			// update the rewrite table
			// LOG.log("update rewrite table in loop!");
			if (ACL_rewrite_table.get(origin) != null) {
				ACL_output_aps.removeAll(ACL_rewrite_table.get(origin));
			}
			ACL_rewrite_table.remove(origin);
			RewriteRule rule = rule_map.get(rulename);
			int parta_rewrite = bdd.apply_rewrite(parta, rule.field_bdd, rule.new_val_bdd);
			int partb_rewrite = bdd.apply_rewrite(partb, rule.field_bdd, rule.new_val_bdd);
			HashSet<Integer> parta_apset = new HashSet<Integer>();
			parta_apset.add(parta_rewrite);
			ACL_rewrite_table.put(parta, parta_apset);
			ACL_output_aps.add(parta_rewrite);
			HashSet<Integer> partb_apset = new HashSet<Integer>();
			partb_apset.add(partb_rewrite);
			ACL_rewrite_table.put(partb, partb_apset);
			ACL_output_aps.add(partb_rewrite);
		}
		else
		{
			HashSet<Integer> apset = port_aps_raw.get(rulename);
			if (!apset.contains(origin)) {
				System.err.println("Error2: " + apset);
				System.exit(1);
			}
			apset.remove(origin);
			apset.add(parta);
			apset.add(partb);
	
			for (HashSet<Integer> aps : rewrite_table.values()) {
				if (aps.contains(origin)) {
					aps.remove(origin);
					aps.add(parta);
					aps.add(partb);
					if(output_aps.contains(origin))
					{
						output_aps.remove(origin);
						output_aps.add(parta);
						output_aps.add(partb);
					}
				}
			}
	
			if (!rewrite_table.containsKey(origin))
				return;
			// update the rewrite table
			// LOG.log("update rewrite table in loop!");
			if (rewrite_table.get(origin) != null) {
				output_aps.removeAll(rewrite_table.get(origin));
			}
			rewrite_table.remove(origin);
			RewriteRule rule = rule_map.get(rulename);
			int parta_rewrite = bdd.apply_rewrite(parta, rule.field_bdd, rule.new_val_bdd);
			int partb_rewrite = bdd.apply_rewrite(partb, rule.field_bdd, rule.new_val_bdd);
			HashSet<Integer> parta_apset = new HashSet<Integer>();
			parta_apset.add(parta_rewrite);
			rewrite_table.put(parta, parta_apset);
			output_aps.add(parta_rewrite);
			HashSet<Integer> partb_apset = new HashSet<Integer>();
			partb_apset.add(partb_rewrite);
			rewrite_table.put(partb, partb_apset);
			output_aps.add(partb_rewrite);
		}
	}

	public void UpdateAPSetSplit(int origin, int parta, int partb, boolean isACL) {
		if(isACL)
		{
			for (String port : ACL_port_aps_raw.keySet()) {
				if (ACL_port_aps_raw.get(port).contains(origin)) {
					UpdateAPSetSplit(port, origin, parta, partb, isACL);
				}
			}
		}
		else
		{
			for (String port : port_aps_raw.keySet()) {
				if (port_aps_raw.get(port).contains(origin)) {
					UpdateAPSetSplit(port, origin, parta, partb, isACL);
				}
			}
		}
	}

	@Override
	public void UpdateAPSetMerge(String rulename, int merged_ap, int ap1, int ap2, boolean isACL) {
		if(isACL)
		{
			HashSet<Integer> apset = ACL_port_aps_raw.get(rulename);
			if (!apset.contains(ap1) || !apset.contains(ap2)) {
				System.err.println("Error2: " + ap1 + " or " + ap2);
				System.exit(1);
			}
			apset.remove(ap1);
			apset.remove(ap2);
			apset.add(merged_ap);
	
			for (HashSet<Integer> aps : ACL_rewrite_table.values()) {
				if (aps.contains(ap1) && aps.contains(ap2)) {
					aps.remove(ap1);
					aps.remove(ap2);
					aps.add(merged_ap);
					ACL_output_aps.remove(ap1);
					ACL_output_aps.remove(ap2);
					ACL_output_aps.add(merged_ap);
				}
			}
	
			if (!ACL_rewrite_table.containsKey(ap1) && !ACL_rewrite_table.containsKey(ap2))
				return;
	
			// update the rewrite table
			if (ACL_rewrite_table.get(ap1) != null) {
				ACL_output_aps.removeAll(ACL_rewrite_table.get(ap1));
			}
			if (ACL_rewrite_table.get(ap2) != null) {
				ACL_output_aps.removeAll(ACL_rewrite_table.get(ap2));
			}
	
			ACL_rewrite_table.remove(ap1);
			ACL_rewrite_table.remove(ap2);
			RewriteRule rule = rule_map.get(rulename);
			int merged_rewrite = bdd.apply_rewrite(merged_ap, rule.field_bdd, rule.new_val_bdd);
			HashSet<Integer> merged_apset = new HashSet<Integer>();
			merged_apset.add(merged_rewrite);
			ACL_rewrite_table.put(merged_ap, merged_apset);
			ACL_output_aps.add(merged_rewrite);
		}
		else
		{
			HashSet<Integer> apset = port_aps_raw.get(rulename);
			if (!apset.contains(ap1) || !apset.contains(ap2)) {
				System.err.println("Error2: " + ap1 + " or " + ap2);
				System.exit(1);
			}
			apset.remove(ap1);
			apset.remove(ap2);
			apset.add(merged_ap);
	
			for (HashSet<Integer> aps : rewrite_table.values()) {
				if (aps.contains(ap1) && aps.contains(ap2)) {
					aps.remove(ap1);
					aps.remove(ap2);
					aps.add(merged_ap);
					output_aps.remove(ap1);
					output_aps.remove(ap2);
					output_aps.add(merged_ap);
				}
			}
	
			if (!rewrite_table.containsKey(ap1) && !rewrite_table.containsKey(ap2))
				return;
	
			// update the rewrite table
			if (rewrite_table.get(ap1) != null) {
				output_aps.removeAll(rewrite_table.get(ap1));
			}
			if (rewrite_table.get(ap2) != null) {
				output_aps.removeAll(rewrite_table.get(ap2));
			}
	
			rewrite_table.remove(ap1);
			rewrite_table.remove(ap2);
			RewriteRule rule = rule_map.get(rulename);
			int merged_rewrite = bdd.apply_rewrite(merged_ap, rule.field_bdd, rule.new_val_bdd);
			HashSet<Integer> merged_apset = new HashSet<Integer>();
			merged_apset.add(merged_rewrite);
			rewrite_table.put(merged_ap, merged_apset);
			output_aps.add(merged_rewrite);
		}
	}

	public void TranferOneAP(String from_rule, String to_rule, int delta, boolean isACL) {
		// move the ap from from_port to to_port
		if (isACL) {
			ACL_port_aps_raw.get(from_rule).remove(delta);
			ACL_port_aps_raw.get(to_rule).add(delta);

			if (ACL_rewrite_table.containsKey(delta)) {
				HashSet<Integer> old_aps = ACL_rewrite_table.get(delta);
				ACL_output_aps.removeAll(old_aps);
				while (true) {
					HashSet<Integer> new_aps = new HashSet<Integer>(old_aps);
					for (int one_ap : new_aps) {
						if (ACL_apc.AP.contains(one_ap)) {
							ACL_apc.TryMergeAP(one_ap);
						}
					}
					if (old_aps.size() == new_aps.size())
						break;
				}
				old_aps.clear();
			} else {
				ACL_rewrite_table.put(delta, new HashSet<Integer>());
			}

			if (to_rule.equals("default")) {
				ACL_rewrite_table.remove(delta);
			} else {
				RewriteRule rule = rule_map.get(to_rule);
				int delta_rewrite = bdd.apply_rewrite(delta, rule.field_bdd, rule.new_val_bdd);
				ACL_rewrite_table.get(delta).add(delta_rewrite);
			}

			// update the AP edge reference
			ACL_apc.UpdateTransferAP(new PositionTuple(name, from_rule), new PositionTuple(name, to_rule), delta);
		} else {
			port_aps_raw.get(from_rule).remove(delta);
			port_aps_raw.get(to_rule).add(delta);

			if (rewrite_table.containsKey(delta)) {
				HashSet<Integer> old_aps = rewrite_table.get(delta);
				output_aps.removeAll(old_aps);
				while (true) {
					HashSet<Integer> new_aps = new HashSet<Integer>(old_aps);
					for (int one_ap : new_aps) {
						if (apc.AP.contains(one_ap)) {
							apc.TryMergeAP(one_ap);
						}
					}
					if (old_aps.size() == new_aps.size())
						break;
				}

				old_aps.clear();
			} else {
				rewrite_table.put(delta, new HashSet<Integer>());
			}

			if (to_rule.equals("default")) {
				rewrite_table.remove(delta);
			} else {
				RewriteRule rule = rule_map.get(to_rule);
				int delta_rewrite = bdd.apply_rewrite(delta, rule.field_bdd, rule.new_val_bdd);
				rewrite_table.get(delta).add(delta_rewrite);
			}

			// update the AP edge reference
			apc.UpdateTransferAP(new PositionTuple(name, from_rule), new PositionTuple(name, to_rule), delta);
		}
	}

	public void UpdateAPSetMergeBatch(HashSet<String> ports, int merged_ap, HashSet<Integer> aps, boolean isACL) {
		// LOG.INFO("merging " + name + "-" + portname + ": " + aps + "->" + merged_ap);
		for (String port : ports) {
			UpdateAPSetMergeBatch(port, merged_ap, aps, isACL);
		}
	}

	public void UpdateAPSetMergeBatch(String portname, int merged_ap, HashSet<Integer> aps, boolean isACL) {
		if(isACL)
		{
			HashSet<Integer> apset = ACL_port_aps_raw.get(portname);
			// System.out.println("ACL");
			// System.out.println("merging " + name + "-" + portname + ": " + aps + "->" + merged_ap);
			// System.out.println(apset);
			if (!apset.containsAll(aps)) {
				System.err.println("merging " + name + "-" + portname + ": " + aps + "->" + merged_ap);
				System.err.println(apset);
				System.err.println("Error: cannot merge " + aps + " into " + merged_ap);
				System.exit(1);
			}
			apset.removeAll(aps);
			apset.add(merged_ap);
		}
		else
		{
			HashSet<Integer> apset = port_aps_raw.get(portname);
			// System.out.println("FW");
			// System.out.println("merging " + name + "-" + portname + ": " + aps + "->" + merged_ap);
			// System.out.println(apset);
			if (!apset.containsAll(aps)) {
				System.err.println("merging " + name + "-" + portname + ": " + aps + "->" + merged_ap);
				System.err.println(apset);
				System.err.println("Error: cannot merge " + aps + " into " + merged_ap);
				System.exit(1);
			}
			apset.removeAll(aps);
			apset.add(merged_ap);
		}
	}
}