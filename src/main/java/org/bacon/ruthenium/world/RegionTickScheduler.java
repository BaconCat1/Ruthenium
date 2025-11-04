package org.bacon.ruthenium.world;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.server.world.ServerWorld;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer.ThreadedRegion;

/**
 * @deprecated Use {@link TickRegionScheduler} instead. This bridge exists temporarily while callers
 * migrate to the new scheduler implementation.
 */
@Deprecated(forRemoval = true)
public final class RegionTickScheduler {

    private RegionTickScheduler() {
        throw new UnsupportedOperationException("RegionTickScheduler has been replaced by TickRegionScheduler");
    }

    public static TickRegionScheduler getInstance() {
        return TickRegionScheduler.getInstance();
    }

    public static boolean tickWorld(final ServerWorld world, final BooleanSupplier shouldKeepTicking) {
        return TickRegionScheduler.getInstance().tickWorld(world, shouldKeepTicking);
    }

    static ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> getCurrentRegion() {
        return TickRegionScheduler.getCurrentRegion();
    }

    static ServerWorld getCurrentWorld() {
        return TickRegionScheduler.getCurrentWorld();
    }

    public void onRegionCreate(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
    }

    public void onRegionDestroy(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        Objects.requireNonNull(region, "region");
    }

    public void onRegionActivated(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        TickRegionScheduler.getInstance().scheduleRegion(region.getData().getScheduleHandle());
    }

    public void onRegionDeactivated(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        final RegionTickData data = region.getData();
        TickRegionScheduler.getInstance().descheduleRegion(data.getScheduleHandle());
        data.refreshScheduleHandle();
    }

    public void onRegionPreMerge(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> from,
                                 final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> into) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(into, "into");
    }

    public void onRegionPreSplit(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> from,
                                 final java.util.List<ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData>> into) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(into, "into");
    }
}
