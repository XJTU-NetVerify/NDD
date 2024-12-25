package application.wlan.bdd.verifier.apkeep.checker;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Stack;

import application.wlan.bdd.verifier.apkeep.core.Network;
import application.wlan.bdd.verifier.apkeep.element.ACLElement;
import application.wlan.bdd.verifier.apkeep.element.ForwardElement;
import application.wlan.bdd.verifier.apkeep.utils.UtilityTools;
import application.wlan.bdd.verifier.common.PositionTuple;

public class Checker_stanford extends Checker {

    public Checker_stanford(Network net) {
        super(net);
    }

    public void PropertyCheck() throws IOException {
        while (!queue.isEmpty()) {
            // System.out.println(net.bdd_engine.getBDD().table_size);
            TranverseNode curr_node = queue.pop();
            if (curr_node.curr.getDeviceName().split(UtilityTools.split_str).length == 1) // forward element
            {
                ForwardElement curr_device = net.FWelements.get(curr_node.curr.getDeviceName());
                for (String port : curr_device.port_aps_raw.keySet()) {
                    HashSet<String> phy_port = new HashSet<String>();
                    HashSet<Integer> next_fw_aps = new HashSet<Integer>(curr_node.fw_aps);
                    next_fw_aps.retainAll(curr_device.port_aps_raw.get(port));
                    if (next_fw_aps.size() == 0)
                        continue;
                    if (port.startsWith("vlan"))
                        phy_port = net.vlan_phy.get(curr_device.name).get(port);
                    else
                        phy_port.add(port);
                    for (String out_port : phy_port) {
                        if (out_port.equalsIgnoreCase("default")
                                || out_port.equalsIgnoreCase(curr_node.curr.getPortName()))
                            continue;

                        if (net.edge_ports.containsKey(curr_node.curr.getDeviceName())
                                && net.edge_ports.get(curr_node.curr.getDeviceName()).contains(out_port)) {
                            int reachPackets = mergeSet(next_fw_aps, curr_node.acl_aps);
                            if (reachPackets != 0) {
                                if (!ans.contains(
                                        curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName())) {
                                    ans.add(curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName());
                                }
                            }
                            if (calculateReach) {
                                if (!reach.containsKey(curr_node.source)) {
                                    reach.put(curr_node.source, new HashMap<PositionTuple, Integer>());
                                }
                                int origin;
                                if (reach.get(curr_node.source)
                                        .containsKey(new PositionTuple(curr_node.curr.getDeviceName(), out_port))) {
                                    origin = reach.get(curr_node.source)
                                            .get(new PositionTuple(curr_node.curr.getDeviceName(), out_port));
                                } else {
                                    origin = net.bdd_engine.BDDFalse;
                                }
                                int reach_bdd = net.bdd_engine.getBDD()
                                        .ref(net.bdd_engine.getBDD().or(origin, reachPackets));
                                net.bdd_engine.getBDD().deref(origin);
                                if (reach_bdd != net.bdd_engine.BDDFalse) {
                                    reach.get(curr_node.source).put(
                                            new PositionTuple(curr_node.curr.getDeviceName(), out_port), reach_bdd);
                                }
                            }
                            net.bdd_engine.getBDD().deref(reachPackets);
                            continue;
                        }
                        for (PositionTuple next_pt : net.topology.get(new PositionTuple(curr_device.name, out_port))) {
                            if (curr_node.visited.contains(next_pt.getDeviceName())) {
                                if (mergeSet(next_fw_aps, curr_node.acl_aps) != net.bdd_engine.BDDFalse) {
                                    // System.out.println("Loop detected !");
                                }
                                continue;
                            }
                            queue.push(new TranverseNode(curr_node.source, next_pt, next_fw_aps, curr_node.acl_aps,
                                    curr_node.visited));
                        }
                    }
                }
            } else // acl element
            {
                ACLElement curr_device = net.ACLelements_application.get(curr_node.curr.getDeviceName());
                for (String out_port : curr_device.port_aps_raw.keySet()) {
                    if (out_port.equalsIgnoreCase("deny") || out_port.equalsIgnoreCase(curr_node.curr.getPortName()))
                        continue;
                    HashSet<Integer> next_acl_aps = new HashSet<Integer>(curr_node.acl_aps);
                    next_acl_aps.retainAll(curr_device.port_aps_raw.get(out_port));
                    if (next_acl_aps.size() == 0)
                        continue;
                    if (net.edge_ports.containsKey(curr_node.curr.getDeviceName())
                            && net.edge_ports.get(curr_node.curr.getDeviceName()).contains(out_port)) {
                        int reachPackets = mergeSet(curr_node.fw_aps, next_acl_aps);
                        if (reachPackets != 0) {
                            if (!ans.contains(
                                    curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName())) {
                                ans.add(curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName());
                            }
                        }
                        if (calculateReach) {
                            if (!reach.containsKey(curr_node.source)) {
                                reach.put(curr_node.source, new HashMap<PositionTuple, Integer>());
                            }
                            int origin;
                            if (reach.get(curr_node.source)
                                    .containsKey(new PositionTuple(curr_node.curr.getDeviceName(), out_port))) {
                                origin = reach.get(curr_node.source)
                                        .get(new PositionTuple(curr_node.curr.getDeviceName(), out_port));
                            } else {
                                origin = net.bdd_engine.BDDFalse;
                            }
                            int reach_bdd = net.bdd_engine.getBDD()
                                    .ref(net.bdd_engine.getBDD().or(origin, reachPackets));
                            net.bdd_engine.getBDD().deref(origin);
                            if (reach_bdd != net.bdd_engine.BDDFalse) {
                                reach.get(curr_node.source)
                                        .put(new PositionTuple(curr_node.curr.getDeviceName(), out_port), reach_bdd);
                            }
                        }
                        net.bdd_engine.getBDD().deref(reachPackets);
                        continue;
                    }
                    for (PositionTuple next_pt : net.topology.get(new PositionTuple(curr_device.name, out_port))) {
                        if (curr_node.visited.contains(next_pt.getDeviceName())) {
                            if (mergeSet(curr_node.fw_aps, next_acl_aps) != net.bdd_engine.BDDFalse) {
                                // System.out.println("ACL Loop detected !");
                            }
                            continue;
                        }
                        queue.push(new TranverseNode(curr_node.source, next_pt, curr_node.fw_aps, next_acl_aps,
                                curr_node.visited));
                    }
                }
            }
        }
    }
}