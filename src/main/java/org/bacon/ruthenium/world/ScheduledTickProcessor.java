package org.bacon.ruthenium.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.ToLongFunction;

final class ScheduledTickProcessor {

    private ScheduledTickProcessor() {
    }

    static <C, E> List<E> drainDueTicks(final Object schedulerMonitor,
                                        final Long2ObjectMap<C> schedulersByChunk,
                                        final long time,
                                        final int maxTicks,
                                        final long[] candidateChunkKeys,
                                        final LongPredicate isChunkLoaded,
                                        final Function<C, E> peek,
                                        final Function<C, E> poll,
                                        final ToLongFunction<E> triggerTime) {
        Objects.requireNonNull(schedulerMonitor, "schedulerMonitor");
        Objects.requireNonNull(schedulersByChunk, "schedulersByChunk");
        Objects.requireNonNull(candidateChunkKeys, "candidateChunkKeys");
        Objects.requireNonNull(isChunkLoaded, "isChunkLoaded");
        Objects.requireNonNull(peek, "peek");
        Objects.requireNonNull(poll, "poll");
        Objects.requireNonNull(triggerTime, "triggerTime");
        if (maxTicks <= 0) {
            return List.of();
        }

        final LongArrayList loadedChunkKeys = new LongArrayList(candidateChunkKeys.length);
        for (int i = 0; i < candidateChunkKeys.length; i++) {
            final long chunkKey = candidateChunkKeys[i];
            if (isChunkLoaded.test(chunkKey)) {
                loadedChunkKeys.add(chunkKey);
            }
        }

        final ArrayList<E> drainedTicks = new ArrayList<>(Math.min(maxTicks, 256));
        synchronized (schedulerMonitor) {
            int drained = 0;
            for (int i = 0; i < loadedChunkKeys.size() && drained < maxTicks; i++) {
                final C chunkScheduler = schedulersByChunk.get(loadedChunkKeys.getLong(i));
                if (chunkScheduler == null) {
                    continue;
                }

                while (drained < maxTicks) {
                    final E next = peek.apply(chunkScheduler);
                    if (next == null || triggerTime.applyAsLong(next) > time) {
                        break;
                    }
                    final E polled = poll.apply(chunkScheduler);
                    if (polled == null) {
                        break;
                    }
                    drainedTicks.add(polled);
                    drained++;
                }
            }
        }

        return drainedTicks;
    }
}

