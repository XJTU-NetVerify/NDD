package application.wlan.bdd.verifier.apkeep.utils;

import application.wlan.bdd.verifier.common.BDDACLWrapper;

public class PrefixItem {

	public int rule_bdd;
	public int matches;
	public int priority;
	public String outinterface;

	public PrefixItem (int p, String iname)
	{
		priority = p;
		outinterface = iname;
		rule_bdd = BDDACLWrapper.BDDFalse;
		matches = BDDACLWrapper.BDDFalse;
	}
	
	public PrefixItem (int p, String iname, int bdd1)
	{
		priority = p;
		outinterface = iname;
		rule_bdd = bdd1;
		matches = BDDACLWrapper.BDDFalse;
	}
	
	public PrefixItem (int p, String iname, int bdd1, int bdd2)
	{
		priority = p;
		outinterface = iname;
		rule_bdd = bdd1;
		matches = bdd2;
	}

	@Override
	public boolean equals(Object o)
	{
		PrefixItem another = (PrefixItem) o;
		if (priority != another.priority) 
			return false;
		if (!outinterface.equals(another.outinterface))
			return false;
		
		return true;
	}
	
	public int GetPriority ()
	{
		return priority;
	}
	
	public String toString ()
	{
		return priority + "; " + outinterface;
	}
	
	public static void main(String[] args) {
		// TODO Auto-generated method stub
	}

}
