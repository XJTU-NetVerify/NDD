package application.wlan.ndd.verifier.apkeep.checker;

import java.util.HashSet;

import application.wlan.ndd.verifier.apkeep.core.NetworkNDDPred;
import application.wlan.ndd.verifier.apkeep.utils.UtilityTools;
import application.wlan.ndd.verifier.common.PositionTuple;
import javafx.util.*;
import org.ants.jndd.diagram.NDD;

public class TranverseNode {
    public PositionTuple source;
    public PositionTuple curr;
    public NDD APs;
    HashSet<String> visited;
    public static NetworkNDDPred net;
    public TranverseNode()
    {

    }
    public TranverseNode(PositionTuple source, NDD APs)
    {
        this.source = source;
        this.curr = source;
        this.APs = APs;
        visited = new HashSet<String>();
        if(curr.getDeviceName().split(UtilityTools.split_str).length == 1)
        {
            visited.add(curr.getDeviceName());
        }
    }
    public TranverseNode(PositionTuple source, PositionTuple curr, NDD APs, HashSet<String> visited)
    {
        this.source = source;
        this.curr = curr;
        this.APs = APs;
        this.visited = new HashSet<String>(visited);
        if(curr.getDeviceName().split(UtilityTools.split_str).length == 1)
        {
            this.visited.add(curr.getDeviceName());
        }
    }
}