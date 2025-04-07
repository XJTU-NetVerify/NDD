package application.wan.bdd.verifier.apkeep.utils;

import java.util.HashSet;

import application.wan.bdd.verifier.apkeep.checker.Property;

public class Parameters {

	public static int BDD_TABLE_SIZE = 10000000;
	public static HashSet<Property> PROPERTIES_TO_CHECK = new HashSet<Property>() {
		{
			add(Property.Reachability);
		}
	};

	public Parameters() {
		// TODO Auto-generated constructor stub
	}
}
