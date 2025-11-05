package org.bacon.ruthenium.world;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RegionTickStats}.
 */
class RegionTickStatsTest {

    @Test
    void retainsRollingWindowOfSamples() {
        final int window = 5;
        final RegionTickStats stats = new RegionTickStats(window);

        for (int i = 0; i < 10; ++i) {
            stats.recordTickDuration(i);
        }

        Assertions.assertEquals(window, stats.getSampleCount());
        Assertions.assertArrayEquals(new long[] {5, 6, 7, 8, 9}, stats.snapshotNanos());
    }

    @Test
    void computesBasicStatistics() {
        final RegionTickStats stats = new RegionTickStats();
        stats.recordTickDuration(1_000_000L);
        stats.recordTickDuration(2_000_000L);
        stats.recordTickDuration(3_000_000L);

        Assertions.assertEquals(3, stats.getSampleCount());
        Assertions.assertEquals(3_000_000L, stats.getMaxTickNanos());
        Assertions.assertEquals(1_000_000L, stats.getMinTickNanos());
        Assertions.assertEquals(3_000_000L, stats.getLastTickNanos());
        Assertions.assertEquals(2_000_000.0D, stats.getAverageTickNanos(), 0.0001D);
        Assertions.assertEquals(2.0D, stats.getAverageTickMillis(), 0.0001D);

        final RegionTickStats.Snapshot snapshot = stats.snapshot();
        Assertions.assertEquals(3, snapshot.sampleCount());
        Assertions.assertEquals(2_000_000.0D, snapshot.averageTickNanos(), 0.0001D);
        Assertions.assertEquals(2.0D, snapshot.averageTickMillis(), 0.0001D);
        Assertions.assertEquals(3.0D, snapshot.lastTickMillis(), 0.0001D);
        Assertions.assertEquals(1.0D, snapshot.minTickMillis(), 0.0001D);
        Assertions.assertEquals(3.0D, snapshot.maxTickMillis(), 0.0001D);
    }

    @Test
    void copyReplicatesSampleState() {
        final RegionTickStats source = new RegionTickStats();
        source.recordTickDuration(4_000_000L);
        source.recordTickDuration(6_000_000L);

        final RegionTickStats target = new RegionTickStats();
        target.recordTickDuration(1_000_000L);
        target.copyFrom(source);

        Assertions.assertArrayEquals(source.snapshotNanos(), target.snapshotNanos());
        Assertions.assertEquals(source.getAverageTickNanos(), target.getAverageTickNanos());
    }
}
