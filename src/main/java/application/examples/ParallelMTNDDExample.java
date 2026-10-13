package application.examples;

import org.ants.jndd.parallel.*;
import static org.ants.jndd.parallel.ParallelMTNDD.*;

/** Run with -Dpmtndd.library=/absolute/path/to/libpmtnddjni.so. */
public final class ParallelMTNDDExample {
    public static void main(String[] args) {
        try (ParallelMTNDD e = new ParallelMTNDD(new ParallelNDDConfig(6), Domain.RATIONAL,
                new Field(2, Backend.BCDD), new Field(2, Backend.ZDD));
             MTNDDNode predicate = e.literal(0, 0, true);
             MTNDDNode weight = e.fraction(1, 3);
             MTNDDNode f = e.times(predicate, weight);
             MTNDDNode sum = e.sumAbstract(f, 0);
             MTNDDNode fused = e.multiplySumAbstract(predicate, weight, 0)) {
            System.out.println(e.terminalValue(sum));
            System.out.println(sum.equals(fused));
            System.out.println(e.satCount(f));
            e.collect();
        }
    }
}
