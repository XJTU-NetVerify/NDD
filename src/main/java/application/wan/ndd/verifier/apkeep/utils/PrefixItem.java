package application.wan.ndd.verifier.apkeep.utils;

import org.ants.jndd.diagram.NDD;

public class PrefixItem {

	public NDD rule_bdd;
	public NDD matches;
	public int priority;
	public String outinterface;

	public PrefixItem(int p, String iname) {
		priority = p;
		outinterface = iname;
		rule_bdd = NDD.getFalse();
		matches = NDD.getFalse();
	}

	public PrefixItem(int p, String iname, NDD bdd1) {
		priority = p;
		outinterface = iname;
		rule_bdd = bdd1;
		matches = NDD.getFalse();
	}

	public PrefixItem(int p, String iname, NDD bdd1, NDD bdd2) {
		priority = p;
		outinterface = iname;
		rule_bdd = bdd1;
		matches = bdd2;
	}

	@Override
	public boolean equals(Object o) {
		PrefixItem another = (PrefixItem) o;
		if (priority != another.priority)
			return false;
		if (!outinterface.equals(another.outinterface))
			return false;

		return true;
	}

	public int GetPriority() {
		return priority;
	}

	public String toString() {
		return priority + "; " + outinterface;
	}

	public static void main(String[] args) {
		// TODO Auto-generated method stub
	}

}
