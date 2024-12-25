package application.wlan.ndd.exp;

import application.wlan.ndd.verifier.DPVerifierNDDPred;
import application.wlan.ndd.verifier.apkeep.utils.UtilityTools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EvalDataplaneVerifierNDDPred {
    private static final String currentPath = System.getProperty("user.dir");

    public static void runFattreeUpdate(String configPath, String ACL_Usage_Path, String ACL_Rule_Path) throws IOException {
        // long t0 = System.nanoTime();
        configPath = Paths.get(configPath).toRealPath().toString();
        String testcase = Paths.get(configPath).toRealPath().getFileName().toString();
        String inPath = Paths.get(configPath, "dpv").toString();
        String outPath = Paths.get(currentPath, "results", testcase).toString();
        File dir = Paths.get(outPath).toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String topoFile = Paths.get(configPath, "layer1Topology").toString();
        String edgePortFile = Paths.get(configPath, "edgePorts").toString();

        ArrayList<String> topo = UtilityTools.readFile(topoFile);
        ArrayList<String> edge_ports = UtilityTools.readFile(edgePortFile);
        File[] inFiles = Paths.get(inPath).toFile().listFiles();
        int count = 0;
        for (File folder : inFiles) {
            count++;
            if (!folder.isDirectory())
                continue;
            // System.out.println(folder.getName());
            String updateFolder = folder.getAbsolutePath();
            Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> ACL_json = new HashMap<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>>();
            Map<String, Map<String, List<Map<String, Map<String, List<String>>>>>> ACL_json1 = new HashMap<String, Map<String, List<Map<String, Map<String, List<String>>>>>>();
            UtilityTools.get_ACL(ACL_Usage_Path, ACL_json);

            DPVerifierNDDPred dpv = new DPVerifierNDDPred(testcase, topo, edge_ports, ACL_json);
            // update base rules
            String baseFile = Paths.get(updateFolder, "change_base").toString();
            ArrayList<String> forwarding_rules = UtilityTools.readFile(baseFile);
            ArrayList<String> acl_rules = new ArrayList<String>();
            acl_rules = UtilityTools.readFile(ACL_Rule_Path);
            dpv.run(forwarding_rules, acl_rules);
        }
    }

    static String aclNum = "1";

    public static void main(String[] args) throws IOException {
        //Ready
        // UtilityTools.split_str = "_";
        // String configPath = "/data/zcli-data/data";
        // String ACL_Usage_Path = "/data/zcli-data/Dataset/acls/usage";
        // String ACL_Rule_Path = "/data/zcli-data/Dataset/acl_rule_"+aclNum;
        // aclNum = args[0];
        // System.out.println(aclNum);

        //Ready
        UtilityTools.split_str = "_";
        String configPath = "/data/zcli-data/pd";
        String ACL_Usage_Path = "/data/zcli-data/purdue/acls/usage";
        String ACL_Rule_Path = "/data/zcli-data/purdue/acl_rule";

        // Not Ready
        // UtilityTools.split_str = "-";
        // String configPath = "/data/zcli-data/st";
        // String ACL_Usage_Path = "/data/zcli-data/stanford/acls/usage";
        // String ACL_Rule_Path = "/data/zcli-data/stanford/acl_rule";

        //Ready
        // UtilityTools.split_str = "_";
        // String configPath = "/data/zcli-data/cam";
        // String ACL_Usage_Path = "/data/zcli-data/campus/acls/usage";
        // String ACL_Rule_Path = "/data/zcli-data/campus/acl_rule";

        //Ready
        // UtilityTools.split_str = "_";
        // String configPath = "/home/zcli/lzc/APKeepBatch/data/internet2";
        // String ACL_Usage_Path = "/home/zcli/lzc/APKeepBatch/data/internet2/acls/usage";
        // String ACL_Rule_Path = "/home/zcli/lzc/APKeepBatch/data/internet2/acl_rule";

        runFattreeUpdate(configPath, ACL_Usage_Path, ACL_Rule_Path);
    }
}
