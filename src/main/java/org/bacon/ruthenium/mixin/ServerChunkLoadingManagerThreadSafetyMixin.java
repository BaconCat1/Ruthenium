package org.bacon.ruthenium.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(net.minecraft.server.world.ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerThreadSafetyMixin {

    @Unique
    private final Object ruthenium$entityTrackerLock = new Object();

    @Unique
    private ObjectCollection<?> ruthenium$snapshotEntityTrackers(final Int2ObjectMap<?> map) {
        synchronized (this.ruthenium$entityTrackerLock) {
            return new ObjectArrayList<>(map.values());
        }
    }

    @Redirect(
        method = {"updatePosition", "loadEntity", "unloadEntity", "tickEntityMovement", "forEachEntityTrackedBy"},
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;")
    )
    private ObjectCollection<?> ruthenium$lockEntityTrackerValues(final Int2ObjectMap<?> map) {
        return this.ruthenium$snapshotEntityTrackers(map);
    }

    @Redirect(
        method = "loadEntity",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;containsKey(I)Z")
    )
    private boolean ruthenium$lockEntityTrackerContainsKey(final Int2ObjectMap<?> map, final int key) {
        synchronized (this.ruthenium$entityTrackerLock) {
            return map.containsKey(key);
        }
    }

    @Redirect(
        method = "loadEntity",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;")
    )
    private Object ruthenium$lockEntityTrackerPut(final Int2ObjectMap<Object> map, final int key, final Object value) {
        synchronized (this.ruthenium$entityTrackerLock) {
            return map.put(key, value);
        }
    }

    @Redirect(
        method = "unloadEntity",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;remove(I)Ljava/lang/Object;")
    )
    private Object ruthenium$lockEntityTrackerRemove(final Int2ObjectMap<Object> map, final int key) {
        synchronized (this.ruthenium$entityTrackerLock) {
            return map.remove(key);
        }
    }

    @Redirect(
        method = {"sendToOtherNearbyPlayers", "sendToOtherNearbyPlayersIf", "sendToNearbyPlayers", "hasTrackingPlayer"},
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;get(I)Ljava/lang/Object;")
    )
    private Object ruthenium$lockEntityTrackerGet(final Int2ObjectMap<?> map, final int key) {
        synchronized (this.ruthenium$entityTrackerLock) {
            return map.get(key);
        }
    }
}

