package application.wlan.ndd.verifier.apkeep.element;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import application.wlan.ndd.verifier.apkeep.core.*;
import application.wlan.ndd.verifier.apkeep.utils.*;
import application.wlan.ndd.verifier.common.ACLRule;
import application.wlan.ndd.verifier.common.BDDACLWrapper;
import application.wlan.ndd.verifier.common.ForwardingRule;
import application.wlan.ndd.verifier.common.RewriteRule;
import javafx.util.*;
import jdd.bdd.BDD;
import ndd.jdd.diagram.NDD;

public class FieldNode {
    public static NetworkNDDPred network = null;
    public static BDDACLWrapper bdd = null;
    public String name;
    public int type;
    public LinkedList<BDDRuleItem<ForwardingRule>> fw_rule; // fw
    public LinkedList<BDDRuleItem<ACLRule>> acl_rule; // acl
    public LinkedList<BDDRuleItemBDD<ACLRule>> acl_ruleBDD; // acl
    public TrieTree trie; // fw
    public TrieTreeBDD trieBDD; // fw
    public LinkedList<BDDRuleItem<RewriteRule>> rewrite_rules;// rewrite
    public HashMap<String, HashSet<RewriteRule>> rule_map;
    public HashSet<String> ports;
    public HashMap<String, NDD> ports_pred;

    public FieldNode() {
    }

    public FieldNode(String ename, NetworkNDDPred net, int type) {
        this.name = ename;
        this.type = type;
        ports = new HashSet<String>();
        ports_pred = new HashMap<String, NDD>();
        if (type == 0) {
            if(NetworkNDDAP.encodeWithNDD)
            {
                trie = new TrieTree();
            }
            else
            {
                trieBDD = new TrieTreeBDD();
            }
            fw_rule = new LinkedList<BDDRuleItem<ForwardingRule>>();
        } else if (type == 1) {
            ACLRule rule = new ACLRule();
            rule.permitDeny = "permit";
            rule.priority = -1;
            // if(NetworkNDDAP.encodeWithNDD)
            // {
                BDDRuleItem<ACLRule> new_rule = new BDDRuleItem<ACLRule>(rule, NDD.getTrue(), NDD.getTrue());
                acl_rule = new LinkedList<BDDRuleItem<ACLRule>>();
                acl_rule.add(new_rule);
            // }
            // else
            // {
            //     BDDRuleItemBDD<ACLRule> new_rule = new BDDRuleItemBDD<ACLRule>(rule, 1, 1);
            //     acl_ruleBDD = new LinkedList<BDDRuleItemBDD<ACLRule>>();
            //     acl_ruleBDD.add(new_rule);
            // }
        } else if (type == -1) {
            ACLRule rule = new ACLRule();
            rule.permitDeny = "deny";
            rule.priority = -1;
            // if(NetworkNDDAP.encodeWithNDD)
            // {
                BDDRuleItem<ACLRule> new_rule = new BDDRuleItem<ACLRule>(rule, NDD.getTrue(), NDD.getTrue());
                acl_rule = new LinkedList<BDDRuleItem<ACLRule>>();
                acl_rule.add(new_rule);
            // }
            // else
            // {
            //     BDDRuleItemBDD<ACLRule> new_rule = new BDDRuleItemBDD<ACLRule>(rule, 1, 1);
            //     acl_ruleBDD = new LinkedList<BDDRuleItemBDD<ACLRule>>();
            //     acl_ruleBDD.add(new_rule);
            // }
        } else if (type == 2) {
            rewrite_rules = new LinkedList<>();
            rule_map = new HashMap<>();
            String default_rule_name = "default";
            RewriteRule default_rule = new RewriteRule(NDD.getTrue(), new ArrayList<Integer>(), NDD.getTrue(), NDD.getTrue(), default_rule_name);
            BDDRuleItem<RewriteRule> default_item = new BDDRuleItem<RewriteRule>(default_rule, NDD.getTrue(), NDD.getTrue());
            rewrite_rules.add(default_item);
            HashSet<RewriteRule> rules = new HashSet<>();
            rules.add(default_rule);
            rule_map.put(default_rule_name, rules);
        }
        bdd = net.bdd_engine;
        if (type == 0) {
            ports.add("default");
            ports_pred.put("default", NDD.getTrue());
        } else if (type == 1) {
            ports.add("permit");
            ports_pred.put("permit", NDD.getTrue());
        } else if (type == -1) {
            ports.add("deny");
            ports_pred.put("deny", NDD.getTrue());
        }
        else if (type == 2)
        {
            ports.add("default");
            ports_pred.put("default", NDD.getTrue());
        }
    }

    Comparator<PrefixItem> byPriority = new Comparator<PrefixItem>() {
        @Override
        public int compare(PrefixItem p1, PrefixItem p2) {
            return p2.priority - p1.priority;
        }
    };

    Comparator<PrefixItemBDD> byPriorityBDD = new Comparator<PrefixItemBDD>() {
        @Override
        public int compare(PrefixItemBDD p1, PrefixItemBDD p2) {
            return p2.priority - p1.priority;
        }
    };

