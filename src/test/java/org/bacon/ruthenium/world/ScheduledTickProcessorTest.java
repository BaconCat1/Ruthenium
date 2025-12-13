package org.bacon.ruthenium.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ScheduledTickProcessorTest {

    private record FakeTick(long triggerTime, String id) {
    }

    private static final class FakeChunkScheduler {
        private final Deque<FakeTick> ticks = new ArrayDeque<>();

        FakeChunkScheduler add(final FakeTick tick) {
            this.ticks.addLast(tick);
            return this;
        }
    }

    @Test
    void drainDueTicksAvoidsMonitorDuringLoadChecksAndReturnsRunnableTicks() {
        final Object monitor = new Object();
        final long chunkA = 1L;
        final long chunkB = 2L;

        final FakeChunkScheduler schedulerA = new FakeChunkScheduler()
            .add(new FakeTick(1L, "A1"))
            .add(new FakeTick(5L, "A5"))
            .add(new FakeTick(12L, "A12"));
        final FakeChunkScheduler schedulerB = new FakeChunkScheduler()
            .add(new FakeTick(2L, "B2"))
            .add(new FakeTick(4L, "B4"));

        final Long2ObjectOpenHashMap<FakeChunkScheduler> byChunk = new Long2ObjectOpenHashMap<>();
        byChunk.put(chunkA, schedulerA);
        byChunk.put(chunkB, schedulerB);

        final AtomicInteger loadChecks = new AtomicInteger();
        final List<FakeTick> drained = ScheduledTickProcessor.drainDueTicks(
            monitor,
            byChunk,
            5L,
            10,
            new long[] {chunkA, chunkB},
            key -> {
                Assertions.assertFalse(Thread.holdsLock(monitor), "Chunk load checks must not hold the scheduler monitor");
                loadChecks.incrementAndGet();
                return true;
            },
            scheduler -> {
                Assertions.assertTrue(Thread.holdsLock(monitor), "peek must hold the scheduler monitor");
                return scheduler.ticks.peekFirst();
            },
            scheduler -> {
                Assertions.assertTrue(Thread.holdsLock(monitor), "poll must hold the scheduler monitor");
                return scheduler.ticks.pollFirst();
            },
            FakeTick::triggerTime
        );

        Assertions.assertEquals(2, loadChecks.get(), "Expected one load check per candidate chunk key");
        Assertions.assertEquals(List.of(
            new FakeTick(1L, "A1"),
            new FakeTick(5L, "A5"),
            new FakeTick(2L, "B2"),
            new FakeTick(4L, "B4")
        ), drained);

        for (final FakeTick tick : drained) {
            Assertions.assertFalse(Thread.holdsLock(monitor), "Callers must run drained ticks without holding the scheduler monitor");
            Assertions.assertNotNull(tick);
        }
    }

    @Test
    void drainDueTicksRespectsMaxTicks() {
        final Object monitor = new Object();
        final long chunkA = 10L;
        final long chunkB = 20L;

        final FakeChunkScheduler schedulerA = new FakeChunkScheduler()
            .add(new FakeTick(1L, "A1"))
            .add(new FakeTick(2L, "A2"));
        final FakeChunkScheduler schedulerB = new FakeChunkScheduler()
            .add(new FakeTick(1L, "B1"))
            .add(new FakeTick(2L, "B2"));

        final Long2ObjectOpenHashMap<FakeChunkScheduler> byChunk = new Long2ObjectOpenHashMap<>();
        byChunk.put(chunkA, schedulerA);
        byChunk.put(chunkB, schedulerB);

        final List<FakeTick> drained = ScheduledTickProcessor.drainDueTicks(
            monitor,
            byChunk,
            10L,
            2,
            new long[] {chunkA, chunkB},
            key -> true,
            scheduler -> scheduler.ticks.peekFirst(),
            scheduler -> scheduler.ticks.pollFirst(),
            FakeTick::triggerTime
        );

        Assertions.assertEquals(List.of(
            new FakeTick(1L, "A1"),
            new FakeTick(2L, "A2")
        ), drained);
    }
}

