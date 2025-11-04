package org.bacon.ruthenium.world;

/**
 * Exposes hooks to temporarily allow chunk ticking while Ruthenium replaces the
 * vanilla world tick loop.
 */
public interface RegionChunkTickAccess {

    /**
     * Marks the current thread as actively ticking a chunk on behalf of the region scheduler.
     */
    void ruthenium$pushRegionChunkTick();

    /**
     * Signals that the current thread finished ticking a chunk.
     */
    void ruthenium$popRegionChunkTick();
}
