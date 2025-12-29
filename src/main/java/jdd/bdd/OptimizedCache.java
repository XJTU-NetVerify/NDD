/*
 * Decompiled with CFR 0.152.
 */
package jdd.bdd;

import jdd.bdd.NodeTable;
import jdd.bdd.SimpleCache;
import jdd.util.JDDConsole;
import jdd.util.Test;
import jdd.util.math.Digits;

public final class OptimizedCache
extends SimpleCache {
    protected int possible_bins_count;
    protected int num_partial_clears;
    protected long partial_count;
    protected long partial_kept;
    protected long partial_given_up;
    private long access_last_gc;
    private int cache_not_used_count;

    public OptimizedCache(String name, int size, int members, int bdds) {
        super(name, size, members, bdds);
        Test.check(bdds <= 3, "BDD members cannot be more than 3 for this type of cache!");
        this.partial_kept = 0L;
        this.partial_count = 0L;
        this.possible_bins_count = 0;
        this.num_partial_clears = 0;
        this.access_last_gc = 0L;
        this.cache_not_used_count = 0;
        this.partial_given_up = 0L;
    }

    protected boolean shouldWipeUnusedCache() {
        this.cache_not_used_count = this.access_last_gc == this.num_access ? ++this.cache_not_used_count : 0;
        this.access_last_gc = this.num_access;
        return this.cache_not_used_count > 5;
    }

    @Override
    public void invalidate_cache() {
        if (this.possible_bins_count != 0) {
            super.invalidate_cache();
            this.possible_bins_count = 0;
        }
    }

    @Override
    protected void grow_and_invalidate_cache() {
        super.grow_and_invalidate_cache();
        this.possible_bins_count = 0;
    }

    @Override
    public void invalidate_cache(NodeTable nt) {
        if (this.bdds < 1) {
            Test.check(false, "Cannot partiall clean a non-bdd cache!");
        }
        if (this.possible_bins_count == 0) {
            return;
        }
        if (this.shouldWipeUnusedCache()) {
            ++this.partial_given_up;
            this.invalidate_cache();
            return;
        }
        int ok = 0;
        if (this.bdds == 3) {
            ok = this.partial_clean3(nt);
        } else if (this.bdds == 2) {
            ok = this.partial_clean2(nt);
        } else if (this.bdds == 1) {
            ok = this.partial_clean1(nt);
        }
        ++this.num_partial_clears;
        this.partial_count += (long)this.cache_size;
        this.partial_kept += (long)ok;
        this.possible_bins_count = ok;
    }

    private final int partial_clean3(NodeTable nt) {
        int ok = 0;
        int i = this.cache_size;
        while (i != 0) {
            if (!(this.isValid(--i) && nt.isValid(this.getIn(i, 1)) && nt.isValid(this.getIn(i, 2)) && nt.isValid(this.getIn(i, 3)) && nt.isValid(this.getOut(i)))) {
                this.invalidate(i);
                continue;
            }
            ++ok;
        }
        return ok;
    }

    private final int partial_clean2(NodeTable nt) {
        int ok = 0;
        int i = this.cache_size;
        while (i != 0) {
            if (!(this.isValid(--i) && nt.isValid(this.getIn(i, 1)) && nt.isValid(this.getIn(i, 2)) && nt.isValid(this.getOut(i)))) {
                this.invalidate(i);
                continue;
            }
            ++ok;
        }
        return ok;
    }

    private final int partial_clean1(NodeTable nt) {
        int ok = 0;
        int i = this.cache_size;
        while (i != 0) {
            if (!(this.isValid(--i) && nt.isValid(this.getIn(i, 1)) && nt.isValid(this.getOut(i)))) {
                this.invalidate(i);
                continue;
            }
            ++ok;
        }
        return ok;
    }

    @Override
    public void insert(int hash, int key1, int value) {
        super.insert(hash, key1, value);
        ++this.possible_bins_count;
    }

    @Override
    public void insert(int hash, int key1, int key2, int value) {
        super.insert(hash, key1, key2, value);
        ++this.possible_bins_count;
    }

    @Override
    public void insert(int hash, int key1, int key2, int key3, int value) {
        super.insert(hash, key1, key2, key3, value);
        ++this.possible_bins_count;
    }

    @Override
    public int getNumberOfPartialClears() {
        return this.num_partial_clears;
    }

    @Override
    public void showStats() {
        if (this.num_access != 0L) {
            JDDConsole.out.printf("%s-cache: ld=%.2f%% sz=%s acces=%s clrs=%d/%d ", this.getName(), this.computeLoadFactor(), Digits.prettify(this.cache_size), Digits.prettify(this.num_access), this.num_clears, this.num_partial_clears);
            if (this.partial_count > 0L) {
                double pck = (double)((int)(10000.0 * (double)this.partial_kept / (double)this.partial_count)) / 100.0;
                JDDConsole.out.printf("pclr=%.2f%% ", pck);
            }
            if (this.partial_given_up > 0L) {
                JDDConsole.out.printf("giveup=%d ", this.partial_given_up);
            }
            JDDConsole.out.printf("hitr=%.2f%% #grow=%d\n", this.computeHitRate(), this.num_grows);
        }
    }
}

