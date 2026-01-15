package org.bacon.ruthenium.mixin;

import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.Entity.RemovalReason;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.raid.Raid;
import net.minecraft.village.raid.RaidManager;
import net.minecraft.world.World;
import org.bacon.ruthenium.Ruthenium;
import org.bacon.ruthenium.debug.FallbackValidator;
import org.bacon.ruthenium.debug.RegionDebug;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.mixin.accessor.ServerWorldAccessor;
import org.bacon.ruthenium.world.MainThreadTickGuard;
import org.bacon.ruthenium.world.RegionTaskDispatcher;
import org.bacon.ruthenium.world.RegionChunkTickAccess;
import org.bacon.ruthenium.world.RegionizedServer;
import org.bacon.ruthenium.world.TickRegionScheduler;
import org.bacon.ruthenium.world.RegionizedWorldData;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.raid.RaidManagerThreadSafe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.chunk.WorldChunk;

/**
 * Injects lifecycle hooks that coordinate Ruthenium regions with the Minecraft world tick.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin implements RegionizedServerWorld, RegionChunkTickAccess {

    @Unique
    private ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> ruthenium$regionizer;

    @Unique
    private RegionizedWorldData ruthenium$worldRegionData;

    @Unique
    private boolean ruthenium$skipVanillaChunkTick;

    @Unique
    private final ThreadLocal<Integer> ruthenium$regionChunkDepth = ThreadLocal.withInitial(() -> 0);

    @Unique
    @SuppressWarnings({"ConstantConditions"})
    private ServerWorld ruthenium$self() {
        return (ServerWorld)(Object)this;
    }

    @Override
    public ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> ruthenium$getRegionizer() {
        if (this.ruthenium$regionizer == null) {
            this.ruthenium$regionizer = Ruthenium.createRegionizer(this.ruthenium$self());
            final RegistryKey<World> worldKey = this.ruthenium$self().getRegistryKey();
            Ruthenium.getLogger().info("Created regionizer for world {}", worldKey.getValue());
        }
        return this.ruthenium$regionizer;
    }

    @Override
    public RegionizedWorldData ruthenium$getWorldRegionData() {
        if (this.ruthenium$worldRegionData == null) {
            this.ruthenium$worldRegionData = new RegionizedWorldData(this.ruthenium$self());
        }
        return this.ruthenium$worldRegionData;
    }

    @Unique
    private void ruthenium$resetVanillaTickGuards() {
        this.ruthenium$skipVanillaChunkTick = false;
        final RegionizedWorldData worldData = this.ruthenium$getWorldRegionData();
        if (worldData.isHandlingTick()) {
            worldData.setHandlingTick(false);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ruthenium$startRegionTicking(final BooleanSupplier shouldKeepTicking, final CallbackInfo ci) {
        final ServerWorld world = this.ruthenium$self();
        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();
        final RegionizedWorldData worldData = this.ruthenium$getWorldRegionData();

        if (worldData.isHandlingTick()) {
            this.ruthenium$skipVanillaChunkTick = true;
            scheduler.logSchedulerConflict(world,
                "Skipping redundant vanilla world tick while scheduler is still handling the dimension");
            ci.cancel();
            return;
        }

        // If regions are already active from a previous tick, the scheduler owns the tick loop.
        // Silently cancel this vanilla tick entry - this is expected behavior, not a violation.
        if (MainThreadTickGuard.isMainThread()) {
            if (scheduler.hasActiveRegions(world) && !scheduler.isHalted() && !scheduler.isGracefulDegradationActiveForWorld(world)) {
                // Regions are active - scheduler handles ticking, just cancel without violation
                ci.cancel();
                return;
            }
        }

        RegionDebug.onWorldTick(world);
        this.ruthenium$skipVanillaChunkTick = false;
        final boolean replaced = scheduler.tickWorld(world, shouldKeepTicking);
        final boolean regionsActive = !scheduler.isHalted() && scheduler.hasActiveRegions(world);
        if (replaced || regionsActive) {
            // The scheduler is responsible for chunk/entity ticking on region threads.
            // Preserve only the world-scoped entity manager maintenance tick here and skip vanilla.
            this.ruthenium$runEntityManagementPhase();
            if (!replaced && regionsActive) {
                scheduler.logSchedulerConflict(world,
                    "Blocking vanilla tick because regions remain active");
            }
            ci.cancel();
            return;
        }

        // Scheduler fallbacks should allow vanilla ticking to proceed only when no regions are active.
        this.ruthenium$skipVanillaChunkTick = false;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void ruthenium$finishRegionTicking(final BooleanSupplier shouldKeepTicking, final CallbackInfo ci) {
        this.ruthenium$resetVanillaTickGuards();
    }

    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/tick/WorldTickScheduler;tick(JILjava/util/function/BiConsumer;)V",
        ordinal = 0), cancellable = true)
    private void ruthenium$validateScheduledTicksFallback(final BooleanSupplier shouldKeepTicking, final CallbackInfo ci) {
        // Use MainThreadTickGuard to validate and potentially block scheduled tick processing
        if (!MainThreadTickGuard.guardScheduledTick(this.ruthenium$self())) {
            ci.cancel();
            return;
        }
        FallbackValidator.validateScheduledTickProcessing(this.ruthenium$self());
    }

    @Inject(method = "onPlayerConnected", at = @At("TAIL"))
    private void ruthenium$trackConnectedPlayer(final ServerPlayerEntity player, final CallbackInfo ci) {
        final ServerWorld world = this.ruthenium$self();
        final int chunkX = player.getBlockX() >> 4;
        final int chunkZ = player.getBlockZ() >> 4;
        if (RegionizedServer.isOnRegionThread()) {
            final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
            if (worldData != null) {
                worldData.addPlayer(player);
                worldData.updatePlayerTrackingPosition(player);
            }
            return;
        }
        RegionTaskDispatcher.runOnChunk(world, chunkX, chunkZ, () -> {
            final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
            if (worldData != null) {
                worldData.addPlayer(player);
                worldData.updatePlayerTrackingPosition(player);
            }
        });
    }

    @Inject(method = "removePlayer", at = @At("HEAD"))
    private void ruthenium$untrackRemovedPlayer(final ServerPlayerEntity player,
                                                final RemovalReason reason,
                                                final CallbackInfo ci) {
        final ServerWorld world = this.ruthenium$self();
        final int chunkX = player.getBlockX() >> 4;
        final int chunkZ = player.getBlockZ() >> 4;
        if (RegionizedServer.isOnRegionThread()) {
            final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
            if (worldData != null) {
                worldData.removePlayer(player);
            }
            return;
        }
        RegionTaskDispatcher.runOnChunk(world, chunkX, chunkZ, () -> {
            final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
            if (worldData != null) {
                worldData.removePlayer(player);
            }
        });
    }

    @Inject(method = "getRaidAt", at = @At("HEAD"), cancellable = true)
    private void ruthenium$threadSafeRaidLookup(final BlockPos pos, final CallbackInfoReturnable<Raid> cir) {
        final RaidManager raidManager = this.ruthenium$self().getRaidManager();
        if (raidManager instanceof RaidManagerThreadSafe threadSafe) {
            cir.setReturnValue(threadSafe.ruthenium$getRaidFor(this.ruthenium$self(), pos, 9216));
        }
    }

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void ruthenium$guardChunkTick(final WorldChunk chunk, final int randomTickSpeed, final CallbackInfo ci) {
        final boolean onRegionThread = RegionizedServer.isOnRegionThread();
        final int depth = this.ruthenium$regionChunkDepth.get();
        if (onRegionThread && depth == 0) {
            throw new IllegalStateException("Region chunk tick invoked without entering guarded context");
        }

        if (this.ruthenium$skipVanillaChunkTick && depth == 0) {
            ci.cancel();
            return;
        }

        // Guard against main thread chunk ticking when regions are active
        if (!onRegionThread && depth == 0) {
            final int chunkX = chunk.getPos().x;
            final int chunkZ = chunk.getPos().z;
            if (!MainThreadTickGuard.guardChunkTick(this.ruthenium$self(), chunkX, chunkZ)) {
                ci.cancel();
                return;
            }
        }

        this.ruthenium$regionChunkDepth.set(depth + 1);
    }

    @Inject(method = "tickChunk", at = @At("RETURN"))
    private void ruthenium$finishChunkTick(final WorldChunk chunk, final int randomTickSpeed, final CallbackInfo ci) {
        final int depth = this.ruthenium$regionChunkDepth.get();
        if (depth > 0) {
            this.ruthenium$regionChunkDepth.set(depth - 1);
        }
    }

    @Override
    public void ruthenium$pushRegionChunkTick() {
        RegionizedServer.ensureOnRegionThread("chunk ticking");
        this.ruthenium$regionChunkDepth.set(this.ruthenium$regionChunkDepth.get() + 1);
    }

    @Override
    public void ruthenium$popRegionChunkTick() {
        RegionizedServer.ensureOnRegionThread("chunk ticking");
        final int depth = this.ruthenium$regionChunkDepth.get();
        if (depth > 0) {
            this.ruthenium$regionChunkDepth.set(depth - 1);
        } else {
            throw new IllegalStateException("Attempted to exit chunk tick guard without matching entry");
        }
    }

    /**
     * Redirects addSyncedBlockEvent to use region data when on a region thread.
     * This ensures block events are queued to the correct region.
     */
    @Inject(method = "addSyncedBlockEvent", at = @At("HEAD"), cancellable = true)
    private void ruthenium$redirectAddBlockEvent(final BlockPos pos, final Block block,
                                                  final int eventId, final int eventParam,
                                                  final CallbackInfo ci) {
        final boolean onRegionThread = RegionizedServer.isOnRegionThread();
        // If the scheduler is actively handling this world, vanilla's block event list will never
        // be processed (because the vanilla world tick is cancelled). In that case we MUST route
        // events to region threads, even if the call originates from the main thread.
        if (!onRegionThread) {
            final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();
            final RegionizedWorldData worldData = this.ruthenium$getWorldRegionData();
            if (!worldData.isHandlingTick() && !scheduler.hasActiveRegions(this.ruthenium$self())) {
                return; // allow vanilla when regions are inactive
            }
        }

        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        if (onRegionThread && this.ruthenium$self() instanceof RegionizedServerWorld regionized &&
            !regionized.ruthenium$isOwnedByCurrentRegion(chunkX, chunkZ)) {
            RegionTaskDispatcher.runOnChunk(this.ruthenium$self(), chunkX, chunkZ, () -> {
                final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
                if (worldData != null) {
                    worldData.pushBlockEvent(pos, block, eventId, eventParam);
                }
            });
            ci.cancel();
            return;
        }

        // If we're not currently inside a region tick, or we're on the main thread, queue it to
        // the correct region via the dispatcher.
        final RegionizedWorldData currentWorldData = TickRegionScheduler.getCurrentWorldData();
        if (!onRegionThread || currentWorldData == null) {
            RegionTaskDispatcher.runOnChunk(this.ruthenium$self(), chunkX, chunkZ, () -> {
                final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
                if (worldData != null) {
                    worldData.pushBlockEvent(pos, block, eventId, eventParam);
                }
            });
            ci.cancel();
            return;
        }

        currentWorldData.pushBlockEvent(pos, block, eventId, eventParam);
        ci.cancel();
    }

    /**
     * Redirects processSyncedBlockEvents to use region data when on a region thread.
     * This ensures block events are processed on the correct region thread.
     */
    @Inject(method = "processSyncedBlockEvents", at = @At("HEAD"), cancellable = true)
    private void ruthenium$redirectProcessBlockEvents(final CallbackInfo ci) {
        // Validate that we're on the correct thread
        if (!RegionizedServer.isOnRegionThread()) {
            // Use MainThreadTickGuard to validate and potentially block
            if (!MainThreadTickGuard.guardBlockEventProcessing(this.ruthenium$self())) {
                ci.cancel();
                return;
            }
            FallbackValidator.validateBlockEventProcessing(this.ruthenium$self());
            return; // Fall back to vanilla if allowed
        }

        final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
        if (worldData != null) {
            worldData.processBlockEvents();
            ci.cancel();
        }
    }

    @Unique
    @SuppressWarnings("resource")
    private void ruthenium$runEntityManagementPhase() {
        ((ServerWorldAccessor)this).ruthenium$getEntityManager().tick();
    }
}
