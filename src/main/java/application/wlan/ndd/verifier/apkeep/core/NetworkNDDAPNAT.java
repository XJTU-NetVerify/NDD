package application.wlan.ndd.verifier.apkeep.core;

import java.io.IOException;
import java.util.*;

import application.wlan.ndd.verifier.apkeep.element.FieldNodeAP;
import application.wlan.ndd.verifier.apkeep.element.FieldNodeAPNAT;
import application.wlan.ndd.verifier.common.RewriteRule;
import application.wlan.ndd.verifier.common.Utility;
import javafx.util.Pair;
import org.ants.jndd.diagram.AtomizedNDD;
import org.ants.jndd.diagram.NDD;

public class NetworkNDDAPNAT extends NetworkNDDAP {
    public HashMap<String, HashSet<String>> startEdge;
    public HashMap<String, HashSet<String>> endEdge;
    public SplitMap splitMapAction;

    public NetworkNDDAPNAT(String name) throws IOException {
        super(name);
        splitMapAction = new SplitMap(AtomizedNDD.getFieldNum());
        splitMapAction.SetType(true);
    }

    public void initializeNetwork(ArrayList<String> l1_links, ArrayList<String> edge_ports,
            Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> dpDevices,
            ArrayList<String> NAT_rules) throws IOException {
        System.out.println(name);
        constructTopology(l1_links);
        if (name.equalsIgnoreCase("pd")) {
            for (int num = 1; num <= 1646; num++) {
                String device_name = "config" + num;
                if (!FieldNodes.containsKey(device_name)) {
                    addForwardNode(device_name);
                }
            }
        }
        setEdgePorts(edge_ports);
        setEndHosts(edge_ports);
        // addHostsToTopology();
        parseACLConfigs(dpDevices);
        setEdge();
        AddNATNodes(NAT_rules);
    }

    public void setEdge() {
        startEdge = new HashMap<>(edge_ports);
        endEdge = new HashMap<>(edge_ports);
    }

    public HashMap<String, HashSet<Integer>> UpdateBatchRules(ArrayList<String> rules, ArrayList<String> acl_rules,
            ArrayList<String> NAT_rules) throws IOException {
        // AddNATNodes(NAT_rules);

        HashMap<String, HashSet<Integer>> moved_aps = new HashMap<String, HashSet<Integer>>();
        HashMap<String, HashMap<String, HashSet<Pair<String, String>>>> fwd_rules = new HashMap<String, HashMap<String, HashSet<Pair<String, String>>>>();
        for (String linestr : rules) {
            addFWDRule(fwd_rules, linestr);
        }

        long t0 = System.nanoTime();

        int count = 0;
        for (String linestr : acl_rules) {
            count++;
            UpdateACLRule(linestr);
            if (count == 1000) {
                UpdateFieldAP();
            }
        }

        for (String acl_name : acl_application.keySet()) {
            for (String acl_app : acl_application.get(acl_name)) {
                FieldNodes.get(acl_app).ports = FieldNodes.get(acl_name).ports;
                FieldNodes.get(acl_app).ports_pred = FieldNodes.get(acl_name).ports_pred;
                FieldNodes.get(acl_app).ports_aps = FieldNodes.get(acl_name).ports_aps;
            }
        }

        long t1 = System.nanoTime();

        updateFWDRuleBatch(fwd_rules, moved_aps);
        UpdateFieldAP();

        long t2 = System.nanoTime();

        updateNATRule(NAT_rules);
        UpdateFieldAP();

        long t3 = System.nanoTime();

        // CheckNDD_Molecule();

        System.out.println("ACL: " + (t1 - t0) / 1000000000.0);
        System.out.println("FW: " + (t2 - t1) / 1000000000.0);
        System.out.println("NAT: " + (t3 - t2) / 1000000000.0);
        return moved_aps;
    }

//    private void CheckNDD_Molecule() {
//        for (FieldNodeAP device : FieldNodes.values()) {
//            for (String port : device.ports_aps.keySet()) {
//                int nddPred = bdd_engine.getBDD().ref(NDD.toBDD(device.ports_pred.get(port)));
//                int moleculePred = bdd_engine.getBDD().ref(Molecule.toBDD(device.ports_aps.get(port)));
//                if (nddPred != moleculePred) {
//                    System.out.println(device.name + " " + port + " " + nddPred + " " + moleculePred);
//                }
//                if (!Molecule.CheckPred(device.ports_aps.get(port))) {
//                    System.out.println(device.name + " " + port + " check failed");
//                }
//            }
//            if(device.type == 2)
//            {
//                FieldNodeAPNAT d = (FieldNodeAPNAT)device;
//                for(String port : d.ports_action_aps.keySet())
//                {
//                    System.out.println(d.name);
//                    int nddPred1 = bdd_engine.getBDD().ref(NDD.toBDD(d.ports_action_pred.get(port).getKey()));
//                    int moleculePred1 = bdd_engine.getBDD().ref(Molecule.toBDD(d.ports_action_aps.get(port).getKey()));
//                    int nddPred2 = bdd_engine.getBDD().ref(NDD.toBDD(d.ports_action_pred.get(port).getValue()));
//                    int moleculePred2 = bdd_engine.getBDD().ref(Molecule.toBDD(d.ports_action_aps.get(port).getValue()));
//                    if(nddPred1 != moleculePred1 || nddPred2 != moleculePred2)
//                    {
//                        System.out.println(d.name+" "+port+" "+nddPred1+" "+moleculePred1+" "+nddPred2+" "+moleculePred2);
//                    }
//                    if (!Molecule.CheckPred(d.ports_action_aps.get(port).getKey())) {
//                        System.out.println(d.name + " " + port + " src check failed");
//                    }
//                    if (!Molecule.CheckPred(d.ports_action_aps.get(port).getValue())) {
//                        System.out.println(d.name + " " + port + " dst check failed");
//                    }
//                }
//            }
//        }
//    }

