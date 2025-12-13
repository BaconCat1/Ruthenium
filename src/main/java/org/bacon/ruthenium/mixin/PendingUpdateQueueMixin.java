package org.bacon.ruthenium.mixin;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.world.chunk.light.PendingUpdateQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PendingUpdateQueue.class)
public abstract class PendingUpdateQueueMixin {

    @Shadow
    @Final
    private int levelCount;

    @Shadow
    @Final
    private LongLinkedOpenHashSet[] pendingIdUpdatesByLevel;

    @Shadow
    private int minPendingLevel;

    @Shadow
    protected abstract void increaseMinPendingLevel(int maxLevel);

    /**
     * Vanilla's light engine queues are not thread-safe. Region threading can hit the queue concurrently
     * when blocks update light while another thread drains pending updates, so serialize queue access.
     */
    @Overwrite
    public synchronized long dequeue() {
        final LongLinkedOpenHashSet levelSet = this.pendingIdUpdatesByLevel[this.minPendingLevel];
        final long id = levelSet.removeFirstLong();
        if (levelSet.isEmpty()) {
            this.increaseMinPendingLevel(this.levelCount);
        }
        return id;
    }

    @Overwrite
    public synchronized boolean isEmpty() {
        return this.minPendingLevel >= this.levelCount;
    }

    @Overwrite
    public synchronized void remove(final long id, final int level, final int levelCount) {
        final LongLinkedOpenHashSet levelSet = this.pendingIdUpdatesByLevel[level];
        levelSet.remove(id);
        if (levelSet.isEmpty() && this.minPendingLevel == level) {
            this.increaseMinPendingLevel(levelCount);
        }
    }

    @Overwrite
    public synchronized void enqueue(final long id, final int level) {
        this.pendingIdUpdatesByLevel[level].add(id);
        if (this.minPendingLevel > level) {
            this.minPendingLevel = level;
        }
    }
}

