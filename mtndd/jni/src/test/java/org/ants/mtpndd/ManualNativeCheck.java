package org.ants.mtpndd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Minimal smoke test that exercises the JNI bindings without relying on external test frameworks.
 */
public final class ManualNativeCheck {

    private ManualNativeCheck() {}

    private static String resolveLibraryName() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "mtpnddjni.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "libmtpnddjni.dylib";
        }
        return "libmtpnddjni.so";
    }

    private static void ensureLibraryPath() {
        if (System.getProperty("org.ants.mtpndd.library.path") != null) {
            return;
        }
        Path candidate = Paths.get("build", resolveLibraryName()).toAbsolutePath();
        if (!Files.exists(candidate)) {
            throw new IllegalStateException("Expected JNI library at " + candidate
                    + ". Build it with `cmake -B build && cmake --build build` inside jni/.");
        }
        System.setProperty("org.ants.mtpndd.library.path", candidate.toString());
    }

    public static void main(String[] args) {
        ensureLibraryPath();

        MTPNDDConfig config = MTPNDDConfig.builder()
                .workers(0)
                .laceDequeSize(1 << 14)
                .bddNodeTableSize(1 << 20)
                .mtpnddNodeTableSize(1 << 18)
                .operationCacheSize(1 << 18)
                .edgeBucketCount(64)
                .nodetableBucketCount(1 << 15)
                .nodeSlabCapacity(1024)
                .edgeEntrySlabCapacity(2048)
                .nodetableEntrySlabCapacity(1024)
                .edgeMapSlabCapacity(1024)
                .build();

        try {
            MTPNDDEngine.init(config);
        } catch (Throwable ex) {
            System.err.println("Failed to initialise native runtime: " + ex);
            if (ex.getCause() != null) {
                ex.getCause().printStackTrace(System.err);
            }
            throw ex;
        }

        try {
            int fieldId = MTPNDDEngine.declareField(1);
            MTPNDDEngine.generateFields();
            MTPNDD var = MTPNDD.getVar(fieldId, 0);
            MTPNDD notVar = MTPNDD.getNotVar(fieldId, 0);
            MTPNDD andResult = var.and(var);
            if (!andResult.equals(var)) {
                throw new AssertionError("AND should preserve literal identity");
            }
            MTPNDD orResult = var.or(notVar);
            if (orResult.isFalse()) {
                throw new AssertionError("OR of literal and negation should not be false");
            }
            MTPNDD diffResult = var.diff(var);
            if (!diffResult.equals(MTPNDD.getFalse())) {
                throw new AssertionError("Difference with self should be false");
            }
            MTPNDD notResult = MTPNDD.getFalse().not();
            if (!notResult.equals(MTPNDD.getTrue())) {
                throw new AssertionError("NOT false should equal true");
            }
            MTPNDD existResult = var.exist(fieldId);
            if (existResult.isFalse()) {
                throw new AssertionError("Existential abstraction should not be false");
            }
            double count = MTPNDD.getFalse().satCount();
            if (count != 0.0) {
                throw new AssertionError("Expected zero satcount");
            }
            MTPNDDStats stats = MTPNDDEngine.stats();
            if (stats == null || stats.nodeCount() < 0) {
                throw new AssertionError("Invalid stats");
            }
            System.out.println("Manual JNI check passed. nodeCount=" + stats.nodeCount());
        } finally {
            if (MTPNDDEngine.isInitialized()) {
                MTPNDDEngine.shutdown();
            }
        }

        NQueensMTPNDD.Result nQueens = NQueensMTPNDD.solve(8);
        if (nQueens.solutions != 92L) {
            throw new AssertionError("Unexpected number of solutions for n=8: " + nQueens.solutions);
        }
        System.out.printf("NQueens n=%d -> %d solutions in %.3fs%n", nQueens.n, nQueens.solutions, nQueens.seconds);
    }

}
