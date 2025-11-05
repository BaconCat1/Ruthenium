package org.bacon.ruthenium.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Objects;

/**
 * Maintains a rolling window of region tick durations for instrumentation and debugging.
 */
public final class RegionTickStats {

    /** Default number of samples retained per region. */
    public static final int DEFAULT_WINDOW_SIZE = 200;

    private final int windowSize;
    private final LongArrayList samples;
    private long totalNanos;

    /**
     * Creates a new stats tracker with the default sample window size.
     */
    public RegionTickStats() {
        this(DEFAULT_WINDOW_SIZE);
    }

    /**
     * Creates a new stats tracker retaining up to {@code windowSize} samples.
     *
     * @param windowSize number of samples to retain; must be positive
     */
    public RegionTickStats(final int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be > 0");
        }
        this.windowSize = windowSize;
        this.samples = new LongArrayList(windowSize);
    }

    /**
     * Records a completed tick duration.
     *
     * @param durationNanos tick duration in nanoseconds
     */
    public synchronized void recordTickDuration(final long durationNanos) {
        if (durationNanos < 0L) {
            throw new IllegalArgumentException("durationNanos must be >= 0");
        }
        this.samples.add(durationNanos);
        this.totalNanos += durationNanos;
        if (this.samples.size() > this.windowSize) {
            final long removed = this.samples.removeLong(0);
            this.totalNanos -= removed;
        }
    }

    /**
     * Clears all recorded samples.
     */
    public synchronized void clear() {
        this.samples.clear();
        this.totalNanos = 0L;
    }

    /**
     * Copies the recorded samples from {@code other}, replacing the existing snapshot.
     *
     * @param other the stats instance to copy from
     */
    public void copyFrom(final RegionTickStats other) {
        Objects.requireNonNull(other, "other");
        final long[] snapshot = other.snapshotNanos();
        synchronized (this) {
            this.samples.clear();
            this.totalNanos = 0L;
            for (final long value : snapshot) {
                this.samples.add(value);
                this.totalNanos += value;
            }
            this.trimIfNeeded();
        }
    }

    /**
     * @return the number of stored samples
     */
    public synchronized int getSampleCount() {
        return this.samples.size();
    }

    /**
     * @return the rolling window size
     */
    public int getWindowSize() {
        return this.windowSize;
    }

    /**
     * @return {@code true} when no samples are stored
     */
    public synchronized boolean isEmpty() {
        return this.samples.isEmpty();
    }

    /**
     * @return duration of the most recent tick in nanoseconds or {@code 0} when empty
     */
    public synchronized long getLastTickNanos() {
        if (this.samples.isEmpty()) {
            return 0L;
        }
        return this.samples.getLong(this.samples.size() - 1);
    }

    /**
     * @return average tick duration in nanoseconds or {@code 0} when no samples exist
     */
    public synchronized double getAverageTickNanos() {
        return this.samples.isEmpty() ? 0.0D : (double)this.totalNanos / (double)this.samples.size();
    }

    /**
     * @return average tick duration in milliseconds
     */
    public synchronized double getAverageTickMillis() {
        return this.getAverageTickNanos() / 1_000_000.0D;
    }

    /**
     * @return maximum recorded tick duration in nanoseconds or {@code 0} when empty
     */
    public synchronized long getMaxTickNanos() {
        if (this.samples.isEmpty()) {
            return 0L;
        }
        long max = Long.MIN_VALUE;
        for (int i = 0, len = this.samples.size(); i < len; ++i) {
            final long value = this.samples.getLong(i);
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    /**
     * @return minimum recorded tick duration in nanoseconds or {@code 0} when empty
     */
    public synchronized long getMinTickNanos() {
        if (this.samples.isEmpty()) {
            return 0L;
        }
        long min = Long.MAX_VALUE;
        for (int i = 0, len = this.samples.size(); i < len; ++i) {
            final long value = this.samples.getLong(i);
            if (value < min) {
                min = value;
            }
        }
        return min;
    }

    /**
     * Creates a defensive copy of the current samples.
     *
     * @return snapshot of recorded durations in nanoseconds
     */
    public long[] snapshotNanos() {
        synchronized (this) {
            return this.samples.toLongArray();
        }
    }

    /**
     * Captures a stable snapshot of the aggregated statistics. This allows callers to
     * display consistent values without performing multiple synchronized lookups that can
     * observe different sample windows.
     *
     * @return snapshot containing the current statistics
     */
    public Snapshot snapshot() {
        synchronized (this) {
            final int count = this.samples.size();
            if (count == 0) {
                return Snapshot.EMPTY;
            }

            long max = Long.MIN_VALUE;
            long min = Long.MAX_VALUE;
            for (int i = 0; i < count; ++i) {
                final long value = this.samples.getLong(i);
                if (value > max) {
                    max = value;
                }
                if (value < min) {
                    min = value;
                }
            }

            final long last = this.samples.getLong(count - 1);
            final double average = (double)this.totalNanos / (double)count;
            return new Snapshot(count, average, last, min, max);
        }
    }

    private void trimIfNeeded() {
        while (this.samples.size() > this.windowSize) {
            final long removed = this.samples.removeLong(0);
            this.totalNanos -= removed;
        }
    }

    /**
     * Immutable snapshot of aggregated statistics.
     */
    public record Snapshot(int sampleCount, double averageTickNanos,
                           long lastTickNanos, long minTickNanos, long maxTickNanos) {

        public static final Snapshot EMPTY = new Snapshot(0, 0.0D, 0L, 0L, 0L);

        public double averageTickMillis() {
            return this.averageTickNanos / 1_000_000.0D;
        }

        public double lastTickMillis() {
            return this.lastTickNanos / 1_000_000.0D;
        }

        public double minTickMillis() {
            return this.minTickNanos / 1_000_000.0D;
        }

        public double maxTickMillis() {
            return this.maxTickNanos / 1_000_000.0D;
        }
    }
}
