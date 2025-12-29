/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd.debug;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Vector;
import jdd.bdd.BDD;
import jdd.bdd.BDDIO;
import jdd.bdd.BDDNames;
import jdd.bdd.Permutation;
import jdd.bdd.debug.ProfiledBDD2;
import jdd.util.JDDConsole;
import jdd.util.Options;

public class BDDTrace {
    private static final int DEFAULT_NODES = 10000;
    private static final int MAX_NODES = 3000000;
    private BDD bdd;
    private InputStream is;
    private StringBuffer sb;
    private String filename;
    private String module;
    private int[] stack;
    private int stack_tos;
    private int nodes;
    private int cache;
    private int vars;
    private HashMap map;
    private Permutation s2sp;
    private Permutation sp2s;
    private TracedVariable last_assignment;
    private Vector operations;
    private Vector variables;
    private int op_count;
    private int line_count;
    private int var_count;
    private long time;
    public static boolean verbose = false;

    public BDDTrace(String file) throws IOException {
        this(file, new FileInputStream(file), 10000);
    }

    public BDDTrace(String file, int nodes) throws IOException {
        this(file, new FileInputStream(file), nodes);
    }

    public BDDTrace(String file, InputStream is) throws IOException {
        this(file, is, 10000);
    }

    public BDDTrace(String file, InputStream is, int nodes) throws IOException {
        this.filename = file;
        this.nodes = nodes;
        this.is = is;
        this.sb = new StringBuffer();
        this.stack = new int[64];
        this.stack_tos = 0;
        this.cache = Math.max(Math.min(nodes / 10, 5000), 50000);
        this.map = new HashMap(1024);
        this.operations = new Vector();
        this.variables = new Vector();
        this.op_count = 0;
        this.line_count = 1;
        this.var_count = 0;
        TracedVariable vret = new TracedVariable();
        vret.last_use = 0;
        vret.bdd = 0;
        this.map.put("0", vret);
        vret = new TracedVariable();
        vret.last_use = 0;
        vret.bdd = 1;
        this.map.put("1", vret);
        this.last_assignment = null;
        this.parse();
        boolean save = Options.verbose;
        Options.verbose = false;
        this.execute();
        Options.verbose = save;
        this.show_results();
        this.bdd.cleanup();
    }

    private void show_code() {
        Object v;
        JDDConsole.out.println("import org.sf.javabdd.*;\npublic class Test {\npublic static void main(String[] args) {\n");
        JDDConsole.out.println("\n\nBDDFactory B = BDDFactory.init(" + this.nodes + ",100);\nB.setVarNum(" + this.variables.size() + ");\nBDD ");
        int i = 0;
        Enumeration e = this.variables.elements();
        while (e.hasMoreElements()) {
            v = (TracedVariable)e.nextElement();
            if (!((TracedVariable)v).is_var) continue;
            if (i != 0) {
                JDDConsole.out.print(",");
            }
            JDDConsole.out.print(((TracedVariable)v).name + "=B.ithVar(" + i + ") ");
            ++i;
        }
        JDDConsole.out.println(";");
        e = this.operations.elements();
        while (e.hasMoreElements()) {
            v = (TracedOperation)e.nextElement();
            ((TracedOperation)v).show_code();
        }
        JDDConsole.out.println("}\n}\n");
    }

    private void setup_bdd(int vars) {
        this.vars = vars;
        this.nodes = (int)Math.min(3000000.0, (double)this.nodes * (1.0 + Math.log(1 + vars)));
        JDDConsole.out.printf("\n", new Object[0]);
        JDDConsole.out.println("loading " + this.module + " from " + this.filename + " (" + this.nodes + " nodes, " + vars + " vars)");
        this.bdd = new ProfiledBDD2(this.nodes, this.cache);
        this.bdd.setNodeNames(new TracedNames());
    }

    private void alloc_var(String name) {
        TracedVariable vret = new TracedVariable();
        vret.last_use = 0;
        vret.bdd = this.bdd.createVar();
        vret.name = name;
        vret.is_var = true;
        this.map.put(name, vret);
        this.variables.add(vret);
        ++this.var_count;
    }

