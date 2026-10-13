package org.ants.mtpndd;

import java.util.Objects;

/**
 * Configuration parameters used while initialising the native MTPNDD runtime.
 */
public final class MTPNDDConfig {
    private final int workers;
    private final long laceDequeSize;
    private final long bddNodeTableSize;
    private final long mtpnddNodeTableSize;
    private final long operationCacheSize;
    private final double quickGrowthThreshold;
    private final long edgeBucketCount;
    private final long nodetableBucketCount;
    private final long nodeSlabCapacity;
    private final long edgeEntrySlabCapacity;
    private final long nodetableEntrySlabCapacity;
    private final long edgeMapSlabCapacity;

    private MTPNDDConfig(Builder builder) {
        this.workers = builder.workers;
        this.laceDequeSize = builder.laceDequeSize;
        this.bddNodeTableSize = builder.bddNodeTableSize;
        this.mtpnddNodeTableSize = builder.mtpnddNodeTableSize;
        this.operationCacheSize = builder.operationCacheSize;
        this.quickGrowthThreshold = builder.quickGrowthThreshold;
        this.edgeBucketCount = builder.edgeBucketCount;
        this.nodetableBucketCount = builder.nodetableBucketCount;
        this.nodeSlabCapacity = builder.nodeSlabCapacity;
        this.edgeEntrySlabCapacity = builder.edgeEntrySlabCapacity;
        this.nodetableEntrySlabCapacity = builder.nodetableEntrySlabCapacity;
        this.edgeMapSlabCapacity = builder.edgeMapSlabCapacity;
    }

    public int workers() {
        return workers;
    }

    public long laceDequeSize() {
        return laceDequeSize;
    }

    public long bddNodeTableSize() {
        return bddNodeTableSize;
    }

    public long mtpnddNodeTableSize() {
        return mtpnddNodeTableSize;
    }

    public long operationCacheSize() {
        return operationCacheSize;
    }

    public double quickGrowthThreshold() {
        return quickGrowthThreshold;
    }

    public long edgeBucketCount() {
        return edgeBucketCount;
    }

    public long nodetableBucketCount() {
        return nodetableBucketCount;
    }
    public long nodeSlabCapacity() {
        return nodeSlabCapacity;
    }

    public long edgeEntrySlabCapacity() {
        return edgeEntrySlabCapacity;
    }

    public long nodetableEntrySlabCapacity() {
        return nodetableEntrySlabCapacity;
    }

