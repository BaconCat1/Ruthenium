package org.bacon.ruthenium.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable coordinate representing a region section.
 * <p>
 * Region sections group several chunks together so that the regionizer can operate on
 * coarse grained spatial data while still guaranteeing the region invariants described
 * by Folia. Instances of this class are value objects and may be used safely as map keys.
 */
public final class RegionSectionPos {

    private final int x;
    private final int z;

    /**
     * Creates a new position.
     *
     * @param x the section x coordinate
     * @param z the section z coordinate
     */
    public RegionSectionPos(final int x, final int z) {
        this.x = x;
        this.z = z;
    }

    /**
     * Converts chunk coordinates into a section coordinate by applying the configured bit shift.
     *
     * @param chunkX      the chunk x coordinate
     * @param chunkZ      the chunk z coordinate
     * @param chunkShift  the log2 of the section grid size
     * @return a new region section position
     */
    public static RegionSectionPos fromChunk(final int chunkX, final int chunkZ, final int chunkShift) {
        return new RegionSectionPos(chunkX >> chunkShift, chunkZ >> chunkShift);
    }

    /**
     * Generates all positions within the supplied Chebyshev radius.
     *
     * @param radius the inclusive radius to iterate
     * @return a collection containing this position and all positions in the radius
     */
    public Collection<RegionSectionPos> surrounding(final int radius) {
        final List<RegionSectionPos> positions = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                positions.add(new RegionSectionPos(this.x + dx, this.z + dz));
            }
        }
        return positions;
    }

    /**
     * Computes the Chebyshev distance between this position and another.
     *
     * @param other the other position
     * @return the Chebyshev distance
     */
    public int chebyshevDistance(final RegionSectionPos other) {
        return Math.max(Math.abs(this.x - other.x), Math.abs(this.z - other.z));
    }

    /**
     * Returns the section X coordinate.
     *
     * @return section X
     */
    public int x() {
        return this.x;
    }

    /**
     * Returns the section Z coordinate.
     *
     * @return section Z
     */
    public int z() {
        return this.z;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RegionSectionPos that = (RegionSectionPos) o;
        return this.x == that.x && this.z == that.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.x, this.z);
    }

    @Override
    public String toString() {
        return "RegionSectionPos{" + "x=" + this.x + ", z=" + this.z + '}';
    }
}
