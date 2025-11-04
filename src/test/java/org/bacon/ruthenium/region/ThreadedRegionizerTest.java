package org.bacon.ruthenium.region;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.bacon.ruthenium.util.CoordinateUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ThreadedRegionizer} using a lightweight set of callbacks that exercise
 * the core region management behaviour without relying on Minecraft world state.
 */
class ThreadedRegionizerTest {

    @Test
    void addChunkCreatesRegionWithBufferSections() {
        final TestHarness harness = this.createHarness();
        final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer = harness.regionizer;

        regionizer.addChunk(0, 0);

        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> region =
            requireRegion(regionizer, 0, 0);

        Assertions.assertEquals(1, collectRegions(regionizer).size(), "Expected a single region");

        final int radius = regionizer.emptySectionCreateRadius;
        final int expectedSections = (radius * 2 + 1) * (radius * 2 + 1);
        Assertions.assertEquals(expectedSections, region.getOwnedSections().size(),
            "Region should allocate surrounding buffer sections");

        final RegionSectionPos origin = RegionSectionPos.fromChunk(0, 0, regionizer.sectionChunkShift);
        Assertions.assertTrue(toSectionPositions(region).contains(origin));

        Assertions.assertEquals(1, harness.callbacks.getCreateInvocations(), "Region create callback invoked once");
        Assertions.assertEquals(1, harness.callbacks.activationCount(region.id), "Region should become active once");
    }

    @Test
    void regionsMergeWhenBridgedWithChunks() {
        final TestHarness harness = this.createHarness();
        final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer = harness.regionizer;

        regionizer.addChunk(0, 0);
        requireRegion(regionizer, 0, 0);

        regionizer.addChunk(64, 0);
        requireRegion(regionizer, 64, 0);

        Assertions.assertEquals(2, collectRegions(regionizer).size(), "Expected two separate regions before merge");

        regionizer.addChunk(32, 0);
        requireRegion(regionizer, 32, 0);

        Assertions.assertEquals(1, collectRegions(regionizer).size(), "Regions should merge after bridge chunk");

        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> originRegion =
            requireRegion(regionizer, 0, 0);
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> distantRegion =
            requireRegion(regionizer, 64, 0);

        Assertions.assertEquals(originRegion.id, distantRegion.id,
            "Bridge should unify both chunks into the same region");

        final int shift = regionizer.sectionChunkShift;
        Assertions.assertTrue(toSectionPositions(originRegion)
            .contains(RegionSectionPos.fromChunk(64, 0, shift)), "Merged region should own distant section");
    }

    @Test
    void deferredMergeCompletesAfterTickRelease() {
        final TestHarness harness = this.createHarness();
        final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer = harness.regionizer;

        regionizer.addChunk(0, 0);
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> primary =
            requireRegion(regionizer, 0, 0);

        Assertions.assertTrue(primary.tryMarkTicking(alwaysFalse()), "Region should enter ticking state");

        regionizer.addChunk(32, 0);
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> secondary =
            requireRegion(regionizer, 32, 0);

        Assertions.assertEquals(2, collectRegions(regionizer).size(),
            "Second region should exist while primary is ticking");
        Assertions.assertNotEquals(primary.id, secondary.id);

        Assertions.assertTrue(primary.markNotTicking(), "Primary region should return to ready state");

        Assertions.assertEquals(1, collectRegions(regionizer).size(), "Regions should merge after tick release");
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> mergedPrimary =
            requireRegion(regionizer, 0, 0);
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> mergedSecondary =
            requireRegion(regionizer, 32, 0);
        Assertions.assertEquals(mergedPrimary.id, mergedSecondary.id,
            "Deferred merge should complete once ticking finishes");
    }

