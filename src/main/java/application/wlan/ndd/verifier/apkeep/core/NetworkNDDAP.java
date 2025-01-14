package application.wlan.ndd.verifier.apkeep.core;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import application.wlan.bdd.exp.EvalDataplaneVerifier;
import application.wlan.bdd.verifier.apkeep.element.ACLElement;
import application.wlan.ndd.exp.EvalDataplaneVerifierNDDAP;
import application.wlan.ndd.verifier.apkeep.checker.TranverseNodeAP;
import application.wlan.ndd.verifier.apkeep.element.FieldNodeAP;
import application.wlan.ndd.verifier.apkeep.utils.UtilityTools;
import application.wlan.ndd.verifier.common.ACLRule;
import javafx.util.*;
import ndd.jdd.diagram.AtomizedNDD;
import ndd.jdd.diagram.NDD;

public class NetworkNDDAP extends NetworkNDDPred {
    public HashMap<String, FieldNodeAP> FieldNodes;
    public SplitMap splitMap;
    public static boolean encodeWithNDD = false;

    public NetworkNDDAP(String name) throws IOException {
        super(name);
        FieldNodes = new HashMap<>();
        TranverseNodeAP.net = this;
        FieldNodeAP.network = this;
        splitMap = new SplitMap(AtomizedNDD.getFieldNum() + 1);
    }

    public void addForwardNode(String element) {
        if (!FieldNodes.containsKey(element)) {
            FieldNodes.put(element, new FieldNodeAP(element, this, 0));
            splitMap.AddDefaultPort(element);
        }
    }

    public void addACLNode_deny(String element) {
        if (!FieldNodes.containsKey(element)) {
            FieldNodes.put(element, new FieldNodeAP(element, this, -1));
            splitMap.AddDenyPort(element);
        }
    }

    /*
     * Process different types of rule update
     */
    public HashMap<String, HashSet<Integer>> UpdateBatchRules(ArrayList<String> rules, ArrayList<String> acl_rules)
            throws IOException {
        long t0 = System.nanoTime();
        HashMap<String, HashSet<Integer>> moved_aps = new HashMap<String, HashSet<Integer>>();
        HashMap<String, HashMap<String, HashSet<Pair<String, String>>>> fwd_rules = new HashMap<String, HashMap<String, HashSet<Pair<String, String>>>>();
        for (String linestr : rules) {
            addFWDRule(fwd_rules, linestr);
        }

        long t1 = System.nanoTime();

        int count = 0;
        for (String linestr : acl_rules) {
            count++;
            if(count == 1000)
            {
                UpdateFieldAP();
            }
            UpdateACLRule(linestr);
        }

        if (EvalDataplaneVerifierNDDAP.CHECK_CORRECTNESS) {
            OutputACLPredicate();
            UpdateFieldAP();
            System.out.println(AtomizedNDD.totalCountOfAtoms());
        }

        long t2 = System.nanoTime();

        updateFWDRuleBatch(fwd_rules, moved_aps);
        UpdateFieldAP();

        for (String acl_name : acl_application.keySet()) {
            for (String acl_app : acl_application.get(acl_name)) {
                FieldNodes.get(acl_app).ports = FieldNodes.get(acl_name).ports;
                FieldNodes.get(acl_app).ports_pred = FieldNodes.get(acl_name).ports_pred;
                FieldNodes.get(acl_app).ports_aps = FieldNodes.get(acl_name).ports_aps;
            }
        }

        long t3 = System.nanoTime();

        System.out.println("FW:" + (t3 - t2) / 1000000000.0);
        System.out.println("ACL:" + (t2 - t1) / 1000000000.0);

        // PrintPredicate();

        // for(int ap : splitMap.ap_ports[0].keySet())
        // {
        //     System.out.println(ap);
        //     System.out.println(splitMap.ap_ports[0].get(ap));
        // }

        return moved_aps;
    }

    private void OutputACLPredicate() throws IOException {
        FileWriter fw = new FileWriter("results\\" + name + "\\ACLPredicateSatCountNDD.txt", false);
        PrintWriter pw = new PrintWriter(fw);
        for (String elementName : acl_application.keySet()) {
            FieldNodeAP aclElement = FieldNodes.get(elementName);
            for (Map.Entry<String, AtomizedNDD> entry : aclElement.ports_aps.entrySet()) {
                String portName = entry.getKey();
                AtomizedNDD predicate = entry.getValue();
                pw.println(elementName+" "+portName+" "+AtomizedNDD.satCount(AtomizedNDD.atomizedToNDD(predicate)));
            }
        }
        pw.flush();
    }

