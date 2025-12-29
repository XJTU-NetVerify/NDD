/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import java.util.Collection;
import java.util.LinkedList;
import jdd.bdd.CacheBase;
import jdd.bdd.NodeStack;
import jdd.bdd.debug.BDDDebuger;
import jdd.util.Allocator;
import jdd.util.Array;
import jdd.util.Configuration;
import jdd.util.JDDConsole;
import jdd.util.Options;
import jdd.util.math.HashFunctions;

public class NodeTable {
    public static int mkCount = 0;
    public static final int NODE_MARK = Integer.MIN_VALUE;
    public static final int NODE_UNMARK = Integer.MAX_VALUE;
    public static final short MAX_REFCOUNT = Short.MAX_VALUE;
    private static final int NODE_WIDTH = 3;
    private static final int OFFSET_VAR = 1;
    private static final int OFFSET_LOW = 0;
    private static final int OFFSET_HIGH = 2;
    private static final int LIST_WIDTH = 2;
    private static final int OFFSET_NEXT = 0;
    private static final int OFFSET_PREV = 1;
    private Collection<BDDDebuger> debugers;
    protected int table_size;
    protected int stat_nt_grow;
    protected int dead_nodes;
    protected int nodesminfree;
    private int[] t_nodes;
    private int[] t_list;
    private short[] t_ref;
    private int first_free_node;
    private int free_nodes_count;
    private boolean stack_marking_enabled;
    protected int stat_gc_count;
    protected int stat_lookup_count;
    protected long stat_gc_freed;
    protected long stat_gc_time;
    protected long stat_grow_time;
    protected long stat_notify_time;
    protected long ht_chain;
    protected final NodeStack nstack = new NodeStack(32);
    private final NodeStack mstack = new NodeStack(32);

    public NodeTable(int nodesize) {
        this.debugers = new LinkedList<>();
        if (nodesize < 100) {
            nodesize = 100;
        }
        this.table_size = nodesize;
        this.t_ref = Allocator.allocateShortArray(this.table_size);
        this.t_nodes = Allocator.allocateIntArray(this.table_size * 3);
        this.t_list = Allocator.allocateIntArray(this.table_size * 2);
        this.first_free_node = 2;
        this.free_nodes_count = nodesize - 2;
        for (int i = 0; i < nodesize; ++i) {
            this.invalidate(i);
            this.setPrev(i, 0);
            this.setNext(i, i + 1);
        }
        this.setNext(nodesize - 1, 0);
        this.setAll(0, -1, 0, 0, (short)Short.MAX_VALUE);
        this.setAll(1, -1, 1, 1, (short)Short.MAX_VALUE);
        this.update_grow_parameters();
        this.stat_nt_grow = 0;
        this.dead_nodes = 0;
        this.stat_lookup_count = 0;
        this.stat_gc_count = 0;
        this.stat_notify_time = 0L;
        this.stat_grow_time = 0L;
        this.stat_gc_time = 0L;
        this.stat_gc_freed = 0L;
        this.ht_chain = 0L;
        this.stack_marking_enabled = false;
    }

    public void cleanup() {
        this.stopDebuggers();
        this.t_ref = null;
        this.t_nodes = null;
        this.t_list = null;
    }

    protected void tree_depth_changed(int n) {
        this.mstack.grow(n * 4 + 3);
    }

    private final int compute_hash(int i, int l, int h) {
        return (HashFunctions.hash_prime(i, l, h) & Integer.MAX_VALUE) % this.table_size;
    }

    private final int compute_increase_limit(int current_size) {
        if (Configuration.nodetableSmallSize <= 0 || Configuration.nodetableLargeSize <= 0) {
            return current_size;
        }
        if (current_size <= Configuration.nodetableSmallSize) {
            return Configuration.nodetableGrowMax;
        }
        if (current_size >= Configuration.nodetableLargeSize) {
            return Configuration.nodetableGrowMin;
        }
        if (Configuration.nodetableLargeSize == Configuration.nodetableSmallSize) {
            return (Configuration.nodetableGrowMax + Configuration.nodetableGrowMin) / 2;
        }
        return Configuration.nodetableGrowMax - (current_size - Configuration.nodetableSmallSize) * (Configuration.nodetableGrowMax - Configuration.nodetableGrowMin) / (Configuration.nodetableLargeSize - Configuration.nodetableSmallSize);
    }

    protected void post_removal_callbak() {
    }

    protected void pre_removal_callback() {
    }

    protected final void signal_removed() {
        long time = System.currentTimeMillis();
        this.post_removal_callbak();
        this.stat_notify_time += System.currentTimeMillis() - time;
    }

