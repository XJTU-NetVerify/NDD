package org.ants.jndd.parallel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.ants.jndd.parallel.ParallelMTNDD.*;

@EnabledIfSystemProperty(named="pmtndd.library", matches=".+")
class ParallelMTNDDTest {
    private ParallelNDDConfig config(int workers) {
        return new ParallelNDDConfig(workers, 16L << 20, 64, 2, 1, 4, 3, true, true, true);
    }
    @Test void rationalMixedBackendsAndOwnedRoots() {
        for (int workers : new int[]{1, 6}) try (ParallelMTNDD e = new ParallelMTNDD(config(workers), Domain.RATIONAL,
                new Field(2, Backend.BDD), new Field(3, Backend.BCDD),
                new Field(2, Backend.ZDD), new Field(2, Backend.BITSET))) {
            try (MTNDDNode a = e.literal(0, 0, true); MTNDDNode b = e.literal(1, 1, false);
                 MTNDDNode c = e.literal(2, 0, true); MTNDDNode d = e.literal(3, 1, true);
                 MTNDDNode ab = e.and(a, b); MTNDDNode cd = e.and(c, d);
                 MTNDDNode all = e.and(ab, cd); MTNDDNode weight = e.fraction(1, 3);
                 MTNDDNode f = e.times(all, weight); MTNDDNode retained = f.retain()) {
                f.close(); e.collect();
                assertEquals(32, e.satCount(retained));
                assertEquals("1/3", e.evaluate(retained, 2, 0, 2, 1).toString());
                assertEquals("0/1", e.evaluate(retained, 0, 0, 2, 1).toString());
                try (MTNDDNode sum = e.sumAbstract(retained, 2);
                     MTNDDNode fused = e.multiplySumAbstract(all, weight, 2)) {
                    assertEquals(sum, fused);
                    assertEquals("2/3", e.evaluate(sum, 2, 0, 0, 1).toString());
                }
                assertThrows(IllegalStateException.class, () -> e.satCount(f));
                assertThrows(IllegalStateException.class, () -> e.and(retained, all));
                assertThrows(IllegalStateException.class, () -> e.exist(retained, 0));
            }
            assertTrue(e.stats().collections > 0);
        }
    }
    @Test void batchOneRunAndFailureRecovery() {
        ParallelNDDConfig config = new ParallelNDDConfig(6, 16L << 20, 64, 2, 1, 4, 0, true, true, true);
        try (ParallelMTNDD e = new ParallelMTNDD(config, Domain.RATIONAL, new Field(3, Backend.ZDD));
             MTNDDNode a = e.fraction(1, 2); MTNDDNode b = e.fraction(1, 3); MTNDDNode zero = e.constant(false)) {
            MTNDDNode[] aa = new MTNDDNode[64], bb = new MTNDDNode[64];
            java.util.Arrays.fill(aa, a); java.util.Arrays.fill(bb, b);
            long runs = e.stats().externalRuns;
            MTNDDNode[] results = e.applyBatch(Operation.PLUS, aa, bb);
            assertTrue(e.stats().externalRuns - runs <= 1);
            for (MTNDDNode result : results) { assertEquals("5/6", e.terminalValue(result).toString()); result.close(); }
            bb[63] = zero;
            assertThrows(ArithmeticException.class, () -> e.applyBatch(Operation.DIVIDE, aa, bb));
            e.collect();
            try (MTNDDNode sum = e.reduce(Operation.PLUS, Reduction.BALANCED_TREE, aa)) {
                assertEquals("32/1", e.terminalValue(sum).toString());
            }
            assertThrows(ArithmeticException.class, () -> e.divide(a, zero));
            assertEquals("1/2", e.terminalValue(a).toString());
        }
    }
    @Test void doubleSkippedWideFieldAndBoundaryErrors() {
        try (ParallelMTNDD e = new ParallelMTNDD(config(6), Domain.DOUBLE,
                new Field(32, Backend.BCDD), new Field(2, Backend.BITSET));
             MTNDDNode half = e.number(0.5); MTNDDNode literal = e.literal(1, 0, true);
             MTNDDNode f = e.times(literal, half); MTNDDNode sum = e.sumAbstract(f, 0)) {
            assertEquals(2147483648.0, e.evaluate(sum, 0, 2).value);
            assertEquals(0, e.evaluate(sum, 0, 0).value);
            assertThrows(IllegalArgumentException.class, () -> e.number(Double.NaN));
            assertThrows(IllegalStateException.class, () -> e.fraction(1, 2));
            assertThrows(IllegalArgumentException.class, () -> e.sumAbstract(f, 2));
        }
        try (ParallelMTNDD e = new ParallelMTNDD(config(1), Domain.RATIONAL, new Field(2, Backend.BDD));
             MTNDDNode max = e.fraction(Long.MAX_VALUE, 1); MTNDDNode one = e.fraction(1, 1)) {
            assertThrows(ArithmeticException.class, () -> e.plus(max, one));
            assertEquals(Long.MAX_VALUE, e.terminalValue(max).numerator);
        }
    }
    @Test void denseImportUsesAllBackendsWithoutPerLeafWrappers() {
        for (Domain domain : Domain.values()) try (ParallelMTNDD e = new ParallelMTNDD(config(6), domain,
                new Field(1, Backend.BDD), new Field(1, Backend.BCDD), new Field(1, Backend.ZDD), new Field(1, Backend.BITSET))) {
            double[] real = new double[16]; long[] n = new long[16], d = new long[16];
            for (int i = 0; i < 16; ++i) { real[i] = i / 3.0; n[i] = i; d[i] = 3; }
            try (MTNDDNode imported = domain == Domain.DOUBLE ? e.fromArray(real) : e.fromFractions(n, d)) {
                e.collect(); assertEquals(15, e.satCount(imported));
                for (int i = 0; i < 16; ++i) assertEquals(real[i], e.evaluate(imported, (i >> 3) & 1, (i >> 2) & 1, (i >> 1) & 1, i & 1).value, 1e-12);
                assertThrows(IllegalArgumentException.class, () -> { if (domain == Domain.DOUBLE) e.fromArray(new double[1]); else e.fromFractions(new long[1], new long[]{1}); });
            }
        }
    }
    @Test void lifecycleCrossEngineAndConcurrentCalls() throws Exception {
        ParallelMTNDD first = new ParallelMTNDD(config(6), Domain.RATIONAL, new Field(3, Backend.BCDD));
        MTNDDNode old = first.fraction(1, 2);
        assertThrows(IllegalStateException.class, () -> new ParallelMTNDD(config(1), Domain.RATIONAL, new Field(2, Backend.BDD)));
        if (System.getProperty("pndd.library") != null)
            assertThrows(IllegalStateException.class, () -> new ParallelNDD(config(1), new ParallelNDD.Field(2, ParallelNDD.Backend.BDD)));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Future<?>[] tasks = new Future<?>[6];
            for (int i = 0; i < tasks.length; ++i) tasks[i] = pool.submit(() -> {
                for (int j = 0; j < 100; ++j) try (MTNDDNode copy = old.retain(); MTNDDNode sum = first.plus(copy, old)) {
                    first.collect(); assertEquals("1/1", first.terminalValue(sum).toString());
                }
            });
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); first.close(); }
        old.close(); old.close(); assertTrue(old.isClosed());
        assertThrows(IllegalStateException.class, () -> first.retain(old));
        try (ParallelMTNDD second = new ParallelMTNDD(config(1), Domain.RATIONAL, new Field(2, Backend.BITSET))) {
            assertThrows(IllegalArgumentException.class, () -> second.retain(old));
        }
        if (System.getProperty("pndd.library") != null) try (ParallelNDD booleanEngine = new ParallelNDD(config(1), new ParallelNDD.Field(2, ParallelNDD.Backend.ZDD)); NDDNode yes = booleanEngine.constant(true)) {
            assertEquals(4, booleanEngine.satCount(yes));
            assertThrows(IllegalStateException.class, () -> new ParallelMTNDD(config(1), Domain.RATIONAL, new Field(2, Backend.BDD)));
        }
    }
    @Test void applicationQueriesAndContractionBatches() {
        for (int workers : new int[]{1,6}) for (Backend backend : Backend.values()) {
            int width=backend==Backend.BITSET?7:70;
            ParallelNDDConfig cfg=new ParallelNDDConfig(workers,16L<<20,64,2,1,4,0,true,false,true,true);
            try (ParallelMTNDD e=new ParallelMTNDD(cfg,Domain.RATIONAL,new Field(width,backend),new Field(2,backend));
                 MTNDDNode high=e.literal(0,0,true); MTNDDNode half=e.fraction(1,2);
                 MTNDDNode weighted=e.times(high,half); MTNDDNode negative=e.fraction(-1,3);
                 MTNDDNode partial=e.times(high,negative)) {
                boolean[][] bits={new boolean[width],new boolean[2]};
                assertEquals("0/1",e.evaluateBits(weighted,bits).toString());bits[0][0]=true;
                assertEquals("1/2",e.evaluateBits(weighted,bits).toString());
                assertEquals("0/1",e.maxTerminal(partial).toString());assertEquals("-1/3",e.maxTerminal(negative).toString());
                MTNDDNode[] a={weighted,partial},b={half,half};long runs=e.stats().externalRuns;
                MTNDDNode[] sum=e.sumAbstractBatch(a,1);assertEquals(runs+1,e.stats().externalRuns);
                MTNDDNode[] fused=e.multiplySumAbstractBatch(a,b,1);
                for(int i=0;i<2;++i)try(MTNDDNode scalar=e.sumAbstract(a[i],1);MTNDDNode contraction=e.multiplySumAbstract(a[i],b[i],1)){
                    assertEquals(scalar,sum[i]);assertEquals(contraction,fused[i]);sum[i].close();fused[i].close();
                }
                assertEquals(0,e.sumAbstractBatch(new MTNDDNode[0],1).length);
                assertThrows(IllegalArgumentException.class,()->e.evaluateBits(high,new boolean[][]{new boolean[1]}));
                if(width==70){assertThrows(ArithmeticException.class,()->e.sumAbstractBatch(a,0));e.collect();assertEquals("1/2",e.evaluateBits(weighted,bits).toString());}
            }
        }
    }
    @Test void admittedScalarIdentityAndCacheHitsAvoidRun() {
        try(ParallelMTNDD e=new ParallelMTNDD(new ParallelNDDConfig(6,16L<<20,262144,2,1,4,0,true,true,true),Domain.RATIONAL,new Field(3,Backend.BDD));
            MTNDDNode x=e.literal(0,0,true);MTNDDNode y=e.literal(0,1,true);MTNDDNode one=e.constant(true);
            MTNDDNode half=e.fraction(1,2);MTNDDNode weighted=e.times(x,half)) {
            long before=e.stats().externalRuns;
            try(MTNDDNode identity=e.times(weighted,one)){assertEquals(weighted,identity);assertEquals(before,e.stats().externalRuns);}
            try(MTNDDNode first=e.plus(x,y)){
                before=e.stats().externalRuns;
                try(MTNDDNode cached=e.plus(y,x)){assertEquals(first,cached);assertEquals(before,e.stats().externalRuns);}
            }
            assertThrows(ArithmeticException.class,()->e.divide(x,x));
        }
    }
}