    private void checkVar(TracedBDDOperation tp) {
        this.checkVar(tp.ret);
        Enumeration e = tp.operands.elements();
        while (e.hasMoreElements()) {
            TracedVariable v = (TracedVariable)e.nextElement();
            this.checkVar(v);
        }
    }

    private void checkVar(TracedVariable v) {
        if (v != null && v.last_use == this.op_count) {
            this.bdd.deref(v.bdd);
            v.last_use = -1;
        }
    }

    private TracedPrintOperation createPrintOperation(boolean graph, TracedVariable v) {
        TracedPrintOperation tp = new TracedPrintOperation();
        tp.index = this.op_count;
        tp.graph = graph;
        tp.v = v;
        this.operations.add(tp);
        return tp;
    }

    private TracedSaveOperation createSaveOperation(TracedVariable v) {
        TracedSaveOperation ts = new TracedSaveOperation();
        ts.index = this.op_count;
        ts.v = v;
        this.operations.add(ts);
        return ts;
    }

    private TracedCheckOperation createCheckOperation(TracedVariable v1, TracedVariable v2) {
        TracedCheckOperation tp = new TracedCheckOperation();
        tp.index = this.op_count;
        tp.t1 = v1;
        tp.t2 = v2;
        this.operations.add(tp);
        return tp;
    }

    private TracedDebugOperation createDebugOperation(String text) {
        TracedDebugOperation tp = new TracedDebugOperation();
        tp.index = this.op_count;
        tp.text = text;
        this.operations.add(tp);
        return tp;
    }

    private TracedBDDOperation createBDDOperation() {
        TracedBDDOperation tp = new TracedBDDOperation();
        tp.index = this.op_count;
        this.operations.add(tp);
        tp.operands = new Vector(3);
        return tp;
    }

    private void show_results() {
        this.time = System.currentTimeMillis() - this.time;
        JDDConsole.out.println("" + this.op_count + " operations performed, total execution time: " + this.time + " [ms]");
        if (Options.verbose) {
            if (this.last_assignment != null) {
                int size = this.node_count(this.last_assignment);
                JDDConsole.out.println("Last assginment: " + this.last_assignment.name + ", " + size + " nodes.");
                JDDConsole.out.println("\n");
            }
            this.bdd.showStats();
        }
        System.err.println("Trace\tFile=" + this.filename + "\ttime=" + this.time);
    }

    private void check_all_variables() {
        Enumeration e = this.variables.elements();
        while (e.hasMoreElements()) {
            TracedVariable v = (TracedVariable)e.nextElement();
            if (v.last_use < this.op_count) continue;
        }
    }

    private void execute() throws IOException {
        this.time = System.currentTimeMillis();
        Enumeration e = this.operations.elements();
        while (e.hasMoreElements()) {
            TracedOperation tp = (TracedOperation)e.nextElement();
            this.op_count = tp.index;
            if (verbose) {
                tp.show();
            }
            tp.execute();
        }
    }

    private int node_count(TracedVariable v) {
        int size = this.bdd.nodeCount(v.bdd);
        return size += v.bdd < 2 ? 1 : 2;
    }

    private void parse() throws IOException {
        this.read_module();
        this.read_input();
        this.skip_output();
        this.read_structure();
    }

    private void read_module() throws IOException {
        this.need("MODULE");
        this.module = this.need();
    }

    private void skip_output() throws IOException {
        this.need("OUTPUT");
        String tmp = this.need();
        while (!tmp.equals(";")) {
            tmp = this.need();
        }
    }

