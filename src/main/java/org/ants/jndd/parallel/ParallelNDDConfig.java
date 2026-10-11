package org.ants.jndd.parallel;

/** Immutable native runtime options. All task layers share the worker pool. */
public final class ParallelNDDConfig {
    public final int workers, cacheEntries, taskThreshold, chunkSize, spawnDepth, gcInterval;
    public final long memoryBytes;
    public final boolean nddParallel, labelParallel, batchParallel;

    public ParallelNDDConfig(int workers) {
        this(workers, 512L << 20);
    }

    public ParallelNDDConfig(int workers, long memoryBytes) {
        this(workers, memoryBytes, 262144, 4, 2, 4, 128, true, false, true);
    }

    public ParallelNDDConfig(int workers, long memoryBytes, int cacheEntries,
            int taskThreshold, int chunkSize, int spawnDepth, int gcInterval,
            boolean nddParallel, boolean labelParallel, boolean batchParallel) {
        if (workers < 1 || workers > 256 || memoryBytes < (4L << 20)
                || cacheEntries < 1 || (cacheEntries & (cacheEntries - 1)) != 0
                || taskThreshold < 1 || chunkSize < 1 || spawnDepth < 0 || spawnDepth > 32
                || gcInterval < 0) {
            throw new IllegalArgumentException("Invalid native runtime configuration");
        }
        this.workers = workers;
        this.memoryBytes = memoryBytes;
        this.cacheEntries = cacheEntries;
        this.taskThreshold = taskThreshold;
        this.chunkSize = chunkSize;
        this.spawnDepth = spawnDepth;
        this.gcInterval = gcInterval;
        this.nddParallel = nddParallel;
        this.labelParallel = labelParallel;
        this.batchParallel = batchParallel;
    }
}
