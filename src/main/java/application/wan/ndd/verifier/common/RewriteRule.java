package application.wan.ndd.verifier.common;

import java.util.ArrayList;

import org.ants.jndd.diagram.NDD;

public class RewriteRule {

	public String name;

	public ArrayList<Integer> fields;
	public NDD new_src;
	public NDD new_dst;
	public NDD old_pkt_bdd;

	public RewriteRule(NDD old_val, ArrayList<Integer> f, NDD new_src, NDD new_dst, String rname) {
		old_pkt_bdd = old_val;
		fields = f;
		this.new_src = new_src;
		this.new_dst = new_dst;
		name = rname;
	}

	@Override
	public boolean equals(Object o) {
		RewriteRule rule = (RewriteRule) o;
		if (!name.equals(rule.name))
			return false;
		return true;
	}

	public void setName(String rname) {
		// TODO Auto-generated method stub
		name = rname;
	}
}
