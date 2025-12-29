/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd.debug;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import jdd.bdd.debug.BDDTrace;
import jdd.util.JDDConsole;
import jdd.util.Options;
import jdd.util.jre.JREInfo;

public class BDDTraceSuite {
    public BDDTraceSuite(String filename, int initial_size) {
        try {
            FileInputStream is = new FileInputStream(filename);
            ZipInputStream zis = new ZipInputStream(is);
            JDDConsole.out.printf("\n***** [%s] *****\n", filename);
            JREInfo.show();
            ZipEntry ze = zis.getNextEntry();
            while (zis.available() != 0) {
                String name = ze.getName();
                if (name.endsWith(".trace")) {
                    this.runTrace(name, zis, initial_size);
                } else if (name.endsWith("README")) {
                    this.showFile(name, zis);
                }
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            zis.close();
            ((InputStream)is).close();
        }
        catch (IOException exx) {
            JDDConsole.out.printf("FAILED: %s\n", exx.getMessage());
            exx.printStackTrace();
            System.exit(20);
        }
    }

    private void runTrace(String name, InputStream is, int size) {
        boolean save = Options.verbose;
        Options.verbose = true;
        try {
            if (size == -1) {
                new BDDTrace(name, is);
            } else {
                new BDDTrace(name, is, size);
            }
        }
        catch (Exception ex) {
            JDDConsole.out.printf("FAILED when running %s: %s\n", name, ex.getMessage());
            ex.printStackTrace();
        }
        Options.verbose = save;
        for (int i = 0; i < 6; ++i) {
            System.gc();
        }
        try {
            Thread.sleep(5000L);
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private void showFile(String name, InputStream is) throws IOException {
        JDDConsole.out.printf("File %s\n", name);
        byte[] buffer = new byte[10240];
        int i;
        while ((i = is.read(buffer, 0, buffer.length)) > 0) {
            JDDConsole.out.printf("%s\n", new String(buffer, 0, i));
        }
        return;
    }

    public static void main(String[] args) {
        if (args.length == 1) {
            new BDDTraceSuite(args[0], -1);
        } else if (args.length == 2) {
            new BDDTraceSuite(args[0], Integer.parseInt(args[1]));
        } else {
            System.err.println("Usage: java BDDTraceSuite <trace-suite.zip> [initial size _base_]");
        }
    }
}

