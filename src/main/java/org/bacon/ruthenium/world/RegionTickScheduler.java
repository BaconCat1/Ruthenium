package org.bacon.ruthenium.world;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.region.RegionTaskQueue;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegion;
import org.bacon.ruthenium.region.ThreadedRegionizer;

/**
 * Coordinates per-region chunk ticking using the {@link ThreadedRegionizer} graph.
 */
public final class RegionTickScheduler {

    private static final Logger LOGGER = LogManager.getLogger(RegionTickScheduler.class);
    private static final RegionTickScheduler INSTANCE = new RegionTickScheduler();
    private static final MethodHandle TICK_CHUNK;
    private static final ThreadLocal<ThreadedRegion<RegionTickData>> CURRENT_REGION = new ThreadLocal<>();
    private static final ThreadLocal<ServerWorld> CURRENT_WORLD = new ThreadLocal<>();

    static {
        MethodHandle handle;
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            handle = lookup.findVirtual(ServerWorld.class, "tickChunk",
                MethodType.methodType(void.class, WorldChunk.class, int.class));
        } catch (final ReflectiveOperationException ex) {
            handle = null;
            LOGGER.error("Unable to resolve ServerWorld#tickChunk; region ticking will be disabled", ex);
        }
        TICK_CHUNK = handle;
    }

    private RegionTickScheduler() {
    }

    /**
     * @return singleton scheduler instance.
     */
    public static RegionTickScheduler getInstance() {
        return INSTANCE;
    }

    static ThreadedRegion<RegionTickData> getCurrentRegion() {
        return CURRENT_REGION.get();
    }

    static ServerWorld getCurrentWorld() {
        return CURRENT_WORLD.get();
    }

    /**
     * Ticks all regions owned by the supplied world. Regions are processed sequentially and each
     * region only ticks the chunks registered with its {@link RegionTickData}. The vanilla world
     * tick continues to execute for cross-region systems such as weather or scheduled block ticks.
     *
     * @param world              world being ticked
     * @param shouldKeepTicking  vanilla tick guard
     */
    public boolean tickWorld(final ServerWorld world, final BooleanSupplier shouldKeepTicking) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(shouldKeepTicking, "shouldKeepTicking");

        final ThreadedRegionizer<RegionTickData> regionizer = requireRegionizer(world);
        final List<ThreadedRegion<RegionTickData>> ticking = new ArrayList<>();

        for (final ThreadedRegion<RegionTickData> region : regionizer.snapshotRegions()) {
            if (regionizer.tryMarkTicking(region)) {
                ticking.add(region);
            }
        }

        if (ticking.isEmpty() || TICK_CHUNK == null) {
            for (final ThreadedRegion<RegionTickData> region : ticking) {
                regionizer.markNotTicking(region);
            }
            return false;
        }

        final MinecraftServer server = world.getServer();
        final int randomTickSpeed = server.getGameRules().getInt(GameRules.RANDOM_TICK_SPEED);
        final ServerChunkManager chunkManager = world.getChunkManager();

        for (final ThreadedRegion<RegionTickData> region : ticking) {
            tickRegion(world, chunkManager, region, randomTickSpeed, shouldKeepTicking);
        }

        for (final ThreadedRegion<RegionTickData> region : ticking) {
            region.getData().advanceCurrentTick();
            region.getData().advanceRedstoneTick();
            regionizer.markNotTicking(region);
        }

        return true;
    }

    private void tickRegion(final ServerWorld world, final ServerChunkManager chunkManager,
                            final ThreadedRegion<RegionTickData> region, final int randomTickSpeed,
                            final BooleanSupplier shouldKeepTicking) {
        if (TICK_CHUNK == null) {
            return;
        }

        CURRENT_REGION.set(region);
        CURRENT_WORLD.set(world);
        try {
            runQueuedTasks(region, shouldKeepTicking);

            final LongIterator iterator = region.getData().getChunks().iterator();
            while (iterator.hasNext()) {
                final long chunkKey = iterator.nextLong();
                if (!shouldKeepTicking.getAsBoolean()) {
                    break;
                }

                final int chunkX = RegionTickData.decodeChunkX(chunkKey);
                final int chunkZ = RegionTickData.decodeChunkZ(chunkKey);
                final Chunk chunk = chunkManager.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (!(chunk instanceof WorldChunk worldChunk)) {
                    continue;
                }

                ((RegionChunkTickAccess)world).ruthenium$pushRegionChunkTick();
                try {
                    TICK_CHUNK.invoke(world, worldChunk, randomTickSpeed);
                } catch (final Throwable throwable) {
                    LOGGER.error("Failed to tick chunk {} in region {}", new ChunkPos(chunkX, chunkZ), region.getId(), throwable);
                } finally {
                    ((RegionChunkTickAccess)world).ruthenium$popRegionChunkTick();
                }
            }

            runQueuedTasks(region, shouldKeepTicking);
        } finally {
            CURRENT_REGION.remove();
            CURRENT_WORLD.remove();
        }
    }

    private void runQueuedTasks(final ThreadedRegion<RegionTickData> region,
                                final BooleanSupplier shouldKeepTicking) {
        final RegionTickData data = region.getData();
        final RegionTaskQueue queue = data.getTaskQueue();

        RegionTaskQueue.RegionChunkTask task;
        while (shouldKeepTicking.getAsBoolean() && (task = queue.pollChunkTask()) != null) {
            if (!data.containsChunk(task.chunkX(), task.chunkZ())) {
                LOGGER.debug("Skipping chunk task for {} in region {} because the chunk is no longer present", new ChunkPos(task.chunkX(), task.chunkZ()), region.getId());
                continue;
            }
            try {
                task.runnable().run();
            } catch (final Throwable throwable) {
                LOGGER.error("Chunk task for {} in region {} failed", new ChunkPos(task.chunkX(), task.chunkZ()), region.getId(), throwable);
            }
        }
    }

    private ThreadedRegionizer<RegionTickData> requireRegionizer(final ServerWorld world) {
        if (!(world instanceof RegionizedServerWorld regionized)) {
            throw new IllegalStateException("World " + world + " is missing RegionizedServerWorld support");
        }
        return regionized.ruthenium$getRegionizer();
    }
}
