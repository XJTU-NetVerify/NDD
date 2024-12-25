package application.wlan.ndd.verifier.apkeep.core;

import ndd.jdd.diagram.NDD;

public class BDDRuleItem<T> {
	public T rule; // the OpenFlow rule
	public NDD rule_bdd; // the BDD encoding of rule
	public NDD matches; // the matched packets

	public BDDRuleItem()
	{
		rule = null;
		rule_bdd = NDD.getFalse();
		matches = NDD.getFalse();
	}

	public BDDRuleItem(T r, NDD bdd)
	{
		rule = r;
		rule_bdd = bdd;
		matches = NDD.getFalse();
	}
	
	public BDDRuleItem(T r, NDD bdd, NDD m)
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
