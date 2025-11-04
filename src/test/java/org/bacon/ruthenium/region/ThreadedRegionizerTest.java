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
}
