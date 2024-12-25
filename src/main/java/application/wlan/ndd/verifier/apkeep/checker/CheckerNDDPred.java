package application.wlan.ndd.verifier.apkeep.checker;

import application.wlan.ndd.verifier.apkeep.core.NetworkNDDPred;
import application.wlan.ndd.verifier.apkeep.element.FieldNode;
import application.wlan.ndd.verifier.common.PositionTuple;
import ndd.jdd.diagram.NDD;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Stack;

public class CheckerNDDPred {
    static boolean checkCorrectness = false;
    NetworkNDDPred net;
    Stack<TranverseNode> queue;
    public HashSet<String> ans;
    HashMap<PositionTuple, HashMap<PositionTuple, NDD>> reach;

    public CheckerNDDPred(NetworkNDDPred net, boolean test) {
        this.net = net;
        queue = new Stack<TranverseNode>();
        ans = new HashSet<>();
        reach = new HashMap<PositionTuple, HashMap<PositionTuple, NDD>>();
        if (!test) {
            for (String device : net.edge_ports.keySet()) {
                for (String port : net.edge_ports.get(device)) {
                    // if(!device.equals("config1161self"))continue;
                    HashMap<PositionTuple, NDD> subMap = new HashMap<PositionTuple, NDD>();
                    subMap.put(new PositionTuple(device, port), NDD.getTrue());
                    reach.put(new PositionTuple(device, port), subMap);
                    queue.add(new TranverseNode(new PositionTuple(device, port), NDD.getTrue()));
                }
            }
        }
        else
        {

        }
    }

    public void CheckPerEdge() throws IOException
    {
        FileWriter fw = new FileWriter("/home/zcli/lzc/Field-Decision-Network/SingleLayerNDD/src/main/java/org/ants/output/"+net.name+"/perEdgePred", false);
        PrintWriter pw = new PrintWriter(fw);
        for(String device : net.edge_ports.keySet())
        {
            for (String port : net.edge_ports.get(device))
            {
                // net.bdd_engine.getBDD().gc();
                // net.bdd_engine.getBDD().op_cache.clear_cache();
                Long t = 0L;
                queue.add(new TranverseNode(new PositionTuple(device, port), NDD.getTrue()));
                Long t0 = System.nanoTime();
                Long ret = PropertyCheck();
                Long t1 = System.nanoTime();
                t = t1 - t0;
                pw.println(device+" "+port+" "+t/1000000.0+" "+ret/1000000.0);
                pw.flush();
            }
        }
    }

    public Long PropertyCheck() throws IOException {
        // BDD.createCount=0;
        // int count = 0;
        Long time = 0L;
        while (!queue.isEmpty()) {
            // count++;
            TranverseNode curr_node = queue.pop();
            FieldNode curr_device = net.FieldNodes.get(curr_node.curr.getDeviceName());
            for (String out_port : curr_device.ports) {
                if (out_port.equalsIgnoreCase("deny") || out_port.equalsIgnoreCase("default")
                        || out_port.equalsIgnoreCase(curr_node.curr.getPortName()))
                    continue;
                Long t0 = System.nanoTime();
                NDD next_AP = NDD.ref(NDD.and(curr_node.APs, curr_device.ports_pred.get(out_port)));
                Long t1 = System.nanoTime();
                time += t1 - t0;
                if (next_AP.isFalse())
                    continue;
                if (net.edge_ports.containsKey(curr_node.curr.getDeviceName())
                        && net.edge_ports.get(curr_node.curr.getDeviceName()).contains(out_port)) {
                    if (!ans.contains(curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName())) {
                        ans.add(curr_node.source.getDeviceName() + "->" + curr_node.curr.getDeviceName());
                    }
                    if (checkCorrectness) {
                        if (!reach.containsKey(curr_node.source))
                            reach.put(curr_node.source, new HashMap<PositionTuple, NDD>());
                        NDD subReach = reach.get(curr_node.source)
                                .get(new PositionTuple(curr_node.curr.getDeviceName(), out_port));
                        if (subReach == null) {
                            subReach = NDD.getFalse();
                        }
                        NDD t = subReach;
                        NDD new_reach = NDD.ref(NDD.or(subReach, next_AP));
                        NDD.deref(t);
                        reach.get(curr_node.source).put(new PositionTuple(curr_node.curr.getDeviceName(), out_port),
                                new_reach);
                    }
                    NDD.deref(next_AP);
                    continue;
                }
                for (PositionTuple next_pt : net.topology.get(new PositionTuple(curr_device.name, out_port))) {
                    if (curr_node.visited.contains(next_pt.getDeviceName())) {
                        // System.out.println("Loop detected !");
                        // NDD.table.deref(next_AP);
                        continue;
                    }
                    NDD.ref(next_AP);
                    queue.push(new TranverseNode(curr_node.source, next_pt, next_AP, curr_node.visited));
                }
                NDD.deref(next_AP);
            }
            NDD.deref(curr_node.APs);
        }
        // System.out.println("BDD create count:"+BDD.createCount);
        // PrintReach();
        // System.out.println(count);
        // System.out.println(time / 1000000000.0);
        return time;
    }

