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
import org.bacon.ruthenium.world.RegionChunkTickAccess;
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
    private int ruthenium$regionChunkDepth;

    @Override
    public ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> ruthenium$getRegionizer() {
        if (this.ruthenium$regionizer == null) {
            this.ruthenium$regionizer = Ruthenium.createRegionizer((ServerWorld)(Object)this);
            final RegistryKey<World> worldKey = ((ServerWorld)(Object)this).getRegistryKey();
            Ruthenium.getLogger().info("Created regionizer for world {}", worldKey.getValue());
        }
        return this.ruthenium$regionizer;
    }

    @Override
    public RegionizedWorldData ruthenium$getWorldRegionData() {
        if (this.ruthenium$worldRegionData == null) {
            this.ruthenium$worldRegionData = new RegionizedWorldData((ServerWorld)(Object)this);
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
        final ServerWorld world = (ServerWorld)(Object)this;
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
        this.ruthenium$skipVanillaChunkTick = true;
        final boolean replaced = scheduler.tickWorld(world, shouldKeepTicking);
        if (replaced) {
            ci.cancel();
            return;
        }

        if (scheduler.isHalted()) {
            this.ruthenium$skipVanillaChunkTick = false;
            return;
        }

        final boolean hasActiveRegions = scheduler.hasActiveRegions(world);
        this.ruthenium$skipVanillaChunkTick = hasActiveRegions;
        if (hasActiveRegions) {
            scheduler.logSchedulerConflict(world,
                "Preventing vanilla chunk tick because scheduler-managed regions remain active");
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void ruthenium$finishRegionTicking(final BooleanSupplier shouldKeepTicking, final CallbackInfo ci) {
        this.ruthenium$resetVanillaTickGuards();
    }

    @Inject(method = "getRaidAt", at = @At("HEAD"), cancellable = true)
    private void ruthenium$threadSafeRaidLookup(final BlockPos pos, final CallbackInfoReturnable<Raid> cir) {
        final RaidManager raidManager = ((ServerWorld)(Object)this).getRaidManager();
        if (raidManager instanceof RaidManagerThreadSafe threadSafe) {
            cir.setReturnValue(threadSafe.ruthenium$getRaidFor((ServerWorld)(Object)this, pos, 9216));
        }
    }

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void ruthenium$guardChunkTick(final WorldChunk chunk, final int randomTickSpeed, final CallbackInfo ci) {
        if (this.ruthenium$skipVanillaChunkTick && this.ruthenium$regionChunkDepth == 0) {
            ci.cancel();
            return;
        }
        this.ruthenium$regionChunkDepth++;
    }

    @Inject(method = "tickChunk", at = @At("RETURN"))
    private void ruthenium$finishChunkTick(final WorldChunk chunk, final int randomTickSpeed, final CallbackInfo ci) {
        if (this.ruthenium$regionChunkDepth > 0) {
            this.ruthenium$regionChunkDepth--;
        }
    }

    @Override
    public void ruthenium$pushRegionChunkTick() {
        this.ruthenium$regionChunkDepth++;
    }

    @Override
    public void ruthenium$popRegionChunkTick() {
        if (this.ruthenium$regionChunkDepth > 0) {
            this.ruthenium$regionChunkDepth--;
        }
    }
}