    public HashMap<String, HashSet<Integer>> UpdateBatchRulesIncre(ArrayList<String> rules, ArrayList<String> acl_rules)
            throws IOException {
        long t0 = System.nanoTime();
        HashMap<String, HashSet<Integer>> moved_aps = new HashMap<String, HashSet<Integer>>();
        HashMap<String, HashMap<String, HashSet<Pair<String, String>>>> fwd_rules = new HashMap<String, HashMap<String, HashSet<Pair<String, String>>>>();
        for (String linestr : rules) {
            addFWDRule(fwd_rules, linestr);
        }

        long t1 = System.nanoTime();

        int count = 0;
        for (String linestr : acl_rules) {
            count++;
            UpdateACLRule(linestr);
        }

        long t2 = System.nanoTime();

        updateFWDRuleBatch(fwd_rules, moved_aps);

        for (String acl_name : acl_application.keySet()) {
            for (String acl_app : acl_application.get(acl_name)) {
                FieldNodes.get(acl_app).ports = FieldNodes.get(acl_name).ports;
                FieldNodes.get(acl_app).ports_pred = FieldNodes.get(acl_name).ports_pred;
                FieldNodes.get(acl_app).ports_aps = FieldNodes.get(acl_name).ports_aps;
            }
        }

        long t3 = System.nanoTime();

        System.out.println("FW:" + (t3 - t2) / 1000000000.0);
        System.out.println("ACL:" + (t2 - t1) / 1000000000.0);

        return moved_aps;
    }

//    private void CheckNDD_Molecule() {
//        HashMap<String, HashMap<String, Integer>> preds = new HashMap<>();
//        for (FieldNodeAP device : FieldNodes.values()) {
//            preds.put(device.name, new HashMap<>());
//            for (String port : device.ports_aps.keySet()) {
//                int nddPred = bdd_engine.getBDD().ref(NDD.toBDD(device.ports_pred.get(port)));
//                int moleculePred = bdd_engine.getBDD().ref(Molecule.toBDD(device.ports_aps.get(port)));
//                if (nddPred != moleculePred) {
//                    System.out.println(device.name + " " + port + " " + nddPred + " " + moleculePred);
//                }
//                if (!Molecule.CheckPred(device.ports_aps.get(port))) {
//                    System.out.println(device.name + " " + port + " check failed");
//                }
//                preds.get(device.name).put(port, moleculePred);
//            }
//        }
//
//        // UpdateFieldAP();
//
//        // for (FieldNodeAP device : FieldNodes.values()) {
//        // for (String port : device.ports_aps.keySet()) {
//        // int moleculePred =
//        // bdd_engine.getBDD().ref(Molecule.toBDD(device.ports_aps.get(port)));
//        // if (preds.get(device.name).get(port) != moleculePred) {
//        // System.out.println(
//        // device.name + " " + port + " " + preds.get(device.name).get(port) + " " +
//        // moleculePred);
//        // }
//        // }
//        // }
//    }

//    private void PrintPredicate() throws IOException {
//        FileWriter f = new FileWriter(
//                "/home/zcli/lzc/Field-Decision-Network/SingleLayerNDD/src/main/java/org/ants/output/pd/predicateA",
//                false);
//        PrintWriter p = new PrintWriter(f);
//        for (FieldNodeAP device : FieldNodes.values()) {
//            // if(device.type != 0)continue;
//            for (String port : device.ports_aps.keySet()) {
//                Molecule aps = device.ports_aps.get(port);
//                int sum = bdd_engine.getBDD().ref(Molecule.toBDD(aps));
//                p.println(device.name + " " + port + " " + bdd_engine.getBDD().satCount(sum));
//                bdd_engine.getBDD().deref(sum);
//            }
//        }
//    }

