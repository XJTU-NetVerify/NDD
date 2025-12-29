/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

import java.io.File;

public class FileUtility {
    public static boolean invalidFilename(String file) {
        char[] badchars;
        if (file.length() == 0) {
            return true;
        }
        if (File.separatorChar != '\\' && file.indexOf(92) != -1) {
            return false;
        }
        for (char badchar : badchars = "\"';&|<>#!$".toCharArray()) {
            if (file.indexOf(badchar) == -1) continue;
            return true;
        }
        return false;
    }

    public static boolean delete(String filename) {
        File f = new File(filename);
        return f.delete();
    }
}