    public long edgeMapSlabCapacity() {
        return edgeMapSlabCapacity;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder exposing the handful of sizing parameters required for the PAL.
     */
    public static final class Builder {
        // 0 means "auto" in native config (use Lace default worker count).
        private int workers = 0;
        private long laceDequeSize = 4096L;
        private long bddNodeTableSize = 1L << 24;
        private long mtpnddNodeTableSize = 1L << 22;
        private long operationCacheSize = 1L << 20;
        private double quickGrowthThreshold = Double.NaN;
        private long edgeBucketCount = -1;
        private long nodetableBucketCount = -1;
        private long nodeSlabCapacity = -1;
        private long edgeEntrySlabCapacity = -1;
        private long nodetableEntrySlabCapacity = -1;
        private long edgeMapSlabCapacity = -1;

        private Builder() {}

        /**
         * Sets worker count; 0 lets native pick the worker count automatically.
         */
        public Builder workers(int workers) {
            if (workers < 0) {
                throw new IllegalArgumentException("workers must be zero or positive");
            }
            this.workers = workers;
            return this;
        }

        public Builder laceDequeSize(long laceDequeSize) {
            this.laceDequeSize = validatePositive(laceDequeSize, "laceDequeSize");
            return this;
        }

        public Builder bddNodeTableSize(long size) {
            this.bddNodeTableSize = validatePositive(size, "bddNodeTableSize");
            return this;
        }

        public Builder mtpnddNodeTableSize(long size) {
            this.mtpnddNodeTableSize = validatePositive(size, "mtpnddNodeTableSize");
            return this;
        }

        public Builder operationCacheSize(long size) {
            this.operationCacheSize = validatePositive(size, "operationCacheSize");
            return this;
        }

        public Builder quickGrowthThreshold(double threshold) {
            if (threshold < 0.0d) {
                throw new IllegalArgumentException("quickGrowthThreshold must be >= 0");
            }
            this.quickGrowthThreshold = threshold;
            return this;
        }

        public Builder edgeBucketCount(long count) {
            this.edgeBucketCount = validateNonNegative(count, "edgeBucketCount");
            return this;
        }

        public Builder nodetableBucketCount(long count) {
            this.nodetableBucketCount = validateNonNegative(count, "nodetableBucketCount");
            return this;
        }
        public Builder nodeSlabCapacity(long capacity) {
            this.nodeSlabCapacity = validateNonNegative(capacity, "nodeSlabCapacity");
            return this;
        }

        public Builder edgeEntrySlabCapacity(long capacity) {
            this.edgeEntrySlabCapacity = validateNonNegative(capacity, "edgeEntrySlabCapacity");
            return this;
        }

        public Builder nodetableEntrySlabCapacity(long capacity) {
            this.nodetableEntrySlabCapacity = validateNonNegative(capacity, "nodetableEntrySlabCapacity");
            return this;
        }

        public Builder edgeMapSlabCapacity(long capacity) {
            this.edgeMapSlabCapacity = validateNonNegative(capacity, "edgeMapSlabCapacity");
            return this;
        }

        public MTPNDDConfig build() {
            return new MTPNDDConfig(this);
        }

        private static long validatePositive(long value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive: " + value);
            }
            return value;
        }

        private static long validateNonNegative(long value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be >= 0: " + value);
            }
            return value;
        }
    }

    @Override
    public String toString() {
        return "MTPNDDConfig{" +
                "workers=" + workers +
                ", laceDequeSize=" + laceDequeSize +
                ", bddNodeTableSize=" + bddNodeTableSize +
                ", mtpnddNodeTableSize=" + mtpnddNodeTableSize +
                ", operationCacheSize=" + operationCacheSize +
                ", quickGrowthThreshold=" + quickGrowthThreshold +
                ", edgeBucketCount=" + edgeBucketCount +
                ", nodetableBucketCount=" + nodetableBucketCount +
                ", nodeSlabCapacity=" + nodeSlabCapacity +
                ", edgeEntrySlabCapacity=" + edgeEntrySlabCapacity +
                ", nodetableEntrySlabCapacity=" + nodetableEntrySlabCapacity +
                ", edgeMapSlabCapacity=" + edgeMapSlabCapacity +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MTPNDDConfig)) {
            return false;
        }
        MTPNDDConfig that = (MTPNDDConfig) o;
        return workers == that.workers
                && laceDequeSize == that.laceDequeSize
                && bddNodeTableSize == that.bddNodeTableSize
                && mtpnddNodeTableSize == that.mtpnddNodeTableSize
                && operationCacheSize == that.operationCacheSize
                && Double.compare(that.quickGrowthThreshold, quickGrowthThreshold) == 0
                && edgeBucketCount == that.edgeBucketCount
                && nodetableBucketCount == that.nodetableBucketCount
                && nodeSlabCapacity == that.nodeSlabCapacity
                && edgeEntrySlabCapacity == that.edgeEntrySlabCapacity
                && nodetableEntrySlabCapacity == that.nodetableEntrySlabCapacity
                && edgeMapSlabCapacity == that.edgeMapSlabCapacity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(workers, laceDequeSize, bddNodeTableSize, mtpnddNodeTableSize,
                operationCacheSize, quickGrowthThreshold, edgeBucketCount, nodetableBucketCount,
                nodeSlabCapacity, edgeEntrySlabCapacity, nodetableEntrySlabCapacity,
                edgeMapSlabCapacity);
    }
}
