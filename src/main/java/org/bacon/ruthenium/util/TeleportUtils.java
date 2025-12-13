package org.bacon.ruthenium.util;

import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.world.BlockEventData;
import org.bacon.ruthenium.world.RegionizedServer;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.RegionTaskDispatcher;
import org.bacon.ruthenium.world.TickRegionScheduler;

/**
 * Utilities for cross-region entity teleportation and task transfers.
 * When entities move across region boundaries, their state must be properly
 * transferred to the destination region's thread.
 */
public final class TeleportUtils {

    private static final Logger LOGGER = LogManager.getLogger(TeleportUtils.class);

    /**
     * Teleport flags mirroring Folia's TeleportUtils constants.
     */
    public static final int TELEPORT_FLAG_LOAD_CHUNK = 1 << 0;
    public static final int TELEPORT_FLAG_UNMOUNT = 1 << 1;
    public static final int TELEPORT_FLAG_DISMOUNT = 1 << 2;

    private TeleportUtils() {}

    /**
     * Teleports an entity, scheduling the operation on the destination region thread.
     *
     * @param from       the entity to teleport
     * @param toWorld    destination world
     * @param toPos      destination position
     * @param yaw        destination yaw
     * @param pitch      destination pitch
     * @param onComplete callback invoked after teleport completes (may be null)
     */
    public static <T extends Entity> void teleport(final T from, final ServerWorld toWorld, final Vec3d toPos,
                                                   final float yaw, final float pitch,
                                                   final Consumer<Entity> onComplete) {
        teleport(from, toWorld, toPos, yaw, pitch, onComplete, null, 0);
    }

    /**
     * Teleports an entity with additional options.
     *
     * @param from        the entity to teleport
     * @param toWorld     destination world
     * @param toPos       destination position
     * @param yaw         destination yaw
     * @param pitch       destination pitch
     * @param onComplete  callback invoked after teleport completes (may be null)
     * @param preTeleport predicate to run before teleport; if it returns false, teleport is cancelled
     * @param flags       teleport flags (see TELEPORT_FLAG_* constants)
     */
    public static <T extends Entity> void teleport(final T from, final ServerWorld toWorld, final Vec3d toPos,
                                                   final float yaw, final float pitch,
                                                   final Consumer<Entity> onComplete,
                                                   final Predicate<T> preTeleport,
                                                   final int flags) {
        if (from == null || toWorld == null || toPos == null) {
            LOGGER.warn("Invalid teleport parameters: entity={}, world={}, pos={}", from, toWorld, toPos);
            return;
        }

        final ServerWorld fromWorld = (ServerWorld) from.getEntityWorld();
        final int toChunkX = ((int) Math.floor(toPos.x)) >> 4;
        final int toChunkZ = ((int) Math.floor(toPos.z)) >> 4;

        // Check if we need to cross regions
        final boolean sameWorld = fromWorld == toWorld;
        final boolean sameRegion = sameWorld && isInSameRegion(fromWorld, from.getBlockPos(),
            new BlockPos((int) toPos.x, (int) toPos.y, (int) toPos.z));

        if (sameRegion && RegionizedServer.isOnRegionThread()) {
            // Same region, execute immediately
            executeTeleport(from, toWorld, toPos, yaw, pitch, onComplete, preTeleport, flags);
            return;
        }

        // Schedule on destination region
        scheduleOnChunk(toWorld, toChunkX, toChunkZ, () -> {
            executeTeleport(from, toWorld, toPos, yaw, pitch, onComplete, preTeleport, flags);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Entity> void executeTeleport(final T from, final ServerWorld toWorld,
                                                            final Vec3d toPos, final float yaw, final float pitch,
                                                            final Consumer<Entity> onComplete,
                                                            final Predicate<T> preTeleport,
                                                            final int flags) {
        if (from.isRemoved()) {
            LOGGER.debug("Entity {} was removed before teleport could complete", from.getUuidAsString());
            return;
        }

        // Handle dismounting if requested
        if ((flags & TELEPORT_FLAG_DISMOUNT) != 0) {
            from.stopRiding();
        }
        if ((flags & TELEPORT_FLAG_UNMOUNT) != 0) {
            from.removeAllPassengers();
        }

        // Run pre-teleport check
        if (preTeleport != null && !preTeleport.test(from)) {
            LOGGER.debug("Pre-teleport check failed for entity {}", from.getUuidAsString());
            return;
        }

        // Perform the actual teleport
        from.teleport(toWorld, toPos.x, toPos.y, toPos.z, java.util.Set.of(), yaw, pitch, true);

        // Invoke completion callback
        if (onComplete != null) {
            onComplete.accept(from);
        }
    }

    /**
     * Checks if two positions are in the same region.
     *
     * @param world the world
     * @param pos1  first position
     * @param pos2  second position
     * @return true if both positions are in the same region
     */
    public static boolean isInSameRegion(final ServerWorld world, final BlockPos pos1, final BlockPos pos2) {
        if (!(world instanceof RegionizedServerWorld regionized)) {
            return true; // No regionizer, treat as same region
        }

        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer =
            regionized.ruthenium$getRegionizer();

        final int chunkX1 = pos1.getX() >> 4;
        final int chunkZ1 = pos1.getZ() >> 4;
        final int chunkX2 = pos2.getX() >> 4;
        final int chunkZ2 = pos2.getZ() >> 4;

        final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region1 =
            regionizer.getRegionForChunk(chunkX1, chunkZ1);
        final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region2 =
            regionizer.getRegionForChunk(chunkX2, chunkZ2);

        return region1 == region2;
    }

    /**
     * Schedules a task to run on the region thread that owns the specified chunk.
     *
     * @param world  the world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param task   the task to run
     */
    public static void scheduleOnChunk(final ServerWorld world, final int chunkX, final int chunkZ,
                                       final Runnable task) {
        if (!(world instanceof RegionizedServerWorld)) {
            task.run();
            return;
        }
        RegionTaskDispatcher.runOnChunk(world, chunkX, chunkZ, task);
    }

    /**
     * Transfers a block event to the appropriate region.
     * Used when a block event needs to be processed by a different region.
     *
     * @param world      the world
     * @param blockEvent the block event to transfer
     */
    public static void transferBlockEvent(final ServerWorld world, final BlockEventData blockEvent) {
        scheduleOnChunk(world, blockEvent.chunkX(), blockEvent.chunkZ(), () -> {
            final var worldData = TickRegionScheduler.getCurrentWorldData();
            if (worldData != null) {
                worldData.pushBlockEvent(blockEvent);
            }
        });
    }

    /**
     * Checks if the current chunk requires a region transfer for an entity moving to a new position.
     *
     * @param entity the entity
     * @param newPos the new position
     * @return true if a region transfer is required
     */
    public static boolean requiresRegionTransfer(final Entity entity, final Vec3d newPos) {
        if (!(entity.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return false;
        }
        return !isInSameRegion(serverWorld, entity.getBlockPos(),
            new BlockPos((int) newPos.x, (int) newPos.y, (int) newPos.z));
    }
}
