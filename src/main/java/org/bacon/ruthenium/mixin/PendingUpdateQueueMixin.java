package org.bacon.ruthenium.mixin;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.world.chunk.light.PendingUpdateQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes light engine queues thread-safe for region threading.
 * Vanilla's light engine queues are not thread-safe. Region threading can hit the queue concurrently
 * when blocks update light while another thread drains pending updates.
 */
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

    @Unique
    private final Object ruthenium$lock = new Object();

    /**
     * Serialize dequeue operations for thread safety.
     */
    @Inject(method = "dequeue", at = @At("HEAD"), cancellable = true)
    private void ruthenium$synchronizedDequeue(final CallbackInfoReturnable<Long> cir) {
        synchronized (this.ruthenium$lock) {
            final LongLinkedOpenHashSet levelSet = this.pendingIdUpdatesByLevel[this.minPendingLevel];
            final long id = levelSet.removeFirstLong();
            if (levelSet.isEmpty()) {
                this.increaseMinPendingLevel(this.levelCount);
            }
            cir.setReturnValue(id);
        }
    }

    /**
     * Serialize isEmpty checks for thread safety.
     */
    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void ruthenium$synchronizedIsEmpty(final CallbackInfoReturnable<Boolean> cir) {
        synchronized (this.ruthenium$lock) {
            cir.setReturnValue(this.minPendingLevel >= this.levelCount);
        }
    }

    /**
     * Serialize remove operations for thread safety.
     */
    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void ruthenium$synchronizedRemove(final long id, final int level, final int levelCount, final CallbackInfo ci) {
        synchronized (this.ruthenium$lock) {
            final LongLinkedOpenHashSet levelSet = this.pendingIdUpdatesByLevel[level];
            levelSet.remove(id);
            if (levelSet.isEmpty() && this.minPendingLevel == level) {
                this.increaseMinPendingLevel(levelCount);
            }
        }
        ci.cancel();
    }

    /**
     * Serialize enqueue operations for thread safety.
     */
    @Inject(method = "enqueue", at = @At("HEAD"), cancellable = true)
    private void ruthenium$synchronizedEnqueue(final long id, final int level, final CallbackInfo ci) {
        synchronized (this.ruthenium$lock) {
            this.pendingIdUpdatesByLevel[level].add(id);
            if (this.minPendingLevel > level) {
                this.minPendingLevel = level;
            }
        }
        ci.cancel();
    }
}

