package org.bacon.ruthenium.util;

/**
 * Encodes chunk coordinates into packed long keys mirroring Folia's CoordinateUtils helper.
 */
public final class CoordinateUtil {

    private CoordinateUtil() {
    }

    public static long getChunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    public static int getChunkX(final long chunkKey) {
        return (int) (chunkKey & 0xFFFFFFFFL);
    }

    public static int getChunkZ(final long chunkKey) {
        return (int) ((chunkKey >>> 32) & 0xFFFFFFFFL);
    }
}
