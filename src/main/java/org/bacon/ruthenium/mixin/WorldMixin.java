package org.bacon.ruthenium.mixin;

import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.profiler.Profilers;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import org.bacon.ruthenium.debug.FallbackValidator;
import org.bacon.ruthenium.world.RegionTaskDispatcher;
import org.bacon.ruthenium.world.RegionizedServer;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.RegionizedWorldData;
import org.bacon.ruthenium.world.TickRegionScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for the base World class to redirect block entity ticking to region data.
 * This ensures block entities are ticked on the appropriate region thread.
 */
@Mixin(World.class)
public abstract class WorldMixin {

    @Shadow
    public abstract boolean isClient();

    /**
     * Redirects addBlockEntityTicker to use region data when on a region thread.
     */
    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"), cancellable = true)
    private void ruthenium$redirectAddBlockEntityTicker(final BlockEntityTickInvoker ticker, final CallbackInfo ci) {
        if (this.isClient()) {
            return; // Don't redirect on client
        }

        //noinspection ConstantConditions
        if (!((Object) this instanceof ServerWorld serverWorld) ||
            !((Object) this instanceof RegionizedServerWorld regionized)) {
            return;
        }

        final BlockPos pos = ticker.getPos();
        if (pos == null) {
            return;
        }

        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;

        if (RegionizedServer.isOnRegionThread()) {
            if (regionized.ruthenium$isOwnedByCurrentRegion(chunkX, chunkZ)) {
                final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
                if (worldData != null) {
                    worldData.addBlockEntityTicker(ticker);
                    ci.cancel();
                }
                return;
            }

            RegionTaskDispatcher.runOnChunk(serverWorld, chunkX, chunkZ, () -> {
                final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
                if (worldData != null) {
                    worldData.addBlockEntityTicker(ticker);
                }
            });
            ci.cancel();
            return;
        }

        RegionTaskDispatcher.runOnChunk(serverWorld, chunkX, chunkZ, () -> {
            final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
            if (worldData != null) {
                worldData.addBlockEntityTicker(ticker);
            }
        });
    }

    /**
     * Redirects tickBlockEntities to use region data when on a region thread.
     */
    @Inject(method = "tickBlockEntities", at = @At("HEAD"), cancellable = true)
    private void ruthenium$redirectTickBlockEntities(final CallbackInfo ci) {
        if (this.isClient()) {
            return; // Don't redirect on client
        }

        //noinspection ConstantConditions
        if (!((Object) this instanceof ServerWorld serverWorld)) {
            return;
        }

        // Validate that we're on the correct thread
        if (!RegionizedServer.isOnRegionThread()) {
            FallbackValidator.validateBlockEntityTicking(serverWorld);
            return; // Fall back to vanilla if not on region thread
        }

        final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
        if (worldData == null) {
            return; // Fall back to vanilla
        }

        // Use regionized block entity ticking
        final Profiler profiler = Profilers.get();
        profiler.push("blockEntities");
        worldData.tickBlockEntities();
        profiler.pop();
        ci.cancel();
    }
}
