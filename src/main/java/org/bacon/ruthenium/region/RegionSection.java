package org.bacon.ruthenium.region;

import java.util.Objects;

/**
 * Represents a group of chunks owned by a {@link ThreadedRegion}.
 */
public final class RegionSection {

    private final RegionSectionPos position;
    private ThreadedRegion<?> owner;
    private int chunkCount;
    private boolean dead;

    /**
     * Creates a new section in the empty state.
     *
     * @param position the section coordinate
     */
    public RegionSection(final RegionSectionPos position) {
        this.position = Objects.requireNonNull(position, "position");
    }

    /**
     * @return the coordinate of this section.
     */
    public RegionSectionPos getPosition() {
        return this.position;
    }

    /**
     * @return the owner region or {@code null} if the section is unassigned.
     */
    public ThreadedRegion<?> getOwner() {
        return this.owner;
    }

    void setOwner(final ThreadedRegion<?> owner) {
        this.owner = owner;
    }

    /**
     * Increments the number of chunks stored in this section.
     */
    public void addChunk() {
        this.chunkCount++;
        this.dead = false;
    }

    /**
     * Decrements the number of chunks stored in this section.
     *
     * @throws IllegalStateException if there are no chunks to remove
     */
    public void removeChunk() {
        if (this.chunkCount <= 0) {
            throw new IllegalStateException("Attempted to remove a chunk from an empty section");
        }
        this.chunkCount--;
    }

    /**
     * @return {@code true} when at least one chunk is present.
     */
    public boolean hasChunks() {
        return this.chunkCount > 0;
    }

    /**
     * @return the number of chunks currently contained by this section.
     */
    public int getChunkCount() {
        return this.chunkCount;
    }

    /**
     * Marks the section as dead. Dead sections are removed during region maintenance.
     *
     * @param deadFlag {@code true} to mark dead, {@code false} to revive
     */
    public void setDead(final boolean deadFlag) {
        this.dead = deadFlag;
    }

    /**
     * @return {@code true} if the section has been marked dead.
     */
    public boolean isDead() {
        return this.dead;
    }
}
