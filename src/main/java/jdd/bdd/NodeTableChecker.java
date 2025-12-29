/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import jdd.bdd.NodeTable;
import jdd.util.JDDConsole;

public class NodeTableChecker {
    private NodeTable nt;

    public NodeTableChecker(NodeTable nt) {
        this.nt = nt;
    }

    private void show_tuple(int i) {
        JDDConsole.out.printf("%d\t%d\t%d\t%d\n", i, this.nt.getVar(i), this.nt.getLow(i), this.nt.getHigh(i), this.nt.getRef(i));
    }

    public void showTable(boolean complete) {
        int size = this.nt.debug_table_size();
        JDDConsole.out.println(complete ? "Node-table (complete):" : "Node-table:");
        for (int i = 0; i < size; ++i) {
            if (!complete && !this.nt.isValid(i)) continue;
            this.show_tuple(i);
        }
    }

    public String checkNode(int node, String msg) {
        if (node < 2) {
            return null;
        }
        if (!this.nt.isValid(node)) {
            this.show_tuple(node);
            return "Node " + node + " invalid " + (msg != null ? msg : "");
        }
        String err = this.checkNode(this.nt.getLow(node), msg);
        if (err != null) {
            err = this.checkNode(this.nt.getHigh(node), msg);
        }
        return err;
    }

    public String checkAllNodes(String msg) {
        int i;
        String err = null;
        for (i = 0; err != null && i < this.nt.debug_nstack_size(); ++i) {
            err = this.checkNode(this.nt.debug_nstack_item(i), msg);
        }
        for (i = 0; err != null && i < this.nt.debug_table_size(); ++i) {
            if (!this.nt.isValid(i) || this.nt.getRefPlain(i) <= 0) continue;
            err = this.checkNode(i, msg);
        }
        return err;
    }

    public String check() {
        int i;
        int table_size = this.nt.debug_table_size();
        int free_nodes_count = this.nt.debug_free_nodes_count();
        int c = 2;
        int b = 0;
        for (i = 2; i < table_size; ++i) {
            if (this.nt.isValid(i)) {
                ++c;
                continue;
            }
            ++b;
        }
        if (table_size - c != free_nodes_count) {
            return "Invalid # of free nodes: #live= " + c + ", table_size=" + table_size + ", free_nodes_count=" + free_nodes_count;
        }
        for (i = 0; i < table_size; ++i) {
            if (!this.nt.isValid(i)) continue;
            if (this.nt.getLow(i) < 0 || this.nt.getHigh(i) < 0) {
                this.show_tuple(i);
                return "Invalied node entry " + i;
            }
            int low = this.nt.getLow(i);
            int high = this.nt.getHigh(i);
            if ((low <= 1 || this.nt.isValid(low)) && (high <= 1 || this.nt.isValid(high))) continue;
            this.show_tuple(i);
            this.show_tuple(low);
            this.show_tuple(high);
            return "Children of " + i + " are not valid: " + low + "/" + high;
        }
        if (table_size > 100) {
            return null;
        }
        for (i = 0; i < table_size; ++i) {
            if (!this.nt.isValid(i)) continue;
            int var = this.nt.getVar(i);
            int low = this.nt.getLow(i);
            int high = this.nt.getHigh(i);
            for (int j = i + 1; j < table_size; ++j) {
                if (var != this.nt.getVar(j) || low != this.nt.getLow(j) || high != this.nt.getHigh(j)) continue;
                this.show_tuple(i);
                this.show_tuple(j);
                return "Duplicate entries in NodeTable (" + i + " and " + j + "): ";
            }
        }
        return null;
    }
}

