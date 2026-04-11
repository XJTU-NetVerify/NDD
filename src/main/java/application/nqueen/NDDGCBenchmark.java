package application.nqueen;

import org.ants.jndd.diagram.NDD;

public class NDDGCBenchmark{

    private static final int N = 11;
    private static final int SMALL_TABLE_SIZE = 100000000; 

    public static void main(String[] args) {
        System.out.println("Constraint: Table Size = " + SMALL_TABLE_SIZE );
        
        NDD.initNDD(SMALL_TABLE_SIZE, 5000000, 2000000);
       
        System.gc();
        try { Thread.sleep(1000); } catch (Exception e) {}
        long baselineMem = getUsedMemory();
        System.out.printf("Baseline Memory: %.2f MB\n\n", baselineMem / 1024.0 / 1024.0);

        long start = System.currentTimeMillis();
        
        runStepByStepWithMonitoring(N);

        long end = System.currentTimeMillis();
        
        System.gc();
        long finalMem = getUsedMemory();
        System.out.printf("\nFinal Memory: %.2f MB \n", finalMem / 1024.0 / 1024.0);
        System.out.println("Total Time: " + (end - start) + " ms");
    }

    private static void runStepByStepWithMonitoring(int n) {
        declareFields(n);
        
        int[] orBatch = new int[n];
        
        System.out.println("Phase 1: Building Existence Constraints (Low GC Pressure)...");
        for (int i = 0; i < n; i++) {
            int condition = NDD.getFalse();
            for (int j = 0; j < n; j++) {
                condition = NDD.orTo(condition, NDD.getVar(i, j));
            }
            orBatch[i] = condition;
           
            if (i % 2 == 0) printMemorySnap("Row " + i);
        }

        System.out.println("\nPhase 2: Building Conflict Constraints (HIGH GC Pressure)...");
        
        int queen = NDD.getTrue();
        
        for(int i=0; i<n; i++) {
            queen = NDD.andTo(queen, orBatch[i]);
            NDD.deref(orBatch[i]);
        }
        int[][] impBatch = new int[n][n];
        long peakMemory = 0;

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                build(i, j, n, impBatch);
                
                queen = NDD.andTo(queen, impBatch[i][j]);
                NDD.deref(impBatch[i][j]);
                
                if ((i * n + j) % 20 == 0) {
                    long currentMem = getUsedMemory();
                    if (currentMem > peakMemory) peakMemory = currentMem;
                    printMemorySnap("Cell " + i + "," + j);
                }
            }
        }
        
        System.out.printf("\nPeak Memory during High Pressure: %.2f MB\n", peakMemory / 1024.0 / 1024.0);
    }

    private static long getUsedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static void printMemorySnap(String label) {
        long mem = getUsedMemory();
        long nodeCount = NDD.getNodeCount(); 
        System.out.printf("[%s] Mem: %6.2f MB | Active Nodes: %6d\n", 
            label, mem / 1024.0 / 1024.0, nodeCount);
    }

    private static void declareFields(int n) {
        for (int i = 0; i < n; i++) NDD.declareField(n);
    }

    private static void build(int i, int j, int n, int[][] impBatch) {
        int a, b, c, d;
        a = b = c = d = NDD.getTrue();
        int k, l;
        for (l = 0; l < n; l++) {
            if (l != j) {
                int mp = NDD.ref(NDD.imp(NDD.getVar(i, j), NDD.getNotVar(i, l)));
                a = NDD.andTo(a, mp); NDD.deref(mp);
            }
        }
        for (k = 0; k < n; k++) {
            if (k != i) {
                int mp = NDD.ref(NDD.imp(NDD.getVar(i, j), NDD.getNotVar(k, j)));
                b = NDD.andTo(b, mp); NDD.deref(mp);
            }
        }
        for (k = 0; k < n; k++) {
            int ll = k - i + j;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    int mp = NDD.ref(NDD.imp(NDD.getVar(i, j), NDD.getNotVar(k, ll)));
                    c = NDD.andTo(c, mp); NDD.deref(mp);
                }
            }
        }
        for (k = 0; k < n; k++) {
            int ll = i + j - k;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    int mp = NDD.ref(NDD.imp(NDD.getVar(i, j), NDD.getNotVar(k, ll)));
                    d = NDD.andTo(d, mp); NDD.deref(mp);
                }
            }
        }
        c = NDD.andTo(c, d); NDD.deref(d);
        b = NDD.andTo(b, c); NDD.deref(c);
        a = NDD.andTo(a, b); NDD.deref(b);
        impBatch[i][j] = a;
    }
}