    public void AddNATNodes(ArrayList<String> NAT_rules) {
        for (String rule : NAT_rules) {
            String[] tokens = rule.split(" ");
            String dname = tokens[2];
            String port = tokens[3];
            String NATname = "NAT" + dname + "_" + tokens[4];
            addNATNode(NATname);
            if (tokens[4].equals("in")) {
                AddOneWayLink(NATname, "default", dname, port);
                startEdge.get(dname).remove(port);
                if (startEdge.get(dname).size() == 0)
                    startEdge.remove(dname);
                HashSet<String> portSet = new HashSet<>();
                portSet.add("inport");
                startEdge.put(NATname, portSet);
            } else {
                AddOneWayLink(dname, port, NATname, "inport");
                endEdge.get(dname).remove(port);
                if (endEdge.get(dname).size() == 0)
                    endEdge.remove(dname);
                HashSet<String> portSet = new HashSet<>();
                portSet.add("default");
                endEdge.put(NATname, portSet);
            }
        }
    }

    public void addNATNode(String element) {
        if (!FieldNodes.containsKey(element)) {
            FieldNodes.put(element, new FieldNodeAPNAT(element, this, 2));
            splitMap.AddDefaultPort(element);
        }
    }

    public void updateNATRule(ArrayList<String> NAT_rules) {
        for (String rule : NAT_rules) {
            String[] tokens = rule.split(" ");
            String element = "NAT" + tokens[2] + "_" + tokens[4];
            FieldNodeAPNAT e = (FieldNodeAPNAT)FieldNodes.get(element);
            NDD old_val = bdd_engine.encodeNAT(tokens[5], tokens[6]);
            ArrayList<Integer> fields = new ArrayList<>();
            if (!tokens[7].equals("any")) {
                fields.add(0);
            }
            if (!tokens[8].equals("any")) {
                fields.add(1);
            }

            int srcBDD;
            if (tokens[7].equals("any")) {
                srcBDD = bdd_engine.BDDTrue;
            } else {
                srcBDD = NDD.encodePrefixBDD(Utility.IPBinRep(tokens[7]), bdd_engine.SRC_IP_FIELD);
            }
            NDD srcNDD = NDD.ref(NDD.toNDD(srcBDD));

            int dstBDD;
            if (tokens[8].equals("any")) {
                dstBDD = bdd_engine.BDDTrue;
            } else {
                dstBDD = NDD.encodePrefixBDD(Utility.IPBinRep(tokens[8]), bdd_engine.DST_IP_FIELD);
            }
            NDD dstNDD = NDD.ref(NDD.toNDD(dstBDD));

            String port = tokens[7] + "_" + tokens[8];

            RewriteRule r = new RewriteRule(old_val, fields, srcNDD, dstNDD, port);
            if (tokens[0].equals("+")) {
                ArrayList changelist = e.InsertRewriteRule(r);
                e.update_ACL(changelist);
            }
        }
    }

