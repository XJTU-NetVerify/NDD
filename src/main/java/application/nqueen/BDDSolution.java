package application.nqueen;

import jdd.bdd.BDD;
import ndd.jdd.diagram.NDD;

public class BDDSolution {
    private static BDD bddEngine;
    private static final int BDD_FALSE = 0;
    private static final int BDD_TRUE = 1;
    private static int[][] vars;
    private static int[][] notVars;

    private static void declareVariables(int n) {
        vars = new int[n][n];
        notVars = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int var = bddEngine.createVar();
                vars[i][j] = var;
                notVars[i][j] = bddEngine.ref(bddEngine.not(var));
            }
        }
    }

    private static void build(int i, int j, int n, int[][] impBatch) {
        int a, b, c, d;
        a = b = c = d = BDD_TRUE;

        int k, l;

        /* No one in the same column */
        for (l = 0; l < n; l++) {
            if (l != j) {
                int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[i][l]));
                a = bddEngine.andTo(a, mp);
                bddEngine.deref(mp);
            }
        }

        /* No one in the same row */
        for (k = 0; k < n; k++) {
            if (k != i) {
                int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[k][j]));
                b = bddEngine.andTo(b, mp);
                bddEngine.deref(mp);
            }
        }

        /* No one in the same up-right diagonal */
        for (k = 0; k < n; k++) {
            int ll = k - i + j;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[k][ll]));
                    c = bddEngine.andTo(c, mp);
                    bddEngine.deref(mp);
                }
            }
        }

        /* No one in the same down-right diagonal */
        for (k = 0; k < n; k++) {
            int ll = i + j - k;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    int mp = bddEngine.ref(bddEngine.imp(vars[i][j], notVars[k][ll]));
                    d = bddEngine.andTo(d, mp);
                    bddEngine.deref(mp);
                }
            }
        }

        c = bddEngine.andTo(c, d);
        b = bddEngine.andTo(b, c);
        a = bddEngine.andTo(a, b);
        bddEngine.deref(d);
        impBatch[i][j] = a;
    }

    public static String Solution(int n) {
        bddEngine = new BDD(1 + Math.max(1000, (int) (Math.pow(4.4, n - 6)) * 1000), 10000);

        double startTime = System.currentTimeMillis();

        declareVariables(n);

        int[] orBatch = new int[n];
        int[][] impBatch = new int[n][n];

        for (int i = 0; i < n; i++) {
            int condition = BDD_FALSE;
            for (int j = 0; j < n; j++) {
                condition = bddEngine.orTo(condition, vars[i][j]);
            }
            orBatch[i] = condition;
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                build(i, j, n, impBatch);
            }
        }

        int queen = BDD_TRUE;

        for (int i = 0; i < n; i++) {
            queen = bddEngine.andTo(queen, orBatch[i]);
            bddEngine.deref(orBatch[i]);
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                queen = bddEngine.andTo(queen, impBatch[i][j]);
                bddEngine.deref(impBatch[i][j]);
            }
        }

        double endTime = System.currentTimeMillis();
        return "\t" + String.format("" + (endTime - startTime) / 1000, ".3f") + "\t" + bddEngine.satCount(queen);
    }

    public static void main(String[] args) {
        Solution(1);
        Solution(2);
        Solution(3);
        Solution(4);
        Solution(5);
        Solution(6);
    }
}
