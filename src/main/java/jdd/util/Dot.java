/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

import java.io.FileOutputStream;
import java.io.IOException;
import jdd.util.FileUtility;
import jdd.util.JDDConsole;

public class Dot {
    public static final int TYPE_EPS = 0;
    public static final int TYPE_PNG = 1;
    public static final int TYPE_DOT = 2;
    public static final int TYPE_FIG = 3;
    public static final int TYPE_GIF = 4;
    public static final int TYPE_JPG = 5;
    private static final String DOT_COMMAND = "dot";
    private static final String[] DOT_TYPES = new String[]{"ps", "png", "dot", "fig", "gif", "jpg"};
    private static Runtime rt = Runtime.getRuntime();
    private static int dot_type = 1;
    private static boolean run_dot = true;
    private static boolean remove_dot_file = true;

    public static String showString(String file, String string) {
        try {
            if (FileUtility.invalidFilename(file)) {
                System.err.println("[Dot] The filename '" + file + "' is invalid.");
                System.err.println("[Dot] Maybe it contains characters we don't like?");
                return null;
            }
            FileOutputStream fs = new FileOutputStream(file);
            fs.write(string.getBytes());
            fs.flush();
            fs.close();
            return Dot.showDot(file);
        }
        catch (IOException exx) {
            JDDConsole.out.printf("Unable to save graph to the file %s. Reason: %s\n", file, exx);
            return null;
        }
    }

    public static String showDot(String infile) {
        if (FileUtility.invalidFilename(infile)) {
            System.err.println("[Dot] The filename '" + infile + "' is invalid.");
            System.err.println("[Dot] Maybe it contains characters we don't like?");
            return null;
        }
        try {
            String outfile = infile + "." + DOT_TYPES[dot_type];
            if (run_dot) {
                String[] cmd = new String[]{DOT_COMMAND, "-T", DOT_TYPES[dot_type], infile, "-o", outfile};
                Process p = rt.exec(cmd);
                p.waitFor();
            }
            if (remove_dot_file) {
                FileUtility.delete(infile);
            }
            return outfile;
        }
        catch (IOException exx) {
            JDDConsole.out.printf("Unable to run DOT on %s. Reason: %s\n", infile, exx);
        }
        catch (InterruptedException exx) {
            JDDConsole.out.printf("DOT interrupted when processing %s. Reason: %s\n", infile, exx);
        }
        catch (Exception exx) {
            JDDConsole.out.printf("Unknown error when DOT processing %s. Reason: %s\n", infile, exx);
        }
        return null;
    }

    public static boolean scalable() {
        return dot_type == 2 || dot_type == 0 || dot_type == 3;
    }

    public static void setType(int t) {
        dot_type = t;
    }

    public static void setExecuteDot(boolean b) {
        run_dot = b;
    }

    public static void setRemoveDotFile(boolean b) {
        remove_dot_file = b;
    }
}