    public void UpdateFieldAP() {
        HashSet<NDD> preds = new HashSet<>();
        for (FieldNodeAP device : FieldNodes.values()) {
            for (NDD pred : device.ports_pred.values()) {
                preds.add(pred);
            }
            if (device.type == 2) {
                for (Pair<NDD, NDD> pair : ((FieldNodeAPNAT)device).ports_action_pred.values()) {
                    if (!pair.getKey().isTrue())
                        preds.add(pair.getKey());
                    if (!pair.getValue().isTrue())
                        preds.add(pair.getValue());
                }
            }
        }

        HashMap<NDD, HashSet<Integer>[]> ndd_aps = new HashMap<>();
        HashMap<NDD, AtomizedNDD> ndd_mol = AtomizedNDD.atomization(preds, ndd_aps);

        splitMap.clear();
        splitMapAction.clear();
        HashMap<Integer, HashSet<Pair<String, String>>>[] ap_ports = splitMap.ap_ports;
        HashMap<Integer, HashSet<Pair<String, String>>>[] ap_ports_action = splitMapAction.ap_ports;

        for (FieldNodeAP device : FieldNodes.values()) {
            String dName = device.name;
            HashSet<AtomizedNDD> oldSet = new HashSet<>(device.ports_aps.values());
            for (Map.Entry<String, NDD> entry : device.ports_pred.entrySet()) {
                String pName = entry.getKey();
                device.ports_aps.put(entry.getKey(), AtomizedNDD.ref(ndd_mol.get(entry.getValue())));
                HashSet<Integer>[] apSet = ndd_aps.get(entry.getValue());
                for (int field = 0; field < AtomizedNDD.getFieldNum(); field++) {
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
            if (device.type == 2) {
                HashSet<Pair<AtomizedNDD, AtomizedNDD>> oldSetAction = new HashSet<>(((FieldNodeAPNAT)device).ports_action_aps.values());
                for (Map.Entry<String, Pair<NDD, NDD>> entry : ((FieldNodeAPNAT)device).ports_action_pred.entrySet()) {
                    String pName = entry.getKey();
                    AtomizedNDD MSrc = AtomizedNDD.getTrue();
                    AtomizedNDD MDst = AtomizedNDD.getTrue();
                    if (!entry.getValue().getKey().isTrue()) {
                        MSrc = AtomizedNDD.ref(ndd_mol.get(entry.getValue().getKey()));
                        HashSet<Integer>[] apSet = ndd_aps.get(entry.getValue().getKey());
                        HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = ap_ports_action[0];
                        for (int ap : apSet[0]) {
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
                    if (!entry.getValue().getValue().isTrue()) {
                        MDst = AtomizedNDD.ref(ndd_mol.get(entry.getValue().getValue()));
                        HashSet<Integer>[] apSet = ndd_aps.get(entry.getValue().getValue());
                        HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = ap_ports_action[1];
                        for (int ap : apSet[1]) {
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
                    ((FieldNodeAPNAT)device).ports_action_aps.put(entry.getKey(), new Pair<>(MSrc, MDst));
                }
                for (Pair<AtomizedNDD, AtomizedNDD> pair : oldSetAction) {
                    AtomizedNDD.deref(pair.getKey());
                    AtomizedNDD.deref(pair.getValue());
                }
            }
        }
        for (int i = 0; i < AtomizedNDD.getFieldNum(); i++) {
            if (AtomizedNDD.getAllAtoms(i).size() == 1) {
                ap_ports[i].put(1, new HashSet<>());
                if(i<=1)ap_ports_action[i].put(1, new HashSet<>());
            }
            else
            {
                if(i<=1)
                {
                    for(int ap : AtomizedNDD.getAllAtoms(i))
                    {
                        if(!ap_ports_action[i].containsKey(ap))
                        {
                            ap_ports_action[i].put(ap, new HashSet<>());
                        }
                    }
                }
                for(int ap : AtomizedNDD.getAllAtoms(i))
                {
                    if(!ap_ports[i].containsKey(ap))
                    {
                        ap_ports[i].put(ap, new HashSet<>());
                    }
                }
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

        HashSet<Pair<String, String>> finished = new HashSet<>();
        HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = splitMap.ap_ports[field];
        HashMap<Integer, HashSet<Integer>> apToSplit = split_ap;
        for (int ap : apToSplit.keySet()) {
            if (ap == 1)
                continue;
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

        if (field == 0 || field == 1) {
            finished = new HashSet<>();
            sub_ap_ports = splitMapAction.ap_ports[field];
            for (int ap : apToSplit.keySet()) {
                if (ap == 1)
                    continue;
                HashSet<Pair<String, String>> ports = sub_ap_ports.get(ap);
                for (Pair<String, String> p : ports) {
                    if (!finished.contains(p)) {
                        FieldNodeAPNAT device = (FieldNodeAPNAT)FieldNodes.get(p.getKey());
                        Pair<AtomizedNDD, AtomizedNDD> pair = device.ports_action_aps.get(p.getValue());
                        if (field == 0) {
                            AtomizedNDD aps = pair.getKey();
                            AtomizedNDD new_aps = AtomizedNDD.splitSingleFieldAtomsWithSingleFieldPredicate(split_ap, aps);
                            if (!new_aps.isFalse()) {
                                AtomizedNDD.ref(new_aps);
                                AtomizedNDD.deref(aps);
                                device.ports_action_aps.put(p.getValue(), new Pair<>(new_aps, pair.getValue()));
                            }
                        } else {
                            AtomizedNDD aps = pair.getValue();
                            AtomizedNDD new_aps = AtomizedNDD.splitSingleFieldAtomsWithSingleFieldPredicate(split_ap, aps);
                            if (!new_aps.isFalse()) {
                                AtomizedNDD.ref(new_aps);
                                AtomizedNDD.deref(aps);
                                device.ports_action_aps.put(p.getValue(), new Pair<>(pair.getKey(), new_aps));
                            }
                        }
                        finished.add(p);
                    }
                }
            }
        }

        splitMap.split(split_ap, field);
        splitMapAction.split(split_ap, field);
    }

    public void split_ap_multi_field(ArrayList<HashMap<Integer, HashSet<Integer>>> split_ap) {
        boolean empty = true;
        for (int curr = 0; curr < 5; curr++) {
            if (split_ap.get(curr).size() > 0) {
                empty = false;
            }
        }
        if (empty)
            return;

        for (int curr_field = 0; curr_field < AtomizedNDD.getFieldNum(); curr_field++) {
            for (Map.Entry<Integer, HashSet<Integer>> entry : split_ap.get(curr_field).entrySet()) {
                AtomizedNDD.getAllAtoms(curr_field).remove(entry.getKey());
                AtomizedNDD.getAllAtoms(curr_field).addAll(entry.getValue());
                bdd_engine.getBDD().deref(entry.getKey());
            }
        }

        HashSet<Pair<String, String>> finished = new HashSet<>();
        for (int field = 0; field < AtomizedNDD.getFieldNum(); field++) {
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

        for (int field = 0; field <= 1; field++) {
            finished = new HashSet<>();
            HashMap<Integer, HashSet<Pair<String, String>>> sub_ap_ports = splitMapAction.ap_ports[field];
            HashMap<Integer, HashSet<Integer>> apToSplit = split_ap.get(field);
            for (int ap : apToSplit.keySet()) {
                if (ap == 1)
                    continue;
                HashSet<Pair<String, String>> ports = sub_ap_ports.get(ap);
                for (Pair<String, String> p : ports) {
                    if (!finished.contains(p)) {
                        FieldNodeAPNAT device = (FieldNodeAPNAT)FieldNodes.get(p.getKey());
                        Pair<AtomizedNDD, AtomizedNDD> pair = device.ports_action_aps.get(p.getValue());
                        if (field == 0) {
                            AtomizedNDD aps = pair.getKey();
                            Pair<Boolean, AtomizedNDD> ret = AtomizedNDD.splitMultipleFieldsAtomsWithMultipleFieldsPredicate(split_ap, aps);
                            if (ret.getKey()) {
                                AtomizedNDD.ref(ret.getValue());
                                AtomizedNDD.deref(aps);
                                device.ports_action_aps.put(p.getValue(), new Pair<>(ret.getValue(), pair.getValue()));
                            }
                        } else {
                            AtomizedNDD aps = pair.getValue();
                            Pair<Boolean, AtomizedNDD> ret = AtomizedNDD.splitMultipleFieldsAtomsWithMultipleFieldsPredicate(split_ap, aps);
                            if (ret.getKey()) {
                                AtomizedNDD.ref(ret.getValue());
                                AtomizedNDD.deref(aps);
                                device.ports_action_aps.put(p.getValue(), new Pair<>(pair.getKey(), ret.getValue()));
                            }
                        }
                        finished.add(p);
                    }
                }
            }
        }

        splitMap.split(split_ap);
        splitMapAction.split(split_ap);
    }
}
