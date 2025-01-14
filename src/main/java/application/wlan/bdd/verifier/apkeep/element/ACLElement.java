package application.wlan.bdd.verifier.apkeep.element;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

import application.wlan.bdd.exp.EvalDataplaneVerifier;
import application.wlan.bdd.verifier.apkeep.core.BDDRuleItem;
import application.wlan.bdd.verifier.apkeep.core.ChangeItem;
import application.wlan.bdd.verifier.common.ACLRule;

import application.wlan.bdd.verifier.common.BDDACLWrapper;
import application.wlan.ndd.exp.EvalDataplaneVerifierNDDAP;
import jdd.bdd.BDD;
import org.ants.jndd.diagram.NDD;

public class ACLElement extends Element{

	LinkedList<BDDRuleItem<ACLRule>> acl_rule;
	
	public ACLElement(String ename)
	{
		super(ename);
		acl_rule = new LinkedList<BDDRuleItem<ACLRule>>();
	}
	
	public void Initialize() {
		// initialize the rule list with a default deny rule
		ACLRule rule = new ACLRule();
		rule.permitDeny = "deny";
		rule.priority = -1;
		BDDRuleItem<ACLRule> new_rule = new BDDRuleItem<ACLRule>(rule, BDDACLWrapper.BDDTrue, BDDACLWrapper.BDDTrue);
		acl_rule.add(new_rule);
		
		// initialize the AP set for port deny
		String deny_port = "deny";
		HashSet<Integer> alltrue = new HashSet<Integer>();
		alltrue.addAll(apc.AP);
		port_aps_raw.put(deny_port, alltrue);
		
		// initialize the AP set for port permit
		String permit_port = "permit";
		HashSet<Integer> allfalse = new HashSet<Integer>();
		port_aps_raw.put(permit_port, allfalse);
	}
	
	public ArrayList<ChangeItem> InsertACLRule(ACLRule rule){

		ArrayList<ChangeItem> changeset = new ArrayList<ChangeItem>();
		
		int rule_bdd = bdd.ConvertACLRule(rule);
		BDD thebdd = bdd.getBDD();
		int priority = rule.getPriority();
		int residual = bdd.getBDD().ref(rule_bdd);
		int residual2 = BDDACLWrapper.BDDFalse;
		int cur_position = 0;
		boolean inserted = false;

		BDDRuleItem<ACLRule> default_item = acl_rule.getLast();
		
		Iterator<BDDRuleItem<ACLRule>> it = acl_rule.iterator();
		while (it.hasNext() && residual != BDDACLWrapper.BDDFalse) {
			BDDRuleItem<ACLRule> item = it.next();
			// TODO: fast check whether the rule is not affected by any rule
			if(item.rule.getPriority() >= priority){
				if(residual != BDDACLWrapper.BDDFalse && thebdd.and(residual, item.rule_bdd) != BDDACLWrapper.BDDFalse) {
					residual = bdd.diffto(residual, item.rule_bdd);
				}
				cur_position ++;
			}
			else{
				if(!inserted) {
					// fast check whether the default rule is the only rule affected
					int temp = bdd.diff(residual, default_item.matches);
					if (temp == BDDACLWrapper.BDDFalse) {
						default_item.matches = bdd.diffto(default_item.matches, residual);
						if (!default_item.rule.permitDeny.equals(rule.get_type())) {
							ChangeItem change_item = new ChangeItem(default_item.rule.permitDeny, rule.get_type(), residual);
							changeset.add(change_item);
						}
						break;
					}
						
					bdd.getBDD().deref(temp);
					residual2 = bdd.getBDD().ref(residual);
					inserted = true;
				}
				
				if(residual2 == BDDACLWrapper.BDDFalse) {
					break;
				}
				
				int delta = bdd.getBDD().ref(bdd.getBDD().and(item.matches, residual2));
				if(delta != BDDACLWrapper.BDDFalse){
					item.matches = bdd.diffto(item.matches, delta);
					residual2 = bdd.diffto(residual2, delta);
					
					String foward_port = item.rule.get_type();
					
					if (!foward_port.equals(rule.get_type())) {
						ChangeItem change_item = new ChangeItem(foward_port, rule.get_type(), delta);
						changeset.add(change_item);
					}
				}
			}
		}
		
		// add the new rule into the installed forwarding rule list
		BDDRuleItem<ACLRule> new_rule = new BDDRuleItem<ACLRule>(rule, rule_bdd);
		new_rule.matches = residual;
		acl_rule.add(cur_position, new_rule);
		
		// check whether the forwarding port exists, if not create it, 
		// and initialize the AP set of the port to empty
		String iname = rule.get_type();
		if(!port_aps_raw.containsKey(iname)) {
			HashSet<Integer> aps_raw = new HashSet<Integer>();
			port_aps_raw.put(iname, aps_raw);
		}
		
		if (residual2 != BDDACLWrapper.BDDFalse){
			bdd.getBDD().deref(residual2);
		}
		
		return changeset;
	}
	
	public ArrayList<ChangeItem> RemoveACLRule(ACLRule rule){
		ArrayList<ChangeItem> changeset = new ArrayList<ChangeItem>();
		Iterator <BDDRuleItem<ACLRule>> it = acl_rule.iterator();
		BDDRuleItem<ACLRule> delete_item = null;
		int remove_position = 0;
		boolean isFound = false;
		while (it.hasNext()) {
			delete_item = it.next();
			if(delete_item.rule.equals(rule)) {
				isFound = true;
				break;
			}
			remove_position ++;
		}
        if(!isFound){
        	System.err.println("Rule not found: " + rule);
        	System.exit(0);
        	return changeset;
        }
		
		int residual = bdd.getBDD().ref(delete_item.matches);
		while (it.hasNext() && residual != BDDACLWrapper.BDDFalse){
			BDDRuleItem<ACLRule> item = it.next();
			int delta = bdd.getBDD().ref(bdd.getBDD().and(residual, item.rule_bdd));
			if (delta != BDDACLWrapper.BDDFalse) {
				item.matches = bdd.getBDD().orTo(item.matches, delta);
				residual = bdd.diffto(residual, delta);
				String foward_port = item.rule.get_type();
				if (!foward_port.equals(rule.get_type())) {
					ChangeItem change_item = new ChangeItem(rule.get_type(), foward_port, delta);
					changeset.add(change_item);
				}
			}
		}
		
		bdd.getBDD().deref(delete_item.matches);
		bdd.getBDD().deref(delete_item.rule_bdd);
		acl_rule.remove(remove_position);
	
		return changeset;
	}
	public HashSet<String> getAPPorts(int ap) {
		HashSet<String> ports = new HashSet<String>();
		for(String port : port_aps_raw.keySet()) {
			if(port_aps_raw.get(port).contains(ap)) {
				ports.add(port);
			}
		}
		return ports;
	}

	//new added
	public String getAPPort(int ap) {
		// TODO Auto-generated method stub
		for(String port : port_aps_raw.keySet()) {
			if(port_aps_raw.get(port).contains(ap)) {
				return port;
			}
		}
		return "deny";
	}
}
