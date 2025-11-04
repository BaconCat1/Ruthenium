package org.bacon.ruthenium.region;

import java.util.Set;

/**
 * {@link RegionDataController} implementation that manages {@link RegionTickData} instances.
 */
public final class RegionTickDataController implements RegionDataController<RegionTickData> {

    @Override
    public RegionTickData createData(final ThreadedRegion<RegionTickData> region) {
        return new RegionTickData();
    }

    @Override
    public void mergeData(final ThreadedRegion<RegionTickData> intoRegion, final RegionTickData intoData,
                          final ThreadedRegion<RegionTickData> fromRegion, final RegionTickData fromData) {
        final long currentOffset = fromData.getCurrentTick() - intoData.getCurrentTick();
        final long redstoneOffset = fromData.getRedstoneTick() - intoData.getRedstoneTick();
        fromData.applyOffset(currentOffset, redstoneOffset);
        intoData.absorb(fromData);
    }

    @Override
    public RegionTickData splitData(final ThreadedRegion<RegionTickData> parentRegion, final RegionTickData parentData,
                                    final Set<RegionSection> newRegionSections) {
        return parentData.copy();
    }
}
