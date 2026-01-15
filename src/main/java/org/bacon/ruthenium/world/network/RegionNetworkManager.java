package org.bacon.ruthenium.world.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.world.RegionizedServer;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.RegionizedWorldData;

/**
 * Manages per-region network connections and packet broadcasting.
 * <p>
 * This class provides:
 * <ul>
 *   <li>Per-region packet broadcast queues for efficient multi-player updates</li>
 *   <li>Region-local connection ticking to process player packets on region threads</li>
 *   <li>Cross-region packet routing for entity tracking across region boundaries</li>
 *   <li>Player disconnect handling from region thread context</li>
 * </ul>
 * <p>
 * All network operations are designed to be thread-safe and integrate with
 * the region scheduler's tick lifecycle.
 */
public final class RegionNetworkManager {

    private static final Logger LOGGER = LogManager.getLogger(RegionNetworkManager.class);

    /**
     * Pending packets to send to players, keyed by player entity.
     */
    private final ConcurrentHashMap<ServerPlayerEntity, ConcurrentLinkedQueue<Packet<?>>> pendingPackets =
        new ConcurrentHashMap<>();

    /**
     * Players pending region transfer, keyed by player entity.
     */
    private final ConcurrentHashMap<ServerPlayerEntity, RegionTransfer> pendingTransfers =
        new ConcurrentHashMap<>();

    /**
     * Players pending disconnect processing.
     */
    private final Set<ServerPlayerEntity> pendingDisconnects =
        ConcurrentHashMap.newKeySet();

    /**
     * Metrics tracking.
     */
    private final AtomicLong packetsProcessed = new AtomicLong();
    private final AtomicLong regionTransfers = new AtomicLong();
    private final AtomicLong crossRegionPackets = new AtomicLong();

    private final ServerWorld world;

    public RegionNetworkManager(final ServerWorld world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    // ==================== Connection Management ====================

    /**
     * Ticks all player connections owned by the current region.
     * This should be called from a region thread during the region's tick.
     *
     * @param worldData the region's world data containing players to tick
     */
    public void tickRegionConnections(final RegionizedWorldData worldData) {
        if (!RegionizedServer.isOnRegionThread()) {
            LOGGER.warn("tickRegionConnections called from non-region thread");
            return;
        }

        final List<ServerPlayerEntity> players = worldData.getPlayers();
        for (final ServerPlayerEntity player : players) {
            if (this.pendingDisconnects.contains(player)) {
                continue; // Skip players pending disconnect
            }

            try {
                this.tickPlayerConnection(player);
            } catch (final Throwable throwable) {
                LOGGER.error("Failed to tick connection for player {}", player.getName().getString(), throwable);
                this.scheduleDisconnect(player);
            }
        }

        // Process any pending packet queue
        this.flushPendingPackets(worldData);
    }

    /**
     * Ticks a single player's network connection.
     */
    private void tickPlayerConnection(final ServerPlayerEntity player) {
        final ServerPlayNetworkHandler networkHandler = player.networkHandler;
        if (networkHandler != null) {
            networkHandler.tick();
            this.packetsProcessed.incrementAndGet();
        }
    }

    /**
     * Schedules a player for disconnect processing on their owning region thread.
     */
    public void scheduleDisconnect(final ServerPlayerEntity player) {
        this.pendingDisconnects.add(player);
    }

    /**
     * Processes pending disconnects for players in the current region.
     *
     * @param worldData the region's world data
     */
    public void processPendingDisconnects(final RegionizedWorldData worldData) {
        final List<ServerPlayerEntity> players = worldData.getPlayers();
        for (final ServerPlayerEntity player : players) {
            if (this.pendingDisconnects.remove(player)) {
                try {
                    this.processDisconnect(player);
                } catch (final Throwable throwable) {
                    LOGGER.error("Failed to process disconnect for player {}", player.getName().getString(), throwable);
                }
            }
        }
    }

    /**
     * Processes a player disconnect on the region thread.
     */
    private void processDisconnect(final ServerPlayerEntity player) {
        LOGGER.info("Processing disconnect for player {} on region thread", player.getName().getString());
        // Clean up any pending state
        this.pendingPackets.remove(player);
        this.pendingTransfers.remove(player);
    }

    // ==================== Packet Broadcasting ====================

    /**
     * Queues a packet for sending to a player.
     * If the player is on the current region thread, the packet is sent immediately.
     * Otherwise, it's queued for later delivery.
     *
     * @param player the player to send to
     * @param packet the packet to send
     */
    public void queuePacket(final ServerPlayerEntity player, final Packet<?> packet) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(packet, "packet");

        // If we're on the region thread owning this player, send immediately
        if (this.isOnPlayerRegionThread(player)) {
            this.sendPacketDirect(player, packet);
            return;
        }

        // Queue for later delivery
        final ConcurrentLinkedQueue<Packet<?>> queue = this.pendingPackets
            .computeIfAbsent(player, ignored -> new ConcurrentLinkedQueue<>());
        queue.add(packet);
        this.crossRegionPackets.incrementAndGet();
    }

