package application.wan.ndd.verifier.apkeep.checker;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Stack;

import application.wan.ndd.verifier.apkeep.core.NetworkNDDAPNAT;
import application.wan.ndd.verifier.apkeep.element.FieldNodeAP;
import application.wan.ndd.verifier.apkeep.element.FieldNodeAPNAT;
import application.wan.ndd.verifier.common.PositionTuple;
import javafx.util.Pair;
import org.ants.jndd.diagram.AtomizedNDD;

public class CheckerNDDAPNAT {
    static boolean checkCorrectness = false;
    NetworkNDDAPNAT net;
    Stack<TranverseNodeAP> queue;
    public HashSet<String> ans;
    HashMap<PositionTuple, HashMap<PositionTuple, AtomizedNDD>> reach;

    public CheckerNDDAPNAT(NetworkNDDAPNAT net, boolean test) {
        this.net = net;
        queue = new Stack<>();
        ans = new HashSet<>();
        reach = new HashMap<>();
        if (!test) {
            for (String device : net.startEdge.keySet()) {
                for (String port : net.startEdge.get(device)) {
                    // if(!device.equals("NATconfig1497_in"))continue;
                    HashMap<PositionTuple, AtomizedNDD> subMap = new HashMap<>();
                    subMap.put(new PositionTuple(device, port), AtomizedNDD.getTrue());
                    reach.put(new PositionTuple(device, port), subMap);
                    queue.add(new TranverseNodeAP(new PositionTuple(device, port), AtomizedNDD.getTrue()));
                }
            }
        } else {

        }
    }

    public void PropertyCheck() throws IOException {
        while (!queue.isEmpty()) {
            TranverseNodeAP curr_node = queue.pop();
            FieldNodeAP curr_device = net.FieldNodes.get(curr_node.curr.getDeviceName());
            if (curr_device.type == 2) {
                AtomizedNDD next_APSet = AtomizedNDD.getFalse();
                for (String port : curr_device.ports_aps.keySet()) {
                    if (port.equals("default")) {
                        AtomizedNDD forward = AtomizedNDD
                                .ref(AtomizedNDD.and(curr_device.ports_aps.get(port), curr_node.APs));
                        AtomizedNDD old = next_APSet;
                        next_APSet = AtomizedNDD.ref(AtomizedNDD.or(next_APSet, forward));
                        AtomizedNDD.deref(forward);
                        AtomizedNDD.deref(old);
                    } else {
                        AtomizedNDD match = AtomizedNDD
                                .ref(AtomizedNDD.and(curr_device.ports_aps.get(port), curr_node.APs));
                        if (match != AtomizedNDD.getFalse()) {
                            Pair<AtomizedNDD, AtomizedNDD> pair = ((FieldNodeAPNAT) curr_device).ports_action_aps
                                    .get(port);
                            if (!pair.getKey().isTrue()) {
                                AtomizedNDD old = match;
                                match = AtomizedNDD.ref(AtomizedNDD.exist(match, 0));
                                AtomizedNDD.deref(old);
                                old = match;
                                match = AtomizedNDD.ref(AtomizedNDD.and(match, pair.getKey()));
                                AtomizedNDD.deref(old);
                            }
                            if (!pair.getValue().isTrue()) {
                                AtomizedNDD old = match;
                                match = AtomizedNDD.ref(AtomizedNDD.exist(match, 1));
                                AtomizedNDD.deref(old);
                                old = match;
                                match = AtomizedNDD.ref(AtomizedNDD.and(match, pair.getValue()));
                                AtomizedNDD.deref(old);
                            }
                            AtomizedNDD old = next_APSet;
                            next_APSet = AtomizedNDD.ref(AtomizedNDD.or(next_APSet, match));
                            AtomizedNDD.deref(match);
                            AtomizedNDD.deref(old);
                        }
                    }
                }

                if (!next_APSet.isFalse()) {
                    String out_port = "default";
                    if (net.endEdge.containsKey(curr_node.curr.getDeviceName())
                            && net.endEdge.get(curr_node.curr.getDeviceName()).contains(out_port)) {
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
                            AtomizedNDD new_reach = AtomizedNDD.ref(AtomizedNDD.or(subReach, next_APSet));
                            AtomizedNDD.deref(t);
                            reach.get(curr_node.source).put(new PositionTuple(curr_node.curr.getDeviceName(), out_port),
                                    new_reach);
                        }
                        AtomizedNDD.deref(next_APSet);
                        continue;
                    }
                    for (PositionTuple next_pt : net.topology.get(new PositionTuple(curr_device.name, out_port))) {
                        if (curr_node.visited.contains(next_pt.getDeviceName())) {
                            // System.out.println("Loop detected !");
                            // AtomizedNDD.deref(next_APSet);
                            continue;
                        }
                        AtomizedNDD.ref(next_APSet);
                        queue.push(new TranverseNodeAP(curr_node.source, next_pt, next_APSet, curr_node.visited));
                    }
                    AtomizedNDD.deref(next_APSet);
                }
            } else {
                for (String out_port : curr_device.ports) {
                    if (out_port.equalsIgnoreCase("deny") || out_port.equalsIgnoreCase("default")
                            || out_port.equalsIgnoreCase(curr_node.curr.getPortName()))
                        continue;
                    AtomizedNDD next_AP = AtomizedNDD
                            .ref(AtomizedNDD.and(curr_node.APs, curr_device.ports_aps.get(out_port)));
                    if (next_AP.isFalse())
                        continue;
                    if (net.endEdge.containsKey(curr_node.curr.getDeviceName())
                            && net.endEdge.get(curr_node.curr.getDeviceName()).contains(out_port)) {
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
                            // AtomizedNDD.deref(next_AP);
                            continue;
                        }
                        AtomizedNDD.ref(next_AP);
                        queue.push(new TranverseNodeAP(curr_node.source, next_pt, next_AP, curr_node.visited));
                    }
                    AtomizedNDD.deref(next_AP);
                }
            }
            AtomizedNDD.deref(curr_node.APs);
        }
    }
}