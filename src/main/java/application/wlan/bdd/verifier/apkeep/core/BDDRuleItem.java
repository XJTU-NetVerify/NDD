package application.wlan.bdd.verifier.apkeep.core;

import application.wlan.bdd.verifier.common.BDDACLWrapper;

public class BDDRuleItem<T> {
	public T rule; // the OpenFlow rule
	public int rule_bdd; // the BDD encoding of rule
	public int matches; // the matched packets

	public BDDRuleItem()
	{
		rule = null;
		rule_bdd = BDDACLWrapper.BDDFalse;
		matches = BDDACLWrapper.BDDFalse;
	}

	public BDDRuleItem(T r, int bdd)
	{
		rule = r;
		rule_bdd = bdd;
		matches = BDDACLWrapper.BDDFalse;
	}
	
	public BDDRuleItem(T r, int bdd, int m)
	{
		rule = r;
		rule_bdd = bdd;
		matches = m;
	}
	
	public T GetRule()
	{
		return rule;
	}
}
