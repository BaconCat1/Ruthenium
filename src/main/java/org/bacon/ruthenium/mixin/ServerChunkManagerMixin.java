package org.bacon.ruthenium.mixin;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.WorldChunk;
import org.bacon.ruthenium.mixin.accessor.ServerChunkLoadingManagerAccessor;
import org.bacon.ruthenium.world.TickRegionScheduler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    @Unique
    private volatile RegistryEntry<Biome> ruthenium$fallbackBiome;

    @Unique
    private RegistryEntry<Biome> ruthenium$getFallbackBiome() {
        RegistryEntry<Biome> cached = this.ruthenium$fallbackBiome;
        if (cached != null) {
            return cached;
        }
        final Registry<Biome> biomeRegistry = this.world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
        cached = biomeRegistry.getEntry(biomeRegistry.get(BiomeKeys.PLAINS));
        this.ruthenium$fallbackBiome = cached;
        return cached;
    }

    @Unique
    private WorldChunk ruthenium$createEmptyChunk(final int chunkX, final int chunkZ) {
        return new EmptyChunk(this.world, new ChunkPos(chunkX, chunkZ), this.ruthenium$getFallbackBiome());
    }

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
            // Never fall back to vanilla off-thread chunk creation/loading.
            // Vanilla may block waiting for main-thread chunk futures, which deadlocks with
            // Ruthenium's chunk access locks and triggers watchdog timeouts.
            //
            // Some vanilla code (notably chunk ticking) calls World.getChunk(..., create=true)
            // and will throw if it receives null. Return an EmptyChunk as a safe, non-blocking
            // placeholder in that case.
            cir.setReturnValue(create ? this.ruthenium$createEmptyChunk(chunkX, chunkZ) : null);
            return;
        }

        final Chunk chunk = holder.getOrNull(leastStatus);
        if (chunk != null) {
            cir.setReturnValue(chunk);
            return;
        }

        // If the chunk isn't already present at the requested status, avoid forcing a sync load
        // on the main thread. For create=true callers, provide an EmptyChunk to prevent
        // IllegalStateException("Should always be able to create a chunk!").
        cir.setReturnValue(create ? this.ruthenium$createEmptyChunk(chunkX, chunkZ) : null);
    }
}
