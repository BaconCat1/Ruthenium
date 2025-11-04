package org.bacon.ruthenium.region;

import java.util.Objects;
import java.util.Set;

/**
 * {@link RegionDataController} implementation that manages {@link RegionTickData} instances.
 */
public final class RegionTickDataController implements RegionDataController<RegionTickData> {

    @Override
    public RegionTickData createData(final ThreadedRegion<RegionTickData> region) {
        Objects.requireNonNull(region, "region");
        return new RegionTickData();
    }

    @Override
    public void mergeData(final ThreadedRegion<RegionTickData> intoRegion, final RegionTickData intoData,
                          final ThreadedRegion<RegionTickData> fromRegion, final RegionTickData fromData) {
        Objects.requireNonNull(intoRegion, "intoRegion");
        Objects.requireNonNull(intoData, "intoData");
        Objects.requireNonNull(fromRegion, "fromRegion");
        Objects.requireNonNull(fromData, "fromData");
        intoData.absorb(fromData);
    }

    @Override
    public RegionTickData splitData(final ThreadedRegion<RegionTickData> parentRegion, final RegionTickData parentData,
                                    final Set<RegionSection> newRegionSections) {
        Objects.requireNonNull(parentRegion, "parentRegion");
        Objects.requireNonNull(parentData, "parentData");
        Objects.requireNonNull(newRegionSections, "newRegionSections");
        final int sectionShift = parentRegion.getRegionizer().getConfig().getSectionChunkShift();
        return parentData.splitForSections(newRegionSections, sectionShift);
    }
}