    @Test
    void regionDataMergeRetainsLargestCounters() {
        final TestHarness harness = this.createHarness();
        final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer = harness.regionizer;

        regionizer.addChunk(0, 0);
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> first =
            requireRegion(regionizer, 0, 0);
        first.getData().advanceCurrentTick(5);
        first.getData().advanceRedstoneTick(3);

        regionizer.addChunk(64, 0);
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> second =
            requireRegion(regionizer, 64, 0);
        second.getData().advanceCurrentTick(12);
        second.getData().advanceRedstoneTick(8);

        regionizer.addChunk(32, 0);
        requireRegion(regionizer, 32, 0);

        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> merged =
            requireRegion(regionizer, 0, 0);
        Assertions.assertEquals(12L, merged.getData().getCurrentTick(),
            "Merge should retain the highest current tick counter");
        Assertions.assertEquals(8L, merged.getData().getRedstoneTick(),
            "Merge should retain the highest redstone tick counter");
    }

    @Test
    void regionDataTracksChunkMembershipAcrossMerges() {
        final TestHarness harness = this.createHarness();
        final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer = harness.regionizer;

        regionizer.addChunk(0, 0);
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> first =
            requireRegion(regionizer, 0, 0);
        first.getData().addChunk(0, 0);

        regionizer.addChunk(64, 0);
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> second =
            requireRegion(regionizer, 64, 0);
        second.getData().addChunk(64, 0);

        regionizer.addChunk(32, 0);
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> bridge =
            requireRegion(regionizer, 32, 0);
        bridge.getData().addChunk(32, 0);

        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> merged =
            requireRegion(regionizer, 0, 0);
        final TestRegionData data = merged.getData();
        Assertions.assertTrue(data.containsChunk(0, 0), "Merged data should retain original chunk");
        Assertions.assertTrue(data.containsChunk(64, 0), "Merged data should include distant chunk");
        Assertions.assertTrue(data.containsChunk(32, 0), "Merged data should include bridge chunk");
    }

    private TestHarness createHarness() {
        final RegionizerConfig config = RegionizerConfig.builder()
            .emptySectionCreationRadius(1)
            .mergeRadius(1)
            .recalculationSectionCount(4)
            .maxDeadSectionPercent(0.10D)
            .sectionChunkShift(4)
            .build();

        final TestRegionCallbacks callbacks = new TestRegionCallbacks();
        final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer =
            new ThreadedRegionizer<>(config, null, callbacks);
        return new TestHarness(regionizer, callbacks);
    }

    private static BooleanSupplier alwaysFalse() {
        return () -> false;
    }

    private static ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> requireRegion(
        final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer,
        final int chunkX, final int chunkZ) {
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> region =
            regionizer.getRegionForChunk(chunkX, chunkZ);
        Assertions.assertNotNull(region, () -> "Expected region at chunk (" + chunkX + ',' + chunkZ + ')');
        return region;
    }

    private static Set<ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData>> collectRegions(
        final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer) {
        final Set<ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData>> regions = new HashSet<>();
        regionizer.computeForAllRegions(regions::add);
        return regions;
    }

    private static Set<RegionSectionPos> toSectionPositions(
        final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> region) {
        final LongArrayList sectionKeys = region.getOwnedSections();
        final Set<RegionSectionPos> positions = new HashSet<>(sectionKeys.size());
        for (int i = 0; i < sectionKeys.size(); ++i) {
            final long key = sectionKeys.getLong(i);
            positions.add(new RegionSectionPos(CoordinateUtil.getChunkX(key), CoordinateUtil.getChunkZ(key)));
        }
        return positions;
    }

    private static final class TestHarness {
        private final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer;
        private final TestRegionCallbacks callbacks;

        private TestHarness(final ThreadedRegionizer<TestRegionData, TestSectionData> regionizer,
                            final TestRegionCallbacks callbacks) {
            this.regionizer = regionizer;
            this.callbacks = callbacks;
        }
    }

