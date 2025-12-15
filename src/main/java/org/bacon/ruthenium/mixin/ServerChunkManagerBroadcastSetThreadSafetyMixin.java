package org.bacon.ruthenium.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.util.profiler.Profiler;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkManager.class)
public abstract class ServerChunkManagerBroadcastSetThreadSafetyMixin {

    @Final
    @Shadow
    private Set<ChunkHolder> chunksToBroadcastUpdate;

    @Unique
    private final Object ruthenium$chunkHolderBroadcastLock = new Object();

    @Unique
    private List<ChunkHolder> ruthenium$chunkHolderBroadcastSnapshot;

    @Redirect(
        method = "markForUpdate(Lnet/minecraft/util/math/BlockPos;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z")
    )
    private boolean ruthenium$lockChunkHolderBroadcastAddFromBlockPos(final Set<ChunkHolder> set, final Object value) {
        synchronized (this.ruthenium$chunkHolderBroadcastLock) {
            return set.add((ChunkHolder) value);
        }
    }

    @Redirect(
        method = "markForUpdate(Lnet/minecraft/server/world/ChunkHolder;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z")
    )
    private boolean ruthenium$lockChunkHolderBroadcastAddFromChunkHolder(final Set<ChunkHolder> set, final Object value) {
        synchronized (this.ruthenium$chunkHolderBroadcastLock) {
            return set.add((ChunkHolder) value);
        }
    }

    @Inject(
        method = "broadcastUpdates(Lnet/minecraft/util/profiler/Profiler;)V",
        at = @At("HEAD")
    )
    private void ruthenium$snapshotChunkHoldersToBroadcast(final Profiler profiler, final CallbackInfo ci) {
        synchronized (this.ruthenium$chunkHolderBroadcastLock) {
            this.ruthenium$chunkHolderBroadcastSnapshot = new ArrayList<>(this.chunksToBroadcastUpdate);
        }
    }

    @Redirect(
        method = "broadcastUpdates(Lnet/minecraft/util/profiler/Profiler;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;")
    )
    private java.util.Iterator<ChunkHolder> ruthenium$useChunkHolderBroadcastSnapshotIterator(final Set<ChunkHolder> set) {
        final List<ChunkHolder> snapshot = this.ruthenium$chunkHolderBroadcastSnapshot;
        if (snapshot == null) {
            return List.<ChunkHolder>of().iterator();
        }
        return snapshot.iterator();
    }

    @Redirect(
        method = "broadcastUpdates(Lnet/minecraft/util/profiler/Profiler;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;clear()V")
    )
    private void ruthenium$clearOnlyChunkHolderBroadcastSnapshot(final Set<ChunkHolder> set) {
        synchronized (this.ruthenium$chunkHolderBroadcastLock) {
            final List<ChunkHolder> snapshot = this.ruthenium$chunkHolderBroadcastSnapshot;
            if (snapshot == null || snapshot.isEmpty()) {
                return;
            }
            set.removeAll(snapshot);
        }
    }

    @Inject(
        method = "broadcastUpdates(Lnet/minecraft/util/profiler/Profiler;)V",
        at = @At("RETURN")
    )
    private void ruthenium$clearChunkHolderBroadcastSnapshot(final Profiler profiler, final CallbackInfo ci) {
        this.ruthenium$chunkHolderBroadcastSnapshot = null;
    }
}
