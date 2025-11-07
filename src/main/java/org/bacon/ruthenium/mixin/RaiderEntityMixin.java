package org.bacon.ruthenium.mixin;

import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.world.World;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects raid movement timing to use the region-aware redstone clock.
 */
@Mixin(RaiderEntity.class)
public abstract class RaiderEntityMixin {

    /**
     * Required mixin constructor.
     */
    protected RaiderEntityMixin() {
    }

    @Redirect(method = "tickMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getTime()J"))
    private long ruthenium$useRedstoneTime(final World world) {
        return RegionThreadUtil.getRedstoneTime(world);
    }
}
