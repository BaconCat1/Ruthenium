package org.bacon.ruthenium.world;

import java.util.Objects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;

/**
 * Utility helpers for determining whether the current thread owns specific
 * world positions when executing within the Ruthenium region scheduler.
 */
public final class RegionThreadUtil {

    private RegionThreadUtil() {
    }

    /**
     * Returns whether the current thread is executing within a region tick.
     *
     * @return {@code true} when the current thread owns a region
     */
    public static boolean isRegionThread() {
        return TickRegionScheduler.getCurrentRegion() != null;
    }

    /**
     * Returns whether the current thread can safely access the block at the given position.
     * On region threads this requires that the owning region matches the current region.
     */
    public static boolean canAccessBlock(final World world, final BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return true; // client or non-server contexts are single-threaded
        }
        if (!isRegionThread()) {
            return true; // main thread orchestrator access is allowed
        }
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        if (!ownsChunk(serverWorld, chunkX, chunkZ)) {
            return false;
        }
        // Mirror Folia's "if loaded" checks: never trigger loads from region threads.
        // IMPORTANT: Avoid ServerChunkManager#getChunk(...) here because it can have main-thread
        // assumptions; instead use getWorldChunk(...) which is the same loaded/full-chunk view we
        // tick against in the region scheduler.
        return serverWorld.getChunkManager().getWorldChunk(chunkX, chunkZ) != null;
    }

    /**
     * Checks whether the current thread owns a region within the supplied world.
     *
     * @param world world to check
     * @return {@code true} when the thread is executing a region belonging to {@code world}
     */
    public static boolean isRegionThreadFor(final ServerWorld world) {
        return world != null && TickRegionScheduler.getCurrentWorld() == world;
    }

    /**
     * Determines whether the current region owns the supplied chunk coordinates.
     *
     * @param world  world containing the chunk
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return {@code true} when the current region contains the chunk
     */
    public static boolean ownsChunk(final ServerWorld world, final int chunkX, final int chunkZ) {
        return ownsChunk(world, chunkX, chunkZ, 0);
    }

    /**
     * Determines whether the current region owns the supplied chunk or any chunk within the
     * provided radius.
     *
     * @param world  world containing the chunk
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param radius radius in chunks to consider
     * @return {@code true} when the current region contains one of the chunks in the search area
     */
    public static boolean ownsChunk(final ServerWorld world, final int chunkX, final int chunkZ, final int radius) {
        if (world == null) {
            return false;
        }
        final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = TickRegionScheduler.getCurrentRegion();
        if (region == null || TickRegionScheduler.getCurrentWorld() != world) {
            return false;
        }
        if (radius <= 0) {
            return region.containsChunk(chunkX, chunkZ);
        }

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                if (region.containsChunk(chunkX + dx, chunkZ + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the current region owns the chunk containing the supplied position.
     *
     * @param world world containing the position
     * @param pos   block position to evaluate
     * @return {@code true} when the position lies within the current region
     */
    public static boolean ownsPosition(final ServerWorld world, final BlockPos pos) {
        return ownsPosition(world, pos, 0);
    }

    /**
     * Checks whether the current region owns the chunk containing the supplied position or any
     * chunk within the provided radius.
     *
     * @param world  world containing the position
     * @param pos    block position to evaluate
     * @param radius radius in chunks to consider
     * @return {@code true} when any chunk in the search area belongs to the current region
     */
    public static boolean ownsPosition(final ServerWorld world, final BlockPos pos, final int radius) {
        Objects.requireNonNull(pos, "pos");
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        return ownsChunk(world, chunkX, chunkZ, radius);
    }

    /**
     * Checks whether the current region owns the chunk around the provided player.
     *
     * @param player player to evaluate
     * @param radius radius in chunks to consider
     * @return {@code true} when the player's chunk falls within the current region
     */
    public static boolean ownsPlayer(final ServerPlayerEntity player, final int radius) {
        if (player == null) {
            return false;
        }
        final ServerWorld world = TickRegionScheduler.getCurrentWorld();
        if (world == null) {
            return false;
        }
        final ChunkPos pos = player.getChunkPos();
        return ownsChunk(world, pos.x, pos.z, radius);
    }

    /**
     * Returns the redstone time that should be used for logic executing on the region thread.
     *
     * @param world world providing the time
     * @return region-aware redstone time
     */
    public static long getRedstoneTime(final World world) {
        if (world instanceof ServerWorld serverWorld && serverWorld instanceof RegionizedServerWorld regionized) {
            return regionized.ruthenium$getWorldRegionData().getRedstoneGameTime();
        }
        return world.getTime();
    }

    /**
     * Schedules work to execute on the region that owns the supplied chunk.
     *
     * @param world  world containing the chunk
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param task   work to execute on the owning region thread
     */
    public static boolean scheduleOnChunk(final ServerWorld world,
                                          final int chunkX,
                                          final int chunkZ,
                                          final Runnable task) {
        if (RegionTaskDispatcher.runOnChunk(world, chunkX, chunkZ, task)) {
            return true;
        }
        task.run();
        return false;
    }

    /**
     * Schedules work to execute on the region that owns the provided player.
     *
     * @param player player whose region should execute the task
     * @param task   work to run on the owning region thread
     */
    public static boolean scheduleOnPlayer(final ServerPlayerEntity player, final Runnable task) {
        Objects.requireNonNull(player, "player");
        final ChunkPos pos = player.getChunkPos();
        final ServerWorld world = player.getEntityWorld();
        return scheduleOnChunk(world, pos.x, pos.z, task);
    }
}
