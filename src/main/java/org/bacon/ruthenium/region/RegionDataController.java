package org.bacon.ruthenium.region;

import java.util.Set;

/**
 * Abstraction used by the {@link ThreadedRegionizer} to manage region local data objects.
 *
 * @param <D> type of region data handled by the controller
 */
public interface RegionDataController<D> {

    /**
     * Creates a brand new data object for the supplied region.
     *
     * @param region the region being created
     * @return the data instance to associate with the region
     */
    D createData(ThreadedRegion<D> region);

    /**
     * Merges the data from {@code fromRegion} into {@code intoRegion}. Implementations are
     * expected to mutate {@code intoData} and to release any resources held by
     * {@code fromData} if necessary.
     *
     * @param intoRegion the region that survives the merge
     * @param intoData   data owned by the surviving region
     * @param fromRegion the region being merged
     * @param fromData   the data owned by the merged region
     */
    void mergeData(ThreadedRegion<D> intoRegion, D intoData, ThreadedRegion<D> fromRegion, D fromData);

    /**
     * Splits data from {@code parentRegion} for a newly created region containing the provided
     * section positions. Implementations may mutate {@code parentData} to remove the transferred
     * state.
     *
     * @param parentRegion the region being split
     * @param parentData   the data object currently attached to the parent region
     * @param newRegionSections sections owned by the new region
     * @return a data object for the new region
     */
    D splitData(ThreadedRegion<D> parentRegion, D parentData, Set<RegionSection> newRegionSections);
}
