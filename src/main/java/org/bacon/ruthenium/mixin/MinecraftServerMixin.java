package org.bacon.ruthenium.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.bacon.ruthenium.world.MainThreadTickGuard;
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
 * <p>
 * Main thread responsibilities enforced here:
 * <ul>
 *   <li>Main thread ONLY orchestrates scheduler via TickRegionScheduler.tickWorld()</li>
 *   <li>Main thread ONLY ticks global services (weather, time, raids at world level)</li>
 *   <li>Main thread ONLY handles chunk loading/unloading coordination</li>
 *   <li>Main thread NEVER directly ticks chunks/entities/blocks</li>
 * </ul>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Unique
    private boolean ruthenium$regionSchedulerRegistered;

    @Inject(method = "runServer", at = @At("HEAD"))
    private void ruthenium$bootstrapScheduler(final CallbackInfo ci) {
        // Register main thread for accurate thread detection
        MainThreadTickGuard.registerMainThread(Thread.currentThread());

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

    /**
     * Validates that tickWorlds is running on main thread as orchestrator only.
     */
    @Inject(method = "tickWorlds", at = @At("HEAD"))
    private void ruthenium$validateOrchestratorRole(final BooleanSupplier shouldKeepTicking, final CallbackInfo ci) {
        MainThreadTickGuard.assertOrchestratorOnly((MinecraftServer)(Object)this);
    }

    /**
     * Intercepts world tick calls to ensure scheduler orchestration.
     * The main thread should ONLY call TickRegionScheduler.tickWorld(), never vanilla tick directly.
     */
    @Redirect(method = "tickWorlds", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/world/ServerWorld;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void ruthenium$orchestrateSchedulerOnly(final ServerWorld world,
                                                    final BooleanSupplier shouldKeepTicking) {
        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();

        // Validate main thread responsibilities
        MainThreadTickGuard.assertMainThread("world tick orchestration");

        if (world instanceof RegionizedServerWorld regionized) {
            final RegionizedWorldData worldData = regionized.ruthenium$getWorldRegionData();
            if (worldData.isHandlingTick()) {
                scheduler.logSchedulerConflict(world,
                    "Skipping vanilla world tick; scheduler already marked the dimension as busy");
                return;
            }
        }

        // Always proceed with world tick - ServerWorldMixin will handle orchestration
        // via scheduler.tickWorld() which properly updates chunk state and manages regions.
        // Proceed with world tick (which will invoke scheduler.tickWorld() via mixin)
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

