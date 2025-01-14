package application.wlan.ndd.verifier.apkeep.element;

import java.util.*;

import application.wlan.ndd.exp.EvalDataplaneVerifierNDDAP;
import application.wlan.ndd.verifier.DPVerifierNDDAPIncre;
import application.wlan.ndd.verifier.apkeep.core.*;
import application.wlan.ndd.verifier.common.ACLRule;
import javafx.util.*;
import org.ants.jndd.diagram.AtomizedNDD;
import org.ants.jndd.diagram.NDD;

public class FieldNodeAP extends FieldNode {
    public static NetworkNDDAP network = null;
    public HashMap<String, AtomizedNDD> ports_aps;

    public FieldNodeAP() {
        super();
    }

    public FieldNodeAP(String ename, NetworkNDDAP net, int type) {
        super(ename, net, type);
        ports_aps = new HashMap<>();
        if (type == 0) {
            ports_aps.put("default", AtomizedNDD.getTrue());
        } else if (type == 1) {
            ports_aps.put("permit", AtomizedNDD.getTrue());
        } else if (type == -1) {
            ports_aps.put("deny", AtomizedNDD.getTrue());
        } else if (type == 2) {
            ports_aps.put("default", AtomizedNDD.getTrue());
        }
    }

    public void updateFWRuleBatch(String ip, HashSet<String> to_ports, HashSet<String> from_ports,
                                  HashMap<String, ArrayList<ChangeTuple>> change_set, HashMap<String, ArrayList<ChangeTuple>> copyto_set,
                                  HashMap<String, ArrayList<ChangeTuple>> remove_set) {
        for (String port : to_ports) {
            if (!ports.contains(port)) {
                ports_aps.put(port, AtomizedNDD.getFalse());
            }
        }
        super.updateFWRuleBatch(ip, to_ports, from_ports, change_set, copyto_set, remove_set);
    }

    public void updateFWRuleBatchBDD(String ip, HashSet<String> to_ports, HashSet<String> from_ports,
                                     HashMap<String, ArrayList<ChangeTupleBDD>> change_set, HashMap<String, ArrayList<ChangeTupleBDD>> copyto_set,
                                     HashMap<String, ArrayList<ChangeTupleBDD>> remove_set) {
        for (String port : to_ports) {
            if (!ports.contains(port)) {
                ports_aps.put(port, AtomizedNDD.getFalse());
            }
        }
        super.updateFWRuleBatchBDD(ip, to_ports, from_ports, change_set, copyto_set, remove_set);
    }

    public ArrayList<ChangeItem> InsertACLRule(ACLRule rule) {
        String iname = rule.get_type();
        if (!ports.contains(iname)) {
            ports_aps.put(iname, AtomizedNDD.getFalse());
        }
        ArrayList<ChangeItem> ret = super.InsertACLRule(rule);
        return ret;
    }

    public ArrayList<ChangeItemBDD> InsertACLRuleBDD(ACLRule rule) {
        String iname = rule.get_type();
        if (!ports.contains(iname)) {
            ports_aps.put(iname, AtomizedNDD.getFalse());
        }
        ArrayList<ChangeItemBDD> ret = super.InsertACLRuleBDD(rule);
        return ret;
    }

