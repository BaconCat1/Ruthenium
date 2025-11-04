package org.bacon.ruthenium.region;

import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RegionTaskQueue}.
 */
class RegionTaskQueueTest {

    @Test
    void absorbMovesTasksBetweenQueues() {
        final RegionTaskQueue source = new RegionTaskQueue();
        final RegionTaskQueue target = new RegionTaskQueue();

        source.queueChunkTask(0, 0, () -> { });
        source.queueChunkTask(16, 0, () -> { });

        Assertions.assertTrue(source.containsTask(0, 0));
        Assertions.assertTrue(source.containsTask(16, 0));

        target.absorb(source);

        Assertions.assertFalse(source.containsTask(0, 0));
        Assertions.assertFalse(source.containsTask(16, 0));
        Assertions.assertTrue(target.containsTask(0, 0));
        Assertions.assertTrue(target.containsTask(16, 0));
    }

    @Test
    void splitForSectionsMovesMatchingTasks() {
        final RegionTaskQueue queue = new RegionTaskQueue();
        queue.queueChunkTask(0, 0, () -> { });
        queue.queueChunkTask(32, 0, () -> { });
        queue.queueChunkTask(64, 0, () -> { });

        final int sectionShift = 4;
        final Set<RegionSectionPos> reassignedSections = Set.of(
            RegionSectionPos.fromChunk(32, 0, sectionShift),
            RegionSectionPos.fromChunk(64, 0, sectionShift)
        );

        final RegionTaskQueue moved = queue.splitForSections(reassignedSections, sectionShift);

        Assertions.assertTrue(queue.containsTask(0, 0));
        Assertions.assertFalse(queue.containsTask(32, 0));
        Assertions.assertFalse(queue.containsTask(64, 0));

        Assertions.assertTrue(moved.containsTask(32, 0));
        Assertions.assertTrue(moved.containsTask(64, 0));
        Assertions.assertFalse(moved.containsTask(0, 0));
    }
}