    private void read_structure() throws IOException {
        this.need("STRUCTURE");
        String ret;
        while (!(ret = this.need()).equals("ENDMODULE")) {
            TracedVariable v;
            String str;
            ++this.op_count;
            if (ret.equals("trace_verbose_print")) {
                this.need("(");
                str = this.getString();
                this.need(")");
                this.need(";");
                this.createDebugOperation(str);
                continue;
            }
            if (ret.equals("are_equal")) {
                this.need("(");
                str = this.need();
                TracedVariable t1 = this.needVar(str);
                this.need(",");
                str = this.need();
                TracedVariable t2 = this.needVar(str);
                this.need(")");
                this.need(";");
                this.createCheckOperation(t1, t2);
                continue;
            }
            if (ret.equals("print_bdd") || ret.equals("show_bdd")) {
                this.need("(");
                str = this.need();
                v = this.needVar(str);
                this.need(")");
                this.need(";");
                this.createPrintOperation(ret.equals("show_bdd"), v);
                continue;
            }
            if (ret.equals("save_bdd")) {
                this.need("(");
                str = this.need();
                v = this.needVar(str);
                this.need(")");
                this.need(";");
                this.createSaveOperation(v);
                continue;
            }
            if (ret.equals("check_point_for_force_reordering")) {
                JDDConsole.out.println("NOTE: ignoring variable-reordering request");
                this.skip_eol();
                continue;
            }
            TracedVariable vret = (TracedVariable)this.map.get(ret);
            if (vret == null) {
                vret = this.addTemporaryVariable(ret);
            }
            this.need("=");
            String op = this.need();
            this.updateUsage(vret);
            TracedBDDOperation tp = this.createBDDOperation();
            TracedVariable var = (TracedVariable)this.map.get(op);
            if (var != null) {
                this.need(";");
                tp.operands.add(var);
                tp.ret = vret;
                tp.op = "=";
                this.updateUsage(var);
            } else {
                tp.op = op;
                tp.ret = vret;
                if (op.equals("new_int_leaf")) {
                    this.need("(");
                    String c = this.need();
                    this.need(")");
                    this.need(";");
                    tp.operands.add(this.map.get(c));
                    tp.ret = vret;
                    tp.op = "=";
                } else {
                    String s1;
                    this.need("(");
                    do {
                        s1 = this.need();
                        tp.operands.add(this.needVar(s1));
                    } while ((s1 = this.need()).equals(","));
                    this.need(";");
                }
            }
            tp.ops = tp.operands.size();
            if (tp.ops > 0) {
                tp.op1 = (TracedVariable)tp.operands.elementAt(0);
            }
            if (tp.ops > 1) {
                tp.op2 = (TracedVariable)tp.operands.elementAt(1);
            }
            if (tp.ops <= 2) continue;
            tp.op3 = (TracedVariable)tp.operands.elementAt(2);
        }
        return;
    }

    private void read_input() throws IOException {
        boolean interleave = false;
        LinkedList<String> list = new LinkedList<String>();
        this.need("INPUT");
        int i = 0;
        while (true) {
            String name = this.need();
            if (i == 0 && (name.equals("CURR_NEXT_ASSOCIATE_EVEN_ODD_INPUT_VARS") || name.equals("STATE_VAR_ASSOCIATE_CURR_NEXT_INTERLEAVE"))) {
                if (name.equals("STATE_VAR_ASSOCIATE_CURR_NEXT_INTERLEAVE")) {
                    interleave = true;
                }
            } else {
                list.add(name);
                name = this.need();
                if (name.equals(";")) break;
                if (!name.equals(",")) {
                    throw new IOException("expected ',' when reading inputs, but got: " + name + " at line " + this.line_count);
                }
            }
            ++i;
        }
        int count = list.size();
        this.setup_bdd(count);
        for (String name : list) {
            this.alloc_var(name);
        }
        int size = this.variables.size();
        int[] v1 = new int[size / 2];
        int[] v2 = new int[size / 2];
        Enumeration e = this.variables.elements();
        for (int i2 = 0; i2 < (size & 0xFFFFFFFE); ++i2) {
            TracedVariable v = (TracedVariable)e.nextElement();
            if (interleave) {
                if (i2 % 2 == 0) {
                    v1[i2 / 2] = v.bdd;
                    continue;
                }
                v2[i2 / 2] = v.bdd;
                continue;
            }
            if (i2 < v1.length) {
                v1[i2] = v.bdd;
                continue;
            }
            v2[i2 - v1.length] = v.bdd;
        }
        this.s2sp = this.bdd.createPermutation(v1, v2);
        this.sp2s = this.bdd.createPermutation(v2, v1);
    }

    private TracedVariable needVar(String str) throws IOException {
        TracedVariable ret = (TracedVariable)this.map.get(str);
        if (ret == null) {
            throw new IOException("Unknown variable/operand " + str + " at line " + this.line_count);
        }
        this.updateUsage(ret);
        return ret;
    }