    /**
     * Broadcasts a packet to all players in a specific region.
     *
     * @param worldData the region's world data
     * @param packet    the packet to broadcast
     */
    public void broadcastToRegion(final RegionizedWorldData worldData, final Packet<?> packet) {
        Objects.requireNonNull(worldData, "worldData");
        Objects.requireNonNull(packet, "packet");

        for (final ServerPlayerEntity player : worldData.getPlayers()) {
            this.queuePacket(player, packet);
        }
    }

    /**
     * Broadcasts a packet to all players within a certain chunk distance.
     *
     * @param worldData the region's world data
     * @param chunkX    center chunk X
     * @param chunkZ    center chunk Z
     * @param distance  maximum chunk distance
     * @param packet    the packet to broadcast
     */
    public void broadcastToNearby(final RegionizedWorldData worldData,
                                  final int chunkX,
                                  final int chunkZ,
                                  final int distance,
                                  final Packet<?> packet) {
        Objects.requireNonNull(worldData, "worldData");
        Objects.requireNonNull(packet, "packet");

        for (final ServerPlayerEntity player : worldData.getPlayers()) {
            final ChunkPos playerChunk = player.getChunkPos();
            final int dx = Math.abs(playerChunk.x - chunkX);
            final int dz = Math.abs(playerChunk.z - chunkZ);
            if (dx <= distance && dz <= distance) {
                this.queuePacket(player, packet);
            }
        }
    }

    /**
     * Sends a packet directly to a player without queueing.
     * Should only be called from the player's owning region thread.
     */
    private void sendPacketDirect(final ServerPlayerEntity player, final Packet<?> packet) {
        final ServerPlayNetworkHandler handler = player.networkHandler;
        if (handler != null) {
            handler.sendPacket(packet);
        }
    }

    /**
     * Flushes pending packets for players in the current region.
     */
    private void flushPendingPackets(final RegionizedWorldData worldData) {
        for (final ServerPlayerEntity player : worldData.getPlayers()) {
            final Queue<Packet<?>> queue = this.pendingPackets.get(player);
            if (queue == null || queue.isEmpty()) {
                continue;
            }

            final ServerPlayNetworkHandler handler = player.networkHandler;
            if (handler == null) {
                queue.clear();
                continue;
            }

            Packet<?> packet;
            while ((packet = queue.poll()) != null) {
                handler.sendPacket(packet);
            }
        }
    }

    /**
     * Returns whether the current thread is the region thread owning the player.
     */
    private boolean isOnPlayerRegionThread(final ServerPlayerEntity player) {
        if (!RegionizedServer.isOnRegionThread()) {
            return false;
        }

        final ServerWorld playerWorld = player.getEntityWorld();
        if (playerWorld != this.world) {
            return false;
        }

        if (!(playerWorld instanceof RegionizedServerWorld regionized)) {
            return false;
        }

        final ChunkPos chunkPos = player.getChunkPos();
        return regionized.ruthenium$isOwnedByCurrentRegion(chunkPos.x, chunkPos.z);
    }

    // ==================== Region Transfer ====================

