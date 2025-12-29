/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Collection;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import jdd.bdd.BDD;
import jdd.util.Array;
import jdd.util.JDDConsole;

public class BDDIO {
    private static final String BDD_HEADER_MAGIC = "FORMAT:JDD.BDD";
    private static BDD manager;
    private static OutputStream os;
    private static Writer wr;
    private static boolean binary_format;
    private static byte[] buffer;

    private static int safe_read(InputStream is, int size, byte[] b) throws IOException {
        int got = 0;
        int errors = 0;
        while (got < size) {
            int len = is.read(b, got, size - got);
            if (len < size - got && ++errors == 3) {
                return got;
            }
            if (len <= 0) continue;
            got += len;
        }
        return got;
    }

    private static void save_int(int n) throws IOException {
        BDDIO.buffer[0] = (byte)(n >> 24 & 0xFF);
        BDDIO.buffer[1] = (byte)(n >> 16 & 0xFF);
        BDDIO.buffer[2] = (byte)(n >> 8 & 0xFF);
        BDDIO.buffer[3] = (byte)(n & 0xFF);
        os.write(buffer, 0, 4);
    }

    private static int load_int(InputStream is) throws IOException {
        int len = BDDIO.safe_read(is, 4, buffer);
        if (len != 4) {
            throw new IOException("immature end of file while reading the header fields");
        }
        int ret = 0;
        for (int i = 0; i < 4; ++i) {
            int x = buffer[i] & 0xFF;
            ret = ret << 8 | x;
        }
        return ret;
    }

    public static void save(BDD manager, int bdd, String filename) throws IOException {
        FileOutputStream fos = new FileOutputStream(filename);
        os = new GZIPOutputStream(fos);
        try {
            BDDIO.manager = manager;
            binary_format = true;
            os.write(BDD_HEADER_MAGIC.getBytes(), 0, BDD_HEADER_MAGIC.length());
            BDDIO.save_int(manager.nodeCount(bdd));
            BDDIO.save_int(bdd);
            BDDIO.recursive_save(bdd);
            os.flush();
            os.close();
            fos.flush();
            ((OutputStream)fos).close();
        }
        catch (IOException exx) {
            JDDConsole.out.printf("BDDIO.save Failed: %s\n", exx);
            throw exx;
        }
        finally {
            manager.unmark_tree(bdd);
            BDDIO.manager = null;
            os = null;
        }
    }

    private static void recursive_save(int bdd) throws IOException {
        if (bdd < 2) {
            return;
        }
        if (!manager.isNodeMarked(bdd)) {
            manager.mark_node(bdd);
            int var = manager.getVarUnmasked(bdd);
            int low = manager.getLow(bdd);
            int high = manager.getHigh(bdd);
            BDDIO.recursive_save(low);
            BDDIO.recursive_save(high);
            if (binary_format) {
                BDDIO.save_int(bdd);
                BDDIO.save_int(var);
                BDDIO.save_int(low);
                BDDIO.save_int(high);
            } else {
                wr.write("" + bdd + "\t" + var + "\t" + low + "\t" + high + "\n");
            }
        }
    }

    public static int load(BDD manager, String filename) throws IOException {
        int ret = 0;
        GZIPInputStream is = new GZIPInputStream(new FileInputStream(filename));
        byte[] magic = new byte[BDD_HEADER_MAGIC.length()];
        int len = BDDIO.safe_read(is, magic.length, magic);
        if (len != magic.length) {
            throw new IOException("immature end of file while reading the header");
        }
        if (!Array.equals(magic, BDD_HEADER_MAGIC.getBytes(), magic.length)) {
            throw new IOException("this is not an BDD file in JDD format");
        }
        int curr_vars = manager.numberOfVariables();
        int size = BDDIO.load_int(is);
        int target = BDDIO.load_int(is);
        HashMap<Integer, Integer> map = new HashMap<Integer, Integer>();
        Integer zero = Integer.valueOf(0);
        Integer one = Integer.valueOf(1);
        map.put(zero, zero);
        map.put(one, one);
        try {
            for (int i = 0; i < size; ++i) {
                int name = BDDIO.load_int(is);
                int var = BDDIO.load_int(is);
                int low = BDDIO.load_int(is);
                int high = BDDIO.load_int(is);
                Integer tmp = (Integer)map.get(Integer.valueOf(low));
                if (tmp == null) {
                    throw new IOException("Unknown child node" + low);
                }
                low = tmp;
                tmp = (Integer)map.get(Integer.valueOf(high));
                if (tmp == null) {
                    throw new IOException("Unknown child node" + high);
                }
                high = tmp;
                while (var >= curr_vars) {
                    manager.createVar();
                    ++curr_vars;
                }
                ret = manager.ref(manager.mk(var, low, high));
                map.put(Integer.valueOf(name), Integer.valueOf(ret));
            }
            ((InputStream)is).close();
            Integer new_target = (Integer)map.get(Integer.valueOf(target));
            if (new_target == null) {
                throw new IOException("Corrupt BDD file");
            }
            ret = new_target;
            Collection<Integer> values = map.values();
            for (Integer i : values) {
                manager.deref(i);
            }
        }
        catch (IOException exx) {
            JDDConsole.out.printf("BDDIO.bddLoad Failed: %s\n", exx);
            throw exx;
        }
        finally {
            ((InputStream)is).close();
        }
        return ret;
    }

    public static void saveBuDDy(BDD manager, int bdd, String filename) throws IOException {
        wr = new OutputStreamWriter(new FileOutputStream(filename));
        try {
            BDDIO.manager = manager;
            binary_format = false;
            if (bdd < 2) {
                wr.write("0 0 " + bdd + "\n");
            } else {
                int vars = manager.numberOfVariables();
                int size = manager.nodeCount(bdd);
                wr.write("" + size + " " + vars + "\n");
                for (int i = 0; i < vars; ++i) {
                    wr.write("" + i + " ");
                }
                wr.write("\n");
                BDDIO.recursive_save(bdd);
            }
            wr.close();
        }
        catch (IOException exx) {
            JDDConsole.out.printf("BDDIO.save Failed: %s\n", exx);
            throw exx;
        }
        finally {
            manager.unmark_tree(bdd);
            BDDIO.manager = null;
            wr = null;
        }
    }

    static {
        buffer = new byte[4];
    }
}