    public ArrayList<PrefixItem> GetAffectedRules(TrieTreeNode node) {
        ArrayList<PrefixItem> affected_rules = new ArrayList<PrefixItem>();
        // add the descendants
        ArrayList<TrieTreeNode> descendants = node.GetDescendant();
        if (descendants != null) {
            Iterator<TrieTreeNode> it = node.GetDescendant().iterator();
            while (it.hasNext()) {
                ArrayList<PrefixItem> items = it.next().GetPrefixItems();
                affected_rules.addAll(items);
            }
        }
        // add the ancestors
        ArrayList<TrieTreeNode> ancestors = node.GetAncestor();
        if (ancestors != null) {
            Iterator<TrieTreeNode> it = node.GetAncestor().iterator();
            while (it.hasNext()) {
                ArrayList<PrefixItem> items = it.next().GetPrefixItems();
                affected_rules.addAll(items);
            }
        }
        // // add the siblings
        affected_rules.addAll(node.GetPrefixItems());
        affected_rules.sort(byPriority);
        return affected_rules;
    }

    public ArrayList<PrefixItemBDD> GetAffectedRules(TrieTreeNodeBDD node) {
        ArrayList<PrefixItemBDD> affected_rules = new ArrayList<PrefixItemBDD>();
        // add the descendants
        ArrayList<TrieTreeNodeBDD> descendants = node.GetDescendant();
        if (descendants != null) {
            Iterator<TrieTreeNodeBDD> it = node.GetDescendant().iterator();
            while (it.hasNext()) {
                ArrayList<PrefixItemBDD> items = it.next().GetPrefixItems();
                affected_rules.addAll(items);
            }
        }
        // add the ancestors
        ArrayList<TrieTreeNodeBDD> ancestors = node.GetAncestor();
        if (ancestors != null) {
            Iterator<TrieTreeNodeBDD> it = node.GetAncestor().iterator();
            while (it.hasNext()) {
                ArrayList<PrefixItemBDD> items = it.next().GetPrefixItems();
                affected_rules.addAll(items);
            }
        }
        // // add the siblings
        affected_rules.addAll(node.GetPrefixItems());
        affected_rules.sort(byPriorityBDD);
        return affected_rules;
    }

