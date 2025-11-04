package org.bacon.ruthenium.region;

import java.util.HashSet;
import java.util.Set;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Simple region data container that stores per-region tick counters.
 */
public final class RegionTickData {

    private long currentTick;
    private long redstoneTick;
    private final LongSet chunks = new LongOpenHashSet();
    private final RegionTaskQueue taskQueue = new RegionTaskQueue();

    /**
     * @return the current tick counter for the region.
     */
    public long getCurrentTick() {
        return this.currentTick;
    }

    /**
     * @return the redstone tick counter for the region.
     */
    public long getRedstoneTick() {
        return this.redstoneTick;
    }

    /**
     * Advances the current tick counter by one.
     */
    public void advanceCurrentTick() {
        this.currentTick++;
    }

    /**
     * Advances the redstone tick counter by one.
     */
    public void advanceRedstoneTick() {
        this.redstoneTick++;
    }

    /**
     * Applies an offset to both counters so that they align with another region's time line.
     *
     * @param currentOffset  offset to apply to the current tick counter
     * @param redstoneOffset offset to apply to the redstone tick counter
     */
    public void applyOffset(final long currentOffset, final long redstoneOffset) {
        this.currentTick += currentOffset;
        this.redstoneTick += redstoneOffset;
    }

    /**
     * Integrates the provided data into this instance, keeping the most up-to-date counters.
     *
     * @param other the data instance to merge
     */
    public void absorb(final RegionTickData other) {
        this.currentTick = Math.max(this.currentTick, other.currentTick);
        this.redstoneTick = Math.max(this.redstoneTick, other.redstoneTick);
        this.chunks.addAll(other.chunks);
        this.taskQueue.absorb(other.taskQueue);
    }

    /**
     * Creates a deep copy of this data object.
     *
     * @return the copy
     */
    public RegionTickData copy() {
        final RegionTickData copy = new RegionTickData();
        copy.currentTick = this.currentTick;
        copy.redstoneTick = this.redstoneTick;
        copy.chunks.addAll(this.chunks);
        this.taskQueue.copyInto(copy.taskQueue);
        return copy;
    }

    public void addChunk(final int chunkX, final int chunkZ) {
        this.chunks.add(encodeChunk(chunkX, chunkZ));
    }

    public void removeChunk(final int chunkX, final int chunkZ) {
        this.chunks.remove(encodeChunk(chunkX, chunkZ));
    }

    public LongSet getChunks() {
        return this.chunks;
    }

    public boolean containsChunk(final int chunkX, final int chunkZ) {
        return this.chunks.contains(encodeChunk(chunkX, chunkZ));
    }

    /**
     * @return queue used to coordinate tasks scheduled against this region.
     */
    public RegionTaskQueue getTaskQueue() {
        return this.taskQueue;
    }

    public RegionTickData splitForSections(final Set<RegionSection> sections, final int sectionChunkShift) {
        final RegionTickData child = new RegionTickData();
        child.currentTick = this.currentTick;
        child.redstoneTick = this.redstoneTick;

        final Set<RegionSectionPos> sectionPositions = new HashSet<>();
        for (final RegionSection section : sections) {
            sectionPositions.add(section.getPosition());
        }

        final LongIterator iterator = this.chunks.iterator();
        while (iterator.hasNext()) {
            final long chunkKey = iterator.nextLong();
            final int chunkX = decodeChunkX(chunkKey);
            final int chunkZ = decodeChunkZ(chunkKey);
            final RegionSectionPos pos = RegionSectionPos.fromChunk(chunkX, chunkZ, sectionChunkShift);
            if (sectionPositions.contains(pos)) {
                iterator.remove();
                child.chunks.add(chunkKey);
            }
        }

        final RegionTaskQueue transferredTasks = this.taskQueue.splitForSections(sectionPositions, sectionChunkShift);
        child.taskQueue.absorb(transferredTasks);

        return child;
    }

    public static int decodeChunkX(final long chunkKey) {
        return (int)(chunkKey & 0xFFFFFFFFL);
    }

    public static int decodeChunkZ(final long chunkKey) {
        return (int)((chunkKey >>> 32) & 0xFFFFFFFFL);
    }

    private static long encodeChunk(final int chunkX, final int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }
}
