package org.bacon.ruthenium.mixin;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.function.LongPredicate;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.light.LevelPropagator;
import net.minecraft.world.chunk.light.PendingUpdateQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Makes LevelPropagator thread-safe by serializing access to the shared pending update structures.
 *
 * Vanilla's lighting propagators were written assuming single-threaded access. With region threads
 * ticking and mutating blocks concurrently, multiple threads can touch {@code pendingUpdates} and
 * {@code pendingUpdateQueue}, leading to corruption in the underlying fastutil maps/sets.
 */
@Mixin(LevelPropagator.class)
public abstract class LevelPropagatorMixin {

    @Shadow
    @Final
    protected int levelCount;

    @Shadow
    @Final
    private PendingUpdateQueue pendingUpdateQueue;

    @Shadow
    @Final
    private Long2ByteMap pendingUpdates;

    @Shadow
    private volatile boolean hasPendingUpdates;

    @Shadow
    private int calculateLevel(int a, int b) {
        throw new AssertionError();
    }

    @Shadow
    private void updateLevel(long sourceId, long id, int level, int currentLevel, int pendingLevel, boolean decrease) {
        throw new AssertionError();
    }

    @Shadow
    protected abstract int recalculateLevel(long id, long excludedId, int maxLevel);

    @Shadow
    protected abstract void propagateLevel(long id, int level, boolean decrease);

    @Shadow
    protected abstract int getLevel(long id);

    @Shadow
    protected abstract void setLevel(long id, int level);

    @Shadow
    protected abstract int getPropagatedLevel(long sourceId, long targetId, int level);

    /**
     * @author Ruthenium
     * @reason Serialize access to pendingUpdates/pendingUpdateQueue.
     */
    @Overwrite
    protected void removePendingUpdate(final long id) {
        synchronized (this) {
            final int pendingLevel = this.pendingUpdates.remove(id) & 255;
            if (pendingLevel != 255) {
                final int currentLevel = this.getLevel(id);
                final int queueLevel = this.calculateLevel(currentLevel, pendingLevel);
                this.pendingUpdateQueue.remove(id, queueLevel, this.levelCount);
                this.hasPendingUpdates = !this.pendingUpdateQueue.isEmpty();
            }
        }
    }

    /**
     * @author Ruthenium
     * @reason Serialize access to pendingUpdates/pendingUpdateQueue.
     */
    @Overwrite
    public void removePendingUpdateIf(final LongPredicate predicate) {
        synchronized (this) {
            final LongList idsToRemove = new LongArrayList();
            this.pendingUpdates.keySet().forEach(id -> {
                if (predicate.test(id)) {
                    idsToRemove.add(id);
                }
            });
            idsToRemove.forEach(this::removePendingUpdate);
        }
    }

    /**
     * @author Ruthenium
     * @reason Serialize access to pendingUpdates/pendingUpdateQueue.
     */
    @Overwrite
    protected void resetLevel(final long id) {
        synchronized (this) {
            this.updateLevel(id, id, this.levelCount - 1, false);
        }
    }

    /**
     * @author Ruthenium
     * @reason Serialize access to pendingUpdates/pendingUpdateQueue.
     */
    @Overwrite
    protected void updateLevel(final long sourceId, final long id, final int level, final boolean decrease) {
        synchronized (this) {
            this.updateLevel(sourceId, id, level, this.getLevel(id), this.pendingUpdates.get(id) & 255, decrease);
            this.hasPendingUpdates = !this.pendingUpdateQueue.isEmpty();
        }
    }

    /**
     * @author Ruthenium
     * @reason Serialize access to pendingUpdates/pendingUpdateQueue.
     */
    @Overwrite
    protected final void propagateLevel(final long sourceId, final long targetId, final int level, final boolean decrease) {
        synchronized (this) {
            final int pendingLevel = this.pendingUpdates.get(targetId) & 255;
            final int propagatedLevel = MathHelper.clamp(this.getPropagatedLevel(sourceId, targetId, level), 0, this.levelCount - 1);
            if (decrease) {
                this.updateLevel(sourceId, targetId, propagatedLevel, this.getLevel(targetId), pendingLevel, true);
                return;
            }

            final boolean hadNoPendingUpdate = pendingLevel == 255;
            final int existingPendingOrCurrent;
            if (hadNoPendingUpdate) {
                existingPendingOrCurrent = MathHelper.clamp(this.getLevel(targetId), 0, this.levelCount - 1);
            } else {
                existingPendingOrCurrent = pendingLevel;
            }

            if (propagatedLevel == existingPendingOrCurrent) {
                this.updateLevel(
                    sourceId,
                    targetId,
                    this.levelCount - 1,
                    hadNoPendingUpdate ? existingPendingOrCurrent : this.getLevel(targetId),
                    pendingLevel,
                    false
                );
            }
        }
    }

    /**
     * @author Ruthenium
     * @reason Serialize access to pendingUpdates/pendingUpdateQueue.
     */
    @Overwrite
    protected final int applyPendingUpdates(final int maxSteps) {
        synchronized (this) {
            if (this.pendingUpdateQueue.isEmpty()) {
                return maxSteps;
            }

            int remainingSteps = maxSteps;
            while (!this.pendingUpdateQueue.isEmpty() && remainingSteps > 0) {
                remainingSteps--;
                final long id = this.pendingUpdateQueue.dequeue();
                final int currentLevel = MathHelper.clamp(this.getLevel(id), 0, this.levelCount - 1);
                final int pendingLevel = this.pendingUpdates.remove(id) & 255;

                if (pendingLevel < currentLevel) {
                    this.setLevel(id, pendingLevel);
                    this.propagateLevel(id, pendingLevel, true);
                } else if (pendingLevel > currentLevel) {
                    this.setLevel(id, this.levelCount - 1);
                    if (pendingLevel != this.levelCount - 1) {
                        this.pendingUpdateQueue.enqueue(id, this.calculateLevel(this.levelCount - 1, pendingLevel));
                        this.pendingUpdates.put(id, (byte)pendingLevel);
                    }

                    this.propagateLevel(id, currentLevel, false);
                }
            }

            this.hasPendingUpdates = !this.pendingUpdateQueue.isEmpty();
            return remainingSteps;
        }
    }

    /**
     * @author Ruthenium
     * @reason Serialize access to pendingUpdates to avoid concurrent iteration/mutation.
     */
    @Overwrite
    public int getPendingUpdateCount() {
        synchronized (this) {
            return this.pendingUpdates.size();
        }
    }
}
