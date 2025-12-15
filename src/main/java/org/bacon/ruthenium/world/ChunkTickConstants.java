package org.bacon.ruthenium.world;

/**
 * Shared constants used when tracking tick ranges and spawn coverage around players.
 */
public final class ChunkTickConstants {

    public static final int PLAYER_SPAWN_TRACK_RANGE = 8;
    public static final int NARROW_SPAWN_TRACK_RANGE = (int)Math.floor((PLAYER_SPAWN_TRACK_RANGE / Math.sqrt(2.0)) - 0.5);

    private ChunkTickConstants() {
    }
}
