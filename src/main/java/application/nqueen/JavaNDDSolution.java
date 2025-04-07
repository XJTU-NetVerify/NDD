package application.nqueen;

import org.ants.javandd.*;

public class JavaNDDSolution {
    public static final int NDD_TABLE_SIZE = 100000000;
    static BDDFactory factory;

    private static void build(int i, int j, int n, BDD[][] impBatch) {
        BDD a, b, c, d;
        a = b = c = d = factory.one();

        int k, l;

        /* No one in the same column */
        for (l = 0; l < n; l++) {
            if (l != j) {
                BDD mp = factory.ithVar(i * n + j).imp(factory.nithVar(i * n + l));
                a.andWith(mp);
            }
        }

        /* No one in the same row */
        for (k = 0; k < n; k++) {
            if (k != i) {
                BDD mp = factory.ithVar(i * n + j).imp(factory.nithVar(k * n + j));
                b.andWith(mp);
            }
        }

        /* No one in the same up-right diagonal */
        for (k = 0; k < n; k++) {
            int ll = k - i + j;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    BDD mp = factory.ithVar(i * n + j).imp(factory.nithVar(k * n + ll));
                    c.andWith(mp);
                }
            }
        }

        /* No one in the same down-right diagonal */
        for (k = 0; k < n; k++) {
            int ll = i + j - k;
            if (ll >= 0 && ll < n) {
                if (k != i) {
                    BDD mp = factory.ithVar(i * n + j).imp(factory.nithVar(k * n + ll));
                    d.andWith(mp);
                }
            }
        }

        c.andWith(d);
        b.andWith(c);
        a.andWith(b);
        impBatch[i][j] = a;
    }

    // N is the number of queens, fieldNum is the number of fields in NDD library.
    public static String Solution(int n) {
        // init NDD library
        factory = new NDDFactory(1 + Math.max(1000, (int) (Math.pow(4.4, n - 6)) * 1000), 10000);

        int[] fields = new int[n];
        for (int i = 0; i < n; i++) {
            fields[i] = n;
        }
        ((NDDFactory) factory).setVarNum(fields, NDD_TABLE_SIZE);

        double startTime = System.currentTimeMillis();

        BDD[] orBatch = new BDD[n];
        BDD[][] impBatch = new BDD[n][n];

        for (int i = 0; i < n; i++) {
            BDD condition = factory.zero();
            for (int j = 0; j < n; j++) {
                condition.orWith(factory.ithVar(i * n + j).id());
            }
            orBatch[i] = condition;
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                build(i, j, n, impBatch);
            }
        }

        BDD queen = factory.one();

        for (int i = 0; i < n; i++) {
            queen.andWith(orBatch[i].id());
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                queen.andWith(impBatch[i][j].id());
            }
        }
        double endTime = System.currentTimeMillis();
        // todo: add a cache for satCount
        return "\t" + String.format("" + (endTime - startTime) / 1000, ".3f") + "\t" + queen.satCount();
    }

    public static void main(String[] args) {
        System.out.println(Solution(1));
        System.out.println(Solution(2));
        System.out.println(Solution(3));
        System.out.println(Solution(4));
        System.out.println(Solution(5));
        System.out.println(Solution(6));
        System.out.println(Solution(7));
        System.out.println(Solution(8));
    }
}
