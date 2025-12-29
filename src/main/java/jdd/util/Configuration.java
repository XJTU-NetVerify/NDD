/*
 * Decompiled with CFR 0.152.
 */
package jdd.util;

public class Configuration {
    public static final int MAX_KEEP_UNUSED_PARTIAL_CACHE = 5;
    public static final int MIN_NODETABLE_SIZE = 100;
    public static final int MIN_CACHE_SIZE = 32;
    public static int DEFAULT_NODETABLE_SIMPLE_DEADCOUNT_THRESHOLD;
    public static final int DEFAULT_NODETABLE_SMALL_SIZE = 200000;
    public static final int DEFAULT_NODETABLE_LARGE_SIZE = 4000000;
    public static final int DEFAULT_NODETABLE_GROW_MIN = 50000;
    public static final int DEFAULT_NODETABLE_GROW_MAX = 300000;
    public static final int DEFAULT_BDD_OPCACHE_DIV = 1;
    public static final int DEFAULT_BDD_NEGCACHE_DIV = 2;
    public static final int DEFAULT_BDD_ITECACHE_DIV = 4;
    public static final int DEFAULT_BDD_QUANTCACHE_DIV = 3;
    public static final int DEFAULT_BDD_RELPRODCACHE_DIV = 2;
    public static final int DEFAULT_BDD_REPLACECACHE_DIV = 3;
    public static final int DEFAULT_BDD_SATCOUNT_DIV = 8;
    public static final int DEFAULT_MAX_NODE_INCREASE = 100000;
    public static final int DEFAULT_MIN_FREE_NODES_PROCENT = 20;
    public static final int DEFAULT_MAX_NODE_FREE = 100000;
    public static final int DEFAULT_MAX_SIMPLECACHE_GROWS = 5;
    public static final int DEFAULT_MIN_SIMPLECACHE_HITRATE_TO_GROW = 40;
    public static final int DEFAULT_MIN_SIMPLECACHE_ACCESS_TO_GROW = 15;
    public static final byte DEFAULT_CACHEENTRY_STICKY_HITS = 16;
    public static final int DEFAULT_MAX_CACHE_GROWS = 3;
    public static final int DEFAULT_MIN_CACHE_LOADFACTOR_TO_GROW = 95;
    public static int nodetableSimpleDeadcountThreshold;
    public static int nodetableSmallSize;
    public static int nodetableLargeSize;
    public static int nodetableGrowMin;
    public static int nodetableGrowMax;
    public static final int DEFAULT_BDD_CACHE_SIZE = 1000;
    public static int bddOpcacheDiv;
    public static int bddNegcacheDiv;
    public static int bddItecacheDiv;
    public static int bddQuantcacheDiv;
    public static final int bddRelprodcacheDiv = 2;
    public static final int bddReplacecacheDiv = 3;
    public static final int bddSatcountDiv = 8;
    public static final int DEFAULT_ZDD_CACHE_SIZE = 1000;
    public static final int ZDD_UNARY_CACHE_DIV = 2;
    public static final int ZDD_BINARY_CACHE_DIV = 2;
    public static final int ZDD_UNATE_CACHE_DIV = 2;
    public static final int ZDD_CSP_CACHE_DIV = 2;
    public static final int ZDD_GRAPH_CACHE_DIV = 2;
    public static int zddUnaryCacheDiv;
    public static int zddBinaryCacheDiv;
    public static int zddUnateCacheDiv;
    public static int zddCSPCacheDiv;
    public static int zddGraphCacheDiv;
    public static int maxNodeIncrease;
    public static int minFreeNodesProcent;
    public static int maxNodeFree;
    public static int maxSimplecacheGrows;
    public static int minSimplecacheHitrateToGrow;
    public static int minSimplecacheAccessToGrow;
    public static final int maxCacheGrows = 3;
    public static final int minCacheLoadfactorToGrow = 95;
    public static byte cacheentryStickyHits;

    static {
        nodetableSimpleDeadcountThreshold = DEFAULT_NODETABLE_SIMPLE_DEADCOUNT_THRESHOLD = 20000;
        nodetableSmallSize = 200000;
        nodetableLargeSize = 4000000;
        nodetableGrowMin = 50000;
        nodetableGrowMax = 300000;
        bddOpcacheDiv = 1;
        bddNegcacheDiv = 2;
        bddItecacheDiv = 4;
        bddQuantcacheDiv = 3;
        zddUnaryCacheDiv = 2;
        zddBinaryCacheDiv = 2;
        zddUnateCacheDiv = 2;
        zddCSPCacheDiv = 2;
        zddGraphCacheDiv = 2;
        maxNodeIncrease = 100000;
        minFreeNodesProcent = 20;
        maxNodeFree = 100000;
        maxSimplecacheGrows = 5;
        minSimplecacheHitrateToGrow = 40;
        minSimplecacheAccessToGrow = 15;
        cacheentryStickyHits = (byte)16;
    }
}

