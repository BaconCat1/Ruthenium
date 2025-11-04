package org.bacon.ruthenium.region;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a logical ticking region managed by a {@link ThreadedRegionizer}.
 *
 * @param <D> type of region data handled by the associated {@link RegionDataController}
 */
public final class ThreadedRegion<D> {

    private final ThreadedRegionizer<D> regionizer;
    private final long id;
    private RegionState state;
    private final Map<RegionSectionPos, RegionSection> sections = new LinkedHashMap<>();
    private final Set<ThreadedRegion<D>> pendingIncoming = new LinkedHashSet<>();
    private final Set<ThreadedRegion<D>> pendingOutgoing = new LinkedHashSet<>();
    private D data;
    private boolean dirty;

    ThreadedRegion(final ThreadedRegionizer<D> regionizer, final long id, final RegionState state) {
        this.regionizer = Objects.requireNonNull(regionizer, "regionizer");
        this.id = id;
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * @return the owning regionizer.
     */
    public ThreadedRegionizer<D> getRegionizer() {
        return this.regionizer;
    }

    /**
     * @return unique identifier used for debugging.
     */
    public long getId() {
        return this.id;
    }

    /**
     * @return current lifecycle state for the region.
     */
    public RegionState getState() {
        return this.state;
    }

    void setState(final RegionState state) {
        this.state = state;
    }

    /**
     * @return immutable view of the sections owned by the region.
     */
    public Map<RegionSectionPos, RegionSection> getSections() {
        return Collections.unmodifiableMap(this.sections);
    }

    void setData(final D data) {
        this.data = data;
    }

    /**
     * @return region-local data instance.
     */
    public D getData() {
        return this.data;
    }

    void addSection(final RegionSection section) {
        this.sections.put(section.getPosition(), section);
        section.setOwner(this);
    }

    void removeSection(final RegionSection section) {
        this.sections.remove(section.getPosition());
        section.setOwner(null);
    }

    /**
     * @return {@code true} when the region currently owns no sections.
     */
    public boolean isEmpty() {
        return this.sections.isEmpty();
    }

    /**
     * @return number of sections managed by this region.
     */
    public int sectionCount() {
        return this.sections.size();
    }

    /**
     * Adds a pending incoming merge. The region will absorb {@code other} once it finishes
     * ticking.
     *
     * @param other the region to merge once this region becomes non-ticking
     */
    public void addPendingIncoming(final ThreadedRegion<D> other) {
        this.pendingIncoming.add(other);
    }

    /**
     * Adds a pending outgoing merge. The region will be merged into {@code other} once
     * {@code other} has stopped ticking.
     *
     * @param other the target of the merge
     */
    public void addPendingOutgoing(final ThreadedRegion<D> other) {
        this.pendingOutgoing.add(other);
    }

    /**
     * Clears and returns the regions that should be merged into this region.
     *
     * @return regions scheduled to merge into this instance
     */
    public Set<ThreadedRegion<D>> drainPendingIncoming() {
        final Set<ThreadedRegion<D>> result = new LinkedHashSet<>(this.pendingIncoming);
        this.pendingIncoming.clear();
        return result;
    }

    /**
     * @return {@code true} if the region still expects to be merged into another region.
     */
    public boolean hasPendingOutgoing() {
        return !this.pendingOutgoing.isEmpty();
    }

    /**
     * Removes the supplied region from the pending outgoing set.
     *
     * @param target region that has consumed the merge
     */
    public void removePendingOutgoing(final ThreadedRegion<D> target) {
        this.pendingOutgoing.remove(target);
    }

    /**
     * Returns a defensive copy of the pending outgoing regions.
     *
     * @return pending outgoing regions
     */
    public Set<ThreadedRegion<D>> getPendingOutgoing() {
        return new LinkedHashSet<>(this.pendingOutgoing);
    }

    Set<ThreadedRegion<D>> pendingIncomingInternal() {
        return this.pendingIncoming;
    }

    Set<ThreadedRegion<D>> pendingOutgoingInternal() {
        return this.pendingOutgoing;
    }

    /**
     * Marks the region for maintenance.
     */
    public void markDirty() {
        this.dirty = true;
    }

    /**
     * @return whether the region requires maintenance.
     */
    public boolean isDirty() {
        return this.dirty;
    }

    void clearDirty() {
        this.dirty = false;
    }

    /**
     * @return {@code true} if the region is currently ticking.
     */
    public boolean isTicking() {
        return this.state == RegionState.TICKING;
    }

    @Override
    public String toString() {
        return "ThreadedRegion{" +
            "id=" + this.id +
            ", state=" + this.state +
            ", sections=" + this.sections.size() +
            '}';
    }
}