    protected final void signal_pre_removal() {
        long time = System.currentTimeMillis();
        this.pre_removal_callback();
        this.stat_notify_time += System.currentTimeMillis() - time;
    }

    public int gc() {
        return this.gc(true);
    }

    private int gc(boolean call_callback) {
        if (Options.gc_log) {
            JDDConsole.out.printf("[JDD GC] Start: table_size=%d, free_nodes=%d, dead_nodes=%d\n",
                this.table_size, this.free_nodes_count, this.dead_nodes);
        }
        if (call_callback) {
            this.signal_pre_removal();
        }
        long time = System.currentTimeMillis();
        ++this.stat_gc_count;
        this.mark_nodes_in_use();
        int old_free = this.free_nodes_count;
        this.free_nodes_count = 0;
        this.first_free_node = 0;
        int i = this.table_size;
        while (i > 2) {
            if (this.isValid(--i) && this.isNodeMarked(i)) {
                this.unmark_node(i);
                int pos = this.compute_hash(this.getVar(i), this.getLow(i), this.getHigh(i));
                this.connect_list(i, pos);
                continue;
            }
            this.invalidate(i);
            this.setNext(i, this.first_free_node);
            this.first_free_node = i;
            ++this.free_nodes_count;
        }
        if (call_callback) {
            this.signal_removed();
        }
        this.stat_gc_time += System.currentTimeMillis() - time;
        int new_free = this.free_nodes_count - old_free;
        this.stat_gc_freed += (long)new_free;
        if (Options.gc_log) {
            JDDConsole.out.printf("[JDD GC] End: #%d, freed=%d, free_nodes=%d, time=%dms\n",
                this.stat_gc_count, new_free, this.free_nodes_count, System.currentTimeMillis() - time);
        }
        if (Options.verbose) {
            JDDConsole.out.printf("Garbage collection #%d: %d nodes, %d freed, time=%d+%d\n", this.stat_gc_count, this.table_size, new_free, this.stat_gc_time, this.stat_notify_time);
        }
        return new_free;
    }

    private final void mark_nodes_in_use() {
        int i;
        int tos = this.nstack.getTOS();
        int[] stack = this.nstack.getData();
        for (i = 0; i < tos; ++i) {
            this.mark_tree(stack[i]);
        }
        i = this.table_size;
        while (i != 0) {
            if (this.isValid(--i) && this.getRefPlain(i) > 0) {
                this.mark_tree(i);
            }
            this.setPrev(i, 0);
        }
    }

    protected void grow() {
        if (this.dead_nodes > 0 || this.table_size > Configuration.nodetableSimpleDeadcountThreshold) {
            this.signal_pre_removal();
            int got = this.gc(false);
            this.dead_nodes = 0;
            if (got >= this.nodesminfree) {
                this.signal_removed();
                return;
            }
        }
        this.signal_pre_removal();
        long time = System.currentTimeMillis();
        ++this.stat_nt_grow;
        int new_size = this.table_size + this.compute_increase_limit(this.nodesminfree);
        int old_size = this.table_size;
        this.resize(new_size);
        this.table_size = new_size;
        this.free_nodes_count = 0;
        this.first_free_node = 0;
        int i = new_size;
        while (i > old_size) {
            this.invalidate(--i);
            this.setPrev(i, 0);
            this.setNext(i, this.first_free_node);
            this.first_free_node = i;
            ++this.free_nodes_count;
        }
        this.clearPrev(0, old_size);
        i = old_size;
        while (i > 2) {
            if (this.isValid(--i)) {
                int hash = this.compute_hash(this.getVar(i), this.getLow(i), this.getHigh(i));
                this.connect_list(i, hash);
                continue;
            }
            this.setNext(i, this.first_free_node);
            this.first_free_node = i;
            ++this.free_nodes_count;
        }
        this.update_grow_parameters();
        this.signal_removed();
        time = System.currentTimeMillis() - time;
        this.stat_grow_time += time;
        if (Options.verbose) {
            JDDConsole.out.printf("Node-table grown #%d: %d -> %d nodes, time=%d\n", this.stat_nt_grow, old_size, new_size, this.stat_grow_time);
        }
    }

    public int add(int v, int l, int h) {
        int hash = this.compute_hash(v, l, h);
        int curr = this.getPrev(hash);
        ++this.stat_lookup_count;
        while (curr != 0) {
            if (this.match_table(curr, v, l, h)) {
                return curr;
            }
            curr = this.getNext(curr);
            ++this.ht_chain;
        }
        if (this.free_nodes_count < 2) {
            this.grow();
            hash = this.compute_hash(v, l, h);
        }
        ++mkCount;
        curr = this.first_free_node;
        this.first_free_node = this.getNext(this.first_free_node);
        --this.free_nodes_count;
        this.setAll(curr, v, l, h, (short)-1);
        this.connect_list(curr, hash);
        return curr;
    }

