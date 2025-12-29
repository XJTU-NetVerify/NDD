/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import jdd.bdd.CacheBase;
import jdd.bdd.NodeTable;
import jdd.util.Allocator;
import jdd.util.Configuration;
import jdd.util.JDDConsole;
import jdd.util.Options;
import jdd.util.math.Digits;
import jdd.util.math.HashFunctions;
import jdd.util.math.Prime;

public class SimpleCache
extends CacheBase {
    private int[] data;
    public int answer;
    public int hash_value;
    private int cache_bits;
    private int shift_bits;
    private int cache_mask;
    protected int members;
    protected int width;
    protected int bdds;
    protected int num_clears;
    protected int num_grows;
    protected int cache_size;
    protected long num_access;
    protected long hit;
    protected long miss;
    protected long last_hit;
    protected long last_access;

    public SimpleCache(String name, int size, int members, int bdds) {
        super(name);
        if (size < 32) {
            size = 32;
        }
        this.members = members;
        this.width = members + 1;
        this.bdds = bdds;
        this.cache_bits = Digits.closest_log2(size);
        this.shift_bits = 32 - this.cache_bits;
        this.cache_size = 1 << this.cache_bits;
        this.cache_mask = this.cache_size - 1;
        this.num_grows = 0;
        this.num_access = 0L;
        this.last_access = 0L;
        this.last_hit = 0L;
        this.miss = 0L;
        this.hit = 0L;
        this.num_clears = 0;
        this.cache_size = Prime.nextPrime(this.cache_size);
        this.data = Allocator.allocateIntArray(this.cache_size * this.width);
        this.clear_cache();
    }

    protected final int getOut(int i) {
        return this.data[i * this.width];
    }

    protected final void setOut(int i, int v) {
        this.data[i * this.width] = v;
    }

    protected final int getIn(int i, int member) {
        return this.data[i * this.width + member];
    }

    protected final void setIn(int i, int member, int v) {
        this.data[i * this.width + member] = v;
    }

    protected final void clear_cache() {
        int i = this.cache_size;
        while (i != 0) {
            this.invalidate(--i);
        }
    }

    protected final void invalidate(int number) {
        this.setIn(number, 1, -1);
    }

    protected final boolean isValid(int number) {
        return this.getIn(number, 1) != -1;
    }

    public long getMemoryUsage() {
        long ret = 0L;
        if (this.data != null) {
            ret += (long)(this.data.length * 4);
        }
        return ret;
    }

    public int getSize() {
        return this.cache_size;
    }

    protected boolean may_grow() {
        if (this.num_grows < Configuration.maxSimplecacheGrows) {
            long acs = this.num_access - this.last_access;
            if (acs * 100L < (long)(this.cache_size * Configuration.minSimplecacheAccessToGrow)) {
                return false;
            }
            int rate = (int)((double)(this.hit - this.last_hit) * 100.0 / (double)acs);
            if (rate > Configuration.minSimplecacheHitrateToGrow) {
                this.last_hit = this.hit;
                this.last_access = this.num_access;
                ++this.num_grows;
                return true;
            }
        }
        return false;
    }

    public void invalidate_cache() {
        this.clear_cache();
        ++this.num_clears;
    }

    public void free_or_grow() {
        if (this.may_grow()) {
            this.grow_and_invalidate_cache();
        } else {
            this.invalidate_cache();
        }
    }

    protected void grow_and_invalidate_cache() {
        ++this.cache_bits;
        --this.shift_bits;
        this.cache_size = 1 << this.cache_bits;
        this.cache_size = Prime.nextPrime(this.cache_size);
        this.cache_mask = this.cache_size - 1;
        this.data = null;
        this.data = Allocator.allocateIntArray(this.cache_size * this.width);
        if (Options.verbose) {
            JDDConsole.out.println("Cache " + this.getName() + " grown to " + this.cache_size + " entries");
        }
        this.invalidate_cache();
    }

    public void free_or_grow(NodeTable nt) {
        if (this.may_grow()) {
            this.grow_and_invalidate_cache();
        } else {
            this.invalidate_cache(nt);
        }
    }

    public void invalidate_cache(NodeTable nt) {
        this.invalidate_cache();
    }

    public void insert(int hash, int key1, int value) {
        this.setOut(hash, value);
        this.setIn(hash, 1, key1);
    }

    public void insert(int hash, int key1, int key2, int value) {
        this.setOut(hash, value);
        this.setIn(hash, 1, key1);
        this.setIn(hash, 2, key2);
    }

    public void insert(int hash, int key1, int key2, int key3, int value) {
        this.setOut(hash, value);
        this.setIn(hash, 1, key1);
        this.setIn(hash, 2, key2);
        this.setIn(hash, 3, key3);
    }

    void add(int key1, int value) {
        this.insert(this.good_hash(key1), key1, value);
    }

    void add(int key1, int key2, int value) {
        this.insert(this.good_hash(key1, key2), key1, key2, value);
    }

    void add(int key1, int key2, int key3, int value) {
        this.insert(this.good_hash(key1, key2, key3), key1, key2, key3, value);
    }

    public final boolean lookup(int a) {
        ++this.num_access;
        int hash = this.good_hash(a);
        if (this.getIn(hash, 1) == a) {
            ++this.hit;
            this.answer = this.getOut(hash);
            return true;
        }
        ++this.miss;
        this.hash_value = hash;
        return false;
    }

    public final boolean lookup(int a, int b) {
        ++this.num_access;
        int hash = this.good_hash(a, b);
        if (this.getIn(hash, 1) == a && this.getIn(hash, 2) == b) {
            ++this.hit;
            this.answer = this.getOut(hash);
            return true;
        }
        ++this.miss;
        this.hash_value = hash;
        return false;
    }

    public final boolean lookup(int a, int b, int c) {
        ++this.num_access;
        int hash = this.good_hash(a, b, c);
        if (this.getIn(hash, 1) == a && this.getIn(hash, 2) == b && this.getIn(hash, 3) == c) {
            ++this.hit;
            this.answer = this.getOut(hash);
            return true;
        }
        ++this.miss;
        this.hash_value = hash;
        return false;
    }

    protected final int good_hash(int i) {
        return i % this.cache_size;
    }

    protected final int good_hash(int i, int j) {
        return (HashFunctions.hash_prime(i, j) & Integer.MAX_VALUE) % this.cache_size;
    }

    protected final int good_hash(int i, int j, int k) {
        return (HashFunctions.hash_prime(i, j, k) & Integer.MAX_VALUE) % this.cache_size;
    }

    @Override
    public double computeLoadFactor() {
        if (this.data == null) {
            return 0.0;
        }
        int bins = 0;
        for (int i = 0; i < this.cache_size; ++i) {
            if (!this.isValid(i)) continue;
            ++bins;
        }
        return (double)(bins * 10000 / this.cache_size) / 100.0;
    }

    @Override
    public double computeHitRate() {
        if (this.num_access == 0L) {
            return 0.0;
        }
        return (double)((int)(this.hit * 10000L / this.num_access)) / 100.0;
    }

    @Override
    public long getAccessCount() {
        return this.num_access;
    }

    @Override
    public int getCacheSize() {
        return this.cache_size;
    }

    @Override
    public int getNumberOfClears() {
        return this.num_clears;
    }

    @Override
    public int getNumberOfPartialClears() {
        return 0;
    }

    @Override
    public int getNumberOfGrows() {
        return this.num_grows;
    }

    public void showStats() {
        if (this.num_access != 0L) {
            JDDConsole.out.printf("%s-cache: ld=%0.2f%% sz=%s acces=%s clrs=%d/0 hitr=%.2f%% #grow=%d\n", this.getName(), this.computeLoadFactor(), Digits.prettify(this.cache_size), Digits.prettify(this.num_access), this.num_clears, this.computeHitRate(), this.num_grows);
        }
    }

    public void show_tuple(int bdd) {
        JDDConsole.out.print("" + bdd + ":   " + this.getOut(bdd));
        for (int i = 0; i < this.members; ++i) {
            JDDConsole.out.print("\t" + this.getIn(bdd, 1 + i));
        }
        JDDConsole.out.printf("\n", new Object[0]);
    }

    public boolean check_cache(NodeTable nt) {
        for (int i = 0; i < this.cache_size; ++i) {
            if (!this.isValid(i)) continue;
            if (!nt.isValid(this.getOut(i))) {
                JDDConsole.out.println("Invalied cache output entry");
                this.show_tuple(i);
                return false;
            }
            for (int m = 0; m < this.bdds; ++m) {
                if (nt.isValid(this.getIn(i, m + 1))) continue;
                JDDConsole.out.println("Invalied cache member " + m + " entry");
                this.show_tuple(i);
                return false;
            }
        }
        return true;
    }
}

