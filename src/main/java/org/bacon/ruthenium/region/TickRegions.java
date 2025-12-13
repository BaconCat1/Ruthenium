package org.bacon.ruthenium.region;

import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.debug.RegionDebug;
import org.bacon.ruthenium.world.TickRegionScheduler;

/**
 * Region callbacks mirroring Folia's TickRegions wiring while delegating to the
 * current TickRegionScheduler implementation.
 */
public final class TickRegions implements ThreadedRegionizer.RegionCallbacks<RegionTickData, RegionTickData.RegionSectionData> {

    private static final Logger LOGGER = LogManager.getLogger(TickRegions.class);

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
        final RegionTickData data = new RegionTickData(forRegion.regioniser.world);
        data.attachRegion(forRegion, this.scheduler);
        return data;
    }

    @Override
    public void onRegionCreate(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
        if (TickRegionScheduler.VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] onRegionCreate: region {} with {} sections (state={})",
                region.id, region.getOwnedSections().size(), region.getStateForDebug());
        }
        if (RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE)) {
            RegionDebug.log(RegionDebug.LogCategory.LIFECYCLE,
                "Region {} created with {} sections", region.id, region.getOwnedSections().size());
        }
    }

    @Override
    public void onRegionDestroy(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
        if (TickRegionScheduler.VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] onRegionDestroy: region {} (state={})", region.id, region.getStateForDebug());
        }
        if (RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE)) {
            RegionDebug.log(RegionDebug.LogCategory.LIFECYCLE,
                "Region {} destroyed", region.id);
        }
    }

    @Override
    public void onRegionActive(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
        if (TickRegionScheduler.VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] onRegionActive: region {} (sections={}, chunks={}, state={})",
                region.id, region.getOwnedSections().size(), region.getOwnedChunks().size(), region.getStateForDebug());
        }
        if (RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE)) {
            RegionDebug.log(RegionDebug.LogCategory.LIFECYCLE,
                "Region {} became ACTIVE (sections={}, chunks={})", region.id,
                region.getOwnedSections().size(), region.getOwnedChunks().size());
        }
        this.scheduler.scheduleRegion(region.getData().getScheduleHandle());
    }

    @Override
    public void onRegionInactive(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
        if (TickRegionScheduler.VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] onRegionInactive: region {} (state={})", region.id, region.getStateForDebug());
        }
        if (RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE)) {
            RegionDebug.log(RegionDebug.LogCategory.LIFECYCLE,
                "Region {} became INACTIVE", region.id);
        }
        final RegionTickData data = region.getData();
        this.scheduler.descheduleRegion(data.getScheduleHandle());
        data.refreshScheduleHandle();
    }

    @Override
    public void preMerge(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> from,
                         final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> into) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(into, "into");
        if (TickRegionScheduler.VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] preMerge: region {} merging into {} (from.state={}, into.state={})",
                from.id, into.id, from.getStateForDebug(), into.getStateForDebug());
        }
        if (RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE)) {
            RegionDebug.log(RegionDebug.LogCategory.LIFECYCLE,
                "Region {} merging into {}", from.id, into.id);
        }
    }

    @Override
    public void preSplit(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> from,
                         final List<ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData>> into) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(into, "into");
        if (TickRegionScheduler.VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] preSplit: region {} splitting into {} regions (state={})",
                from.id, into.size(), from.getStateForDebug());
        }
        if (RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE)) {
            RegionDebug.log(RegionDebug.LogCategory.LIFECYCLE,
                "Region {} splitting into {} regions", from.id, into.size());
        }
    }
}