    public void updateFWRuleBatch(String ip, HashSet<String> to_ports, HashSet<String> from_ports,
                                  HashMap<String, ArrayList<ChangeTuple>> change_set, HashMap<String, ArrayList<ChangeTuple>> copyto_set,
                                  HashMap<String, ArrayList<ChangeTuple>> remove_set) {
        // find the exact node in prefixTree
        long destip = Long.parseLong(ip.split("/")[0]);
        int prefixlen = Integer.parseInt(ip.split("/")[1]);
        int priority = prefixlen;
        ArrayList<ChangeTuple> change = new ArrayList<ChangeTuple>();
        ArrayList<ChangeTuple> copyto = new ArrayList<ChangeTuple>();
        ArrayList<ChangeTuple> remove = new ArrayList<ChangeTuple>();
        TrieTreeNode node = trie.Search(destip, prefixlen);
        if (node == null) {
            /*
             * no same prefix was inserted in this element before from_ports must be empty
             */
            node = trie.Insert(destip, prefixlen);
            // /*
            // * if(!from_ports.isEmpty()) {
            // * if(node == null){
            // * System.err.println(name + ", node not found: "+ destip + "/" + prefixlen +
            // "; "
            // * + "outinterface: " + from_ports.toArray()[0] + "; priority: " + priority);
            // * System.exit(1);
            // * }
            // * }
            // */
            NDD rule_ndd = bdd.GetPrefixNDD(destip, prefixlen);
            ArrayList<PrefixItem> affected_rules = GetAffectedRules(node);
            NDD residual = NDD.ref(rule_ndd);
            NDD residual2 = NDD.getFalse();
            boolean inserted = false;
            int last_priority = 65535;
            NDD last_sum = NDD.getFalse();
            Iterator<PrefixItem> it = affected_rules.iterator();
            while (it.hasNext()) {
                PrefixItem item = it.next();
                if (item.priority > priority) {
                    NDD t = residual;
                    residual = NDD.ref(NDD.diff(residual, item.rule_bdd));
                    NDD.deref(t);
                    if (residual.isFalse()) {
                        break;
                    }
                }
                /*
                 * no same prefix was inserted before else if(item.priority == priority) {
                 * System.exit(1); }
                 */
                else {
                    if (!inserted) {
                        residual2 = residual;
                        NDD.ref(residual2);
                        inserted = true;
                        last_priority = item.priority;
                    }
                    if (last_priority != item.priority) {
                        NDD t = residual2;
                        residual2 = NDD.ref(NDD.diff(residual2, last_sum));
                        NDD.deref(t);
                        last_sum = NDD.getFalse();
                        last_priority = item.priority;
                    }
                    if (residual2.isFalse()) {
                        break;
                    }
                    NDD delta = NDD.ref(NDD.and(item.matches, residual2));
                    if (delta.isFalse()) {
                        continue;
                    }
                    NDD t1 = item.matches;
                    NDD t2 = last_sum;
                    item.matches = NDD.ref(NDD.diff(item.matches, delta));
                    last_sum = NDD.ref(NDD.or(last_sum, delta));
                    NDD.deref(t1);
                    NDD.deref(t2);
                    HashSet<String> ports = new HashSet<String>();
                    ports.add(item.outinterface);
                    HashSet<NDD> delta_set = new HashSet<NDD>();
                    delta_set.add(delta);
                    ChangeTuple ct = new ChangeTuple(ports, to_ports, delta_set);
                    if (!ct.from_ports.equals(ct.to_ports))
                    {
                        NDD.ref(delta);
                        change.add(ct);
                    }
                    NDD.deref(delta);
                }
            }
            /*
             * if (residual2 != BDDACLWrapper.BDDFalse) {
             * System.out.println("not overriding any low-priority rule"); System.exit(0); }
             */

            NDD hit_bdd = residual;
            for (String port : to_ports) {
                NDD.ref(rule_ndd);
                NDD.ref(hit_bdd);
                PrefixItem insert_item = new PrefixItem(priority, port, rule_ndd, hit_bdd);
                // check whether the forwarding port exists, if not create it,
                // and initialize the AP set of the port to empty
                if (!ports.contains(port)) {
                    ports.add(port);
                    ports_pred.put(port, NDD.getFalse());
                }
                // insert the rule
                node.AddPrefixItem(insert_item);
            }
            NDD.deref(rule_ndd);
            NDD.deref(residual);
            NDD.deref(residual2);
        } else { // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                 // System.out.println("tag");
                 // we have pre-rules, just use matches field to give a short cut
            ArrayList<PrefixItem> node_rules = node.GetPrefixItems();
            ArrayList<PrefixItem> insert_items = new ArrayList<PrefixItem>();
            ArrayList<PrefixItem> delete_items = new ArrayList<PrefixItem>();
            NDD rule_ndd = NDD.getFalse();
            NDD hit_bdd = NDD.getFalse();
            if (!node_rules.isEmpty()) {
                rule_ndd = node_rules.get(0).rule_bdd;
                hit_bdd = node_rules.get(0).matches;
                if (node_rules.size() == 1 && node_rules.get(0).priority == -1) {
                    // we hit the default rule
                    String outinterface = "default";
                    NDD delta = node_rules.get(0).matches;
                    node_rules.get(0).matches = NDD.getFalse();
                    // bdd.getBDD().deref(delta);
                    HashSet<String> ports = new HashSet<String>();
                    ports.add(outinterface);
                    HashSet<NDD> delta_set = new HashSet<NDD>();
                    delta_set.add(delta);
                    ChangeTuple ct = new ChangeTuple(ports, to_ports, delta_set);
                    if (!ct.from_ports.equals(ct.to_ports))
                    {
                        NDD.ref(delta);
                        change.add(ct);
                    }
                } else if (node_rules.size() != 1 && node_rules.get(0).priority == -1) {
                    rule_ndd = node_rules.get(1).rule_bdd;
                    hit_bdd = node_rules.get(1).matches;
                    if (from_ports.isEmpty()) {
                        String outinterface = node_rules.get(1).outinterface;
                        HashSet<String> ports = new HashSet<String>();
                        ports.add(outinterface);
                        HashSet<NDD> delta_set = new HashSet<NDD>();
                        delta_set.add(hit_bdd);
                        ChangeTuple ct = new ChangeTuple(ports, to_ports, delta_set);
                        if (!ct.from_ports.containsAll(ct.to_ports))
                        {
                            NDD.ref(hit_bdd);
                            copyto.add(ct);
                        }
                    }
                } else {
                    // node_rules.get(0).priority != -1 case
                    if (from_ports.isEmpty()) {
                        String outinterface = node_rules.get(0).outinterface;
                        HashSet<String> ports = new HashSet<String>();
                        ports.add(outinterface);
                        HashSet<NDD> delta_set = new HashSet<NDD>();
                        delta_set.add(hit_bdd);
                        ChangeTuple ct = new ChangeTuple(ports, to_ports, delta_set);
                        if (!ct.from_ports.containsAll(ct.to_ports))
                        {
                            NDD.ref(hit_bdd);
                            copyto.add(ct);
                        }
                    }
                }
            }
            /*
             * else { System.err.println("node is invalid!"); System.exit(1); }
             */
            for (String port : to_ports) {
                NDD.ref(rule_ndd);
                NDD.ref(hit_bdd);
                insert_items.add(new PrefixItem(priority, port, rule_ndd, hit_bdd));
                if (!ports.contains(port)) {
                    ports.add(port);
                    ports_pred.put(port, NDD.getFalse());
                }
            }
            for (String port : from_ports) {
                NDD.deref(rule_ndd);
                NDD.deref(hit_bdd);
                PrefixItem delete_rule = new PrefixItem(priority, port, rule_ndd, hit_bdd);
                if (!node.HasPrefixItem(delete_rule)) {
                    System.exit(1);
                }
                delete_items.add(delete_rule);
            }
            node_rules.removeAll(delete_items);
            node_rules.addAll(insert_items);

            if (node.IsInValid()) {
                ArrayList<PrefixItem> affected_rules = GetAffectedRules(node);
                NDD residual = NDD.ref(hit_bdd);
                int last_priority = 65535;
                NDD last_sum = NDD.getFalse();
                boolean inserted = false;
                Iterator<PrefixItem> it = affected_rules.iterator();
                while (it.hasNext() && !residual.isFalse()) {
                    PrefixItem item = it.next();
                    if (item.priority > priority) {
                        continue;
                    } else if (item.priority == priority) {
                        System.exit(1);
                    } else {
                        if (!inserted) {
                            inserted = true;
                        }
                        if (last_priority != item.priority) {
                            NDD t = residual;
                            residual = NDD.ref(NDD.diff(residual, last_sum));
                            NDD.deref(t);
                            last_sum = NDD.getFalse();
                            last_priority = item.priority;
                        }
                        if (residual.isFalse()) {
                            break;
                        }
                        NDD delta = NDD.ref(NDD.and(residual, item.rule_bdd));
                        if (delta.isFalse()) {
                            continue;
                        }
                        NDD t1 = item.matches;
                        NDD t2 = last_sum;
                        item.matches = NDD.ref(NDD.or(item.matches, delta));
                        last_sum = NDD.ref(NDD.or(last_sum, delta));
                        NDD.deref(t1);
                        NDD.deref(t2);
                        HashSet<String> ports = new HashSet<String>();
                        ports.add(item.outinterface);
                        HashSet<NDD> delta_set = new HashSet<NDD>();
                        delta_set.add(delta);
                        ChangeTuple ct = new ChangeTuple(from_ports, ports, delta_set);
                        if (!ct.from_ports.equals(ct.to_ports))
                        {
                            NDD.ref(delta);
                            change.add(ct);
                        }
                        NDD.deref(delta);
                    }
                }
                if (!last_sum.isFalse()) {
                    NDD t = residual;
                    residual = NDD.ref(NDD.diff(residual, last_sum));
                    NDD.deref(t);
                    last_sum = NDD.getFalse();
                }
                if (!residual.isFalse()) {
                    System.err.println("not fully deleted");
                    System.exit(1);
                }
                bdd.RemovePrefixNDD(destip, prefixlen);
                node.Delete();
            } else if (node_rules.size() == 1 && node_rules.get(0).priority == -1) {
                // we hit the default rule
                String outinterface = "default";
                node_rules.get(0).matches = NDD.ref(hit_bdd);
                NDD delta = node_rules.get(0).matches;
                HashSet<String> ports = new HashSet<String>();
                ports.add(outinterface);
                HashSet<NDD> delta_set = new HashSet<NDD>();
                delta_set.add(delta);
                ChangeTuple ct = new ChangeTuple(from_ports, ports, delta_set);
                if (!ct.from_ports.equals(ct.to_ports))
                {
                    NDD.ref(delta);
                    change.add(ct);
                }
            } else {
                if (from_ports.isEmpty()) {
                    // only insert
                    // do nothing
                } else if (to_ports.isEmpty()) {
                    // only delete
                    HashSet<String> ports = new HashSet<String>();
                    HashSet<NDD> delta_set = new HashSet<NDD>();
                    NDD.ref(hit_bdd);
                    delta_set.add(hit_bdd);
                    ChangeTuple ct = new ChangeTuple(from_ports, ports, delta_set);
                    remove.add(ct);
                } else {
                    HashSet<NDD> delta_set = new HashSet<NDD>();
                    delta_set.add(hit_bdd);
                    ChangeTuple ct = new ChangeTuple(from_ports, to_ports, delta_set);
                    if (!ct.from_ports.equals(ct.to_ports))
                    {
                        NDD.ref(hit_bdd);
                        change.add(ct);
                    }
                }
            }
        }
        if (!change_set.containsKey(name)) {
            change_set.put(name, change);
        } else {
            change_set.get(name).addAll(change);
        }
        if (!copyto_set.containsKey(name)) {
            copyto_set.put(name, copyto);
        } else {
            copyto_set.get(name).addAll(copyto);
        }
        if (!remove_set.containsKey(name)) {
            remove_set.put(name, remove);
        } else {
            remove_set.get(name).addAll(remove);
        }
    }

