/*
 * Decompiled with CFR 0.152.
 */
package jdd.util.jre;

import java.util.Random;
import jdd.util.JDDConsole;
import jdd.util.jre.JREInfo;
import jdd.util.math.FastRandom;

public class JRETest {
    public static void copy1(int[] x, int[] y) {
        int len = x.length;
        for (int i = 0; i < len; ++i) {
            x[i] = y[i];
        }
    }

    public static void copy2(int[] x, int[] y) {
        System.arraycopy(y, 0, x, 0, x.length);
    }

    public static void copy3(int[] x, int[] y) {
        int o = 0;
        for (int i = x.length / 4; i != 0; --i) {
            x[o + 0] = y[o + 0];
            x[o + 1] = y[o + 1];
            x[o + 2] = y[o + 2];
            x[o + 3] = y[o + 3];
            o += 4;
        }
        while (o < x.length) {
            x[o] = y[o];
            ++o;
        }
    }

    public static void main(String[] args) {
        int SIZE = 10240;
        int ROUNDS = 9876;
        int[] buffer1 = new int[10240];
        int[] buffer2 = new int[10240];
        long t1 = 0L;
        long t2 = 0L;
        long t3 = 0L;
        JREInfo.show();
        for (int w = 0; w < 2; ++w) {
            int i;
            t1 = System.currentTimeMillis();
            for (i = 0; i < 9876; ++i) {
                JRETest.copy1(buffer1, buffer2);
            }
            t1 = System.currentTimeMillis() - t1;
            t2 = System.currentTimeMillis();
            for (i = 0; i < 9876; ++i) {
                JRETest.copy2(buffer1, buffer2);
            }
            t2 = System.currentTimeMillis() - t2;
            t3 = System.currentTimeMillis();
            for (i = 0; i < 9876; ++i) {
                JRETest.copy3(buffer1, buffer2);
            }
            t3 = System.currentTimeMillis() - t3;
        }
        System.out.printf("COPY: Java code is %s than System.arraycopy() [%d vs %d]\n", t1 < t2 ? "faster" : "slower", t1, t2);
        System.out.printf("COPY: unrolled loop is %s than System.arraycopy() [%d vs %d]\n", t3 < t3 ? "faster" : "slower", t3, t2);
        int MAX = 10000;
        Random rnd = new Random();
        for (int w = 0; w < 2; ++w) {
            int y;
            int i;
            t1 = System.currentTimeMillis();
            for (i = 0; i < 1000000; ++i) {
                y = FastRandom.mtrand() % MAX;
                y = FastRandom.mtrand() % MAX;
                y = FastRandom.mtrand() % MAX;
                y = FastRandom.mtrand() % MAX;
                y = FastRandom.mtrand() % MAX;
                y = FastRandom.mtrand() % MAX;
            }
            t1 = System.currentTimeMillis() - t1;
            t2 = System.currentTimeMillis();
            for (i = 0; i < 1000000; ++i) {
                y = rnd.nextInt(MAX);
                y = rnd.nextInt(MAX);
                y = rnd.nextInt(MAX);
                y = rnd.nextInt(MAX);
                y = rnd.nextInt(MAX);
                y = rnd.nextInt(MAX);
            }
            t2 = System.currentTimeMillis() - t2;
        }
        JDDConsole.out.printf("LPRNG: FastRandom.mtrand() is %s than Random [%d vs %d]\n", t1 < t2 ? "faster" : "slower", t1, t2);
    }
}

