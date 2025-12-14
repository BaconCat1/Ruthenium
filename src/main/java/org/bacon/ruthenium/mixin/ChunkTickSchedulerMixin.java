package org.bacon.ruthenium.mixin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.ChunkTickScheduler;
import net.minecraft.world.tick.OrderedTick;
import net.minecraft.world.tick.SerializableTickScheduler;
import net.minecraft.world.tick.Tick;
import org.bacon.ruthenium.util.CollectionUtils;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Makes ChunkTickScheduler thread-safe by synchronizing all public methods.
 * This is necessary because region threads may access the same ChunkTickScheduler
 * concurrently with the main thread during region merges/splits or when blocks
 * schedule ticks during random ticking.
 *
 * The underlying data structures (Queue, Set) are not thread-safe,
 * so all access must be serialized.
 */
@Mixin(ChunkTickScheduler.class)
public abstract class ChunkTickSchedulerMixin<T> implements SerializableTickScheduler<T> {

    @Shadow
    @Final
    private Queue<OrderedTick<T>> tickQueue;

    @Shadow
    @Nullable
    private List<Tick<T>> ticks;

    @Shadow
    @Final
    private Set<OrderedTick<?>> queuedTicks;

    @Shadow
    @Nullable
    private BiConsumer<ChunkTickScheduler<T>, OrderedTick<T>> tickConsumer;

    @Shadow
    private void queueTick(final OrderedTick<T> orderedTick) {
        throw new AssertionError();
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe tick scheduling for parallel region ticking.
     */
    @SuppressWarnings("unchecked")
    @Overwrite
    public void scheduleTick(final OrderedTick<T> orderedTick) {
        synchronized (this) {
            if (this.queuedTicks.add(orderedTick)) {
                this.queueTick(orderedTick);
            }
        }
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe tick queue access for parallel region ticking
     */
    @Overwrite
    @Nullable
    public OrderedTick<T> peekNextTick() {
        synchronized (this) {
            return this.tickQueue.peek();
        }
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe tick queue access for parallel region ticking
     */
    @Overwrite
    @Nullable
    public OrderedTick<T> pollNextTick() {
        synchronized (this) {
            final OrderedTick<T> orderedTick = this.tickQueue.poll();
            if (orderedTick != null) {
                this.queuedTicks.remove(orderedTick);
            }
            return orderedTick;
        }
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe queued check for parallel region ticking
     */
    @Overwrite
    public boolean isQueued(final BlockPos pos, final T type) {
        synchronized (this) {
            return this.queuedTicks.contains(OrderedTick.create(type, pos));
        }
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe removal for parallel region ticking
     */
    @Overwrite
    public void removeTicksIf(final Predicate<OrderedTick<T>> predicate) {
        synchronized (this) {
            final Iterator<OrderedTick<T>> iterator = this.tickQueue.iterator();
            while (iterator.hasNext()) {
                final OrderedTick<T> tick = iterator.next();
                if (predicate.test(tick)) {
                    iterator.remove();
                    this.queuedTicks.remove(tick);
                }
            }
        }
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe stream access for parallel region ticking
     */
    @Overwrite
    public Stream<OrderedTick<T>> getQueueAsStream() {
        synchronized (this) {
            // Return a copy to avoid concurrent modification
            return new ArrayList<>(this.tickQueue).stream();
        }
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe count access for parallel region ticking
     */
    @Overwrite
    public int getTickCount() {
        synchronized (this) {
            return this.tickQueue.size() + (this.ticks != null ? this.ticks.size() : 0);
        }
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe consumer setting for parallel region ticking
     */
    @Overwrite
    public void setTickConsumer(@Nullable final BiConsumer<ChunkTickScheduler<T>, OrderedTick<T>> consumer) {
        synchronized (this) {
            this.tickConsumer = consumer;
        }
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe tick collection for parallel region ticking
     */
    @Overwrite
    public List<Tick<T>> collectTicks(final long time) {
        synchronized (this) {
            final List<Tick<T>> collected;
            if (this.ticks != null) {
                /*
                 * Vanilla sometimes stores an immutable list (for example, an empty List.of()) when a
                 * scheduler is deserialized without pending ticks. We must always collect into a mutable
                 * list because we append converted entries from tickQueue below.
                 */
                collected = CollectionUtils.mutableCopy(this.ticks);
                this.ticks = null;
            } else {
                collected = new ArrayList<>(this.tickQueue.size());
            }

            for (final OrderedTick<T> orderedTick : this.tickQueue) {
                collected.add(orderedTick.toTick(time));
            }
            return collected;
        }
    }

    /**
     * @author Ruthenium
     * @reason Thread-safe disable for parallel region ticking
     */
    @Overwrite
    public void disable(final long time) {
        synchronized (this) {
            this.ticks = this.collectTicks(time);
            this.tickQueue.clear();
            this.queuedTicks.clear();
        }
    }
}
