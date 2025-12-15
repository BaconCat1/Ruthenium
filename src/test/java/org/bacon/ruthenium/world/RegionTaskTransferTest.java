package org.bacon.ruthenium.world;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.RegionizerConfig;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Coverage for cross-region chunk task handoff and pending task queues.
 */
class RegionTaskTransferTest {

    private static final Identifier TEST_WORLD_ID = Identifier.of("ruthenium", "task_transfer_world");

    @Test
    void runOnChunkQueuesPendingUntilChunkRegistered() throws Exception {
        final ServerWorld world = createStubWorld();
        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer =
            createRegionizer(world);
        installRegionizer(world, regionizer);

        final AtomicBoolean ran = new AtomicBoolean(false);
        RegionTaskDispatcher.runOnChunk(world, 0, 0, () -> ran.set(true));

        // No region/chunk exists yet, so the task must be held pending.
        regionizer.addChunk(0, 0);
        final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region =
            requireRegion(regionizer, 0, 0);

        Assertions.assertFalse(region.getData().getTaskQueue().containsTask(0, 0),
            "Task should be held pending until the chunk is registered");

        region.getData().addChunk(0, 0);

        Assertions.assertTrue(region.getData().getTaskQueue().containsTask(0, 0),
            "Pending task should flush into the region queue once chunk registration occurs");

        final var task = region.getData().getTaskQueue().pollChunkTask();
        Assertions.assertNotNull(task, "Expected flushed task to be available");
        task.runnable().run();
        Assertions.assertTrue(ran.get(), "Flushed task should execute");
    }

    @Test
    void runOnChunkQueuesImmediatelyWhenChunkOwnedByRegion() throws Exception {
        final ServerWorld world = createStubWorld();
        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer =
            createRegionizer(world);
        installRegionizer(world, regionizer);

        regionizer.addChunk(0, 0);
        final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region =
            requireRegion(regionizer, 0, 0);

        final AtomicBoolean ran = new AtomicBoolean(false);
        RegionTaskDispatcher.runOnChunk(world, 0, 0, () -> ran.set(true));

        Assertions.assertTrue(region.getData().getTaskQueue().containsTask(0, 0),
            "Task should queue immediately when the regionizer already owns the chunk");

        final var task = region.getData().getTaskQueue().pollChunkTask();
        Assertions.assertNotNull(task, "Expected queued task to be available");
        task.runnable().run();
        Assertions.assertTrue(ran.get(), "Queued task should execute");
    }

    @Test
    void runQueuedTasksTransfersWorkToOwningRegion() throws Exception {
        final ServerWorld world = createStubWorld();
        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer =
            createRegionizer(world);
        installRegionizer(world, regionizer);

        regionizer.addChunk(0, 0);
        regionizer.addChunk(64, 0);

        final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> first =
            requireRegion(regionizer, 0, 0);
        final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> second =
            requireRegion(regionizer, 64, 0);
        Assertions.assertNotEquals(first.id, second.id, "Chunks should land in distinct regions for the transfer test");

        first.getData().addChunk(0, 0);
        second.getData().addChunk(64, 0);

        final AtomicBoolean ran = new AtomicBoolean(false);
        first.getData().getTaskQueue().queueChunkTask(64, 0, () -> ran.set(true));

        final int processed = invokeRunQueuedTasks(TickRegionScheduler.getInstance(), first.getData(), first, () -> true);
        Assertions.assertEquals(0, processed, "Transfer should requeue work instead of running on the wrong region");

        Assertions.assertFalse(ran.get(), "Transferred work should not execute on the source region");
        Assertions.assertTrue(second.getData().getTaskQueue().containsTask(64, 0),
            "Task should move onto the owning region queue");

        final var task = second.getData().getTaskQueue().pollChunkTask();
        Assertions.assertNotNull(task, "Expected transferred task to be present");
        task.runnable().run();
        Assertions.assertTrue(ran.get(), "Transferred task should execute when run from the owning region");
    }

    private static int invokeRunQueuedTasks(final TickRegionScheduler scheduler,
                                           final RegionTickData data,
                                           final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region,
                                           final BooleanSupplier guard) throws Exception {
        final Method method = TickRegionScheduler.class.getDeclaredMethod(
            "runQueuedTasks",
            RegionTickData.class,
            ThreadedRegionizer.ThreadedRegion.class,
            BooleanSupplier.class
        );
        method.setAccessible(true);
        return (int)method.invoke(scheduler, data, region, guard);
    }

    private static ServerWorld createStubWorld() {
        final RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, TEST_WORLD_ID);
        return new ServerWorld(key);
    }

    private static ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> createRegionizer(final ServerWorld world) {
        final RegionizerConfig config = RegionizerConfig.builder()
            .emptySectionCreationRadius(1)
            .mergeRadius(1)
            .recalculationSectionCount(4)
            .maxDeadSectionPercent(0.10D)
            .sectionChunkShift(4)
            .build();
        return new ThreadedRegionizer<>(config, world, new NoScheduleTickRegions(world));
    }

    private static void installRegionizer(final ServerWorld world,
                                          final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer) throws Exception {
        final Field field = ServerWorld.class.getDeclaredField("regionizer");
        field.setAccessible(true);
        field.set(world, regionizer);
    }

    private static ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> requireRegion(
        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer,
        final int chunkX,
        final int chunkZ) {
        final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region =
            regionizer.getRegionForChunk(chunkX, chunkZ);
        Assertions.assertNotNull(region, () -> "Expected region at chunk (" + chunkX + ',' + chunkZ + ')');
        return region;
    }

    private static final class NoScheduleTickRegions
        implements ThreadedRegionizer.RegionCallbacks<RegionTickData, RegionTickData.RegionSectionData> {

        private final ServerWorld world;
        private final TickRegionScheduler scheduler;

        private NoScheduleTickRegions(final ServerWorld world) {
            this.world = world;
            this.scheduler = TickRegionScheduler.getInstance();
        }

        @Override
        public RegionTickData.RegionSectionData createNewSectionData(final int sectionX, final int sectionZ, final int sectionShift) {
            return new RegionTickData.RegionSectionData();
        }

        @Override
        public RegionTickData createNewData(
            final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> forRegion) {
            final RegionTickData data = new RegionTickData(this.world);
            data.attachRegion(forRegion, this.scheduler);
            return data;
        }

        @Override
        public void onRegionCreate(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        }

        @Override
        public void onRegionDestroy(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        }

        @Override
        public void onRegionActive(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        }

        @Override
        public void onRegionInactive(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
        }

        @Override
        public void preMerge(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> from,
                             final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> into) {
        }

        @Override
        public void preSplit(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> from,
                             final java.util.List<ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData>> into) {
        }
    }
}