    //pure NDD
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
                for (NDD n : ct.delta_set) {
                    if (!set.contains(n)) {
                        set.add(n);
                    } else {
                        NDD.deref(n);
                    }
                }
            } else {
                simplified.put(key, ct.delta_set);
            }
        }
        change_set.clear();
        for (Map.Entry<Pair<HashSet<String>, HashSet<String>>, HashSet<NDD>> entry : simplified.entrySet()) {
            change_set.add(new ChangeTuple(entry.getKey().getKey(), entry.getKey().getValue(), entry.getValue()));
        }
        simplified.clear();
        for (ChangeTuple ct : copyto_set) {
            Pair<HashSet<String>, HashSet<String>> key = new Pair<HashSet<String>, HashSet<String>>(ct.from_ports,
                    ct.to_ports);
            if (simplified.containsKey(key)) {
                HashSet<NDD> set = simplified.get(key);
                for (NDD n : ct.delta_set) {
                    if (!set.contains(n)) {
                        set.add(n);
                    } else {
                        NDD.deref(n);
                    }
                }
            } else {
                simplified.put(key, ct.delta_set);
            }
        }
        copyto_set.clear();
        for (Map.Entry<Pair<HashSet<String>, HashSet<String>>, HashSet<NDD>> entry : simplified.entrySet()) {
            copyto_set.add(new ChangeTuple(entry.getKey().getKey(), entry.getKey().getValue(), entry.getValue()));
        }

        // can be inplemented into NDD library
        NDD delta;
        for (ChangeTuple item : change_set) {
            delta = NDD.getFalse();
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

            HashSet<Integer> delta_aps = new HashSet<>();
            HashMap<Integer, HashSet<Integer>> split_ap = new HashMap<>();
            int d = 0;
            if (delta.isTrue()) {
                d = 1;
            } else if (delta.isFalse()) {
                d = 0;
            } else {
                for (int pred : delta.getEdges().values()) {
                    d = pred;
                }
            }
            for (String from_port : item.from_ports) {
                AtomizedNDD from_aps = ports_aps.get(from_port);
                // System.out.println(name+" "+from_port+" "+from_aps+" "+from_aps.edges);
                AtomizedNDD.getAtomsToSplitSingleField(from_aps, d, delta_aps, split_ap, 1);
                network.split_ap_one_field(split_ap, 1);
                break;
            }

            AtomizedNDD moved_aps = AtomizedNDD.getFalse();
            if (delta_aps.size() == AtomizedNDD.getAllAtoms(bdd.DST_IP_FIELD).size()) {
                moved_aps = AtomizedNDD.getTrue();
            } else if (delta_aps.size() == 0) {
                moved_aps = AtomizedNDD.getFalse();
            } else {
                HashMap<AtomizedNDD, HashSet<Integer>> tempMap = new HashMap<>();
                tempMap.put(AtomizedNDD.getTrue(), delta_aps);
                moved_aps = AtomizedNDD.mkAtomized(1, tempMap);
                AtomizedNDD.ref(moved_aps);
            }

            for (String from_port : item.from_ports) {
                AtomizedNDD old_from_aps = ports_aps.get(from_port);
                AtomizedNDD new_from_aps = AtomizedNDD.ref(AtomizedNDD.diff(old_from_aps, moved_aps));
                HashSet<Integer> toRemove = new HashSet<>();
                HashSet<Integer> toAdd = new HashSet<>();
                AtomizedNDD.changeAtomsSingleField(old_from_aps, new_from_aps, toRemove, toAdd);
                AtomizedNDD.deref(old_from_aps);
                ports_aps.put(from_port, new_from_aps);

                HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[1];
                for (int ap : toRemove) {
                    sub_ap_ports.get(ap).remove(new Pair<>(name, from_port));
                }
                for (int ap : toAdd) {
                    sub_ap_ports.get(ap).add(new Pair<>(name, from_port));
                }
            }
            for (String to_port : item.to_ports) {
                AtomizedNDD old_to_aps = ports_aps.get(to_port);
                if (old_to_aps == null) {
                    old_to_aps = AtomizedNDD.getFalse();
                }
                AtomizedNDD new_to_aps = AtomizedNDD.ref(AtomizedNDD.or(old_to_aps, moved_aps));
                HashSet<Integer> toRemove = new HashSet<>();
                HashSet<Integer> toAdd = new HashSet<>();
                AtomizedNDD.changeAtomsSingleField(old_to_aps, new_to_aps, toRemove, toAdd);
                AtomizedNDD.deref(old_to_aps);
                ports_aps.put(to_port, new_to_aps);

                HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[1];
                for (int ap : toRemove) {
                    sub_ap_ports.get(ap).remove(new Pair<>(name, to_port));
                }
                for (int ap : toAdd) {
                    sub_ap_ports.get(ap).add(new Pair<>(name, to_port));
                }
            }
            AtomizedNDD.deref(moved_aps);
            NDD.deref(delta);
        }
        for (ChangeTuple item : copyto_set) {
            delta = NDD.getFalse();
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

            HashSet<Integer> delta_aps = new HashSet<>();
            HashMap<Integer, HashSet<Integer>> split_ap = new HashMap<>();
            int d = 0;
            if (delta.isTrue()) {
                d = 1;
            } else if (delta.isFalse()) {
                d = 0;
            } else {
                for (int pred : delta.getEdges().values()) {
                    d = pred;
                }
            }
            AtomizedNDD from_aps = AtomizedNDD.getTrue();
            AtomizedNDD.getAtomsToSplitSingleField(from_aps, d, delta_aps, split_ap, 1);
            network.split_ap_one_field(split_ap, 1);// !!!!!!!!!!!!!!!!!!!!!!!!!!! map

            HashSet<Integer> apSet = new HashSet<>(delta_aps);
            AtomizedNDD moved_aps = AtomizedNDD.getFalse();
            if (delta_aps.size() == AtomizedNDD.getAllAtoms(bdd.DST_IP_FIELD).size()) {
                moved_aps = AtomizedNDD.getTrue();
            } else if (delta_aps.size() == 0) {
                moved_aps = AtomizedNDD.getFalse();
            } else {
                HashMap<AtomizedNDD, HashSet<Integer>> tempMap = new HashMap<>();
                tempMap.put(AtomizedNDD.getTrue(), delta_aps);
                moved_aps = AtomizedNDD.mkAtomized(1, tempMap);
                AtomizedNDD.ref(moved_aps);
            }
            for (String to_port : item.to_ports) {
                AtomizedNDD old_to_aps = ports_aps.get(to_port);
                if (old_to_aps == null) {
                    old_to_aps = AtomizedNDD.getFalse();
                }
                AtomizedNDD new_to_aps = AtomizedNDD.ref(AtomizedNDD.or(old_to_aps, moved_aps));// map!!!!!!!
                AtomizedNDD.deref(old_to_aps);
                ports_aps.put(to_port, new_to_aps);

                HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[1];
                for (int ap : apSet) {
                    sub_ap_ports.get(ap).add(new Pair<>(name, to_port));
                }
            }
            AtomizedNDD.deref(moved_aps);
            NDD.deref(delta);
        }
    }

    //BDD->NDD
    public void update_FW_BDD(ArrayList<ChangeTupleBDD> change_set, ArrayList<ChangeTupleBDD> copyto_set) {
        if (change_set.size() == 0) {
            return;
        }

        HashMap<Pair<HashSet<String>, HashSet<String>>, HashSet<Integer>> simplified = new HashMap<Pair<HashSet<String>, HashSet<String>>, HashSet<Integer>>();
        for (ChangeTupleBDD ct : change_set) {
            Pair<HashSet<String>, HashSet<String>> key = new Pair<HashSet<String>, HashSet<String>>(ct.from_ports,
                    ct.to_ports);
            if (simplified.containsKey(key)) {
                HashSet<Integer> set = simplified.get(key);
                for (int n : ct.delta_set) {
                    if (!set.contains(n)) {
                        set.add(n);
                    } else {
                        bdd.getBDD().deref(n);
                    }
                }
            } else {
                simplified.put(key, ct.delta_set);
            }
        }
        change_set.clear();
        for (Map.Entry<Pair<HashSet<String>, HashSet<String>>, HashSet<Integer>> entry : simplified.entrySet()) {
            change_set.add(new ChangeTupleBDD(entry.getKey().getKey(), entry.getKey().getValue(), entry.getValue()));
        }
        simplified.clear();
        for (ChangeTupleBDD ct : copyto_set) {
            Pair<HashSet<String>, HashSet<String>> key = new Pair<HashSet<String>, HashSet<String>>(ct.from_ports,
                    ct.to_ports);
            if (simplified.containsKey(key)) {
                HashSet<Integer> set = simplified.get(key);
                for (int n : ct.delta_set) {
                    if (!set.contains(n)) {
                        set.add(n);
                    } else {
                        bdd.getBDD().deref(n);
                    }
                }
            } else {
                simplified.put(key, ct.delta_set);
            }
        }
        copyto_set.clear();
        for (Map.Entry<Pair<HashSet<String>, HashSet<String>>, HashSet<Integer>> entry : simplified.entrySet()) {
            copyto_set.add(new ChangeTupleBDD(entry.getKey().getKey(), entry.getKey().getValue(), entry.getValue()));
        }

        NDD delta;
        int deltaBDD;
        for (ChangeTupleBDD item : change_set) {
            deltaBDD = 0;
            for (int abdd : item.delta_set) {
                int t = deltaBDD;
                deltaBDD = bdd.getBDD().ref(bdd.getBDD().or(deltaBDD, abdd));
                bdd.getBDD().deref(t);
                bdd.getBDD().deref(abdd);
            }
            delta = NDD.ref(NDD.toNDD(deltaBDD, 1));

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

            HashSet<Integer> delta_aps = new HashSet<>();
            HashMap<Integer, HashSet<Integer>> split_ap = new HashMap<>();
            int d = 0;
            if (delta.isTrue()) {
                d = 1;
            } else if (delta.isFalse()) {
                d = 0;
            } else {
                for (int pred : delta.getEdges().values()) {
                    d = pred;
                }
            }
            for (String from_port : item.from_ports) {
                AtomizedNDD from_aps = ports_aps.get(from_port);
                // System.out.println(name+" "+from_port+" "+from_aps+" "+from_aps.edges);
                AtomizedNDD.getAtomsToSplitSingleField(from_aps, d, delta_aps, split_ap, 1);
                network.split_ap_one_field(split_ap, 1);
                break;
            }

            AtomizedNDD moved_aps = AtomizedNDD.getFalse();
            if (delta_aps.size() == AtomizedNDD.getAllAtoms(bdd.DST_IP_FIELD).size()) {
                moved_aps = AtomizedNDD.getTrue();
            } else if (delta_aps.size() == 0) {
                moved_aps = AtomizedNDD.getFalse();
            } else {
                HashMap<AtomizedNDD, HashSet<Integer>> tempMap = new HashMap<>();
                tempMap.put(AtomizedNDD.getTrue(), delta_aps);
                moved_aps = AtomizedNDD.mkAtomized(1, tempMap);
                AtomizedNDD.ref(moved_aps);
            }

            for (String from_port : item.from_ports) {
                AtomizedNDD old_from_aps = ports_aps.get(from_port);
                AtomizedNDD new_from_aps = AtomizedNDD.ref(AtomizedNDD.diff(old_from_aps, moved_aps));
                HashSet<Integer> toRemove = new HashSet<>();
                HashSet<Integer> toAdd = new HashSet<>();
                AtomizedNDD.changeAtomsSingleField(old_from_aps, new_from_aps, toRemove, toAdd);
                AtomizedNDD.deref(old_from_aps);
                ports_aps.put(from_port, new_from_aps);

                HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[1];
                for (int ap : toRemove) {
                    sub_ap_ports.get(ap).remove(new Pair<>(name, from_port));
                }
                for (int ap : toAdd) {
                    sub_ap_ports.get(ap).add(new Pair<>(name, from_port));
                }
            }
            for (String to_port : item.to_ports) {
                AtomizedNDD old_to_aps = ports_aps.get(to_port);
                if (old_to_aps == null) {
                    old_to_aps = AtomizedNDD.getFalse();
                }
                AtomizedNDD new_to_aps = AtomizedNDD.ref(AtomizedNDD.or(old_to_aps, moved_aps));
                HashSet<Integer> toRemove = new HashSet<>();
                HashSet<Integer> toAdd = new HashSet<>();
                AtomizedNDD.changeAtomsSingleField(old_to_aps, new_to_aps, toRemove, toAdd);
                AtomizedNDD.deref(old_to_aps);
                ports_aps.put(to_port, new_to_aps);

                HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[1];
                for (int ap : toRemove) {
                    sub_ap_ports.get(ap).remove(new Pair<>(name, to_port));
                }
                for (int ap : toAdd) {
                    sub_ap_ports.get(ap).add(new Pair<>(name, to_port));
                }
            }
            AtomizedNDD.deref(moved_aps);
            NDD.deref(delta);
        }
        for (ChangeTupleBDD item : copyto_set) {
            deltaBDD = 0;
            for (int abdd : item.delta_set) {
                int t = deltaBDD;
                deltaBDD = bdd.getBDD().ref(bdd.getBDD().or(deltaBDD, abdd));
                bdd.getBDD().deref(t);
                bdd.getBDD().deref(abdd);
            }
            delta = NDD.ref(NDD.toNDD(deltaBDD, 1));

            for (String to_port : item.to_ports) {
                NDD t = ports_pred.get(to_port);
                ports_pred.put(to_port, NDD.ref(NDD.or(t, delta)));
                NDD.deref(t);
            }

            HashSet<Integer> delta_aps = new HashSet<>();
            HashMap<Integer, HashSet<Integer>> split_ap = new HashMap<>();
            int d = 0;
            if (delta.isTrue()) {
                d = 1;
            } else if (delta.isFalse()) {
                d = 0;
            } else {
                for (int pred : delta.getEdges().values()) {
                    d = pred;
                }
            }
            AtomizedNDD from_aps = AtomizedNDD.getTrue();
            AtomizedNDD.getAtomsToSplitSingleField(from_aps, d, delta_aps, split_ap, 1);
            network.split_ap_one_field(split_ap, 1);// !!!!!!!!!!!!!!!!!!!!!!!!!!! map

            HashSet<Integer> apSet = new HashSet<>(delta_aps);
            AtomizedNDD moved_aps = AtomizedNDD.getFalse();
            if (delta_aps.size() == AtomizedNDD.getAllAtoms(bdd.DST_IP_FIELD).size()) {
                moved_aps = AtomizedNDD.getTrue();
            } else if (delta_aps.size() == 0) {
                moved_aps = AtomizedNDD.getFalse();
            } else {
                HashMap<AtomizedNDD, HashSet<Integer>> tempMap = new HashMap<>();
                tempMap.put(AtomizedNDD.getTrue(), delta_aps);
                moved_aps = AtomizedNDD.mkAtomized(1, tempMap);
                AtomizedNDD.ref(moved_aps);
            }
            for (String to_port : item.to_ports) {
                AtomizedNDD old_to_aps = ports_aps.get(to_port);
                if (old_to_aps == null) {
                    old_to_aps = AtomizedNDD.getFalse();
                }
                AtomizedNDD new_to_aps = AtomizedNDD.ref(AtomizedNDD.or(old_to_aps, moved_aps));// map!!!!!!!
                AtomizedNDD.deref(old_to_aps);
                ports_aps.put(to_port, new_to_aps);

                HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[1];
                for (int ap : apSet) {
                    sub_ap_ports.get(ap).add(new Pair<>(name, to_port));
                }
            }
            AtomizedNDD.deref(moved_aps);
            NDD.deref(delta);
        }
    }

    static int count = 0;

    // can be implemented in NDD library, not in verifiers
    public void update_ACL(ArrayList<ChangeItem> change_set) {
        if (change_set.size() == 0) {
            return;
        }

        HashSet<Integer> newAP = new HashSet<>();

        for (ChangeItem item : change_set) {
            String from_port = item.from_port;
            String to_port = item.to_port;
            NDD delta = item.delta;

            // update pred
            NDD t1 = ports_pred.get(from_port);
            NDD t2 = ports_pred.get(to_port);
            ports_pred.put(from_port, NDD.ref(NDD.diff(t1, delta)));
            ports_pred.put(to_port, NDD.ref(NDD.or(t2, delta)));
            NDD.deref(t1);
            NDD.deref(t2);

            ArrayList<int[]> change_bdd = NDD.toArray(delta);

            for (int[] bdd_vec : change_bdd) {
                // update aps
                ArrayList<HashSet<Integer>> delta_aps = new ArrayList<>();
                ArrayList<HashMap<Integer, HashSet<Integer>>> split_ap = new ArrayList<>();
                for (int curr = 0; curr <= AtomizedNDD.getFieldNum(); curr++) {
                    delta_aps.add(new HashSet<>());
                    split_ap.add(new HashMap<>());
                }
                AtomizedNDD from_aps = ports_aps.get(from_port);
                AtomizedNDD.getAtomsToSplitMultipleFields(from_aps, bdd_vec, delta_aps, split_ap, 0);
                if(DPVerifierNDDAPIncre.getSplitNum)
                {
                    for(HashMap<Integer, HashSet<Integer>> map : split_ap)
                    {
                        for(Map.Entry<Integer, HashSet<Integer>> entry : map.entrySet())
                        {
                            newAP.remove(entry.getKey());
                            newAP.addAll(entry.getValue());
                        }
                    }
                }
                // split
                network.split_ap_multi_field(split_ap);
                // transfer
                AtomizedNDD moved_aps = AtomizedNDD.getTrue();
                for (int curr_field = AtomizedNDD.getFieldNum(); curr_field >= 0; curr_field--) {
                    HashMap<AtomizedNDD, HashSet<Integer>> tempMap = new HashMap<>();
                    tempMap.put(moved_aps, delta_aps.get(curr_field));
                    moved_aps = AtomizedNDD.mkAtomized(curr_field, tempMap);
                }

                AtomizedNDD.ref(moved_aps);
                AtomizedNDD old_from_aps = ports_aps.get(from_port);
                AtomizedNDD new_from_aps = AtomizedNDD.ref(AtomizedNDD.diff(old_from_aps, moved_aps));
                ports_aps.put(from_port, new_from_aps);

                AtomizedNDD old_to_aps = ports_aps.get(to_port);
                AtomizedNDD new_to_aps = AtomizedNDD.ref(AtomizedNDD.or(old_to_aps, moved_aps));
                ports_aps.put(to_port, new_to_aps);

                HashSet<Integer> [] toAdd = new HashSet [AtomizedNDD.getFieldNum() + 1];
                HashSet<Integer> [] toRemove = new HashSet [AtomizedNDD.getFieldNum() + 1];
                AtomizedNDD.changeAtoms(old_from_aps, new_from_aps, toRemove, toAdd);

                for (int field = 0; field <= AtomizedNDD.getFieldNum(); field++) {
                    HashSet<Integer> removeAP = toRemove[field];
                    HashSet<Integer> addAP = toAdd[field];
                    HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[field];
                    for (int ap : removeAP) {
                        sub_ap_ports.get(ap).remove(new Pair<>(name, from_port));
                    }
                    for (int ap : addAP) {
                        sub_ap_ports.get(ap).add(new Pair<>(name, from_port));
                    }
                }
                toAdd = new HashSet [AtomizedNDD.getFieldNum() + 1];
                toRemove = new HashSet [AtomizedNDD.getFieldNum() + 1];
                AtomizedNDD.changeAtoms(old_to_aps, new_to_aps, toRemove, toAdd);
                for (int field = 0; field <= AtomizedNDD.getFieldNum(); field++) {
                    HashSet<Integer> removeAP = toRemove[field];
                    HashSet<Integer> addAP = toAdd[field];
                    HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[field];
                    for (int ap : removeAP) {
                        sub_ap_ports.get(ap).remove(new Pair<>(name, to_port));
                    }
                    for (int ap : addAP) {
                        sub_ap_ports.get(ap).add(new Pair<>(name, to_port));
                    }
                }
                AtomizedNDD.deref(moved_aps);
                AtomizedNDD.deref(old_from_aps);
                AtomizedNDD.deref(old_to_aps);
            }
            NDD.deref(delta);
        }

        if(DPVerifierNDDAPIncre.getSplitNum)
        {
            DPVerifierNDDAPIncre.splitNum = newAP.size();
        }
    }

    public void update_ACL_BDD(ArrayList<ChangeItemBDD> change_set) {
        if (change_set.size() == 0) {
            return;
        }

        for (ChangeItemBDD item : change_set) {
            String from_port = item.from_port;
            String to_port = item.to_port;
            NDD delta = NDD.ref(NDD.toNDD(item.delta));

            // update pred
            NDD t1 = ports_pred.get(from_port);
            NDD t2 = ports_pred.get(to_port);
            ports_pred.put(from_port, NDD.ref(NDD.diff(t1, delta)));
            ports_pred.put(to_port, NDD.ref(NDD.or(t2, delta)));
            NDD.deref(t1);
            NDD.deref(t2);

            ArrayList<int[]> change_bdd = NDD.toArray(delta);

            for (int[] bdd_vec : change_bdd) {
                // update aps
                ArrayList<HashSet<Integer>> delta_aps = new ArrayList<>();
                ArrayList<HashMap<Integer, HashSet<Integer>>> split_ap = new ArrayList<>();
                for (int curr = 0; curr <= AtomizedNDD.getFieldNum(); curr++) {
                    delta_aps.add(new HashSet<>());
                    split_ap.add(new HashMap<>());
                }
                AtomizedNDD from_aps = ports_aps.get(from_port);
                AtomizedNDD.getAtomsToSplitMultipleFields(from_aps, bdd_vec, delta_aps, split_ap, 0);

                // split
                network.split_ap_multi_field(split_ap);

                // transfer
                AtomizedNDD moved_aps = AtomizedNDD.getTrue();
                for (int curr_field = AtomizedNDD.getFieldNum(); curr_field >= 0; curr_field--) {
                    if (delta_aps.get(curr_field).size() == AtomizedNDD.getAllAtoms(curr_field).size())
                    {
                        continue;
                    }
                    HashMap<AtomizedNDD, HashSet<Integer>> tempMap = new HashMap<>();
                    tempMap.put(moved_aps, delta_aps.get(curr_field));
                    moved_aps = AtomizedNDD.mkAtomized(curr_field, tempMap);
                }
                AtomizedNDD.ref(moved_aps);

                AtomizedNDD old_from_aps = ports_aps.get(from_port);
                AtomizedNDD new_from_aps = AtomizedNDD.ref(AtomizedNDD.diff(old_from_aps, moved_aps));
                AtomizedNDD.deref(old_from_aps);
                ports_aps.put(from_port, new_from_aps);

                AtomizedNDD old_to_aps = ports_aps.get(to_port);
                AtomizedNDD new_to_aps = AtomizedNDD.ref(AtomizedNDD.or(old_to_aps, moved_aps));
                AtomizedNDD.deref(old_to_aps);
                ports_aps.put(to_port, new_to_aps);

                HashSet<Integer> [] toAdd = new HashSet [AtomizedNDD.getFieldNum() + 1];
                HashSet<Integer> [] toRemove = new HashSet [AtomizedNDD.getFieldNum() + 1];
                AtomizedNDD.changeAtoms(old_from_aps, new_from_aps, toRemove, toAdd);

                for (int field = 0; field <= AtomizedNDD.getFieldNum(); field++) {
                    HashSet<Integer> removeAP = toRemove[field];
                    HashSet<Integer> addAP = toAdd[field];
                    HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[field];
                    for (int ap : removeAP) {
                        sub_ap_ports.get(ap).remove(new Pair<>(name, from_port));
                    }
                    for (int ap : addAP) {
                        sub_ap_ports.get(ap).add(new Pair<>(name, from_port));
                    }
                }

                toAdd = new HashSet [AtomizedNDD.getFieldNum() + 1];
                toRemove = new HashSet [AtomizedNDD.getFieldNum() + 1];
                AtomizedNDD.changeAtoms(old_to_aps, new_to_aps, toRemove, toAdd);

                for (int field = 0; field <= AtomizedNDD.getFieldNum(); field++) {
                    HashSet<Integer> removeAP = toRemove[field];
                    HashSet<Integer> addAP = toAdd[field];
                    HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = network.splitMap.ap_ports[field];
                    for (int ap : removeAP) {
                        sub_ap_ports.get(ap).remove(new Pair<>(name, to_port));
                    }
                    for (int ap : addAP) {
                        sub_ap_ports.get(ap).add(new Pair<>(name, to_port));
                    }
                }

                AtomizedNDD.deref(moved_aps);
            }
            NDD.deref(delta);
        }
    }
}