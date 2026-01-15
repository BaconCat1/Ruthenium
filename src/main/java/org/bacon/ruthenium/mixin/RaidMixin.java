package org.bacon.ruthenium.mixin;

import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.raid.Raid;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.bacon.ruthenium.world.raid.RaidThreadSafe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds region-ownership guards to raid interactions so raid logic executes on the correct region
 * thread.
 */
@Mixin(Raid.class)
public abstract class RaidMixin implements RaidThreadSafe {

    /**
     * Required mixin constructor.
     */
    protected RaidMixin() {
    }

    /**
     * Provides access to the raid's center position defined by the target class.
     */
    @Shadow public abstract BlockPos getCenter();

    /**
     * Determines whether the supplied world owns this raid's center chunk.
     *
     * @param world world performing the ownership check
     * @return {@code true} when the region executing in {@code world} should manage this raid
     */
    @Override
    public boolean ruthenium$ownsRaid(final ServerWorld world) {
        if (world == null) {
            return false;
        }
        final BlockPos center = this.getCenter();
        if (center == null) {
            return false;
        }
        return RegionThreadUtil.ownsPosition(world, center, 8);
    }

    @Inject(method = "addRaider", at = @At("HEAD"), cancellable = true)
    private void ruthenium$guardAddRaider(final ServerWorld world, final int wave, final RaiderEntity raider, final BlockPos pos, final boolean existing, final CallbackInfo ci) {
        if (!this.ruthenium$ownsRaid(world)) {
            ci.cancel();
        }
    }
}
