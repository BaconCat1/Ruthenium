package org.bacon.ruthenium.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.RegionizedWorldData;
import org.bacon.ruthenium.world.TickRegionScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Coordinates server lifecycle events and per-dimension iteration with the region scheduler.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Unique
    private boolean ruthenium$regionSchedulerRegistered;

    @Inject(method = "runServer", at = @At("HEAD"))
    private void ruthenium$bootstrapScheduler(final CallbackInfo ci) {
        if (!this.ruthenium$regionSchedulerRegistered) {
            TickRegionScheduler.getInstance().registerServer((MinecraftServer)(Object)this);
            this.ruthenium$regionSchedulerRegistered = true;
        }
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void ruthenium$shutdownScheduler(final CallbackInfo ci) {
        if (this.ruthenium$regionSchedulerRegistered) {
            final MinecraftServer server = (MinecraftServer)(Object)this;
            TickRegionScheduler.getInstance().unregisterServer(server);
            this.ruthenium$regionSchedulerRegistered = false;
        }
        TickRegionScheduler.getInstance().shutdown();
    }

    @Redirect(method = "tickWorlds", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/world/ServerWorld;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void ruthenium$validateSchedulerState(final ServerWorld world,
                                                  final BooleanSupplier shouldKeepTicking) {
        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();
        if (world instanceof RegionizedServerWorld regionized) {
            final RegionizedWorldData worldData = regionized.ruthenium$getWorldRegionData();
            if (worldData.isHandlingTick()) {
                scheduler.logSchedulerConflict(world,
                    "Skipping vanilla world tick; scheduler already marked the dimension as busy");
                return;
            }
        }

        world.tick(shouldKeepTicking);

        if (world instanceof RegionizedServerWorld regionized) {
            final RegionizedWorldData worldData = regionized.ruthenium$getWorldRegionData();
            if (worldData.isHandlingTick()) {
                scheduler.logSchedulerConflict(world,
                    "World tick completed but scheduler flag remained set; forcing reset");
                worldData.finishTick();
            }
        }
    }
}

