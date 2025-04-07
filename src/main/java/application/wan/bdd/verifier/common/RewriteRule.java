package application.wan.bdd.verifier.common;

public class RewriteRule {

    public String name;

    public int field_bdd;
    public int new_val_bdd;
    public int old_pkt_bdd;

    // public String new_dst_ip;
    public int priority;

    public RewriteRule(long old_src_prefix, int old_src_len, long old_dst_prefix, int old_dst_len, long new_src_prefix,
            int new_src_len, long new_dst_prefix, int new_dst_len, String rname, int p, BDDACLWrapper baw) {
        int old_src;
        if (old_src_len == 0) {
            old_src = baw.BDDTrue;
        } else {
            old_src = baw.encodeSrcIPPrefix(old_src_prefix, old_src_len);
        }
        int old_dst;
        if (old_dst_len == 0) {
            old_dst = baw.BDDTrue;
        } else {
            old_dst = baw.encodeDstIPPrefix(old_dst_prefix, old_dst_len);
        }
        old_pkt_bdd = baw.aclBDD.ref(baw.aclBDD.and(old_src, old_dst));
        baw.aclBDD.deref(old_src);
        baw.aclBDD.deref(old_dst);

        int new_src;
        if (new_src_len == 0) {
            new_src = baw.BDDTrue;
        } else {
            new_src = baw.encodeSrcIPPrefix(new_src_prefix, new_src_len);
        }
        int new_dst;
        if (new_dst_len == 0) {
            new_dst = baw.BDDTrue;
        } else {
            new_dst = baw.encodeDstIPPrefix(new_dst_prefix, new_dst_len);
        }
        new_val_bdd = baw.aclBDD.ref(baw.aclBDD.and(new_src, new_dst));
        baw.aclBDD.deref(new_src);
        baw.aclBDD.deref(new_dst);

        field_bdd = baw.IPField;
        name = rname;
        priority = p;
    }

    public RewriteRule(int old_val, int field, int new_val, String rname, int p) {
        old_pkt_bdd = old_val;
        field_bdd = field;
        new_val_bdd = new_val;
        name = rname;
        priority = p;
    }

    @Override
    public boolean equals(Object o) {
        RewriteRule rule = (RewriteRule) o;
        if (!name.equals(rule.name))
            return false;
        if (priority != rule.priority)
            return false;
        return true;
    }
    // public void setDstIP(String prefix)
    // {
    // new_dst_ip = prefix;
    // }

    public void setName(String rname) {
        // TODO Auto-generated method stub
        name = rname;
    }

    public String toString() {
        return old_pkt_bdd + ", " + new_val_bdd;
    }
}