    private void updateUsage(TracedVariable v) {
        v.last_use = this.op_count;
    }

    private TracedVariable addTemporaryVariable(String name) {
        TracedVariable vret = new TracedVariable();
        vret.last_use = this.op_count;
        vret.name = name;
        vret.bdd = this.bdd.ref(0);
        this.variables.add(vret);
        this.map.put(name, vret);
        return vret;
    }

    private void need(String what) throws IOException {
        String got = this.need();
        if (!got.equals(what)) {
            this.check(false, "Syntax error: expected '" + what + "', but read '" + got + "', op=" + this.op_count);
        }
    }

    private String need() throws IOException {
        String ret = this.next();
        if (ret == null) {
            this.check(false, "pre-mature end of file");
        }
        return ret;
    }

    private int read() {
        int c = -1;
        if (this.stack_tos > 0) {
            c = this.stack[--this.stack_tos];
        } else {
            try {
                c = this.is.read();
            }
            catch (IOException iOException) {
                // empty catch block
            }
        }
        if (c == 10) {
            ++this.line_count;
        }
        return c;
    }

    private void push(int c) {
        this.stack[this.stack_tos++] = c;
        if (c == 10) {
            --this.line_count;
        }
    }

    private boolean isSpace(int c) {
        return c == 32 || c == 10 || c == 9 || c == 13;
    }

    private boolean isAlnum(int c) {
        return c >= 48 && c <= 57 || c >= 97 && c <= 122 || c >= 65 && c <= 90 || c == 95;
    }

    private String getString() throws IOException {
        StringBuffer buffer = new StringBuffer();
        int c = 0;
        while (this.isSpace(c = this.read())) {
        }
        if (c != 34) {
            throw new IOException("Not an string at line " + this.line_count);
        }
        while ((c = this.read()) != 34) {
            buffer.append((char)c);
        }
        return buffer.toString();
    }

    private void skip_eol() {
        int c;
        while ((c = this.read()) != -1 && c != 10) {
        }
    }

    private String next() {
        int c;
        this.sb.setLength(0);
        do {
            if ((c = this.read()) != -1) continue;
            return null;
        } while (this.isSpace(c));
        if (this.isAlnum(c)) {
            do {
                this.sb.append((char)c);
                c = this.read();
                if (c != -1) continue;
                return this.sb.toString();
            } while (this.isAlnum(c));
            if (!this.isSpace(c)) {
                this.push(c);
            }
        } else {
            if (c == 37 || c == 35) {
                int old_line = this.line_count;
                if (c == 37) {
                    String count = this.next();
                    TracedOperation tp = (TracedOperation)this.operations.lastElement();
                    if (tp.size == -1) {
                        tp.size = Integer.parseInt(count);
                    }
                }
                if (old_line == this.line_count) {
                    this.skip_eol();
                }
                return this.next();
            }
            return "" + (char)c;
        }
        return this.sb.toString();
    }

    void checkEquality(int a, int b, String txt) throws IOException {
        if (a != b) {
            throw new IOException(txt + ", " + a + " != " + b);
        }
    }

    void check(boolean b, String txt) throws IOException {
        if (!b) {
            throw new IOException(txt);
        }
    }

    void check(boolean b) throws IOException {
        if (!b) {
            throw new IOException("Check failed");
        }
    }

    public static void main(String[] args) {
        verbose = true;
        Options.verbose = true;
        Options.profile_cache = true;
        try {
            if (args.length == 2) {
                new BDDTrace(args[0], Integer.parseInt(args[1]));
            } else if (args.length == 1) {
                new BDDTrace(args[0]);
            } else {
                JDDConsole.out.println("Usage:  java jdd.bdd.BDDTrace file.trace [initial node-base]");
            }
        }
        catch (IOException exx) {
            JDDConsole.out.println("FAILED: " + exx.getMessage());
            exx.printStackTrace();
            System.exit(20);
        }
    }

