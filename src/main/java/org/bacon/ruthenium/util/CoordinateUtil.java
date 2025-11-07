package org.bacon.ruthenium.util;

/**
 * Encodes chunk coordinates into packed long keys mirroring Folia's CoordinateUtils helper.
 */
public final class CoordinateUtil {

    private CoordinateUtil() {
    }

    /**
     * Packs chunk coordinates into a single long using Folia's chunk key encoding.
     *
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return combined chunk key
     */
    public static long getChunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    /**
     * Extracts the chunk X coordinate from a packed chunk key.
     *
     * @param chunkKey combined chunk key
     * @return original chunk X coordinate
     */
    public static int getChunkX(final long chunkKey) {
        return (int) (chunkKey & 0xFFFFFFFFL);
    }

    /**
     * Extracts the chunk Z coordinate from a packed chunk key.
     *
     * @param chunkKey combined chunk key
     * @return original chunk Z coordinate
     */
    public static int getChunkZ(final long chunkKey) {
        return (int) ((chunkKey >>> 32) & 0xFFFFFFFFL);
    }
}
