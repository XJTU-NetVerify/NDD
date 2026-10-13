package org.ants.mtpndd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class MTPNDDFractionTest {

    private static String resolveLibraryName() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "mtpnddjni.dll";
        if (os.contains("mac") || os.contains("darwin")) return "libmtpnddjni.dylib";
        return "libmtpnddjni.so";
    }

    @BeforeAll
    static void setupLibraryPath() {
        String explicit = System.getProperty("org.ants.mtpndd.library.path");
        if (explicit != null && Files.exists(Paths.get(explicit))) {
            MTPNDDEngine.isInitialized();
            return;
        }
        Path legacy = Paths.get("build", resolveLibraryName()).toAbsolutePath();
        Path toplevel = Paths.get("..", "build", "jni", resolveLibraryName()).toAbsolutePath();
        Path candidate = Files.exists(legacy) ? legacy : toplevel;
        if (!Files.exists(candidate)) {
            throw new IllegalStateException("JNI library not found at " + legacy + " or " + toplevel);
        }
        System.setProperty("org.ants.mtpndd.library.path", candidate.toString());
        MTPNDDEngine.isInitialized();
    }

    @BeforeEach
    void initialise() {
        if (MTPNDDEngine.isInitialized()) {
            MTPNDDEngine.shutdown();
        }
        MTPNDDConfig config = MTPNDDConfig.builder()
                .workers(1)
                .laceDequeSize(1 << 14)
                .bddNodeTableSize(1 << 16)
                .mtpnddNodeTableSize(1 << 14)
                .operationCacheSize(1 << 12)
                .edgeBucketCount(16)
                .nodetableBucketCount(1 << 10)
                .nodeSlabCapacity(1024)
                .edgeEntrySlabCapacity(2048)
                .nodetableEntrySlabCapacity(1024)
                .edgeMapSlabCapacity(1024)
                .build();
        MTPNDDEngine.init(config);
        MTPNDDEngine.declareField(2);
        MTPNDDEngine.generateFields();
    }

    @AfterEach
    void shutdown() {
        if (MTPNDDEngine.isInitialized()) {
            MTPNDDEngine.shutdown();
        }
    }

    @Test
    void fractionCanonicalization() {
        MTPNDD a = MTPNDDEngine.makeFraction(2, 4);
        MTPNDD b = MTPNDDEngine.makeFraction(1, 2);
        assertEquals(a, b);
        assertEquals(1, MTPNDDEngine.getNumer(a));
        assertEquals(2, MTPNDDEngine.getDenom(a));
        assertTrue(MTPNDDEngine.isFractionLeaf(a));
        assertFalse(MTPNDDEngine.isDoubleLeaf(a));
        assertTrue(MTPNDDEngine.isLeaf(a));

        // 0/k → MTPNDD_FALSE
        MTPNDD zero = MTPNDDEngine.makeFraction(0, 5);
        assertEquals(MTPNDD.getFalse(), zero);
        // k/k → MTPNDD_TRUE
        MTPNDD one = MTPNDDEngine.makeFraction(3, 3);
        assertEquals(MTPNDD.getTrue(), one);
    }

    @Test
    void fractionArithmetic() {
        MTPNDD a = MTPNDDEngine.makeFraction(1, 3);
        MTPNDD b = MTPNDDEngine.makeFraction(1, 6);
        MTPNDD sum = MTPNDDEngine.plus(a, b);
        assertEquals(1, MTPNDDEngine.getNumer(sum));
        assertEquals(2, MTPNDDEngine.getDenom(sum));

        MTPNDD prod = MTPNDDEngine.times(a, b);
        assertEquals(1, MTPNDDEngine.getNumer(prod));
        assertEquals(18, MTPNDDEngine.getDenom(prod));

        MTPNDD quot = MTPNDDEngine.divide(a, b);
        assertEquals(2, MTPNDDEngine.getNumer(quot));
        assertEquals(1, MTPNDDEngine.getDenom(quot));

        // a - a = 0
        MTPNDD diff = MTPNDDEngine.minus(a, a);
        assertEquals(MTPNDD.getFalse(), diff);
    }

    @Test
    void doubleLeaves() {
        MTPNDD a = MTPNDDEngine.makeDouble(0.5);
        MTPNDD b = MTPNDDEngine.makeDouble(0.25);
        assertTrue(MTPNDDEngine.isDoubleLeaf(a));
        assertEquals(0.5, MTPNDDEngine.getDoubleLeaf(a));

        MTPNDD sum = MTPNDDEngine.plus(a, b);
        assertTrue(MTPNDDEngine.isDoubleLeaf(sum));
        assertEquals(0.75, MTPNDDEngine.getDoubleLeaf(sum));

        // Mixing types → exception
        MTPNDD frac = MTPNDDEngine.makeFraction(1, 2);
        assertThrows(MTPNDDException.class, () -> MTPNDDEngine.plus(a, frac));
    }

    @Test
    void leafCount() {
        MTPNDD a = MTPNDDEngine.makeFraction(1, 2);
        assertEquals(1L, MTPNDDEngine.leafCount(a));
        MTPNDD b = MTPNDDEngine.makeFraction(1, 3);
        MTPNDD sum = MTPNDDEngine.plus(a, b);  // leaf 5/6
        assertEquals(1L, MTPNDDEngine.leafCount(sum));
    }

    @Test
    void leafGc() {
        // Create a handful of loose leaves that are not referenced.
        for (int i = 1; i <= 20; i++) {
            MTPNDDEngine.makeFraction(i, 97);
        }
        long reclaimed = MTPNDDEngine.leafGc();
        // At least the 20 loose leaves we created should be reclaimed.
        assertTrue(reclaimed >= 20L, "expected >= 20 reclaimed, got " + reclaimed);
    }

    @Test
    void abstractPlusValidatePassesOnLeaf() {
        // A leaf is trivially valid: no internal nodes, no labels to check.
        MTPNDD leaf = MTPNDDEngine.makeFraction(3, 7);
        assertTrue(MTPNDDEngine.abstractPlusValidate(leaf));
    }

    @Test void booleanAndArithmeticCachesAreIsolated() {
        int field = 1; // setup already generated the two-bit field
        MTPNDD a = MTPNDD.getVar(field, 0), b = MTPNDD.getVar(field, 1);
        MTPNDD union = a.or(b).ref();
        MTPNDD sum = MTPNDDEngine.plus(a, b).ref();
        try {
            assertEquals(3, MTPNDDEngine.getNumer(MTPNDDEngine.abstractPlus(union, field)));
            assertEquals(4, MTPNDDEngine.getNumer(MTPNDDEngine.abstractPlus(sum, field)));
            assertEquals(union, a.or(b));
        } finally { sum.deref(); union.deref(); }
    }
    @Test void doubleSumOutUsesDoubleWeightsIncludingSkippedFields() {
        MTPNDDEngine.shutdown();
        MTPNDDEngine.init(MTPNDDConfig.builder().workers(1).bddNodeTableSize(1 << 16)
                .mtpnddNodeTableSize(1 << 14).operationCacheSize(1 << 12).build());
        int skip = MTPNDDEngine.declareField(1), wide = MTPNDDEngine.declareField(32);
        MTPNDDEngine.generateFields();
        MTPNDD half = MTPNDDEngine.makeDouble(0.5).ref();
        try {
            assertEquals(1.0, MTPNDDEngine.getDoubleLeaf(MTPNDDEngine.abstractPlus(half, skip)));
            assertEquals(Math.scalb(0.5, 32), MTPNDDEngine.getDoubleLeaf(MTPNDDEngine.abstractPlus(half, wide)));
            assertEquals(MTPNDD.getFalse(), MTPNDDEngine.abstractPlus(MTPNDD.getFalse(), wide));
            MTPNDD pattern = MTPNDDEngine.times(MTPNDD.getVar(wide, 0), half).ref();
            try {
                assertEquals(Math.scalb(0.5, 31), MTPNDDEngine.getDoubleLeaf(MTPNDDEngine.abstractPlus(pattern, wide)));
                MTPNDD scaled = MTPNDDEngine.abstractPlus(pattern, skip).ref();
                try { assertEquals(Math.scalb(1.0, 31), MTPNDDEngine.getDoubleLeaf(MTPNDDEngine.abstractPlus(scaled, wide))); }
                finally { scaled.deref(); }
            } finally { pattern.deref(); }
        } finally { half.deref(); }
    }
    @Test void oldEngineNodesAreRejectedBeforeNativeDereference() {
        MTPNDD old = MTPNDDEngine.makeFraction(2, 3).ref();
        assertThrows(IllegalStateException.class, () -> MTPNDDEngine.init(MTPNDDConfig.builder().workers(1).build()));
        MTPNDDEngine.shutdown();
        assertThrows(IllegalStateException.class, () -> MTPNDDEngine.getNumer(old));
        MTPNDDEngine.init(MTPNDDConfig.builder().workers(1).build());
        assertThrows(IllegalStateException.class, () -> MTPNDDEngine.ref(old));
        assertThrows(IllegalStateException.class, () -> MTPNDD.andBatch(new MTPNDD[]{old}, new MTPNDD[]{MTPNDD.getTrue()}));
    }
}