    public void updateFWRuleBatchBDD(String ip, HashSet<String> to_ports, HashSet<String> from_ports,
            HashMap<String, ArrayList<ChangeTupleBDD>> change_set, HashMap<String, ArrayList<ChangeTupleBDD>> copyto_set,
            HashMap<String, ArrayList<ChangeTupleBDD>> remove_set) {
        // find the exact node in prefixTree
        long destip = Long.parseLong(ip.split("/")[0]);
        int prefixlen = Integer.parseInt(ip.split("/")[1]);
        int priority = prefixlen;
        ArrayList<ChangeTupleBDD> change = new ArrayList<ChangeTupleBDD>();
        ArrayList<ChangeTupleBDD> copyto = new ArrayList<ChangeTupleBDD>();
        ArrayList<ChangeTupleBDD> remove = new ArrayList<ChangeTupleBDD>();
        TrieTreeNodeBDD node = trieBDD.Search(destip, prefixlen);
        if (node == null) {
            /*
             * no same prefix was inserted in this element before from_ports must be empty
             */
            node = trieBDD.Insert(destip, prefixlen);
            int rule_bdd = bdd.GetPrefixBDD(destip, prefixlen);
            ArrayList<PrefixItemBDD> affected_rules = GetAffectedRules(node);
            int residual = bdd.getBDD().ref(rule_bdd);
            int residual2 = 0;
            boolean inserted = false;
            int last_priority = 65535;
            int last_sum = 0;
            Iterator<PrefixItemBDD> it = affected_rules.iterator();
            while (it.hasNext()) {
                PrefixItemBDD item = it.next();
                if (item.priority > priority) {
                    int t = residual;
                    residual = bdd.diff(residual, item.rule_bdd);
                    bdd.getBDD().deref(t);
                    if (residual == 0) {
                        break;
                    }
                }
                /*
                 * no same prefix was inserted before else if(item.priority == priority) {
                 * System.exit(1); }
                 */
                else {
                    if (!inserted) {
                        residual2 = residual;
                        bdd.getBDD().ref(residual2);
                        inserted = true;
                        last_priority = item.priority;
                    }
                    if (last_priority != item.priority) {
                        int t = residual2;
                        residual2 = bdd.diff(residual2, last_sum);
                        bdd.getBDD().deref(t);
                        last_sum = 0;
                        last_priority = item.priority;
                    }
                    if (residual2 == 0) {
                        break;
                    }
                    int delta = bdd.getBDD().ref(bdd.getBDD().and(item.matches, residual2));
                    if (delta == 0) {
                        continue;
                    }
                    int t1 = item.matches;
                    int t2 = last_sum;
                    item.matches = bdd.diff(item.matches, delta);
                    last_sum = bdd.getBDD().ref(bdd.getBDD().or(last_sum, delta));
                    bdd.getBDD().deref(t1);
                    bdd.getBDD().deref(t2);
                    HashSet<String> ports = new HashSet<String>();
                    ports.add(item.outinterface);
                    HashSet<Integer> delta_set = new HashSet<Integer>();
                    delta_set.add(delta);
                    ChangeTupleBDD ct = new ChangeTupleBDD(ports, to_ports, delta_set);
                    if (!ct.from_ports.equals(ct.to_ports))
                    {
                        bdd.getBDD().ref(delta);
                        change.add(ct);
                    }
                    bdd.getBDD().deref(delta);
                }
            }
            /*
             * if (residual2 != BDDACLWrapper.BDDFalse) {
             * System.out.println("not overriding any low-priority rule"); System.exit(0); }
             */

            int hit_bdd = residual;
            for (String port : to_ports) {
                bdd.getBDD().ref(rule_bdd);
                bdd.getBDD().ref(hit_bdd);
                PrefixItemBDD insert_item = new PrefixItemBDD(priority, port, rule_bdd, hit_bdd);
                // check whether the forwarding port exists, if not create it,
                // and initialize the AP set of the port to empty
                if (!ports.contains(port)) {
                    ports.add(port);
                    ports_pred.put(port, NDD.getFalse());
                }
                // insert the rule
                node.AddPrefixItem(insert_item);
            }
            bdd.getBDD().deref(rule_bdd);
            bdd.getBDD().deref(residual);
            bdd.getBDD().deref(residual2);
        } else { // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                 // System.out.println("tag");
                 // we have pre-rules, just use matches field to give a short cut
            ArrayList<PrefixItemBDD> node_rules = node.GetPrefixItems();
            ArrayList<PrefixItemBDD> insert_items = new ArrayList<PrefixItemBDD>();
            ArrayList<PrefixItemBDD> delete_items = new ArrayList<PrefixItemBDD>();
            int rule_ndd = 0;
            int hit_bdd = 0;
            if (!node_rules.isEmpty()) {
                rule_ndd = node_rules.get(0).rule_bdd;
                hit_bdd = node_rules.get(0).matches;
                if (node_rules.size() == 1 && node_rules.get(0).priority == -1) {
                    // we hit the default rule
                    String outinterface = "default";
                    int delta = node_rules.get(0).matches;
                    node_rules.get(0).matches = 0;
                    // bdd.getBDD().deref(delta);
                    HashSet<String> ports = new HashSet<String>();
                    ports.add(outinterface);
                    HashSet<Integer> delta_set = new HashSet<Integer>();
                    delta_set.add(delta);
                    ChangeTupleBDD ct = new ChangeTupleBDD(ports, to_ports, delta_set);
                    if (!ct.from_ports.equals(ct.to_ports))
                    {
                        bdd.getBDD().ref(delta);
                        change.add(ct);
                    }
                } else if (node_rules.size() != 1 && node_rules.get(0).priority == -1) {
                    rule_ndd = node_rules.get(1).rule_bdd;
                    hit_bdd = node_rules.get(1).matches;
                    if (from_ports.isEmpty()) {
                        String outinterface = node_rules.get(1).outinterface;
                        HashSet<String> ports = new HashSet<String>();
                        ports.add(outinterface);
                        HashSet<Integer> delta_set = new HashSet<Integer>();
                        delta_set.add(hit_bdd);
                        ChangeTupleBDD ct = new ChangeTupleBDD(ports, to_ports, delta_set);
                        if (!ct.from_ports.containsAll(ct.to_ports))
                        {
                            bdd.getBDD().ref(hit_bdd);
                            copyto.add(ct);
                        }
                    }
                } else {
                    // node_rules.get(0).priority != -1 case
                    if (from_ports.isEmpty()) {
                        String outinterface = node_rules.get(0).outinterface;
                        HashSet<String> ports = new HashSet<String>();
                        ports.add(outinterface);
                        HashSet<Integer> delta_set = new HashSet<Integer>();
                        delta_set.add(hit_bdd);
                        ChangeTupleBDD ct = new ChangeTupleBDD(ports, to_ports, delta_set);
                        if (!ct.from_ports.containsAll(ct.to_ports))
                        {
                            bdd.getBDD().ref(hit_bdd);
                            copyto.add(ct);
                        }
                    }
                }
            }
            /*
             * else { System.err.println("node is invalid!"); System.exit(1); }
             */
            for (String port : to_ports) {
                bdd.getBDD().ref(rule_ndd);
                bdd.getBDD().ref(hit_bdd);
                insert_items.add(new PrefixItemBDD(priority, port, rule_ndd, hit_bdd));
                if (!ports.contains(port)) {
                    ports.add(port);
                    ports_pred.put(port, NDD.getFalse());
                }
            }
            for (String port : from_ports) {
                bdd.getBDD().deref(rule_ndd);
                bdd.getBDD().deref(hit_bdd);
                PrefixItemBDD delete_rule = new PrefixItemBDD(priority, port, rule_ndd, hit_bdd);
                if (!node.HasPrefixItem(delete_rule)) {
                    System.exit(1);
                }
                delete_items.add(delete_rule);
            }
            node_rules.removeAll(delete_items);
            node_rules.addAll(insert_items);

            if (node.IsInValid()) {
                ArrayList<PrefixItemBDD> affected_rules = GetAffectedRules(node);
                int residual = bdd.getBDD().ref(hit_bdd);
                int last_priority = 65535;
                int last_sum = 0;
                boolean inserted = false;
                Iterator<PrefixItemBDD> it = affected_rules.iterator();
                while (it.hasNext() && residual!=0) {
                    PrefixItemBDD item = it.next();
                    if (item.priority > priority) {
                        continue;
                    } else if (item.priority == priority) {
                        System.exit(1);
                    } else {
                        if (!inserted) {
                            inserted = true;
                        }
                        if (last_priority != item.priority) {
                            int t = residual;
                            residual = bdd.diff(residual, last_sum);
                            bdd.getBDD().deref(t);
                            last_sum = 0;
                            last_priority = item.priority;
                        }
                        if (residual == 0) {
                            break;
                        }
                        int delta = bdd.getBDD().ref(bdd.getBDD().and(residual, item.rule_bdd));
                        if (delta == 0) {
                            continue;
                        }
                        int t1 = item.matches;
                        int t2 = last_sum;
                        item.matches = bdd.getBDD().ref(bdd.getBDD().or(item.matches, delta));
                        last_sum = bdd.getBDD().ref(bdd.getBDD().or(last_sum, delta));
                        bdd.getBDD().deref(t1);
                        bdd.getBDD().deref(t2);
                        HashSet<String> ports = new HashSet<String>();
                        ports.add(item.outinterface);
                        HashSet<Integer> delta_set = new HashSet<Integer>();
                        delta_set.add(delta);
                        ChangeTupleBDD ct = new ChangeTupleBDD(from_ports, ports, delta_set);
                        if (!ct.from_ports.equals(ct.to_ports))
                        {
                            bdd.getBDD().ref(delta);
                            change.add(ct);
                        }
                        bdd.getBDD().deref(delta);
                    }
                }
                if (last_sum != 0) {
                    int t = residual;
                    residual = bdd.diff(residual, last_sum);
                    bdd.getBDD().deref(t);
                    last_sum = 0;
                }
                if (residual != 0) {
                    System.err.println("not fully deleted");
                    System.exit(1);
                }
                bdd.RemovePrefixNDD(destip, prefixlen);
                node.Delete();
            } else if (node_rules.size() == 1 && node_rules.get(0).priority == -1) {
                // we hit the default rule
                String outinterface = "default";
                node_rules.get(0).matches = bdd.getBDD().ref(hit_bdd);
                int delta = node_rules.get(0).matches;
                HashSet<String> ports = new HashSet<String>();
                ports.add(outinterface);
                HashSet<Integer> delta_set = new HashSet<Integer>();
                delta_set.add(delta);
                ChangeTupleBDD ct = new ChangeTupleBDD(from_ports, ports, delta_set);
                if (!ct.from_ports.equals(ct.to_ports))
                {
                    bdd.getBDD().ref(delta);
                    change.add(ct);
                }
            } else {
                if (from_ports.isEmpty()) {
                    // only insert
                    // do nothing
                } else if (to_ports.isEmpty()) {
                    // only delete
                    HashSet<String> ports = new HashSet<String>();
                    HashSet<Integer> delta_set = new HashSet<Integer>();
                    bdd.getBDD().ref(hit_bdd);
                    delta_set.add(hit_bdd);
                    ChangeTupleBDD ct = new ChangeTupleBDD(from_ports, ports, delta_set);
                    remove.add(ct);
                } else {
                    HashSet<Integer> delta_set = new HashSet<Integer>();
                    delta_set.add(hit_bdd);
                    ChangeTupleBDD ct = new ChangeTupleBDD(from_ports, to_ports, delta_set);
                    if (!ct.from_ports.equals(ct.to_ports))
                    {
                        bdd.getBDD().ref(hit_bdd);
                        change.add(ct);
                    }
                }
            }
        }
        if (!change_set.containsKey(name)) {
            change_set.put(name, change);
        } else {
            change_set.get(name).addAll(change);
        }
        if (!copyto_set.containsKey(name)) {
            copyto_set.put(name, copyto);
        } else {
            copyto_set.get(name).addAll(copyto);
        }
        if (!remove_set.containsKey(name)) {
            remove_set.put(name, remove);
        } else {
            remove_set.get(name).addAll(remove);
        }
    }

