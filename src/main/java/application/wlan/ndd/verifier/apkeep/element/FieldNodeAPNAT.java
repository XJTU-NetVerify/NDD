package application.wlan.ndd.verifier.apkeep.element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import application.wlan.ndd.verifier.apkeep.core.BDDRuleItem;
import application.wlan.ndd.verifier.apkeep.core.ChangeItem;
import application.wlan.ndd.verifier.apkeep.core.NetworkNDDAPNAT;
import application.wlan.ndd.verifier.common.RewriteRule;
import javafx.util.Pair;
import ndd.jdd.diagram.AtomizedNDD;
import ndd.jdd.diagram.NDD;

public class FieldNodeAPNAT extends FieldNodeAP {
    public HashMap<String, Pair<NDD, NDD>> ports_action_pred;
    public HashMap<String, Pair<AtomizedNDD, AtomizedNDD>> ports_action_aps;
    public static NetworkNDDAPNAT network = null;
    public FieldNodeAPNAT(String ename, NetworkNDDAPNAT net, int type) {
        super(ename, net, type);
        network = net;
        ports_action_pred = new HashMap<>();
        ports_action_aps = new HashMap<>();
    }

    public ArrayList<ChangeItem> InsertRewriteRule(RewriteRule rule)
    {
        if (!ports.contains(rule.name)) {
            ports.add(rule.name);
            ports_pred.put(rule.name, NDD.getFalse());
            ports_aps.put(rule.name, AtomizedNDD.getFalse());
            // System.out.println(name+" "+rule.name+" "+NDD.toBDD(rule.new_src)+" "+NDD.toBDD(rule.new_dst));
            ports_action_pred.put(rule.name, new Pair<>(rule.new_src, rule.new_dst));
            AtomizedNDD action1 = AtomizedNDD.getTrue();
            if(!rule.new_src.isTrue())
            {
                HashSet<Integer> delta_aps = new HashSet<>();
                HashMap<Integer, HashSet<Integer>> split_ap = new HashMap<>();
                AtomizedNDD.getAtomsToSplitSingleField(rule.new_src, delta_aps, split_ap, 0);
                HashMap<AtomizedNDD, HashSet<Integer>> tempMap = new HashMap<>();
                tempMap.put(action1, delta_aps);
                action1 = AtomizedNDD.ref(AtomizedNDD.mkAtomized(0,tempMap));
                network.split_ap_one_field(split_ap, 0);
                for(int ap : delta_aps)
                {
                    network.splitMapAction.ap_ports[0].get(ap).add(new Pair<>(name, rule.name));
                }
            }

            AtomizedNDD action2 = AtomizedNDD.getTrue();
            if(!rule.new_dst.isTrue())
            {
                HashSet<Integer> delta_aps = new HashSet<>();
                HashMap<Integer, HashSet<Integer>> split_ap = new HashMap<>();
                AtomizedNDD.getAtomsToSplitSingleField(rule.new_dst, delta_aps, split_ap, 1);
                HashMap<AtomizedNDD, HashSet<Integer>> tempMap = new HashMap<>();
                tempMap.put(action2, delta_aps);
                action2 = AtomizedNDD.ref(AtomizedNDD.mkAtomized(1,tempMap));
                network.split_ap_one_field(split_ap, 1);
                for(int ap : delta_aps)
                {
                    network.splitMapAction.ap_ports[1].get(ap).add(new Pair<>(name, rule.name));
                }
            }
            ports_action_aps.put(rule.name, new Pair<>(action1, action2));
        }

        ArrayList<ChangeItem> changeset = new ArrayList<ChangeItem>();
        int cur_position = 0;
        BDDRuleItem<RewriteRule> default_item = rewrite_rules.getLast();
        changeset.add(new ChangeItem(default_item.rule.name, rule.name, NDD.ref(rule.old_pkt_bdd)));

        // add the new rule into the installed forwarding rule list
        BDDRuleItem<RewriteRule> new_rule = new BDDRuleItem<RewriteRule>(rule, rule.old_pkt_bdd);
        new_rule.matches = rule.old_pkt_bdd;
        rewrite_rules.add(cur_position, new_rule);
        if(!rule_map.containsKey(rule.name)) rule_map.put(rule.name, new HashSet<>());
        rule_map.get(rule.name).add(rule);

        return changeset;
    }
}