    protected String UpdateACLRule(String linestr) {
        String[] tokens = linestr.split(" ");

        // if(tokens[2].contains("config1511_151"))
        // {
        // System.out.println(linestr);
        // }

        FieldNodeAP e = FieldNodes.get(tokens[2]);
        if (e == null) {
            // System.out.println(tokens[2]);
            return null;
        }

        String[] tempVec = tokens[2].split(UtilityTools.split_str);
        String ACLstr;

        // + acl config10_9UJfHYB6Z6ILnzjpTIKf permit null null 183.15.80.169 null null
        // null null null null null -1 65535
        // + acl pozb_rtr_199 deny 0 255 171.64.201.44 null null null any null null null
        // -1 65535
        ACLstr = "accessList " + tempVec[1] + " " + tokens[3] + " " + tokens[4] + " " + tokens[5] + " " + tokens[6]
                + " " + tokens[7] + " " + tokens[8] + " " + tokens[9] + " " + tokens[10] + " " + tokens[11] + " "
                + tokens[12] + " " + tokens[13] + " " + tokens[15];

        ACLRule r = new ACLRule(ACLstr);

        /*
         * compute change tuple
         */
        ArrayList<ChangeItem> change_set = null;
        ArrayList<ChangeItemBDD> change_setBDD = null;
        if (tokens[0].equals("+")) {
            // if(encodeWithNDD)
            // {
                change_set = e.InsertACLRule(r);
                e.update_ACL(change_set);
            // }
            // else
            // {
            //     change_setBDD = e.InsertACLRuleBDD(r);
            //     e.update_ACL_BDD(change_setBDD);
            // }
        } else if (tokens[0].equals("-")) {
            System.out.println("Remove not implement !");
            // change_set = e.RemoveACLRule(r);
        }

        return tokens[2];
    }

    protected void updateFWDRuleBatch(HashMap<String, HashMap<String, HashSet<Pair<String, String>>>> fwd_rules,
            HashMap<String, HashSet<Integer>> moved_aps) {
        long t0 = System.nanoTime();
        ArrayList<String> updated_prefix = new ArrayList<String>();
        HashSet<String> updated_elements = new HashSet<String>();
        for (String ip : fwd_rules.keySet()) {
            updated_prefix.add(ip);
        }

        /*
         * from longest to shortest
         */
        Collections.sort(updated_prefix, new sortRulesByPriority());

        long t1 = System.nanoTime();
        // first step - get the change_set & copy_set & remove_set
        HashMap<String, ArrayList<ChangeTuple>> change_set = new HashMap<String, ArrayList<ChangeTuple>>();
        HashMap<String, ArrayList<ChangeTuple>> remove_set = new HashMap<String, ArrayList<ChangeTuple>>();
        HashMap<String, ArrayList<ChangeTuple>> copyto_set = new HashMap<String, ArrayList<ChangeTuple>>();
        HashMap<String, ArrayList<ChangeTupleBDD>> change_setBDD = new HashMap<String, ArrayList<ChangeTupleBDD>>();
        HashMap<String, ArrayList<ChangeTupleBDD>> remove_setBDD = new HashMap<String, ArrayList<ChangeTupleBDD>>();
        HashMap<String, ArrayList<ChangeTupleBDD>> copyto_setBDD = new HashMap<String, ArrayList<ChangeTupleBDD>>();
        for (String ip : updated_prefix) {
            for (String element_name : fwd_rules.get(ip).keySet()) {
                HashSet<Pair<String, String>> actions = fwd_rules.get(ip).get(element_name);

                HashSet<String> to_ports = new HashSet<String>();
                HashSet<String> from_ports = new HashSet<String>();

                for (Pair<String, String> pair : actions) {
                    if (pair.getKey().equals("+")) {
                        to_ports.add(pair.getValue());
                    } else if (pair.getKey().equals("-")) {
                        from_ports.add(pair.getValue());
                    }
                }

                // filter ports remained unchange
                HashSet<String> retained = new HashSet<String>(to_ports);
                retained.retainAll(from_ports);
                if (!retained.isEmpty()) {
                    to_ports.removeAll(retained);
                    from_ports.removeAll(retained);
                }
                FieldNodeAP e = FieldNodes.get(element_name);
                if (e == null) {
                    // System.out.println(element_name);
                    continue;
                }
                if(encodeWithNDD)
                {
                    e.updateFWRuleBatch(ip, to_ports, from_ports, change_set, copyto_set, remove_set);
                }
                else
                {
                    e.updateFWRuleBatchBDD(ip, to_ports, from_ports, change_setBDD, copyto_setBDD, remove_setBDD);
                }
                updated_elements.add(element_name);
            }
        }

        long t2 = System.nanoTime();

        for (String element_name : updated_elements) {
            FieldNodeAP e = FieldNodes.get(element_name);
            if (e == null) {
                System.err.println("Forwarding element " + element_name + " not found");
                System.exit(1);
            }
            if(encodeWithNDD)
            {
                e.update_FW(change_set.get(element_name), copyto_set.get(element_name));
            }
            else
            {
                e.update_FW_BDD(change_setBDD.get(element_name), copyto_setBDD.get(element_name));
            }
        }
        long t3 = System.nanoTime();
        // System.out.println((t1 - t0) / 1000000000.0);
        System.out.println("FW time1:" + (t2 - t1) / 1000000000.0);
        System.out.println("FW time2:" + (t3 - t2) / 1000000000.0);
    }