    public ArrayList<ChangeItem> InsertACLRule(ACLRule rule) {

        ArrayList<ChangeItem> changeset = new ArrayList<ChangeItem>();

        NDD rule_bdd = bdd.ConvertACLRuleNDD(rule);
        int priority = rule.getPriority();
        NDD residual = rule_bdd;
        NDD.ref(residual);
        NDD residual2 = NDD.getFalse();
        int cur_position = 0;
        boolean inserted = false;

        BDDRuleItem<ACLRule> default_item = acl_rule.getLast();

        Iterator<BDDRuleItem<ACLRule>> it = acl_rule.iterator();
        while (it.hasNext() && !residual.isFalse()) {
            BDDRuleItem<ACLRule> item = it.next();
            // TODO: fast check whether the rule is not affected by any rule
            if (item.rule.getPriority() >= priority) {
                if (!residual.isFalse()) {
                    NDD t = residual;
                    residual = NDD.ref(NDD.diff(residual, item.rule_bdd));
                    NDD.deref(t);
                }
                cur_position++;
            } else {
                if (!inserted) {
                    // fast check whether the default rule is the only rule affected
                    NDD temp = NDD.diff(residual, default_item.matches);
                    if (temp.isFalse()) {
                        NDD t = default_item.matches;
                        default_item.matches = NDD.ref(NDD.diff(default_item.matches, residual));
                        NDD.deref(t);
                        if (!default_item.rule.permitDeny.equals(rule.get_type())) {
                            NDD.ref(residual);
                            ChangeItem change_item = new ChangeItem(default_item.rule.permitDeny, rule.get_type(),
                                    residual);
                            changeset.add(change_item);
                        }
                        break;
                    }
                    residual2 = residual;
                    NDD.ref(residual2);
                    inserted = true;
                }

                if (residual2.isFalse()) {
                    break;
                }

                NDD delta = NDD.ref(NDD.and(item.matches, residual2));
                if (!delta.isFalse()) {
                    NDD t1 = item.matches;
                    NDD t2 = residual2;
                    item.matches = NDD.ref(NDD.diff(item.matches, delta));
                    residual2 = NDD.ref(NDD.diff(residual2, delta));
                    NDD.deref(t1);
                    NDD.deref(t2);

                    String foward_port = item.rule.get_type();

                    if (!foward_port.equals(rule.get_type())) {
                        NDD.ref(delta);
                        ChangeItem change_item = new ChangeItem(foward_port, rule.get_type(), delta);
                        changeset.add(change_item);
                    }
                    NDD.deref(delta);
                }
            }
        }

        NDD.deref(residual2);

        // add the new rule into the installed forwarding rule list
        BDDRuleItem<ACLRule> new_rule = new BDDRuleItem<ACLRule>(rule, rule_bdd);
        new_rule.matches = residual;
        acl_rule.add(cur_position, new_rule);

        // check whether the forwarding port exists, if not create it,
        // and initialize the AP set of the port to empty
        String iname = rule.get_type();
        if (!ports.contains(iname)) {
            ports.add(iname);
            ports_pred.put(iname, NDD.getFalse());
        }

        return changeset;
    }