    private static final class TestRegionCallbacks
        implements ThreadedRegionizer.RegionCallbacks<TestRegionData, TestSectionData> {

        private final Map<Long, Integer> activationCounts = new HashMap<>();
        private int createInvocations;
        private int destroyInvocations;

        @Override
        public TestSectionData createNewSectionData(final int sectionX, final int sectionZ, final int sectionShift) {
            return new TestSectionData();
        }

        @Override
        public TestRegionData createNewData(
            final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> forRegion) {
            final TestRegionData data = new TestRegionData();
            data.attach(forRegion);
            return data;
        }

        @Override
        public void onRegionCreate(
            final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> region) {
            this.createInvocations++;
        }

        @Override
        public void onRegionDestroy(
            final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> region) {
            this.destroyInvocations++;
        }

        @Override
        public void onRegionActive(
            final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> region) {
            this.activationCounts.merge(region.id, 1, Integer::sum);
        }

        @Override
        public void onRegionInactive(
            final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> region) {
            this.activationCounts.merge(region.id, 1, Integer::sum);
        }

        @Override
        public void preMerge(
            final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> from,
            final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> into) {
            // no-op test hook
        }

        @Override
        public void preSplit(
            final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> from,
            final java.util.List<ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData>> into) {
            // no-op test hook
        }

        int getCreateInvocations() {
            return this.createInvocations;
        }

        int getDestroyInvocations() {
            return this.destroyInvocations;
        }

        int activationCount(final long regionId) {
            return this.activationCounts.getOrDefault(regionId, 0);
        }
    }

    private static final class TestRegionData
        implements ThreadedRegionizer.ThreadedRegionData<TestRegionData, TestSectionData> {

        private ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> region;
        private long currentTick;
        private long redstoneTick;
        private final LongSet chunks = new LongOpenHashSet();

        void attach(final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> region) {
            this.region = region;
        }

        void advanceCurrentTick(final long amount) {
            this.currentTick += amount;
        }

        void advanceRedstoneTick(final long amount) {
            this.redstoneTick += amount;
        }

        long getCurrentTick() {
            return this.currentTick;
        }

        long getRedstoneTick() {
            return this.redstoneTick;
        }

        void addChunk(final int chunkX, final int chunkZ) {
            this.chunks.add(CoordinateUtil.getChunkKey(chunkX, chunkZ));
        }

        void removeChunk(final int chunkX, final int chunkZ) {
            this.chunks.remove(CoordinateUtil.getChunkKey(chunkX, chunkZ));
        }

        boolean containsChunk(final int chunkX, final int chunkZ) {
            return this.chunks.contains(CoordinateUtil.getChunkKey(chunkX, chunkZ));
        }

        @Override
        public void split(final ThreadedRegionizer<TestRegionData, TestSectionData> regioniser,
                          final Long2ReferenceOpenHashMap<ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData>> into,
                          final ReferenceOpenHashSet<ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData>> regions) {
            for (final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> target : regions) {
                final TestRegionData targetData = target.getData();
                targetData.currentTick = Math.max(targetData.currentTick, this.currentTick);
                targetData.redstoneTick = Math.max(targetData.redstoneTick, this.redstoneTick);
            }

            final int shift = regioniser.sectionChunkShift;
            final LongIterator iterator = this.chunks.iterator();
            while (iterator.hasNext()) {
                final long chunkKey = iterator.nextLong();
                final int sectionX = CoordinateUtil.getChunkX(chunkKey) >> shift;
                final int sectionZ = CoordinateUtil.getChunkZ(chunkKey) >> shift;
                final long sectionKey = CoordinateUtil.getChunkKey(sectionX, sectionZ);
                final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> target = into.get(sectionKey);
                if (target != null) {
                    target.getData().chunks.add(chunkKey);
                    iterator.remove();
                }
            }
        }

        @Override
        public void mergeInto(final ThreadedRegionizer.ThreadedRegion<TestRegionData, TestSectionData> into) {
            final TestRegionData targetData = into.getData();
            targetData.currentTick = Math.max(targetData.currentTick, this.currentTick);
            targetData.redstoneTick = Math.max(targetData.redstoneTick, this.redstoneTick);
            targetData.chunks.addAll(this.chunks);
            this.chunks.clear();
        }
    }

    private static final class TestSectionData implements ThreadedRegionizer.ThreadedRegionSectionData {
    }
}