    public void UpdateFieldAP() {
        HashSet<NDD> preds = new HashSet<>();
        for (FieldNodeAP device : FieldNodes.values()) {
            for (NDD pred : device.ports_pred.values()) {
                preds.add(pred);
            }
        }

        HashMap<NDD, HashSet<Integer>[]> ndd_aps = new HashMap<>();
        HashMap<NDD, AtomizedNDD> ndd_mol = AtomizedNDD.atomization(preds, ndd_aps);

        splitMap.clear();
        HashMap<Integer, HashSet<Pair<String, String>>>[] ap_ports = splitMap.ap_ports;

        for (FieldNodeAP device : FieldNodes.values()) {
            String dName = device.name;
            HashSet<AtomizedNDD> oldSet = new HashSet<>(device.ports_aps.values());
            for (Map.Entry<String, NDD> entry : device.ports_pred.entrySet()) {
                String pName = entry.getKey();
                device.ports_aps.put(entry.getKey(), AtomizedNDD.ref(ndd_mol.get(entry.getValue())));
                HashSet<Integer>[] apSet = ndd_aps.get(entry.getValue());
                for (int field = 0; field <= AtomizedNDD.getFieldNum(); field++) {
                    HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = ap_ports[field];
                    for (int ap : apSet[field]) {
                        HashSet<Pair<String, String>> ports = sub_ap_ports.get(ap);
                        if (ports == null) {
                            ports = new HashSet<>();
                            ports.add(new Pair<String, String>(dName, pName));
                            sub_ap_ports.put(ap, ports);
                        } else {
                            ports.add(new Pair<String, String>(dName, pName));
                        }
                    }
                }
            }
            for (AtomizedNDD atomizedNDD : oldSet) {
                AtomizedNDD.deref(atomizedNDD);
            }
        }
        for(int i=0;i<=AtomizedNDD.getFieldNum();i++)
        {
            if(AtomizedNDD.getAllAtoms(i).size() == 1)
            {
                ap_ports[i].put(1, new HashSet<>());
            }
        }
    }