    private void PrintReach() throws IOException {
        FileWriter fw = new FileWriter(
                "/home/zcli/lzc/Field-Decision-Network/SingleLayerNDD/src/main/java/org/ants/output/purdue/reachPred",
                false);
        PrintWriter pw = new PrintWriter(fw);
        for (String str : ans) {
            pw.println(str);
            pw.flush();
        }
    }

    // public void Output_Result(String path) throws IOException {
    // FileWriter fw = new FileWriter(path, false);
    // PrintWriter pw = new PrintWriter(fw);
    // for (PositionTuple source : reach.keySet()) {
    // for (PositionTuple dest : reach.get(source).keySet()) {
    // pw.println(source + " " + dest + " "
    // +
    // net.bdd_engine.getBDD().satCount(FieldAPSet.toBDD(reach.get(source).get(dest)))
    // / 4722366482869645213696.0);
    // pw.flush();
    // }
    // }
    // }

    // public void Output_Result_ACL(String path) throws IOException {
    // FileWriter fw = new FileWriter(path, false);
    // PrintWriter pw = new PrintWriter(fw);
    // for (PositionTuple source : reach.keySet()) {
    // for (PositionTuple dest : reach.get(source).keySet()) {
    // pw.println(source + " " + dest + " "
    // +
    // net.bdd_engine.getBDD().satCount(FieldAPSet.toBDD(reach.get(source).get(dest)))+
    // " " + FieldAPSet.toBDD(reach.get(source).get(dest)));
    // pw.flush();
    // }
    // }
    // }

    // public void OutputReachBDD() throws IOException
    // {
    // for(PositionTuple src : reach.keySet())
    // {
    // for(PositionTuple dst : reach.get(src).keySet())
    // {
    // if(EvalDataplaneVerifier.incrementACL)
    // {
    // BDDIO.save(net.bdd_engine.getBDD(),
    // FieldAPSet.toBDD(reach.get(src).get(dst)),
    // "/home/zcli/lzc/Field-Decision-Network/apkatra-main/output/single/"+"increment"+"/correctness/NDD/"+src.getDeviceName()+"_"+dst.getDeviceName());
    // }
    // else
    // {
    // BDDIO.save(net.bdd_engine.getBDD(),
    // FieldAPSet.toBDD(reach.get(src).get(dst)),
    // "/home/zcli/lzc/Field-Decision-Network/apkatra-main/output/single/"+net.name+"/correctness/NDD/"+src.getDeviceName()+"_"+dst.getDeviceName());
    // }
    // }
    // }
    // }

    // public void printReachSize()
    // {
    // int sum=0;
    // for(PositionTuple src : reach.keySet())
    // {
    // sum += reach.get(src).size();
    // }
    // System.out.println("reach size:"+sum);
    // }
}