    public Collection<CacheBase> addDebugger(BDDDebuger d) {
        this.debugers.add(d);
        return new LinkedList<>();
    }

    private void stopDebuggers() {
        for (BDDDebuger d : this.debugers) {
            d.stop();
        }
    }

    protected void update_grow_parameters() {
        this.nodesminfree = Math.min(this.table_size * Configuration.minFreeNodesProcent / 100, Configuration.maxNodeFree - 1);
    }

    private void resize(int new_size) {
        this.t_ref = Array.resize(this.t_ref, this.table_size, new_size);
        try {
            this.t_nodes = Array.resize(this.t_nodes, 3 * this.table_size, 3 * new_size);
            this.t_list = Array.resize(this.t_list, 2 * this.table_size, 2 * new_size);
        }
        catch (OutOfMemoryError e) {
            this.fatal(e, "NodeTable.resize failed...");
        }
    }

    public final int ref(int bdd) {
        short ref = this.getRefPlain(bdd);
        if (ref == -1) {
            ref = 1;
        } else if (ref == 0) {
            ref = 1;
            --this.dead_nodes;
        } else if (ref != Short.MAX_VALUE) {
            ref = (short)(ref + 1);
        }
        this.setRef(bdd, ref);
        return bdd;
    }

    public final int deref(int bdd) {
        short ref = this.getRefPlain(bdd);
        if (ref == 1) {
            ref = 0;
            ++this.dead_nodes;
        } else if (ref != Short.MAX_VALUE && ref > 0) {
            ref = (short)(ref - 1);
        }
        this.setRef(bdd, ref);
        return bdd;
    }

    public final void saturate(int bdd) {
        this.setRef(bdd, (short)Short.MAX_VALUE);
    }

    final short getRefPlain(int bdd) {
        return this.t_ref[bdd];
    }

    private final void setRef(int bdd, short r) {
        this.t_ref[bdd] = r;
    }

    public final short getRef(int bdd) {
        if (this.t_ref[bdd] == -1) {
            return 0;
        }
        return this.t_ref[bdd];
    }

    private final void setVar(int bdd, int v) {
        this.t_nodes[1 + 3 * bdd] = v;
    }

    private final void setLow(int bdd, int v) {
        this.t_nodes[0 + 3 * bdd] = v;
    }

    private final void setHigh(int bdd, int v) {
        this.t_nodes[2 + 3 * bdd] = v;
    }

    public final int getVar(int bdd) {
        return this.t_nodes[1 + 3 * bdd];
    }

    public final int getLow(int bdd) {
        return this.t_nodes[0 + 3 * bdd];
    }

    public final int getHigh(int bdd) {
        return this.t_nodes[2 + 3 * bdd];
    }

    protected final int getVarUnmasked(int bdd) {
        return this.t_nodes[1 + 3 * bdd] & Integer.MAX_VALUE;
    }

    public final boolean isValid(int bdd) {
        return this.t_nodes[1 + 3 * bdd] != -1;
    }

    protected final void invalidate(int bdd) {
        this.t_nodes[1 + 3 * bdd] = -1;
    }

    protected final void setAll(int bdd, int v, int l, int h, short r) {
        this.t_nodes[3 * bdd + 1] = v;
        this.t_nodes[3 * bdd + 0] = l;
        this.t_nodes[3 * bdd + 2] = h;
        this.t_ref[bdd] = r;
    }

    protected final void setAll(int bdd, int v, int l, int h) {
        this.t_nodes[3 * bdd + 1] = v;
        this.t_nodes[3 * bdd + 0] = l;
        this.t_nodes[3 * bdd + 2] = h;
    }

    protected final boolean match_table(int bdd, int var, int low, int high) {
        int offset = bdd * 3;
        return this.t_nodes[offset + 1] == var && this.t_nodes[offset + 0] == low && this.t_nodes[offset + 2] == high;
    }

    private final void setNext(int bdd, int v) {
        this.t_list[0 + 2 * bdd] = v;
    }

    private final void setPrev(int bdd, int v) {
        this.t_list[1 + 2 * bdd] = v;
    }

    private final int getNext(int bdd) {
        return this.t_list[0 + 2 * bdd];
    }

    private final int getPrev(int bdd) {
        return this.t_list[1 + 2 * bdd];
    }

    private final void clearPrev(int from, int upto) {
        upto = upto * 2 + 1;
        for (from = from * 2 + 1; from < upto; from += 2) {
            this.t_list[from] = 0;
        }
    }

