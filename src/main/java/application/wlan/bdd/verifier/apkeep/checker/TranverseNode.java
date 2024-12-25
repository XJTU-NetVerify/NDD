package application.wlan.bdd.verifier.apkeep.checker;
import java.util.HashSet;

import application.wlan.bdd.verifier.apkeep.core.Network;
import application.wlan.bdd.verifier.apkeep.utils.UtilityTools;
import application.wlan.bdd.verifier.common.PositionTuple;

public class TranverseNode{
    public static Network net;
    public PositionTuple source;
    public PositionTuple curr;
    public HashSet<Integer> fw_aps;
    public HashSet<Integer> acl_aps;
    HashSet<String> visited;
    public TranverseNode(PositionTuple source, HashSet<Integer> fw_aps, HashSet<Integer> acl_aps)
    {
        this.source = source;
        this.curr = source;
        this.fw_aps = new HashSet<Integer>(fw_aps);
        this.acl_aps = new HashSet<Integer>(acl_aps);
        visited = new HashSet<String>();
        if(curr.getDeviceName().split(UtilityTools.split_str).length == 1)
        {
            visited.add(curr.getDeviceName());
        }
    }
    public TranverseNode(PositionTuple source, PositionTuple curr, HashSet<Integer> fw_aps, HashSet<Integer> acl_aps, HashSet<String> visited)
    {
        this.source = source;
        this.curr = curr;
        this.fw_aps = new HashSet<Integer>(fw_aps);
        this.acl_aps = new HashSet<Integer>(acl_aps);
        this.visited = new HashSet<String>(visited);
        if(curr.getDeviceName().split(UtilityTools.split_str).length == 1)
        {
            this.visited.add(curr.getDeviceName());
        }
    }
}