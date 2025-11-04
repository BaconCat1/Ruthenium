package org.bacon.ruthenium.region;

import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ThreadedRegionizer}.
 */
class ThreadedRegionizerTest {

    private ThreadedRegionizer<RegionTickData> createRegionizer() {
        return new ThreadedRegionizer<>(
            RegionizerConfig.builder()
                .emptySectionCreationRadius(1)
                .mergeRadius(1)
                .recalculationSectionCount(4)
                .maxDeadSectionPercent(0.10D)
                .sectionChunkShift(4)
                .build(),
            new RegionTickDataController()
        );
    }

    private void advanceTicks(final RegionTickData data, final long currentTicks, final long redstoneTicks) {
        for (int i = 0; i < currentTicks; i++) {
            data.advanceCurrentTick();
        }
        for (int i = 0; i < redstoneTicks; i++) {
            data.advanceRedstoneTick();
        }
    }

    @Test
    void testNewChunkCreatesRegion() {
        final ThreadedRegionizer<RegionTickData> regionizer = createRegionizer();
        final ThreadedRegion<RegionTickData> region = regionizer.addChunk(0, 0);
        Assertions.assertNotNull(region);
        Assertions.assertEquals(RegionState.READY, region.getState());
        final int expectedSections = (int) Math.pow((regionizer.getConfig().getEmptySectionCreationRadius() * 2) + 1, 2);
        Assertions.assertEquals(expectedSections, region.sectionCount(), "Region should own buffer sections");
        final RegionSectionPos origin = RegionSectionPos.fromChunk(0, 0, regionizer.getConfig().getSectionChunkShift());
        Assertions.assertTrue(region.getSections().containsKey(origin));
    }

    @Test
    void testRegionsMergeWhenAdjacent() {
        final ThreadedRegionizer<RegionTickData> regionizer = createRegionizer();
        final ThreadedRegion<RegionTickData> first = regionizer.addChunk(0, 0);
        final ThreadedRegion<RegionTickData> second = regionizer.addChunk(64, 0);
        Assertions.assertNotEquals(first.getId(), second.getId());
        Assertions.assertEquals(2, regionizer.snapshotRegions().size());
        regionizer.addChunk(32, 0);
        Assertions.assertEquals(1, regionizer.snapshotRegions().size());
        final ThreadedRegion<RegionTickData> merged = regionizer.snapshotRegions().iterator().next();
        final RegionSectionPos origin = RegionSectionPos.fromChunk(0, 0, regionizer.getConfig().getSectionChunkShift());
        final RegionSectionPos distant = RegionSectionPos.fromChunk(64, 0, regionizer.getConfig().getSectionChunkShift());
        Assertions.assertTrue(merged.getSections().containsKey(origin));
        Assertions.assertTrue(merged.getSections().containsKey(distant));
    }

    @Test
    void testMergeLaterCompletesAfterTick() {
        final ThreadedRegionizer<RegionTickData> regionizer = createRegionizer();
        final ThreadedRegion<RegionTickData> ticking = regionizer.addChunk(0, 0);
        Assertions.assertTrue(regionizer.tryMarkTicking(ticking));
        final ThreadedRegion<RegionTickData> transientRegion = regionizer.addChunk(16, 0);
        Assertions.assertNotEquals(ticking.getId(), transientRegion.getId());
        Assertions.assertEquals(RegionState.TRANSIENT, transientRegion.getState());
        Assertions.assertEquals(2, regionizer.snapshotRegions().size());
        Assertions.assertTrue(regionizer.markNotTicking(ticking));
        final Set<ThreadedRegion<RegionTickData>> remaining = regionizer.snapshotRegions();
        Assertions.assertEquals(1, remaining.size());
        final ThreadedRegion<RegionTickData> survivor = remaining.iterator().next();
        Assertions.assertEquals(ticking.getId(), survivor.getId());
        final RegionSectionPos neighbor = RegionSectionPos.fromChunk(16, 0, regionizer.getConfig().getSectionChunkShift());
        Assertions.assertTrue(survivor.getSections().containsKey(neighbor));
    }

    @Test
    void testRegionDataMergePrefersLargestCounters() {
        final ThreadedRegionizer<RegionTickData> regionizer = createRegionizer();
        final ThreadedRegion<RegionTickData> first = regionizer.addChunk(0, 0);
        final ThreadedRegion<RegionTickData> second = regionizer.addChunk(64, 0);

        advanceTicks(first.getData(), 5, 3);
        advanceTicks(second.getData(), 12, 8);

        regionizer.addChunk(32, 0);

        final Set<ThreadedRegion<RegionTickData>> regions = regionizer.snapshotRegions();
        Assertions.assertEquals(1, regions.size());
        final ThreadedRegion<RegionTickData> merged = regions.iterator().next();
        Assertions.assertEquals(12, merged.getData().getCurrentTick());
        Assertions.assertEquals(8, merged.getData().getRedstoneTick());
    }
}
