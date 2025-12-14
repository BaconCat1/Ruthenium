package org.bacon.ruthenium.mixin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import net.minecraft.server.network.PlayerAssociatedNetworkHandler;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The entity tracker listener set is a vanilla IdentityHashSet, which is not thread-safe.
 *
 * Region threads frequently send packets (iteration) while the orchestrator updates tracking
 * status (mutation), which can throw {@link java.util.ConcurrentModificationException}.
 *
 * This mixin snapshots the listener set for iteration and synchronizes mutations against the
 * same monitor (the set instance).
 */
@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public abstract class ServerChunkLoadingManagerEntityTrackerListenersThreadSafetyMixin {

    @Redirect(
        method = {"sendToListeners", "sendToSelfAndListeners", "sendToListenersIf", "stopTracking"},
        at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;")
    )
    private Iterator<?> ruthenium$snapshotListenersIterator(final Set<?> set) {
        synchronized (set) {
            return new ArrayList<>(set).iterator();
        }
    }

    @Redirect(
        method = {"updateTrackedStatus(Lnet/minecraft/server/network/ServerPlayerEntity;)V"},
        at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z")
    )
    private boolean ruthenium$lockListenerAdd(final Set<PlayerAssociatedNetworkHandler> set, final Object value) {
        synchronized (set) {
            return set.add((PlayerAssociatedNetworkHandler)value);
        }
    }

    @Redirect(
        method = {"stopTracking(Lnet/minecraft/server/network/ServerPlayerEntity;)V"},
        at = @At(value = "INVOKE", target = "Ljava/util/Set;remove(Ljava/lang/Object;)Z")
    )
    private boolean ruthenium$lockListenerRemove(final Set<PlayerAssociatedNetworkHandler> set, final Object value) {
        synchronized (set) {
            return set.remove(value);
        }
    }

    @Redirect(
        method = {"stopTracking(Lnet/minecraft/server/network/ServerPlayerEntity;)V"},
        at = @At(value = "INVOKE", target = "Ljava/util/Set;isEmpty()Z")
    )
    private boolean ruthenium$lockListenerIsEmpty(final Set<?> set) {
        synchronized (set) {
            return set.isEmpty();
        }
    }

    @Redirect(
        method = {"updateTrackedStatus(Lnet/minecraft/server/network/ServerPlayerEntity;)V"},
        at = @At(value = "INVOKE", target = "Ljava/util/Set;size()I")
    )
    private int ruthenium$lockListenerSize(final Set<?> set) {
        synchronized (set) {
            return set.size();
        }
    }
}

