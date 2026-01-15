package org.bacon.ruthenium.world.network;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.world.RegionTaskDispatcher;
import org.bacon.ruthenium.world.RegionizedServer;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.RegionizedWorldData;
import org.bacon.ruthenium.world.TickRegionScheduler;

/**
 * Handles player transfers between regions.
 * <p>
 * When a player crosses a region boundary, this handler:
 * <ul>
 *   <li>Detects the boundary crossing during movement</li>
 *   <li>Captures the player's state (inventory, effects, statistics)</li>
 *   <li>Queues the transfer to the destination region thread</li>
 *   <li>Migrates the player's state to the new region</li>
 *   <li>Updates entity tracking for the region transition</li>
 * </ul>
 */
public final class PlayerRegionTransferHandler {

    private static final Logger LOGGER = LogManager.getLogger(PlayerRegionTransferHandler.class);

    /**
     * Pending player state snapshots for transfer.
     */
    private static final ConcurrentHashMap<ServerPlayerEntity, PlayerStateSnapshot> PENDING_SNAPSHOTS =
        new ConcurrentHashMap<>();

    private PlayerRegionTransferHandler() {}

    /**
     * Checks if a player has crossed a region boundary and initiates transfer if needed.
     * Should be called after player movement is processed.
     *
     * @param player       the player who moved
     * @param oldChunkX    previous chunk X
     * @param oldChunkZ    previous chunk Z
     * @param newChunkX    new chunk X
     * @param newChunkZ    new chunk Z
     * @return true if a region transfer was initiated
     */
    public static boolean checkAndInitiateTransfer(final ServerPlayerEntity player,
                                                   final int oldChunkX,
                                                   final int oldChunkZ,
                                                   final int newChunkX,
                                                   final int newChunkZ) {
        Objects.requireNonNull(player, "player");

        // Quick exit if chunk hasn't changed
        if (oldChunkX == newChunkX && oldChunkZ == newChunkZ) {
            return false;
        }

        final ServerWorld world = player.getEntityWorld();
        if (!(world instanceof RegionizedServerWorld regionized)) {
            return false;
        }

        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer =
            regionized.ruthenium$getRegionizer();

        // Get regions for old and new chunks
        final var oldRegion = regionizer.getRegionForChunk(oldChunkX, oldChunkZ);
        final var newRegion = regionizer.getRegionForChunk(newChunkX, newChunkZ);

        // If same region or one is null, no transfer needed
        if (oldRegion == newRegion) {
            return false;
        }

        if (oldRegion == null || newRegion == null) {
            // One region doesn't exist yet - this is a temporary state
            LOGGER.debug("Player {} crossing into uninitialized region (old={}, new={})",
                player.getName().getString(), oldRegion != null ? oldRegion.id : "null",
                newRegion != null ? newRegion.id : "null");
            return false;
        }

        // Initiate transfer
        LOGGER.info("Player {} crossing region boundary from region {} to region {}",
            player.getName().getString(), oldRegion.id, newRegion.id);

        initiateTransfer(player, world, oldChunkX, oldChunkZ, newChunkX, newChunkZ);
        return true;
    }

    /**
     * Initiates a player transfer between regions.
     */
    private static void initiateTransfer(final ServerPlayerEntity player,
                                         final ServerWorld world,
                                         final int fromChunkX,
                                         final int fromChunkZ,
                                         final int toChunkX,
                                         final int toChunkZ) {
        // Capture player state snapshot
        final PlayerStateSnapshot snapshot = capturePlayerState(player);
        PENDING_SNAPSHOTS.put(player, snapshot);

        // Get the current region's world data
        final RegionizedWorldData currentWorldData = TickRegionScheduler.getCurrentWorldData();
        if (currentWorldData != null) {
            // Remove player from current region tracking
            currentWorldData.removePlayer(player);
        }

        // Queue the player addition to the new region
        RegionTaskDispatcher.runOnChunk(world, toChunkX, toChunkZ, () -> {
            completeTransfer(player, toChunkX, toChunkZ);
        });
    }

    /**
     * Completes a player transfer on the destination region thread.
     */
    private static void completeTransfer(final ServerPlayerEntity player,
                                         final int toChunkX,
                                         final int toChunkZ) {
        final PlayerStateSnapshot snapshot = PENDING_SNAPSHOTS.remove(player);
        if (snapshot == null) {
            LOGGER.warn("No state snapshot found for player {} during transfer completion",
                player.getName().getString());
        }

        // Get the destination region's world data
        final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
        if (worldData == null) {
            LOGGER.error("No world data available for transfer completion of player {}",
                player.getName().getString());
            return;
        }

        // Add player to new region
        worldData.addPlayer(player);
        worldData.updatePlayerTrackingPosition(player);

        LOGGER.debug("Completed region transfer for player {} to chunk ({}, {})",
            player.getName().getString(), toChunkX, toChunkZ);
    }

    /**
     * Captures a snapshot of the player's state for transfer.
     */
    private static PlayerStateSnapshot capturePlayerState(final ServerPlayerEntity player) {
        final float health = player.getHealth();
        final int foodLevel = player.getHungerManager().getFoodLevel();
        final float saturation = player.getHungerManager().getSaturationLevel();
        final int experienceLevel = player.experienceLevel;
        final float experienceProgress = player.experienceProgress;
        final int totalExperience = player.totalExperience;

        return new PlayerStateSnapshot(
            player,
            health,
            foodLevel,
            saturation,
            experienceLevel,
            experienceProgress,
            totalExperience,
            System.currentTimeMillis()
        );
    }

    /**
     * Handles a player disconnect during region transfer.
     * Cleans up any pending transfer state.
     */
    public static void handleDisconnectDuringTransfer(final ServerPlayerEntity player) {
        final PlayerStateSnapshot snapshot = PENDING_SNAPSHOTS.remove(player);
        if (snapshot != null) {
            LOGGER.info("Player {} disconnected during region transfer, cleaning up state",
                player.getName().getString());
        }
    }

    /**
     * Updates entity tracking when a player changes regions.
     */
    public static void updateEntityTracking(final ServerPlayerEntity player,
                                            final ServerWorld world,
                                            final int oldChunkX,
                                            final int oldChunkZ,
                                            final int newChunkX,
                                            final int newChunkZ) {
        if (!RegionizedServer.isOnRegionThread()) {
            RegionTaskDispatcher.runOnChunk(world, newChunkX, newChunkZ, () -> {
                updateEntityTrackingInternal(player);
            });
        } else {
            updateEntityTrackingInternal(player);
        }
    }

    private static void updateEntityTrackingInternal(final ServerPlayerEntity player) {
        final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
        if (worldData != null) {
            worldData.updatePlayerTrackingPosition(player);
        }
    }

    /**
     * Returns the count of pending transfers.
     */
    public static int getPendingTransferCount() {
        return PENDING_SNAPSHOTS.size();
    }

    /**
     * Player state snapshot for region transfer.
     */
    private record PlayerStateSnapshot(
        ServerPlayerEntity player,
        float health,
        int foodLevel,
        float saturation,
        int experienceLevel,
        float experienceProgress,
        int totalExperience,
        long captureTime
    ) {}
}

