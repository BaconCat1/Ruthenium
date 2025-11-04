package org.bacon.ruthenium.region;

import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RegionTaskQueue} and the task bookkeeping performed by
 * {@link RegionTickData}.
 */
class RegionTaskQueueTest {

    @Test
    void testAbsorbTransfersTasks() {
        final RegionTickData source = new RegionTickData();
        final RegionTickData target = new RegionTickData();

        source.getTaskQueue().queueChunkTask(0, 0, () -> { });
        Assertions.assertTrue(source.getTaskQueue().containsTask(0, 0));

        target.absorb(source);

        Assertions.assertFalse(source.getTaskQueue().containsTask(0, 0));
        Assertions.assertTrue(target.getTaskQueue().containsTask(0, 0));
    }

    @Test
    void testSplitMovesTasksToChild() {
        final RegionTickData parent = new RegionTickData();
        parent.addChunk(0, 0);
        parent.addChunk(32, 0);
        parent.getTaskQueue().queueChunkTask(0, 0, () -> { });
        parent.getTaskQueue().queueChunkTask(32, 0, () -> { });

        final int sectionShift = 4;
        final RegionSection secondSection = new RegionSection(RegionSectionPos.fromChunk(32, 0, sectionShift));

        final RegionTickData child = parent.splitForSections(Set.of(secondSection), sectionShift);

        Assertions.assertTrue(parent.containsChunk(0, 0));
        Assertions.assertFalse(parent.containsChunk(32, 0));
        Assertions.assertTrue(child.containsChunk(32, 0));

        Assertions.assertTrue(parent.getTaskQueue().containsTask(0, 0));
        Assertions.assertFalse(parent.getTaskQueue().containsTask(32, 0));
        Assertions.assertTrue(child.getTaskQueue().containsTask(32, 0));
    }
}
