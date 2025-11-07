package org.bacon.ruthenium.region;

/**
 * Represents the lifecycle of a {@link ThreadedRegionizer.ThreadedRegion}.
 * <p>
 * Regions flow through the states as follows:
 * <ul>
 *     <li>{@link #READY} - the region is eligible to begin ticking.</li>
 *     <li>{@link #TICKING} - the region is actively being ticked by a worker thread.</li>
 *     <li>{@link #TRANSIENT} - the region temporarily exists only to hold sections while
 *     pending merges are completed.</li>
 *     <li>{@link #DEAD} - the region has released all sections and must not be used again.</li>
 * </ul>
 */
public enum RegionState {
    /**
     * Region can be scheduled to tick.
     */
    READY,

    /**
     * Region is currently ticking and must not be modified by other threads.
     */
    TICKING,

    /**
     * Region temporarily exists until it can be merged into another region.
     */
    TRANSIENT,

    /**
     * Region has been merged or otherwise removed from the regionizer.
     */
    DEAD
}
