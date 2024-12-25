package application.wlan.ndd.verifier.apkeep.core;

public class BDDRuleItemBDD<T> {
	public T rule; // the OpenFlow rule
	public int rule_bdd; // the BDD encoding of rule
	public int matches; // the matched packets

	public BDDRuleItemBDD()
	{
		rule = null;
		rule_bdd = 0;
		matches = 0;
	}

	public BDDRuleItemBDD(T r, int bdd)
	{
		rule = r;
		rule_bdd = bdd;
		matches = 0;
	}
	
	public BDDRuleItemBDD(T r, int bdd, int m)
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
