package application.wan.ndd.verifier.apkeep.checker;

import application.wan.ndd.verifier.apkeep.core.NetworkNDDAP;
import application.wan.ndd.verifier.apkeep.utils.UtilityTools;
import application.wan.ndd.verifier.common.PositionTuple;
import org.ants.jndd.diagram.AtomizedNDD;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class TranverseNodeAP {
    public PositionTuple source;
    public PositionTuple curr;
    public AtomizedNDD APs;
    HashSet<String> visited;
    public static NetworkNDDAP net;

    public TranverseNodeAP() {

    }

    public TranverseNodeAP(PositionTuple source, AtomizedNDD APs) {
        this.source = source;
        this.curr = source;
        this.APs = APs;
        visited = new HashSet<String>();
        if (curr.getDeviceName().split(UtilityTools.split_str).length == 1) {
            visited.add(curr.getDeviceName());
        }
    }

    public TranverseNodeAP(PositionTuple source, PositionTuple curr, AtomizedNDD APs, HashSet<String> visited) {
        this.source = source;
        this.curr = curr;
        this.APs = APs;
        this.visited = new HashSet<String>(visited);
        if (curr.getDeviceName().split(UtilityTools.split_str).length == 1) {
            this.visited.add(curr.getDeviceName());
        }
    }
}