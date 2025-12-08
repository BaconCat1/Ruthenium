package org.bacon.ruthenium.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * Lightweight tracker that records recent tick activity for each region. The
 * data is used for diagnostics commands and to confirm that regions keep
 * ticking over time.
 */
public final class RegionTickMonitor {

    private static final RegionTickMonitor INSTANCE = new RegionTickMonitor();

    private final ConcurrentHashMap<RegistryKey<World>, ConcurrentHashMap<Long, RegionStats>> stats =
        new ConcurrentHashMap<>();

    private RegionTickMonitor() {
    }

    public static RegionTickMonitor getInstance() {
        return INSTANCE;
    }

    public void recordTick(final ServerWorld world,
                           final long regionId,
                           final int chunkCount,
                           final int processedTasks,
                           final long durationNanos) {
        final RegistryKey<World> worldKey = world.getRegistryKey();
        final ConcurrentHashMap<Long, RegionStats> perWorld =
            this.stats.computeIfAbsent(worldKey, ignored -> new ConcurrentHashMap<>());
        final RegionStats regionStats =
            perWorld.computeIfAbsent(regionId, ignored -> new RegionStats(regionId));
        regionStats.record(chunkCount, processedTasks, durationNanos);
    }

    public void recordError(final ServerWorld world, final long regionId) {
        final RegistryKey<World> worldKey = world.getRegistryKey();
        final ConcurrentHashMap<Long, RegionStats> perWorld =
            this.stats.computeIfAbsent(worldKey, ignored -> new ConcurrentHashMap<>());
        final RegionStats regionStats =
            perWorld.computeIfAbsent(regionId, ignored -> new RegionStats(regionId));
        regionStats.recordError();
    }

    public void recordStall(final ServerWorld world, final long regionId) {
        final RegistryKey<World> worldKey = world.getRegistryKey();
        final ConcurrentHashMap<Long, RegionStats> perWorld =
            this.stats.computeIfAbsent(worldKey, ignored -> new ConcurrentHashMap<>());
        final RegionStats regionStats =
            perWorld.computeIfAbsent(regionId, ignored -> new RegionStats(regionId));
        regionStats.recordStall();
    }

    public List<String> buildReport() {
        final List<String> lines = new ArrayList<>();
        for (final Map.Entry<RegistryKey<World>, ConcurrentHashMap<Long, RegionStats>> worldEntry : this.stats.entrySet()) {
            final RegistryKey<World> worldKey = worldEntry.getKey();
            lines.add("World=" + worldKey.getValue());
            final List<RegionStats> regionStats = new ArrayList<>(worldEntry.getValue().values());
            regionStats.sort(Comparator.comparingLong(RegionStats::regionId));
            if (regionStats.isEmpty()) {
                lines.add("  (no region ticks recorded)");
                continue;
            }
            for (final RegionStats stats : regionStats) {
                final long ageMs = stats.lastAgeMillis();
                final String status = ageMs < 0 ? "NEVER_TICKED"
                    : ageMs > 10000 ? "STALLED"
                    : ageMs > 5000 ? "SLOW"
                    : "OK";

                lines.add(String.format("  region=%d status=%s", stats.regionId(), status));
                lines.add(String.format("    ticks=%d lastAgeMs=%d consecutiveStalls=%d errors=%d",
                    stats.totalTicks(), ageMs, stats.consecutiveStalls(), stats.totalErrors()));
                lines.add(String.format("    duration: last=%.3fms min=%.3fms max=%.3fms",
                    stats.lastDurationMillis(), stats.minDurationMillis(), stats.maxDurationMillis()));
                lines.add(String.format("    lastTick: chunks=%d tasks=%d",
                    stats.lastChunkSample(), stats.lastProcessedTasks()));

                if (stats.totalErrors() > 0) {
                    final long errorAge = stats.lastErrorAgeMillis();
                    lines.add(String.format("    lastError: %dms ago", errorAge < 0 ? -1 : errorAge));
                }
            }
        }
        if (lines.isEmpty()) {
            return Collections.singletonList("No tick data recorded yet.");
        }
        return lines;
    }

    private static final class RegionStats {

        private final long regionId;
        private final AtomicLong totalTicks = new AtomicLong();
        private final AtomicLong lastDurationNanos = new AtomicLong();
        private final AtomicLong minDurationNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxDurationNanos = new AtomicLong();
        private final AtomicLong lastChunkSample = new AtomicLong();
        private final AtomicLong lastProcessedTasks = new AtomicLong();
        private final AtomicLong lastTickMillis = new AtomicLong();
        private final AtomicLong totalErrors = new AtomicLong();
        private final AtomicLong consecutiveStalls = new AtomicLong();
        private final AtomicLong lastErrorMillis = new AtomicLong();

        RegionStats(final long regionId) {
            this.regionId = regionId;
        }

        long regionId() {
            return this.regionId;
        }

        long totalTicks() {
            return this.totalTicks.get();
        }

        double lastDurationMillis() {
            return this.lastDurationNanos.get() / 1_000_000.0D;
        }

        double minDurationMillis() {
            final long min = this.minDurationNanos.get();
            return min == Long.MAX_VALUE ? 0.0D : min / 1_000_000.0D;
        }

        double maxDurationMillis() {
            return this.maxDurationNanos.get() / 1_000_000.0D;
        }

        long lastChunkSample() {
            return this.lastChunkSample.get();
        }

        long lastProcessedTasks() {
            return this.lastProcessedTasks.get();
        }

        long lastAgeMillis() {
            final long lastMillis = this.lastTickMillis.get();
            if (lastMillis <= 0L) {
                return -1L;
            }
            return Math.max(0L, System.currentTimeMillis() - lastMillis);
        }

        long totalErrors() {
            return this.totalErrors.get();
        }

        long consecutiveStalls() {
            return this.consecutiveStalls.get();
        }

        long lastErrorAgeMillis() {
            final long lastMillis = this.lastErrorMillis.get();
            if (lastMillis <= 0L) {
                return -1L;
            }
            return Math.max(0L, System.currentTimeMillis() - lastMillis);
        }

        void record(final int chunkSample,
                    final int processedTasks,
                    final long durationNanos) {
            this.totalTicks.incrementAndGet();
            this.lastDurationNanos.set(durationNanos);
            this.lastChunkSample.set(chunkSample);
            this.lastProcessedTasks.set(processedTasks);
            final long now = System.currentTimeMillis();
            this.lastTickMillis.set(now);

            // Update min/max duration
            long currentMin;
            do {
                currentMin = this.minDurationNanos.get();
            } while (durationNanos < currentMin && !this.minDurationNanos.compareAndSet(currentMin, durationNanos));

            long currentMax;
            do {
                currentMax = this.maxDurationNanos.get();
            } while (durationNanos > currentMax && !this.maxDurationNanos.compareAndSet(currentMax, durationNanos));

            // Reset stall counter on successful tick
            this.consecutiveStalls.set(0);
        }

        void recordError() {
            this.totalErrors.incrementAndGet();
            this.lastErrorMillis.set(System.currentTimeMillis());
        }

        void recordStall() {
            this.consecutiveStalls.incrementAndGet();
        }
    }
}
