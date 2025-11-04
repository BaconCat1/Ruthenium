package org.bacon.ruthenium.region;

import java.util.List;
import java.util.Objects;
import org.bacon.ruthenium.world.TickRegionScheduler;

/**
 * Region callbacks mirroring Folia's TickRegions wiring while delegating to the
 * current TickRegionScheduler implementation.
 */
public final class TickRegions implements ThreadedRegionizer.RegionCallbacks<RegionTickData, RegionTickData.RegionSectionData> {

    private final TickRegionScheduler scheduler;

    public TickRegions(final TickRegionScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public RegionTickData.RegionSectionData createNewSectionData(final int sectionX, final int sectionZ, final int sectionShift) {
        return new RegionTickData.RegionSectionData();
    }

    @Override
    public RegionTickData createNewData(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> forRegion) {
        Objects.requireNonNull(forRegion, "forRegion");
        final RegionTickData data = new RegionTickData();
        data.attachRegion(forRegion, this.scheduler);
        return data;
    }

    @Override
    public void onRegionCreate(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
    }

    @Override
    public void onRegionDestroy(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
    }

    @Override
    public void onRegionActive(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
        this.scheduler.scheduleRegion(region.getData().getScheduleHandle());
    }

    @Override
    public void onRegionInactive(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
        final RegionTickData data = region.getData();
        this.scheduler.descheduleRegion(data.getScheduleHandle());
        data.refreshScheduleHandle();
    }

    @Override
    public void preMerge(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> from,
                         final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> into) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(into, "into");
    }

    @Override
    public void preSplit(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> from,
                         final List<ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData>> into) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(into, "into");
    }
}
