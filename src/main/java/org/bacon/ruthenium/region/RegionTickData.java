package org.bacon.ruthenium.region;

/**
 * Simple region data container that stores per-region tick counters.
 */
public final class RegionTickData {

    private long currentTick;
    private long redstoneTick;

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
        return copy;
    }
}
