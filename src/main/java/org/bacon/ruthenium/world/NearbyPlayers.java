package org.bacon.ruthenium.world;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.bacon.ruthenium.util.CoordinateUtil;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks nearby players with chunk-distance bucketing so region services can answer proximity queries.
 */
public final class NearbyPlayers {

    private static final int GENERAL_AREA_VIEW_DISTANCE = 33;
    private static final int GENERAL_SMALL_VIEW_DISTANCE = 10;
    private static final int GENERAL_REALLY_SMALL_VIEW_DISTANCE = 3;

    private final ServerWorld world;
    private final Reference2ReferenceOpenHashMap<ServerPlayerEntity, TrackedPlayer[]> trackedPlayers = new Reference2ReferenceOpenHashMap<>();
    private final Long2ReferenceOpenHashMap<TrackedChunk> byChunk = new Long2ReferenceOpenHashMap<>();

    public NearbyPlayers(final ServerWorld world) {
        this.world = world;
    }

    public void addPlayer(final ServerPlayerEntity player) {
        final TrackedPlayer[] trackers = new TrackedPlayer[NearbyMapType.values().length];
        if (this.trackedPlayers.putIfAbsent(player, trackers) != null) {
            return;
        }
        for (int i = 0; i < trackers.length; ++i) {
            trackers[i] = new TrackedPlayer(player, NearbyMapType.values()[i]);
        }
        this.tickPlayer(player);
    }

    public void removePlayer(final ServerPlayerEntity player) {
        final TrackedPlayer[] trackers = this.trackedPlayers.remove(player);
        if (trackers == null) {
            return;
        }
        for (final TrackedPlayer tracker : trackers) {
            tracker.remove();
        }
    }

    public void clear() {
        if (this.trackedPlayers.isEmpty()) {
            return;
        }
        final List<ServerPlayerEntity> snapshot = new ArrayList<>(this.trackedPlayers.keySet());
        for (final ServerPlayerEntity player : snapshot) {
            this.removePlayer(player);
        }
    }

    public void tickPlayer(final ServerPlayerEntity player) {
        final TrackedPlayer[] trackers = this.trackedPlayers.get(player);
        if (trackers == null) {
            return;
        }
        final ChunkPos chunk = player.getChunkPos();
        for (final TrackedPlayer tracker : trackers) {
            tracker.update(chunk.x, chunk.z, tracker.getDistance(player));
        }
    }

    public ReferenceOpenHashSet<ServerPlayerEntity> getPlayersByChunk(final int chunkX,
                                                                       final int chunkZ,
                                                                       final NearbyMapType type) {
        final TrackedChunk chunk = this.byChunk.get(CoordinateUtil.getChunkKey(chunkX, chunkZ));
        return chunk == null ? null : chunk.getPlayers(type);
    }

    public ReferenceOpenHashSet<ServerPlayerEntity> getPlayersByChunk(final ChunkPos chunkPos,
                                                                       final NearbyMapType type) {
        return this.getPlayersByChunk(chunkPos.x, chunkPos.z, type);
    }

    public ReferenceOpenHashSet<ServerPlayerEntity> getPlayersByPosition(final int blockX,
                                                                         final int blockZ,
                                                                         final NearbyMapType type) {
        return this.getPlayersByChunk(blockX >> 4, blockZ >> 4, type);
    }

    private TrackedChunk getOrCreateChunk(final long chunkKey) {
        final TrackedChunk chunk = this.byChunk.get(chunkKey);
        if (chunk != null) {
            return chunk;
        }
        final TrackedChunk created = new TrackedChunk(chunkKey);
        this.byChunk.put(chunkKey, created);
        return created;
    }

    private void removeChunkIfEmpty(final long chunkKey, final TrackedChunk chunk) {
        if (chunk != null && chunk.isEmpty()) {
            this.byChunk.remove(chunkKey);
        }
    }

    private final class TrackedChunk {

        private final long chunkKey;
        @SuppressWarnings({"unchecked", "rawtypes"})
        private final ReferenceOpenHashSet<ServerPlayerEntity>[] playerSets = (ReferenceOpenHashSet<ServerPlayerEntity>[]) new ReferenceOpenHashSet[NearbyMapType.values().length];

        TrackedChunk(final long chunkKey) {
            this.chunkKey = chunkKey;
        }

        ReferenceOpenHashSet<ServerPlayerEntity> getPlayers(final NearbyMapType type) {
            return this.playerSets[type.ordinal()];
        }

        void addPlayer(final ServerPlayerEntity player, final NearbyMapType type) {
            final int idx = type.ordinal();
            ReferenceOpenHashSet<ServerPlayerEntity> set = this.playerSets[idx];
            if (set == null) {
                set = new ReferenceOpenHashSet<>();
                this.playerSets[idx] = set;
            }
            set.add(player);
        }

        void removePlayer(final ServerPlayerEntity player, final NearbyMapType type) {
            final int idx = type.ordinal();
            final ReferenceOpenHashSet<ServerPlayerEntity> set = this.playerSets[idx];
            if (set == null) {
                return;
            }
            set.remove(player);
        }

        boolean isEmpty() {
            for (final ReferenceOpenHashSet<ServerPlayerEntity> set : this.playerSets) {
                if (set != null && !set.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    private final class TrackedPlayer extends SingleUserAreaMap<ServerPlayerEntity> {

        private final NearbyMapType type;

        TrackedPlayer(final ServerPlayerEntity player, final NearbyMapType type) {
            super(player);
            this.type = type;
        }

        int getDistance(final ServerPlayerEntity player) {
            switch (this.type) {
                case GENERAL:
                    return GENERAL_AREA_VIEW_DISTANCE;
                case GENERAL_SMALL:
                    return GENERAL_SMALL_VIEW_DISTANCE;
                case GENERAL_REALLY_SMALL:
                    return GENERAL_REALLY_SMALL_VIEW_DISTANCE;
                case SPAWN_RANGE:
                    return ChunkTickConstants.PLAYER_SPAWN_TRACK_RANGE;
                default:
                    throw new IllegalStateException("Unexpected map type " + this.type);
            }
        }

        @Override
        protected void addCallback(final ServerPlayerEntity player, final int chunkX, final int chunkZ) {
            final long chunkKey = CoordinateUtil.getChunkKey(chunkX, chunkZ);
            final TrackedChunk chunk = NearbyPlayers.this.getOrCreateChunk(chunkKey);
            chunk.addPlayer(player, this.type);
        }

        @Override
        protected void removeCallback(final ServerPlayerEntity player, final int chunkX, final int chunkZ) {
            final long chunkKey = CoordinateUtil.getChunkKey(chunkX, chunkZ);
            final TrackedChunk chunk = NearbyPlayers.this.byChunk.get(chunkKey);
            if (chunk != null) {
                chunk.removePlayer(player, this.type);
                NearbyPlayers.this.removeChunkIfEmpty(chunkKey, chunk);
            }
        }
    }

    public enum NearbyMapType {
        GENERAL,
        GENERAL_SMALL,
        GENERAL_REALLY_SMALL,
        SPAWN_RANGE
    }
}