    class TracedBDDOperation
    extends TracedOperation {
        public int ops;
        public TracedVariable ret;
        public TracedVariable op1;
        public TracedVariable op2;
        public TracedVariable op3;
        public Vector operands;

        TracedBDDOperation() {
        }

        @Override
        public void show() {
            JDDConsole.out.print(this.index + "\t");
            this.ret.show();
            JDDConsole.out.print(" = ");
            if (this.op.equals("=")) {
                this.op1.show();
                JDDConsole.out.print(";");
            } else {
                JDDConsole.out.print(this.op + "(");
                boolean first = true;
                Enumeration e = this.operands.elements();
                while (e.hasMoreElements()) {
                    TracedVariable v = (TracedVariable)e.nextElement();
                    if (first) {
                        first = false;
                    } else {
                        JDDConsole.out.print(", ");
                    }
                    v.show();
                }
                JDDConsole.out.print(");");
            }
            if (this.size != -1) {
                JDDConsole.out.print("\t% " + this.size);
            }
            JDDConsole.out.printf("\n", new Object[0]);
        }

        @Override
        public void execute() throws IOException {
            int size2;
            BDDTrace.this.bdd.deref(this.ret.bdd);
            if (this.op.equals("not")) {
                this.do_not();
            } else if (this.op.equals("=")) {
                this.do_assign();
            } else if (this.op.equals("and")) {
                this.do_and();
            } else if (this.op.equals("or")) {
                this.do_or();
            } else if (this.op.equals("xor")) {
                this.do_xor();
            } else if (this.op.equals("xnor")) {
                this.do_xnor();
            } else if (this.op.equals("nor")) {
                this.do_nor();
            } else if (this.op.equals("nand")) {
                this.do_nand();
            } else if (this.op.equals("ite")) {
                this.do_ite();
            } else if (this.op.equals("vars_curr_to_next")) {
                this.do_s2sp();
            } else if (this.op.equals("vars_next_to_curr")) {
                this.do_sp2s();
            } else if (this.op.equals("support_vars")) {
                this.do_support();
            } else if (this.op.equals("exists")) {
                this.do_exists();
            } else if (this.op.equals("forall")) {
                this.do_forall();
            } else if (this.op.equals("restrict")) {
                this.do_restrict();
            } else if (this.op.equals("rel_prod")) {
                this.do_relprod();
            } else {
                throw new IOException("Unknown operation '" + this.op + "', #" + BDDTrace.this.op_count);
            }
            BDDTrace.this.bdd.ref(this.ret.bdd);
            BDDTrace.this.last_assignment = this.ret;
            BDDTrace.this.checkVar(this);
            if (this.size != -1 && this.size != (size2 = BDDTrace.this.node_count(this.ret))) {
                JDDConsole.out.println("\n*************************************************************************");
                JDDConsole.out.println("Size comparison failed after " + this.op + " ( wanted " + this.size + ", got " + size2 + ")");
                this.show();
                JDDConsole.out.println("\n");
                throw new IOException("Size comparison failed");
            }
        }

        private void do_not() throws IOException {
            BDDTrace.this.checkEquality(this.ops, 1, "do_not");
            this.ret.bdd = BDDTrace.this.bdd.not(this.op1.bdd);
        }

        private void do_assign() throws IOException {
            BDDTrace.this.checkEquality(this.ops, 1, "do_assign");
            this.ret.bdd = this.op1.bdd;
        }

        private void do_or() {
            if (this.ops == 2) {
                this.ret.bdd = BDDTrace.this.bdd.or(this.op1.bdd, this.op2.bdd);
            } else {
                Enumeration e = this.operands.elements();
                while (e.hasMoreElements()) {
                    if (((TracedVariable)e.nextElement()).bdd != 1) continue;
                    this.ret.bdd = 1;
                    return;
                }
                int tmp = 0;
                Enumeration e2 = this.operands.elements();
                while (e2.hasMoreElements()) {
                    TracedVariable v = (TracedVariable)e2.nextElement();
                    int tmp2 = BDDTrace.this.bdd.ref(BDDTrace.this.bdd.or(tmp, v.bdd));
                    BDDTrace.this.bdd.deref(tmp);
                    tmp = tmp2;
                }
                this.ret.bdd = BDDTrace.this.bdd.deref(tmp);
            }
        }

        private void do_and() {
            if (this.ops == 2) {
                this.ret.bdd = BDDTrace.this.bdd.and(this.op1.bdd, this.op2.bdd);
            } else {
                Enumeration e = this.operands.elements();
                while (e.hasMoreElements()) {
                    if (((TracedVariable)e.nextElement()).bdd != 0) continue;
                    this.ret.bdd = 0;
                    return;
                }
                int tmp = 1;
                Enumeration e2 = this.operands.elements();
                while (e2.hasMoreElements()) {
                    TracedVariable v = (TracedVariable)e2.nextElement();
                    int tmp2 = BDDTrace.this.bdd.ref(BDDTrace.this.bdd.and(tmp, v.bdd));
                    BDDTrace.this.bdd.deref(tmp);
                    tmp = tmp2;
                }
                this.ret.bdd = BDDTrace.this.bdd.deref(tmp);
            }
        }

        private void do_nand() {
            if (this.ops == 2) {
                this.ret.bdd = BDDTrace.this.bdd.nand(this.op1.bdd, this.op2.bdd);
            } else {
                this.do_and();
                int tmp = BDDTrace.this.bdd.ref(this.ret.bdd);
                this.ret.bdd = BDDTrace.this.bdd.not(tmp);
                BDDTrace.this.bdd.deref(tmp);
            }
        }

        private void do_nor() {
            if (this.ops == 2) {
                this.ret.bdd = BDDTrace.this.bdd.nor(this.op1.bdd, this.op2.bdd);
            } else {
                this.do_or();
                int tmp = BDDTrace.this.bdd.ref(this.ret.bdd);
                this.ret.bdd = BDDTrace.this.bdd.not(tmp);
                BDDTrace.this.bdd.deref(tmp);
            }
        }

        private void do_xor() throws IOException {
            BDDTrace.this.check(this.ops == 2);
            this.ret.bdd = BDDTrace.this.bdd.xor(this.op1.bdd, this.op2.bdd);
        }

        private void do_xnor() throws IOException {
            BDDTrace.this.check(this.ops == 2);
            this.ret.bdd = BDDTrace.this.bdd.biimp(this.op1.bdd, this.op2.bdd);
        }

        private void do_ite() throws IOException {
            BDDTrace.this.check(this.ops == 3);
            this.ret.bdd = BDDTrace.this.bdd.ite(this.op1.bdd, this.op2.bdd, this.op3.bdd);
        }

        private void do_s2sp() throws IOException {
            BDDTrace.this.check(this.ops == 1);
            this.ret.bdd = BDDTrace.this.bdd.replace(this.op1.bdd, BDDTrace.this.s2sp);
        }

        private void do_sp2s() throws IOException {
            BDDTrace.this.check(this.ops == 1);
            this.ret.bdd = BDDTrace.this.bdd.replace(this.op1.bdd, BDDTrace.this.sp2s);
        }

        private void do_support() throws IOException {
            BDDTrace.this.check(this.ops == 1);
            this.ret.bdd = BDDTrace.this.bdd.support(this.op1.bdd);
        }

        private void do_exists() throws IOException {
            BDDTrace.this.check(this.ops == 2);
            this.ret.bdd = BDDTrace.this.bdd.exists(this.op2.bdd, this.op1.bdd);
        }

        private void do_forall() throws IOException {
            BDDTrace.this.check(this.ops == 2);
            this.ret.bdd = BDDTrace.this.bdd.forall(this.op2.bdd, this.op1.bdd);
        }

        private void do_restrict() throws IOException {
            BDDTrace.this.check(this.ops == 2);
            this.ret.bdd = BDDTrace.this.bdd.restrict(this.op1.bdd, this.op2.bdd);
        }

        private void do_relprod() throws IOException {
            BDDTrace.this.check(this.ops == 3);
            this.ret.bdd = BDDTrace.this.bdd.relProd(this.op2.bdd, this.op3.bdd, this.op1.bdd);
        }

        @Override
        public void show_code() {
            Enumeration e = this.operands.elements();
            TracedVariable v = (TracedVariable)e.nextElement();
            if (this.op.equals("=")) {
                JDDConsole.out.println("BDD " + this.ret.name + " = " + v.name + ";");
            } else {
                JDDConsole.out.print("BDD " + this.ret.name + " = " + v.name + "." + this.op);
                JDDConsole.out.print("(");
                boolean mode2 = this.op.equals("ite");
                int i = 0;
                i = 0;
                while (e.hasMoreElements()) {
                    v = (TracedVariable)e.nextElement();
                    if (mode2 && i != 0) {
                        JDDConsole.out.print(", ");
                    }
                    JDDConsole.out.print(v.name);
                    if (e.hasMoreElements() && !mode2) {
                        JDDConsole.out.print("." + this.op + "(");
                    }
                    ++i;
                }
                if (!mode2) {
                    for (int j = 1; j < i; ++j) {
                        JDDConsole.out.print(")");
                    }
                }
                JDDConsole.out.println(");");
            }
            if (this.op.equals("ite")) {
                JDDConsole.out.println("System.out.println(\"" + this.ret.name + " ==> \"+" + this.ret.name + ".nodeCount());");
            }
        }
    }