    /**
     * Initiates a player transfer to a new region.
     * Called when a player crosses a region boundary.
     */
    public void initiateRegionTransfer(final ServerPlayerEntity player,
                                       final int fromChunkX,
                                       final int fromChunkZ,
                                       final int toChunkX,
                                       final int toChunkZ) {
        Objects.requireNonNull(player, "player");

        final RegionTransfer transfer = new RegionTransfer(
            player, fromChunkX, fromChunkZ, toChunkX, toChunkZ
        );

        if (this.pendingTransfers.putIfAbsent(player, transfer) == null) {
            LOGGER.debug("Initiated region transfer for player {} from ({}, {}) to ({}, {})",
                player.getName().getString(), fromChunkX, fromChunkZ, toChunkX, toChunkZ);
            this.regionTransfers.incrementAndGet();
        }
    }

    /**
     * Processes all pending region transfers for the current region.
     *
     * @param worldData the region's world data
     */
    public void processPendingTransfers(final RegionizedWorldData worldData) {
        final List<ServerPlayerEntity> playersToRemove = new ArrayList<>();

        for (final ServerPlayerEntity player : worldData.getPlayers()) {
            final RegionTransfer transfer = this.pendingTransfers.get(player);
            if (transfer == null) {
                continue;
            }

            if (!(this.world instanceof RegionizedServerWorld regionized)) {
                continue;
            }

            if (!regionized.ruthenium$isOwnedByCurrentRegion(transfer.fromChunkX(), transfer.fromChunkZ())) {
                continue;
            }

            playersToRemove.add(player);
            this.pendingTransfers.remove(player);
        }

        for (final ServerPlayerEntity player : playersToRemove) {
            worldData.removePlayer(player);
            LOGGER.debug("Completed transfer of player {} from region", player.getName().getString());
        }
    }

    /**
     * Routes entity tracking packets to nearby players.
     */
    public void routeEntityTrackingPacket(final int entityChunkX,
                                          final int entityChunkZ,
                                          final Packet<?> packet,
                                          final ServerPlayerEntity exclude) {
        if (!(this.world instanceof RegionizedServerWorld regionized)) {
            return;
        }

        final RegionizedWorldData worldData = regionized.ruthenium$getWorldRegionData();
        if (worldData == null) {
            return;
        }

        final int renderDistance = this.world.getServer().getPlayerManager().getViewDistance();
        for (final ServerPlayerEntity player : worldData.getPlayers()) {
            if (player == exclude) {
                continue;
            }
            final ChunkPos playerChunk = player.getChunkPos();
            final int dx = Math.abs(playerChunk.x - entityChunkX);
            final int dz = Math.abs(playerChunk.z - entityChunkZ);
            if (dx <= renderDistance && dz <= renderDistance) {
                this.queuePacket(player, packet);
            }
        }
    }

    // ==================== Metrics ====================

    /**
     * Returns metrics for this network manager.
     */
    public NetworkMetrics getMetrics() {
        return new NetworkMetrics(
            this.packetsProcessed.get(),
            this.regionTransfers.get(),
            this.crossRegionPackets.get(),
            this.pendingPackets.size(),
            this.pendingTransfers.size(),
            this.pendingDisconnects.size()
        );
    }

    /**
     * Resets metrics counters.
     */
    public void resetMetrics() {
        this.packetsProcessed.set(0);
        this.regionTransfers.set(0);
        this.crossRegionPackets.set(0);
    }

    /**
     * Clears all pending state.
     */
    public void clear() {
        this.pendingPackets.clear();
        this.pendingTransfers.clear();
        this.pendingDisconnects.clear();
    }

    // ==================== Data Classes ====================

    /**
     * Represents a pending player region transfer.
     */
    public record RegionTransfer(
        ServerPlayerEntity player,
        int fromChunkX,
        int fromChunkZ,
        int toChunkX,
        int toChunkZ
    ) {
        public RegionTransfer {
            Objects.requireNonNull(player, "player");
        }
    }

    /**
     * Network manager metrics.
     */
    public record NetworkMetrics(
        long packetsProcessed,
        long regionTransfers,
        long crossRegionPackets,
        int pendingPacketQueues,
        int pendingTransfers,
        int pendingDisconnects
    ) {}
}