    public ArrayList<ChangeItemBDD> InsertACLRuleBDD(ACLRule rule) {

        ArrayList<ChangeItemBDD> changeset = new ArrayList<ChangeItemBDD>();

        int rule_bdd = bdd.ConvertACLRule(rule);
        int priority = rule.getPriority();
        int residual = rule_bdd;
        bdd.getBDD().ref(residual);
        int residual2 = 0;
        int cur_position = 0;
        boolean inserted = false;

        BDDRuleItemBDD<ACLRule> default_item = acl_ruleBDD.getLast();

        Iterator<BDDRuleItemBDD<ACLRule>> it = acl_ruleBDD.iterator();
        while (it.hasNext() && residual != 0) {
            BDDRuleItemBDD<ACLRule> item = it.next();
            // TODO: fast check whether the rule is not affected by any rule
            if (item.rule.getPriority() >= priority) {
                if (residual != 0) {
                    int t = residual;
                    residual = bdd.diff(residual, item.rule_bdd);
                    bdd.getBDD().deref(t);
                }
                cur_position++;
            } else {
                if (!inserted) {
                    // fast check whether the default rule is the only rule affected
                    int temp = bdd.diff(residual, default_item.matches);
                    bdd.getBDD().deref(temp);
                    if (temp == 0) {
                        int t = default_item.matches;
                        default_item.matches = bdd.diff(default_item.matches, residual);
                        bdd.getBDD().deref(t);
                        if (!default_item.rule.permitDeny.equals(rule.get_type())) {
                            bdd.getBDD().ref(residual);
                            ChangeItemBDD change_item = new ChangeItemBDD(default_item.rule.permitDeny, rule.get_type(),
                                    residual);
                            changeset.add(change_item);
                        }
                        break;
                    }
                    residual2 = residual;
                    bdd.getBDD().ref(residual2);
                    inserted = true;
                }

                if (residual2 == 0) {
                    break;
                }

                int delta = bdd.getBDD().ref(bdd.getBDD().and(item.matches, residual2));
                if (delta != 0) {
                    int t1 = item.matches;
                    int t2 = residual2;
                    item.matches = bdd.diff(item.matches, delta);
                    residual2 = bdd.diff(residual2, delta);
                    bdd.getBDD().deref(t1);
                    bdd.getBDD().deref(t2);

                    String foward_port = item.rule.get_type();

                    if (!foward_port.equals(rule.get_type())) {
                        bdd.getBDD().ref(delta);
                        ChangeItemBDD change_item = new ChangeItemBDD(foward_port, rule.get_type(), delta);
                        changeset.add(change_item);
                    }
                    bdd.getBDD().deref(delta);
                }
            }
        }

        bdd.getBDD().deref(residual2);

        // add the new rule into the installed forwarding rule list
        BDDRuleItemBDD<ACLRule> new_rule = new BDDRuleItemBDD<ACLRule>(rule, rule_bdd);
        new_rule.matches = residual;
        acl_ruleBDD.add(cur_position, new_rule);

        // check whether the forwarding port exists, if not create it,
        // and initialize the AP set of the port to empty
        String iname = rule.get_type();
        if (!ports.contains(iname)) {
            ports.add(iname);
            ports_pred.put(iname, NDD.getFalse());
        }

        return changeset;
    }

