/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import jdd.bdd.CacheBase;
import jdd.bdd.NodeTable;
import jdd.util.Allocator;
import jdd.util.Array;
import jdd.util.Configuration;
import jdd.util.JDDConsole;
import jdd.util.math.Digits;

public final class DoubleCache
extends CacheBase {
    private int[] in;
    private double[] out;
    public int hash_value;
    public double answer;
    private int cache_bits;
    private int shift_bits;
    private int cache_size;
    private int cache_mask;
    private int possible_bins_count;
    private int num_clears;
    private int num_partial_clears;
    private int num_grows;
    private long num_access;
    private long partial_count;
    private long partial_kept;
    private long hit;
    private long miss;
    private long last_hit;
    private long last_access;

    public DoubleCache(String name, int size) {
        super(name);
        this.cache_bits = size < 32 ? 5 : Digits.closest_log2(size);
        this.shift_bits = 32 - this.cache_bits;
        this.cache_size = 1 << this.cache_bits;
        this.cache_mask = this.cache_size - 1;
        this.num_grows = 0;
        this.num_access = 0L;
        this.last_access = 0L;
        this.last_hit = 0L;
        this.miss = 0L;
        this.hit = 0L;
        this.partial_kept = 0L;
        this.partial_count = 0L;
        this.possible_bins_count = 0;
        this.num_partial_clears = 0;
        this.num_clears = 0;
        this.in = Allocator.allocateIntArray(this.cache_size);
        this.out = Allocator.allocateDoubleArray(this.cache_size);
        Array.set(this.in, -1);
    }

    public int getSize() {
        return this.cache_size;
    }

    public long getMemoryUsage() {
        long ret = 0L;
        if (this.in != null) {
            ret += (long)(this.in.length * 4);
        }
        if (this.out != null) {
            ret += (long)(this.out.length * 4);
        }
        return ret;
    }

    private boolean may_grow() {
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
        Array.set(this.in, -1);
        this.possible_bins_count = 0;
        ++this.num_clears;
    }

    public void free_or_grow() {
        if (this.may_grow()) {
            this.grow_and_invalidate_cache();
        } else {
            this.invalidate_cache();
        }
    }

    private void grow_and_invalidate_cache() {
        ++this.cache_bits;
        --this.shift_bits;
        this.cache_size = 1 << this.cache_bits;
        this.cache_mask = this.cache_size - 1;
        this.in = null;
        this.in = Allocator.allocateIntArray(this.cache_size);
        this.out = null;
        this.out = Allocator.allocateDoubleArray(this.cache_size);
        Array.set(this.in, -1);
        this.possible_bins_count = 0;
        ++this.num_clears;
    }

    public void free_or_grow(NodeTable nt) {
        if (this.may_grow()) {
            this.grow_and_invalidate_cache();
        } else {
            this.invalidate_cache(nt);
        }
    }

    public void invalidate_cache(NodeTable nt) {
        if (this.possible_bins_count == 0) {
            return;
        }
        ++this.num_partial_clears;
        int ok = 0;
        for (int i = 0; i < this.cache_size; ++i) {
            if (this.in[i] == -1 || !nt.isValid(this.in[i])) {
                this.in[i] = -1;
                continue;
            }
            ++ok;
        }
        this.partial_count += (long)this.cache_size;
        this.partial_kept += (long)ok;
        this.possible_bins_count = ok;
    }

    public void insert(int hash, int key1, double value) {
        ++this.possible_bins_count;
        this.in[hash] = key1;
        this.out[hash] = value;
    }

    public final boolean lookup(int a) {
        ++this.num_access;
        int hash = a & this.cache_mask;
        if (this.in[hash] == a) {
            ++this.hit;
            this.answer = this.out[hash];
            return true;
        }
        ++this.miss;
        this.hash_value = hash;
        return false;
    }

    private final int good_hash(int i) {
        return i & this.cache_mask;
    }

    @Override
    public double computeLoadFactor() {
        int bins = 0;
        for (int i = 0; i < this.cache_size; ++i) {
            if (this.in[i] == -1) continue;
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
        return this.num_partial_clears;
    }

    @Override
    public int getNumberOfGrows() {
        return this.num_grows;
    }

    public void showStats() {
        if (this.num_access != 0L) {
            JDDConsole.out.printf("%s-cache: ld=%.2f%% sz=%s acces=%s clrs=%d/%d ", this.getName(), this.computeLoadFactor(), Digits.prettify(this.cache_size), Digits.prettify(this.num_access), this.num_clears, this.num_partial_clears);
            if (this.partial_count > 0L) {
                double pck = (double)((int)(10000.0 * (double)this.partial_kept / (double)this.partial_count)) / 100.0;
                JDDConsole.out.printf("pclr=%.2f%% ", pck);
            }
            JDDConsole.out.printf("hitr=%.2f%% #grow=%d\n", this.computeHitRate(), this.num_grows);
        }
    }
}

