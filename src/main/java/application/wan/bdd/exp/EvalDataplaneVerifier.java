package application.wan.bdd.exp;

// import org.ants.main.DNA;
import application.wan.bdd.verifier.DPVerifier;
import application.wan.bdd.verifier.DPVerifierNAT;
import application.wan.bdd.verifier.DPVerifier_Incre;
import application.wan.bdd.verifier.apkeep.utils.UtilityTools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EvalDataplaneVerifier {
    private static final String currentPath = System.getProperty("user.dir");
    private static boolean incrementACL = false;
    public static boolean runNAT = false;
    public static boolean divideACL = true;
    public static boolean CHECK_CORRECTNESS = false;
    public static boolean DEBUG_MODEL = false;

    public static void runFattreeUpdate(String configPath, String ACL_Path) throws IOException {
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
            if (count != 1)
                continue;
            if (!folder.isDirectory())
                continue;
            System.out.println(folder.getName());
            String updateFolder = folder.getAbsolutePath();

            Map<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>> ACL_json = new HashMap<String, Map<String, List<Map<String, Map<String, List<Map<String, String>>>>>>>();
            UtilityTools.get_ACL(ACL_Path + "/acls/usage", ACL_json);
            if (incrementACL) {
                divideACL = true;
                DPVerifier_Incre dpv = new DPVerifier_Incre(testcase, topo, edge_ports, ACL_json);
                // update base rules
                String baseFile = Paths.get(updateFolder, "change_base").toString();
                ArrayList<String> forwarding_rules = UtilityTools.readFile(baseFile);
                ArrayList<String> acl_rules = new ArrayList<String>();
                acl_rules = UtilityTools.readFile(ACL_Path + "/acl_rule");
                dpv.run(forwarding_rules, acl_rules);
            } else if (runNAT) {
                divideACL = true;
                // divideACL = false;
                // ACL_json = new HashMap<>();
                System.out.println(num1 + " " + num2);
                ArrayList<String> NAT_rules = UtilityTools.readFile(configPath + "/NAT/nat" + num1 + "_" + num2);
                DPVerifierNAT dpv = new DPVerifierNAT(testcase, topo, edge_ports, ACL_json, NAT_rules);
                // update base rules
                String baseFile = Paths.get(updateFolder, "change_base").toString();
                ArrayList<String> forwarding_rules = UtilityTools.readFile(baseFile);
                ArrayList<String> acl_rules = new ArrayList<String>();
                acl_rules = UtilityTools.readFile(ACL_Path + "/acl_rule");
                dpv.run(forwarding_rules, acl_rules, NAT_rules);
                System.out.println();
            } else {
                divideACL = true;
                DPVerifier dpv = new DPVerifier(testcase, topo, edge_ports, ACL_json);
                // update base rules
                String baseFile = Paths.get(updateFolder, "change_base").toString();
                ArrayList<String> forwarding_rules = UtilityTools.readFile(baseFile);
                ArrayList<String> acl_rules = new ArrayList<String>();
                acl_rules = UtilityTools.readFile(ACL_Path + "/acl_rule");

                Runtime r = Runtime.getRuntime();
                r.gc();
                r.gc();
                long m1 = r.totalMemory() - r.freeMemory();

                dpv.run(forwarding_rules, acl_rules);
                dpv.dpm_time = 0;
                dpv.dpv_time = 0;

                Runtime r1 = Runtime.getRuntime();
                r1.gc();
                r1.gc();
                long m2 = r1.totalMemory() - r1.freeMemory();

                System.out.println("memory:" + (m2 - m1) / (1024 * 1024) + "MB");
                System.out.flush();
            }
        }
    }

    public static int num1 = 0;
    public static int num2 = 0;

    public static void main(String[] args) throws IOException {
        UtilityTools.split_str = "_";
        String configPath = "/data/zcli-data/network-decision-diagram/datasets/wan/purdue/pd";
        String ACL_Path = "/data/zcli-data/network-decision-diagram/datasets/wan/purdue/purdue";

        // UtilityTools.split_str = "-";
        // String configPath = "datasets\\wan\\stanford\\st";
        // String ACL_Path = "datasets\\wan\\stanford\\stanford";

        // UtilityTools.split_str = "_";
        // String configPath = "datasets\\wan\\campus\\cam";
        // String ACL_Path = "datasets\\wan\\campus\\campus";

        // UtilityTools.split_str = "_";
        // String configPath = "datasets\\wan\\internet2";
        // String ACL_Path = "datasets\\wan\\internet2";

        if (runNAT) {
            num1 = Integer.parseInt(args[0]);
            num2 = Integer.parseInt(args[1]);
        }

        runFattreeUpdate(configPath, ACL_Path);
    }
}