    public void update_ACL(ArrayList<ChangeItem> change_set) {
        if (change_set.size() == 0) {
            return;
        }
        for (ChangeItem item : change_set) {
            String from_port = item.from_port;
            String to_port = item.to_port;
            NDD delta = item.delta;
            NDD t1 = ports_pred.get(from_port);
            NDD t2 = ports_pred.get(to_port);
            ports_pred.put(from_port, NDD.ref(NDD.diff(t1, delta)));
            ports_pred.put(to_port, NDD.ref(NDD.or(t2, delta)));
            NDD.deref(t1);
            NDD.deref(t2);
            NDD.deref(delta);
        }
    }

    public void update_FW(ArrayList<ChangeTuple> change_set, ArrayList<ChangeTuple> copyto_set) {
        if (change_set.size() == 0) {
            return;
        }

		HashMap<Pair<HashSet<String>, HashSet<String>>, HashSet<NDD>> simplified = new HashMap<Pair<HashSet<String>, HashSet<String>>, HashSet<NDD>>();
		for (ChangeTuple ct : change_set) {
			Pair<HashSet<String>, HashSet<String>> key = new Pair<HashSet<String>, HashSet<String>>(ct.from_ports,
					ct.to_ports);
			if (simplified.containsKey(key)) {
                HashSet<NDD> set = simplified.get(key);
                for(NDD n : ct.delta_set)
                {
                    if(!set.contains(n))
                    {
                        set.add(n);
                    }
                    else
                    {
                        NDD.deref(n);
                    }
                }
			} else {
				simplified.put(key, ct.delta_set);
			}
		}
		change_set.clear();
		for (Pair<HashSet<String>, HashSet<String>> key : simplified.keySet()) {
			change_set.add(new ChangeTuple(key.getKey(), key.getValue(), simplified.get(key)));
        }
        simplified.clear();
		for (ChangeTuple ct : copyto_set) {
			Pair<HashSet<String>, HashSet<String>> key = new Pair<HashSet<String>, HashSet<String>>(ct.from_ports,
					ct.to_ports);
			if (simplified.containsKey(key)) {
                HashSet<NDD> set = simplified.get(key);
                for(NDD n : ct.delta_set)
                {
                    if(!set.contains(n))
                    {
                        set.add(n);
                    }
                    else
                    {
                        NDD.deref(n);
                    }
                }
			} else {
				simplified.put(key, ct.delta_set);
			}
		}
		copyto_set.clear();
		for (Pair<HashSet<String>, HashSet<String>> key : simplified.keySet()) {
			copyto_set.add(new ChangeTuple(key.getKey(), key.getValue(), simplified.get(key)));
		}

        for (ChangeTuple item : change_set) {
            NDD delta = NDD.getFalse();
            for (NDD abdd : item.delta_set) {
                NDD t = delta;
                delta = NDD.ref(NDD.or(delta, abdd));
                NDD.deref(t);
                NDD.deref(abdd);
            }
            for (String from_port : item.from_ports) {
                NDD t = ports_pred.get(from_port);
                ports_pred.put(from_port, NDD.ref(NDD.diff(t, delta)));
                NDD.deref(t);
            }
            for (String to_port : item.to_ports) {
                NDD t = ports_pred.get(to_port);
                ports_pred.put(to_port, NDD.ref(NDD.or(t, delta)));
                NDD.deref(t);
            }
            NDD.deref(delta);
        }
        for (ChangeTuple item : copyto_set) {
            NDD delta = NDD.getFalse();
            for (NDD abdd : item.delta_set) {
                NDD t = delta;
                delta = NDD.ref(NDD.or(delta, abdd));
                NDD.deref(t);
                NDD.deref(abdd);
            }
            for (String to_port : item.to_ports) {
                NDD t = ports_pred.get(to_port);
                ports_pred.put(to_port, NDD.ref(NDD.or(t, delta)));
                NDD.deref(t);
            }
            NDD.deref(delta);
        }
    }
}