package org.ants.jndd.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class NDDPrintDotTest {

    @Test
    void sharedLabelsAreDrawnOnceForFieldsOfTheSameWidth() throws IOException {
        NDD.initNDD(10_000, 10_000, 1_000);
        int n = 4;
        for (int i = 0; i < n; i++) NDD.declareField(2);
        NDD.generateFields();
        int root = NDD.getFalse();
        for (int i = 0; i < n; i++) {
            root = NDD.apply(NDD.BinaryOperation.XOR, root, NDD.and(NDD.getVar(i, 0), NDD.getVar(i, 1)));
        }

        String dot = printDot(root);
        assertEquals(2 * n - 1, count(dot, "\\bn\\d+ \\[shape=circle"));
        // One label group for all four 2-bit fields, holding x ∧ y and ¬(x ∧ y) once each.
        assertEquals(1, count(dot, "subgraph cluster_g"));
        assertTrue(dot.contains("BDD labels, 2-bit fields (f1, f2, f3, f4)"));
        assertEquals(2, count(dot, "shape=plaintext"));
        assertEquals(4, count(dot, "g0_\\d+ \\[shape=circle"));
        assertReferencesAreDeclared(dot);
    }

    @Test
    void labelNodesAreNamedByTheirBitInTheField() throws IOException {
        NDD.initNDD(1_000, 1_000, 1_000);
        int ip = NDD.declareField(4);
        int port = NDD.declareField(2);
        NDD.generateFields();

        // ip bit 3 and port bit 1 share one BDD variable, but must be named after their own field.
        String dot = printDot(NDD.and(NDD.getVar(ip, 3), NDD.getVar(port, 0)));
        assertTrue(dot.contains("BDD labels, 4-bit fields (f1)"));
        assertTrue(dot.contains("BDD labels, 2-bit fields (f2)"));
        assertTrue(dot.contains("label=\"b3\""));
        assertTrue(dot.contains("label=\"b0\""));
        assertFalse(dot.contains("label=\"b1\""));
        assertFalse(Pattern.compile("label=\"v\\d+\"").matcher(dot).find());
        assertReferencesAreDeclared(dot);
    }

    @Test
    void mixedBackendsGetTheirOwnGroupsAndNamedLabels() throws IOException {
        NDD.initNDD(1_000, 1_000, 1_000);
        int bdd = NDD.declareField(2, NDD.LabelMode.BDD);
        int zdd = NDD.declareField(2, NDD.LabelMode.ZDD);
        int bcdd = NDD.declareField(2, NDD.LabelMode.COMPLEMENTED_BDD);
        NDD.generateFields();

        int root = NDD.and(NDD.and(NDD.getVar(bdd, 0), NDD.getVar(zdd, 1)), NDD.getVar(bcdd, 0));
        String dot = printDot(root);
        assertTrue(dot.contains("BDD labels, 2-bit fields (f1)"));
        assertTrue(dot.contains("ZDD labels, 2-bit fields (f2)"));
        assertTrue(dot.contains("COMPLEMENTED_BDD labels, 2-bit fields (f3)"));
        assertTrue(dot.contains("label=\"ZDD #"));
        assertReferencesAreDeclared(dot);
    }

    @Test
    void bitsetLabelsAreBoxes() throws IOException {
        NDD.initNDD(1_000, 1_000, 1_000, NDD.LabelMode.BITSET);
        int field = NDD.declareField(3);
        NDD.generateFields();

        String dot = printDot(NDD.getVar(field, 0));
        assertTrue(dot.contains("BITSET labels, 3-bit fields (f1)"));
        assertEquals(1, count(dot, "_l\\d+ \\[shape=box"));
        assertReferencesAreDeclared(dot);
    }

    @Test
    void onlyTheOneTerminalIsDrawnUnlessTheRootIsFalse() throws IOException {
        NDD.initNDD(1_000, 1_000, 1_000);
        int field = NDD.declareField(2);
        NDD.generateFields();

        for (int root : new int[]{NDD.getTrue(), NDD.getVar(field, 0)}) {
            String dot = printDot(root);
            assertTrue(dot.contains("n_true [shape=box, label=\"1\"]"));
            assertFalse(dot.contains("n_false"));
        }
        String falseDot = printDot(NDD.getFalse());
        assertTrue(falseDot.contains("n_false [shape=box, label=\"0\"]"));
        assertFalse(falseDot.contains("n_true"));
    }

    private static String printDot(int root) throws IOException {
        Path file = Files.createTempFile("ndd", ".dot");
        try {
            NDD.printDot(root, file.toString());
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static int count(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    /** Every node used by an edge must be declared, or Graphviz silently draws an unlabeled node. */
    private static void assertReferencesAreDeclared(String dot) {
        java.util.Set<String> declared = new java.util.HashSet<>();
        Matcher declaration = Pattern.compile("(?m)^\\s*(\\w+) \\[").matcher(dot);
        while (declaration.find()) declared.add(declaration.group(1));
        Matcher edge = Pattern.compile("(\\w+) -> (\\w+)").matcher(dot);
        while (edge.find()) {
            assertTrue(declared.contains(edge.group(1)), "undeclared node " + edge.group(1));
            assertTrue(declared.contains(edge.group(2)), "undeclared node " + edge.group(2));
        }
    }
}
