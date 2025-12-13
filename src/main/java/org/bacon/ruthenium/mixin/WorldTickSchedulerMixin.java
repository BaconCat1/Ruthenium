package org.bacon.ruthenium.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import java.util.Queue;
import java.util.function.BiConsumer;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.profiler.Profilers;
import net.minecraft.world.tick.ChunkTickScheduler;
import net.minecraft.world.tick.OrderedTick;
import net.minecraft.world.tick.WorldTickScheduler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(WorldTickScheduler.class)
public abstract class WorldTickSchedulerMixin<T> {

    @Shadow
    @Final
    private Long2ObjectMap<ChunkTickScheduler<T>> chunkTickSchedulers;

    @Shadow
    @Final
    private Long2LongMap nextTriggerTickByChunkPos;

    @Shadow
    @Final
    private BiConsumer<ChunkTickScheduler<T>, OrderedTick<T>> queuedTickConsumer;

    @Shadow
    @Final
    private Queue<OrderedTick<T>> tickableTicks;

    @Shadow
    private void collectTickableTicks(long time, int maxTicks, Profiler profiler) {
        throw new AssertionError();
    }

    @Shadow
    private void tick(BiConsumer<BlockPos, T> ticker) {
        throw new AssertionError();
    }

    @Shadow
    private void clear() {
        throw new AssertionError();
    }

    /**
     * Region threads can schedule ticks concurrently with the orchestrator draining schedulers.
     * Vanilla's WorldTickScheduler is not thread-safe, so serialize access.
     */
    @Overwrite
    public void scheduleTick(final OrderedTick<T> orderedTick) {
        synchronized (this) {
            final long chunkPos = ChunkPos.toLong(orderedTick.pos());
            final ChunkTickScheduler<T> scheduler = this.chunkTickSchedulers.get(chunkPos);
            if (scheduler == null) {
                Util.logErrorOrPause("Trying to schedule tick in not loaded position " + orderedTick.pos());
                return;
            }
            scheduler.scheduleTick(orderedTick);
        }
    }

    @Overwrite
    public void addChunkTickScheduler(final ChunkPos pos, final ChunkTickScheduler<T> scheduler) {
        synchronized (this) {
            final long packedChunkPos = pos.toLong();
            this.chunkTickSchedulers.put(packedChunkPos, scheduler);
            final OrderedTick<T> next = scheduler.peekNextTick();
            if (next != null) {
                this.nextTriggerTickByChunkPos.put(packedChunkPos, next.triggerTick());
            }
            scheduler.setTickConsumer(this.queuedTickConsumer);
        }
    }

    @Overwrite
    public void removeChunkTickScheduler(final ChunkPos pos) {
        synchronized (this) {
            final long packedChunkPos = pos.toLong();
            final ChunkTickScheduler<T> removed = this.chunkTickSchedulers.remove(packedChunkPos);
            this.nextTriggerTickByChunkPos.remove(packedChunkPos);
            if (removed != null) {
                removed.setTickConsumer(null);
            }
        }
    }

    /**
     * Serialize the drain path to avoid concurrent mutation of internal queues/maps.
     */
    @Overwrite
    public void tick(final long time, final int maxTicks, final BiConsumer<BlockPos, T> ticker) {
        synchronized (this) {
            final Profiler profiler = Profilers.get();
            profiler.push("collect");
            this.collectTickableTicks(time, maxTicks, profiler);
            profiler.swap("run");
            profiler.visit("ticksToRun", this.tickableTicks.size());
            this.tick(ticker);
            profiler.swap("cleanup");
            this.clear();
            profiler.pop();
        }
    }
}
