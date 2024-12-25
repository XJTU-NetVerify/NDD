package application.wlan.ndd.verifier.apkeep.core;

import java.util.HashSet;

public class ChangeTupleBDD {
	public HashSet<String> from_ports;
	public HashSet<String> to_ports;
	public HashSet<Integer> delta_set;

	public ChangeTupleBDD(HashSet<String> ports1, HashSet<String> ports2, HashSet<Integer> set) {
		// TODO Auto-generated constructor stub
		from_ports = new HashSet<String>(ports1);
		to_ports = new HashSet<String>(ports2);
		delta_set = new HashSet<Integer>(set);
	}
	@Override
	public boolean equals(Object o) {
		if(o instanceof ChangeTupleBDD) {
			ChangeTupleBDD another = (ChangeTupleBDD) o;
			if(!another.from_ports.equals(from_ports)) return false;
			if(!another.to_ports.equals(to_ports)) return false;
			if(!another.delta_set.equals(delta_set)) return false;
			return true;
		}
		return false;
	}
    @Override
    public int hashCode() {
        return from_ports.hashCode() + to_ports.hashCode() + delta_set.hashCode();
    }
}