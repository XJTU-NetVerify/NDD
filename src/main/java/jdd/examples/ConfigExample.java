/*
 * Decompiled with CFR 0.152.
 */
package jdd.examples;

import jdd.examples.Adder;
import jdd.util.Configuration;
import jdd.util.JDDConsole;

public class ConfigExample {
    private static final int N = 256;

    private static void test() {
        long time = System.currentTimeMillis();
        Adder adder = new Adder(256);
        long memory = adder.getMemoryUsage() / 1024L;
        time = System.currentTimeMillis() - time;
        adder.showStats();
        adder.cleanup();
        JDDConsole.out.println("**** TIME = " + time + "ms , MEMORY = " + memory + "KB ****\n");
    }

    public static void main(String[] args) {
        JDDConsole.out.println("ConfigExample.java:");
        JDDConsole.out.println("We will now profile Adder(256) under different configurations");
        JDDConsole.out.println("\nDefault configuration");
        ConfigExample.test();
        JDDConsole.out.println("\nSmaller OP cache");
        Configuration.bddOpcacheDiv = 8;
        ConfigExample.test();
        Configuration.bddOpcacheDiv = 1;
        JDDConsole.out.println("\nToo small OP cache");
        Configuration.bddOpcacheDiv = 1000;
        ConfigExample.test();
        Configuration.bddOpcacheDiv = 1;
        JDDConsole.out.println("\nFaster nodetable grow:");
        Configuration.nodetableGrowMax = 500000;
        Configuration.nodetableGrowMin = 500000;
        ConfigExample.test();
        Configuration.nodetableGrowMin = 50000;
        Configuration.nodetableGrowMax = 300000;
        JDDConsole.out.println("\nComputation caches are NOT allowed to grow:");
        Configuration.maxSimplecacheGrows = 0;
        ConfigExample.test();
        Configuration.maxSimplecacheGrows = 5;
        JDDConsole.out.println("\nComputation caches are allowed to grow, but only under very high hitrate:");
        Configuration.minSimplecacheHitrateToGrow = 85;
        ConfigExample.test();
        Configuration.minSimplecacheHitrateToGrow = 40;
        JDDConsole.out.println("\n\nThe results wasn't what you were expecting huh?");
        JDDConsole.out.println("Hope this example has learned you the importance of BDD tuning!");
        JDDConsole.out.printf("\n", new Object[0]);
    }
}