    class TracedCheckOperation
    extends TracedOperation {
        public TracedVariable t1;
        public TracedVariable t2;

        TracedCheckOperation() {
        }

        @Override
        public void execute() throws IOException {
            boolean equal;
            boolean bl = equal = this.t1.bdd == this.t2.bdd;
            if (this.size != -1) {
                boolean expected;
                boolean bl2 = expected = this.size != 0;
                if (equal != expected) {
                    throw new IOException("are_equal(" + this.t1.name + ", " + this.t2.name + ") failed. expected " + expected + ", got " + equal);
                }
            }
        }
    }

    class TracedPrintOperation
    extends TracedOperation {
        public TracedVariable v;
        public boolean graph;

        TracedPrintOperation() {
        }

        @Override
        public void execute() {
            if (this.graph) {
                BDDTrace.this.bdd.printDot(this.v.name, this.v.bdd);
            } else {
                JDDConsole.out.println(this.v.name + ":");
                BDDTrace.this.bdd.printSet(this.v.bdd);
            }
        }

        @Override
        public void show_code() {
            if (this.graph) {
                JDDConsole.out.println(this.v.name + ".printDot();");
            } else {
                JDDConsole.out.println(this.v.name + ".printSet();");
            }
        }
    }

    class TracedSaveOperation
    extends TracedOperation {
        public TracedVariable v;

        TracedSaveOperation() {
        }

        @Override
        public void execute() {
            try {
                BDDIO.saveBuDDy(BDDTrace.this.bdd, this.v.bdd, this.v.name + ".buddy");
                BDDIO.save(BDDTrace.this.bdd, this.v.bdd, this.v.name + ".bdd");
            }
            catch (IOException iOException) {
                // empty catch block
            }
        }

        @Override
        public void show_code() {
            JDDConsole.out.println("BDDIO.saveBuDDy(bdd, " + this.v.bdd + ",\"" + this.v.name + ".buddy\");");
        }
    }

    class TracedDebugOperation
    extends TracedOperation {
        public String text;

        TracedDebugOperation() {
        }

        @Override
        public void execute() {
            if (verbose) {
                JDDConsole.out.println(this.text);
            }
        }

        @Override
        public void show_code() {
            JDDConsole.out.println("//" + this.text);
        }
    }

    abstract class TracedOperation {
        public int index;
        public int size = -1;
        public String op;

        TracedOperation() {
        }

        public void show() {
        }

        public abstract void execute() throws IOException;

        public void show_code() {
        }
    }

    class TracedVariable {
        public String name;
        public int index;
        public int last_use;
        public int bdd;
        public boolean is_var = false;

        TracedVariable() {
        }

        public void show() {
            JDDConsole.out.print(this.name);
        }

        public void show(BDD bdd) {
            JDDConsole.out.print("\n\t");
            this.show();
            JDDConsole.out.printf("\n", new Object[0]);
            bdd.printSet(this.bdd);
        }
    }

    class TracedNames
    extends BDDNames {
        TracedNames() {
        }

        @Override
        public String variable(int n) {
            if (n < 0) {
                return "(none)";
            }
            TracedVariable t = (TracedVariable)BDDTrace.this.variables.elementAt(n);
            return t.name;
        }
    }
}