    private final void connect_list(int a, int b) {
        int o1 = a * 2;
        int o2 = b * 2;
        this.t_list[o1 + 0] = this.t_list[o2 + 1];
        this.t_list[o2 + 1] = a;
    }

    public void enableStackMarking() {
        this.stack_marking_enabled = true;
    }

    public final void mark_tree(int bdd) {
        if (this.stack_marking_enabled) {
            this.mark_tree_stack(bdd);
        } else {
            this.mark_tree_rec(bdd);
        }
    }

    private final void mark_tree_rec(int bdd) {
        if (bdd < 2) {
            return;
        }
        if (this.isNodeMarked(bdd)) {
            return;
        }
        this.mark_node(bdd);
        this.mark_tree(this.getLow(bdd));
        this.mark_tree_rec(this.getHigh(bdd));
    }

    private final void mark_tree_stack(int bdd) {
        if (bdd < 2) {
            return;
        }
        this.mstack.reset();
        this.mstack.push(bdd);
        this.mark_node(bdd);
        while (this.mstack.getTOS() > 0) {
            int next = this.mstack.pop();
            int tmp = this.getLow(next);
            if (tmp > 1 && !this.isNodeMarked(tmp)) {
                this.mark_node(tmp);
                this.mstack.push(tmp);
            }
            if ((tmp = this.getHigh(next)) <= 1 || this.isNodeMarked(tmp)) continue;
            this.mark_node(tmp);
            this.mstack.push(tmp);
        }
    }

    public final void unmark_tree(int bdd) {
        if (bdd < 2) {
            return;
        }
        if (!this.isNodeMarked(bdd)) {
            return;
        }
        this.unmark_node(bdd);
        this.unmark_tree(this.getLow(bdd));
        this.unmark_tree(this.getHigh(bdd));
    }

    public final void mark_node(int bdd) {
        int n = 1 + 3 * bdd;
        this.t_nodes[n] = this.t_nodes[n] | Integer.MIN_VALUE;
    }

    public final void unmark_node(int bdd) {
        int n = 1 + 3 * bdd;
        this.t_nodes[n] = this.t_nodes[n] & Integer.MAX_VALUE;
    }

    public final boolean isNodeMarked(int bdd) {
        return (this.t_nodes[1 + 3 * bdd] & Integer.MIN_VALUE) != 0;
    }

    public long getMemoryUsage() {
        long ret = 0L;
        if (this.t_nodes != null) {
            ret += (long)(this.t_nodes.length * 4);
        }
        if (this.t_list != null) {
            ret += (long)(this.t_list.length * 4);
        }
        if (this.t_ref != null) {
            ret += (long)(this.t_ref.length * 2);
        }
        if (this.nstack != null) {
            ret += (long)(this.nstack.getCapacity() * 4);
        }
        if (this.mstack != null) {
            ret += (long)(this.mstack.getCapacity() * 4);
        }
        return ret;
    }

    public int debug_nstack_size() {
        return this.nstack.getTOS();
    }

    public int debug_nstack_item(int index) {
        return this.nstack.getData()[index];
    }

    public int debug_table_size() {
        return this.table_size;
    }

    public int debug_free_nodes_count() {
        return this.free_nodes_count;
    }

    public int debug_compute_free_nodes_count() {
        int ret = 0;
        int root = this.first_free_node;
        while (root != 0) {
            ++ret;
            root = this.getNext(root);
        }
        return ret;
    }

    public void fatal(Error e, String message) {
        JDDConsole.out.printf("FATAL ERROR: %s\n", message);
        if (e != null) {
            JDDConsole.out.printf("ERROR: %s\n", e);
        }
        System.exit(20);
    }

    public int debug_compute_root_nodes() {
        int c = 0;
        for (int i = 0; i < this.table_size; ++i) {
            if (!this.isValid(i) || this.getRef(i) <= 0 || this.getRef(i) == Short.MAX_VALUE) continue;
            ++c;
        }
        return c;
    }

    public void showStats() {
        JDDConsole.out.printf("NT nodes=%d free=%d #grow=%d grow-time=%d dead=%d root=%d\n", this.table_size, this.free_nodes_count, this.stat_nt_grow, this.stat_grow_time, this.dead_nodes, this.debug_compute_root_nodes());
        JDDConsole.out.printf("HT chain=%d access=%d\n", this.ht_chain, this.stat_lookup_count);
        JDDConsole.out.printf("GC count=%d #freed=%d time=%d signal-time=%d\n", this.stat_gc_count, this.stat_gc_freed, this.stat_gc_time, this.stat_notify_time);
    }
}
