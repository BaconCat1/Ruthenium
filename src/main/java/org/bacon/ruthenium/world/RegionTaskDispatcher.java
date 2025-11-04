package org.bacon.ruthenium.world;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.region.ThreadedRegionizer.ThreadedRegion;
import it.unimi.dsi.fastutil.longs.LongIterator;

/**
 * Utility for scheduling work against regionised chunk queues.
 */
public final class RegionTaskDispatcher {

    private static final Logger LOGGER = LogManager.getLogger(RegionTaskDispatcher.class);

    private RegionTaskDispatcher() {
    }

    /**
     * Queues a task that should execute when the owning region next ticks the
     * specified chunk. If the chunk is not currently registered with any
     * region, the task executes immediately on the caller thread.
     *
     * @param world   the world containing the chunk
     * @param chunkX  chunk X coordinate
     * @param chunkZ  chunk Z coordinate
     * @param task    work to execute
     */
    public static void runOnChunk(final ServerWorld world, final int chunkX, final int chunkZ, final Runnable task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");

        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = requireRegionizer(world);
        final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = regionizer.getRegionForChunk(chunkX, chunkZ);
        if (region == null) {
            LOGGER.debug("Executing chunk task immediately because chunk {} is not yet regionised", new ChunkPos(chunkX, chunkZ));
            task.run();
            return;
        }

        region.getData().getTaskQueue().queueChunkTask(chunkX, chunkZ, task);
    }

    /**
     * Queues a task on the region currently being processed by the ticking
     * thread. This mirrors Folia's current-region scheduling helpers and makes
     * it possible for world subsystems to defer follow-up work while staying on
     * the same region thread.
     *
     * @param task supplier used to lazily create work; returning {@code null}
     *             skips scheduling entirely
     */
    public static void runOnCurrentRegion(final Supplier<Runnable> task) {
        Objects.requireNonNull(task, "task");
        final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = TickRegionScheduler.getCurrentRegion();
        if (region == null || TickRegionScheduler.getCurrentWorld() == null) {
            throw new IllegalStateException("No region is currently ticking on this thread");
        }

        final Runnable runnable = task.get();
        if (runnable == null) {
            return;
        }

        final LongIterator iterator = region.getData().getChunks().iterator();
        final long chunkKey = iterator.hasNext() ? iterator.nextLong() : 0L;
        final ChunkPos representative = new ChunkPos(
            RegionTickData.decodeChunkX(chunkKey),
            RegionTickData.decodeChunkZ(chunkKey)
        );
        region.getData().getTaskQueue().queueChunkTask(representative.x, representative.z, runnable);
    }

    private static ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> requireRegionizer(final ServerWorld world) {
        if (!(world instanceof RegionizedServerWorld regionized)) {
            throw new IllegalStateException("World " + world + " is missing RegionizedServerWorld support");
        }
        return regionized.ruthenium$getRegionizer();
    }
}
