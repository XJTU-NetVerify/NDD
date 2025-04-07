package application.wan.ndd.verifier.apkeep.utils;

public class PrefixItemBDD {

	public int rule_bdd;
	public int matches;
	public int priority;
	public String outinterface;

	public PrefixItemBDD(int p, String iname) {
		priority = p;
		outinterface = iname;
		rule_bdd = 0;
		matches = 0;
	}

	public PrefixItemBDD(int p, String iname, int bdd1) {
		priority = p;
		outinterface = iname;
		rule_bdd = bdd1;
		matches = 0;
	}

	public PrefixItemBDD(int p, String iname, int bdd1, int bdd2) {
		priority = p;
		outinterface = iname;
		rule_bdd = bdd1;
		matches = bdd2;
	}

	@Override
	public boolean equals(Object o) {
		PrefixItemBDD another = (PrefixItemBDD) o;
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
