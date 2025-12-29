/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

public class Test {
    private static void fail() {
        Thread.dumpStack();
        System.exit(20);
    }

    public static void check(boolean c) {
        Test.check(c, null);
    }

    public static void check(boolean c, String s) {
        if (!c) {
            if (s != null) {
                System.err.println("ASSERTION FAILED: " + s + "     ");
            }
            Test.fail();
        }
    }

    public static void checkEquality(int a, int b, String s) {
        if (a != b) {
            System.err.print("ASSERTION FAILED: ");
            if (s != null) {
                System.err.print(s + " ");
            }
            System.err.println("" + a + " != " + b + "    ");
            Test.fail();
        }
    }

    public static void checkInequality(int a, int b, String s) {
        if (a == b) {
            System.err.print("ASSERTION FAILED: ");
            if (s != null) {
                System.err.print(s + " ");
            }
            System.err.println("" + a + " == " + b + "    ");
            Test.fail();
        }
    }
}

