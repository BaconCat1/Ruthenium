package org.bacon.ruthenium.mixin;

import java.util.function.BooleanSupplier;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.raid.Raid;
import net.minecraft.village.raid.RaidManager;
import net.minecraft.world.World;
import org.bacon.ruthenium.Ruthenium;
import org.bacon.ruthenium.debug.RegionDebug;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.mixin.accessor.ServerWorldAccessor;
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

        RegionDebug.onWorldTick(world);
        this.ruthenium$skipVanillaChunkTick = false;
        final boolean replaced = scheduler.tickWorld(world, shouldKeepTicking);
        if (replaced) {
            // The scheduler fully replaced chunk ticking, so manually run the vanilla entity and
            // block-entity phases without executing the rest of the world tick twice.
            this.ruthenium$skipVanillaChunkTick = true;
            this.ruthenium$runEntityPhase();
            this.ruthenium$resetVanillaTickGuards();
            ci.cancel();
            return;
        }

        // Scheduler fallbacks should allow vanilla ticking to proceed so the world keeps
        // advancing even when region threads are stalled. Still log whenever active regions
        // exist to surface the degraded state for operators.
        this.ruthenium$skipVanillaChunkTick = false;
        if (!scheduler.isHalted() && scheduler.hasActiveRegions(world)) {
            scheduler.logSchedulerConflict(world,
                "Scheduler fell back to vanilla tick while regions remain active");
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void ruthenium$finishRegionTicking(final BooleanSupplier shouldKeepTicking, final CallbackInfo ci) {
        this.ruthenium$resetVanillaTickGuards();
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

    @Unique
    @SuppressWarnings("resource")
    private void ruthenium$runEntityPhase() {
        final ServerWorld world = this.ruthenium$self();
        final RegionizedWorldData worldData = this.ruthenium$getWorldRegionData();
        final boolean tickAllowed = worldData.isTickAllowed();
        if (tickAllowed) {
            ((ServerWorldAccessor)this).ruthenium$invokeProcessSyncedBlockEvents();
        }
        ((ServerWorldAccessor)this).ruthenium$setInBlockTick(false);
        if (!tickAllowed) {
            return;
        }

        world.tickBlockEntities();
        ((ServerWorldAccessor)this).ruthenium$getEntityManager().tick();
    }
}
