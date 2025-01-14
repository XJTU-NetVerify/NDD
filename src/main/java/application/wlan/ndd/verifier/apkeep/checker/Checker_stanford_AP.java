package application.wlan.ndd.verifier.apkeep.checker;

import application.wlan.ndd.verifier.apkeep.core.NetworkNDDAP;
import application.wlan.ndd.verifier.apkeep.element.FieldNodeAP;
import application.wlan.ndd.verifier.apkeep.utils.UtilityTools;
import application.wlan.ndd.verifier.common.PositionTuple;
import org.ants.jndd.diagram.AtomizedNDD;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Stack;

public class Checker_stanford_AP{
    static boolean checkCorrectness = false;
    NetworkNDDAP net;
    Stack<TranverseNodeAP> queue;
    public HashSet<String> ans;
    HashMap<PositionTuple, HashMap<PositionTuple, AtomizedNDD>> reach;

    public Checker_stanford_AP(NetworkNDDAP net, boolean test) {
        this.net = net;
        queue = new Stack<>();
        ans = new HashSet<>();
        reach = new HashMap<>();
        if (!test) {
            for (String device : net.edge_ports.keySet()) {
                for (String port : net.edge_ports.get(device)) {
                    // if(!device.equals("config1161self"))continue;
                    HashMap<PositionTuple, AtomizedNDD> subMap = new HashMap<>();
                    subMap.put(new PositionTuple(device, port), AtomizedNDD.getTrue());
                    reach.put(new PositionTuple(device, port), subMap);
                    queue.add(new TranverseNodeAP(new PositionTuple(device, port), AtomizedNDD.getTrue()));
                }
            }
        }
        else
        {

        }
    }

    public void PropertyCheck() throws IOException {
        while (!queue.isEmpty()) {
            TranverseNodeAP curr_node = queue.pop();
            FieldNodeAP curr_device = net.FieldNodes.get(curr_node.curr.getDeviceName());
            if (curr_node.curr.getDeviceName().split(UtilityTools.split_str).length == 1) // forward element
            {
                for (String port : curr_device.ports_aps.keySet()) {
                    HashSet<String> phy_port = new HashSet<String>();
                    AtomizedNDD next_AP = AtomizedNDD
                            .ref(AtomizedNDD.and(curr_node.APs, curr_device.ports_aps.get(port)));
                    if (next_AP.isFalse())
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
                            if (!ans.contains(
                                    curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName())) {
                                ans.add(curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName());
                            }
                            if (checkCorrectness) {
                                if (!reach.containsKey(curr_node.source))
                                    reach.put(curr_node.source, new HashMap<>());
                                AtomizedNDD subReach = reach.get(curr_node.source)
                                        .get(new PositionTuple(curr_node.curr.getDeviceName(), out_port));
                                if (subReach == null) {
                                    subReach = AtomizedNDD.getFalse();
                                }
                                AtomizedNDD t = subReach;
                                AtomizedNDD new_reach = AtomizedNDD.ref(AtomizedNDD.or(subReach, next_AP));
                                AtomizedNDD.deref(t);
                                reach.get(curr_node.source)
                                        .put(new PositionTuple(curr_node.curr.getDeviceName(), out_port), new_reach);
                            }
                            continue;
                        }
                        for (PositionTuple next_pt : net.topology.get(new PositionTuple(curr_device.name, out_port))) {
                            if (curr_node.visited.contains(next_pt.getDeviceName())) {
                                // System.out.println("Loop detected !");
                                // Molecule.table.deref(next_AP);
                                continue;
                            }
                            queue.push(new TranverseNodeAP(curr_node.source, next_pt, next_AP, curr_node.visited));
                        }
                    }
                    AtomizedNDD.deref(next_AP);
                }
                AtomizedNDD.deref(curr_node.APs);
            } else // acl element
            {
                for (String out_port : curr_device.ports_aps.keySet()) {
                    if (out_port.equalsIgnoreCase("deny") || out_port.equalsIgnoreCase(curr_node.curr.getPortName()))
                        continue;
                    AtomizedNDD next_AP = AtomizedNDD
                            .ref(AtomizedNDD.and(curr_node.APs, curr_device.ports_aps.get(out_port)));
                    if (next_AP.isFalse())
                        continue;
                    if (net.edge_ports.containsKey(curr_node.curr.getDeviceName())
                            && net.edge_ports.get(curr_node.curr.getDeviceName()).contains(out_port)) {
                        if (!ans.contains(curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName())) {
                            ans.add(curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName());
                        }
                        if (checkCorrectness) {
                            if (!reach.containsKey(curr_node.source))
                                reach.put(curr_node.source, new HashMap<PositionTuple, AtomizedNDD>());
                            AtomizedNDD subReach = reach.get(curr_node.source)
                                    .get(new PositionTuple(curr_node.curr.getDeviceName(), out_port));
                            if (subReach == null) {
                                subReach = AtomizedNDD.getFalse();
                            }
                            AtomizedNDD t = subReach;
                            AtomizedNDD new_reach = AtomizedNDD.ref(AtomizedNDD.or(subReach, next_AP));
                            AtomizedNDD.deref(t);
                            reach.get(curr_node.source).put(new PositionTuple(curr_node.curr.getDeviceName(), out_port),
                                    new_reach);
                        }
                        AtomizedNDD.deref(next_AP);
                        continue;
                    }
                    for (PositionTuple next_pt : net.topology.get(new PositionTuple(curr_device.name, out_port))) {
                        if (curr_node.visited.contains(next_pt.getDeviceName())) {
                            // System.out.println("Loop detected !");
                            // Molecule.table.deref(next_AP);
                            continue;
                        }
                        AtomizedNDD.ref(next_AP);
                        queue.push(new TranverseNodeAP(curr_node.source, next_pt, next_AP, curr_node.visited));
                    }
                    AtomizedNDD.deref(next_AP);
                }
                AtomizedNDD.deref(curr_node.APs);
            }
        }
    }
}