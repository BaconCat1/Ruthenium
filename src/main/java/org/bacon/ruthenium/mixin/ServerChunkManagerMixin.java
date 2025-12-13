package org.bacon.ruthenium.mixin;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.bacon.ruthenium.mixin.accessor.ServerChunkLoadingManagerAccessor;
import org.bacon.ruthenium.world.TickRegionScheduler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkManager.class)
public abstract class ServerChunkManagerMixin {

    @Shadow
    @Final
    ServerWorld world;

    @Shadow
    @Final
    Thread serverThread;

    @Shadow
    @Final
    public ServerChunkLoadingManager chunkLoadingManager;

    @Inject(method = "getWorldChunk", at = @At("HEAD"), cancellable = true)
    private void ruthenium$getWorldChunkRegionThread(final int chunkX, final int chunkZ,
                                                     final CallbackInfoReturnable<WorldChunk> cir) {
        if (Thread.currentThread() == this.serverThread) {
            return;
        }

        final ServerWorld currentWorld = TickRegionScheduler.getCurrentWorld();
        if (currentWorld != this.world) {
            return;
        }
        if (TickRegionScheduler.getCurrentRegion() == null) {
            return;
        }

        final long pos = ChunkPos.toLong(chunkX, chunkZ);
        final ChunkHolder holder =
            ((ServerChunkLoadingManagerAccessor)this.chunkLoadingManager).ruthenium$getChunkHolder(pos);
        if (holder == null) {
            cir.setReturnValue(null);
            return;
        }

        final Chunk chunk = holder.getOrNull(ChunkStatus.FULL);
        cir.setReturnValue(chunk instanceof WorldChunk worldChunk ? worldChunk : null);
    }

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void ruthenium$getChunkRegionThread(final int chunkX, final int chunkZ, final ChunkStatus leastStatus,
                                                final boolean create, final CallbackInfoReturnable<Chunk> cir) {
        if (Thread.currentThread() == this.serverThread) {
            return;
        }

        final ServerWorld currentWorld = TickRegionScheduler.getCurrentWorld();
        if (currentWorld != this.world) {
            return;
        }
        if (TickRegionScheduler.getCurrentRegion() == null) {
            return;
        }

        final long pos = ChunkPos.toLong(chunkX, chunkZ);
        final ChunkHolder holder =
            ((ServerChunkLoadingManagerAccessor)this.chunkLoadingManager).ruthenium$getChunkHolder(pos);
        if (holder == null) {
            if (!create) {
                cir.setReturnValue(null);
            }
            return;
        }

        final Chunk chunk = holder.getOrNull(leastStatus);
        if (chunk != null || !create) {
            cir.setReturnValue(chunk);
        }
    }
}