    public void split_ap_one_field(HashMap<Integer, HashSet<Integer>> split_ap, int field) {
        if (split_ap.size() == 0)
            return;

        for (Map.Entry<Integer, HashSet<Integer>> entry : split_ap.entrySet()) {
            AtomizedNDD.getAllAtoms(field).remove(entry.getKey());
            AtomizedNDD.getAllAtoms(field).addAll(entry.getValue());
            bdd_engine.getBDD().deref(entry.getKey());
        }

        // for (FieldNodeAP device : FieldNodes.values()) {
        // if (device.type == 0) {
        // for (String port : device.ports_aps.keySet()) {
        // Molecule aps = device.ports_aps.get(port);
        // Molecule new_aps = Molecule.split_port_ap_1_1(split_ap, aps);
        // if (!new_aps.is_False()) {
        // Molecule.table.ref(new_aps);
        // Molecule.table.deref(aps);
        // device.ports_aps.put(port, new_aps);
        // }
        // }
        // } else {
        // for (String port : device.ports_aps.keySet()) {
        // Molecule aps = device.ports_aps.get(port);
        // Pair<Boolean, Molecule> ret = Molecule.split_port_ap_1_n(split_ap, aps,
        // field);
        // if (ret.getKey()) {
        // Molecule.table.ref(ret.getValue());
        // Molecule.table.deref(aps);
        // device.ports_aps.put(port, ret.getValue());
        // }
        // }
        // }
        // }

        HashSet<Pair<String, String>> finished = new HashSet<>();
        HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = splitMap.ap_ports[field];
        HashMap<Integer, HashSet<Integer>> apToSplit = split_ap;
        for (int ap : apToSplit.keySet()) {
            if(ap == 1)continue;
            HashSet<Pair<String, String>> ports = sub_ap_ports.get(ap);
            for (Pair<String, String> p : ports) {
                if (!finished.contains(p)) {
                    FieldNodeAP device = FieldNodes.get(p.getKey());
                    if (device.type == 0) {
                        AtomizedNDD aps = device.ports_aps.get(p.getValue());
                        AtomizedNDD new_aps = AtomizedNDD.splitSingleFieldAtomsWithSingleFieldPredicate(split_ap, aps);
                        if (!new_aps.isFalse()) {
                            AtomizedNDD.ref(new_aps);
                            AtomizedNDD.deref(aps);
                            device.ports_aps.put(p.getValue(), new_aps);
                        }
                    } else {
                        AtomizedNDD aps = device.ports_aps.get(p.getValue());
                        Pair<Boolean, AtomizedNDD> ret = AtomizedNDD.splitSingleFieldAtomsWithMultipleFieldsPredicate(split_ap, aps, field);
                        if (ret.getKey()) {
                            AtomizedNDD.ref(ret.getValue());
                            AtomizedNDD.deref(aps);
                            device.ports_aps.put(p.getValue(), ret.getValue());
                        }
                    }
                    finished.add(p);
                }
            }
        }

        splitMap.split(split_ap, field);
    }

    public void split_ap_multi_field(ArrayList<HashMap<Integer, HashSet<Integer>>> split_ap) {
        boolean empty = true;
        for (int curr = 0; curr <= AtomizedNDD.getFieldNum(); curr++) {
            if (split_ap.get(curr).size() > 0) {
                empty = false;
            }
        }
        if (empty)
            return;

        for (int curr_field = 0; curr_field <= AtomizedNDD.getFieldNum(); curr_field++) {
            for (Map.Entry<Integer, HashSet<Integer>> entry : split_ap.get(curr_field).entrySet()) {
                AtomizedNDD.getAllAtoms(curr_field).remove(entry.getKey());
                AtomizedNDD.getAllAtoms(curr_field).addAll(entry.getValue());
                bdd_engine.getBDD().deref(entry.getKey());
            }
        }

        // for (FieldNodeAP device : FieldNodes.values()) {
        // if (device.type == 0 && split_ap.get(1).size() == 0)
        // continue;
        // for (Map.Entry<String, Molecule> entry : device.ports_aps.entrySet()) {
        // Molecule aps = entry.getValue();
        // Pair<Boolean, Molecule> ret = Molecule.split_port_ap_n_n(split_ap, aps);
        // if (ret.getKey()) {
        // Molecule.table.ref(ret.getValue());
        // Molecule.table.deref(aps);
        // device.ports_aps.put(entry.getKey(), ret.getValue());
        // }
        // }
        // }

        HashSet<Pair<String, String>> finished = new HashSet<>();
        for (int field = 0; field <= AtomizedNDD.getFieldNum(); field++) {
            HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = splitMap.ap_ports[field];
            HashMap<Integer, HashSet<Integer>> apToSplit = split_ap.get(field);
            for (int ap : apToSplit.keySet()) {
                if(ap==1)continue;
                HashSet<Pair<String, String>> ports = sub_ap_ports.get(ap);
                for (Pair<String, String> port : ports) {
                    if (!finished.contains(port)) {
                        FieldNodeAP device = FieldNodes.get(port.getKey());
                        AtomizedNDD moleculeToSplit = device.ports_aps.get(port.getValue());
                        Pair<Boolean, AtomizedNDD> ret = AtomizedNDD.splitMultipleFieldsAtomsWithMultipleFieldsPredicate(split_ap, moleculeToSplit);
                        if (ret.getKey()) {
                            AtomizedNDD.ref(ret.getValue());
                            AtomizedNDD.deref(moleculeToSplit);
                            device.ports_aps.put(port.getValue(), ret.getValue());
                        }
                        finished.add(port);
                    }
                }
            }
        }

        splitMap.split(split_ap);
    }
}