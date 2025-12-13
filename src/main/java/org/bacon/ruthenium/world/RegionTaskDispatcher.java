package org.bacon.ruthenium.world;

import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import net.minecraft.server.world.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.region.ThreadedRegionizer.ThreadedRegion;
import org.bacon.ruthenium.util.CoordinateUtil;
import it.unimi.dsi.fastutil.longs.LongIterator;

/**
 * Utility for scheduling work against regionised chunk queues.
 */
public final class RegionTaskDispatcher {

    private static final Logger LOGGER = LogManager.getLogger(RegionTaskDispatcher.class);
    private static final Map<ServerWorld, ConcurrentHashMap<Long, ConcurrentLinkedQueue<Runnable>>> PENDING_CHUNK_TASKS =
        new ConcurrentHashMap<>();

    private RegionTaskDispatcher() {
    }

    /**
     * Queues a task that should execute when the owning region next ticks the
     * specified chunk.
     *
     * @param world   the world containing the chunk
     * @param chunkX  chunk X coordinate
     * @param chunkZ  chunk Z coordinate
     * @param task    work to execute
     *
     * @return {@code true} when the task was queued for region execution
     */
    public static boolean runOnChunk(final ServerWorld world, final int chunkX, final int chunkZ, final Runnable task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");

        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = requireRegionizer(world);
        final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = regionizer.getRegionForChunk(chunkX, chunkZ);
        if (region == null) {
            queuePendingChunkTask(world, chunkX, chunkZ, task);
            LOGGER.debug("Queued pending chunk task for chunk ({}, {}) (region not ready)", chunkX, chunkZ);
            return true;
        }

        final RegionTickData data = region.getData();
        if (!data.containsChunk(chunkX, chunkZ)) {
            queuePendingChunkTask(world, chunkX, chunkZ, task);
            LOGGER.debug("Queued pending chunk task for chunk ({}, {}) (chunk not registered yet)", chunkX, chunkZ);
            return true;
        }
        data.getTaskQueue().queueChunkTask(chunkX, chunkZ, task);
        TickRegionScheduler.getInstance().notifyRegionTasks(data.getScheduleHandle());
        return true;
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
        final RegionTickData data = region.getData();
        data.getTaskQueue().queueChunkTask(RegionTickData.decodeChunkX(chunkKey), RegionTickData.decodeChunkZ(chunkKey), runnable);
        TickRegionScheduler.getInstance().notifyRegionTasks(data.getScheduleHandle());
    }

    private static ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> requireRegionizer(final ServerWorld world) {
        if (!(world instanceof RegionizedServerWorld regionized)) {
            throw new IllegalStateException("World " + world + " is missing RegionizedServerWorld support");
        }
        return regionized.ruthenium$getRegionizer();
    }

    private static void queuePendingChunkTask(final ServerWorld world,
                                              final int chunkX,
                                              final int chunkZ,
                                              final Runnable task) {
        final long chunkKey = CoordinateUtil.getChunkKey(chunkX, chunkZ);
        final ConcurrentHashMap<Long, ConcurrentLinkedQueue<Runnable>> byChunk =
            PENDING_CHUNK_TASKS.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>());
        final ConcurrentLinkedQueue<Runnable> queue =
            byChunk.computeIfAbsent(chunkKey, ignored -> new ConcurrentLinkedQueue<>());
        queue.add(task);
    }

    public static void flushPendingChunkTasks(final ServerWorld world,
                                              final int chunkX,
                                              final int chunkZ,
                                              final RegionTickData data) {
        final ConcurrentHashMap<Long, ConcurrentLinkedQueue<Runnable>> byChunk = PENDING_CHUNK_TASKS.get(world);
        if (byChunk == null) {
            return;
        }
        final long chunkKey = CoordinateUtil.getChunkKey(chunkX, chunkZ);
        final Queue<Runnable> queue = byChunk.remove(chunkKey);
        if (queue == null) {
            return;
        }
        Runnable runnable;
        int drained = 0;
        while ((runnable = queue.poll()) != null) {
            data.getTaskQueue().queueChunkTask(chunkX, chunkZ, runnable);
            drained++;
        }
        if (byChunk.isEmpty()) {
            PENDING_CHUNK_TASKS.remove(world, byChunk);
        }
        if (drained > 0) {
            TickRegionScheduler.getInstance().notifyRegionTasks(data.getScheduleHandle());
        }
    }
}
